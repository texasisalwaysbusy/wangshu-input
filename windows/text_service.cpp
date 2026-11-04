// Native TSF text service. No keyboard hook, SendInput, networking or surrounding-text history.
// Optional local learning retains only explicitly confirmed short candidate selections.
#define UNICODE
#define _UNICODE
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <windowsx.h>
#include <msctf.h>
#include <inputscope.h>
#include <sapi.h>
#include <string>
#include <vector>
#include <filesystem>
#include <new>
#include <algorithm>
#include <cstring>
#include <memory>
#include <mutex>
#include "../core/engine.h"
#include "voice_process.h"
#include "voice_position.h"
#include "numeric_candidates.h"
#include "phrase_associations.h"
#include "../core/mixed_composition.h"
#include "context_candidates.h"
#include "personal_store.h"
#include "learning_queue.h"
#include "tsf_extended.h"

#ifdef __MINGW32__
// Present in SDK headers but omitted by this MinGW UUID import archive.
const GUID GUID_PROP_INPUTSCOPE={0x1713dd5a,0x68e7,0x4a5b,{0x9a,0xf6,0x59,0x2a,0x59,0x5c,0x77,0x8d}};
const IID IID_ITfInputScope={0xfde1eaee,0x6924,0x4cdf,{0x91,0xe7,0xda,0x38,0xcf,0xf5,0x55,0x9d}};
#endif

static HINSTANCE moduleHandle;
static LONG objectCount=0,serverLocks=0;
// Project-owned, stable identifiers; not identifiers of another input method.
const CLSID CLSID_Wangshu={0x2158928d,0x82f3,0x4d52,{0x91,0x4e,0xa8,0x3a,0x56,0xf9,0xc1,0x6d}};
const GUID PROFILE_Wangshu={0x297331cb,0x1972,0x4d66,{0xb7,0x3b,0xa7,0x64,0x4f,0x28,0x53,0x9d}};
static constexpr LANGID Chinese=MAKELANGID(LANG_CHINESE,SUBLANG_CHINESE_SIMPLIFIED);
static constexpr UINT VoiceMessage=WM_APP+31;
static constexpr UINT VoiceTimer=32;
static constexpr UINT LearningTimer=33;
template<class T> static void drop(T*& value){if(value){value->Release();value=nullptr;}}
static std::wstring wide(const std::string& s){auto u=wangshu::utf16(s);return std::wstring(u.begin(),u.end());}
static std::string narrow(const std::wstring& s){return wangshu::utf8(std::u16string(s.begin(),s.end()));}
static std::wstring modulePath(){std::vector<wchar_t> p(32768);DWORD n=GetModuleFileNameW(moduleHandle,p.data(),static_cast<DWORD>(p.size()));return n&&n<p.size()?std::wstring(p.data(),n):L"";}
// Share only immutable built-in words inside this process; each service owns its composition and audio session.
static std::shared_ptr<const wangshu::Engine> sharedLexicon(const std::wstring& directory){static std::mutex gate;static std::weak_ptr<const wangshu::Engine> cached;static std::wstring cachedDirectory;std::lock_guard<std::mutex> lock(gate);if(directory==cachedDirectory)if(auto existing=cached.lock())return existing;auto fresh=std::make_shared<wangshu::Engine>();if(!fresh->load(narrow(directory)))return {};cachedDirectory=directory;cached=fresh;return fresh;}
static std::wstring guidString(REFGUID id){wchar_t s[40];StringFromGUID2(id,s,40);return s;}
static HRESULT inputScopeAllows(ITfContext* context,TfEditCookie cookie,ITfRange* range,bool personalized=false,bool* privateScope=nullptr){
    if(privateScope)*privateScope=false;
    if(!context||!range)return E_POINTER;
    // Missing legacy metadata is compatible. A failed existing query is not
    // permission for optional context capture or personal ranking/learning.
    // A query denial is unknown, not proof of an explicit PRIVATE scope.
    auto unknown=[&](HRESULT reason=E_FAIL){if(privateScope)*privateScope=true;return personalized?(FAILED(reason)&&reason!=E_ACCESSDENIED?reason:E_FAIL):S_OK;};
    ITfReadOnlyProperty* property=nullptr;
    HRESULT h=context->GetAppProperty(GUID_PROP_INPUTSCOPE,&property);
    if(h==S_FALSE||h==E_NOTIMPL){drop(property);return S_OK;}
    if(h!=S_OK||!property){drop(property);return unknown(h);}
    VARIANT value;VariantInit(&value);h=property->GetValue(cookie,range,&value);drop(property);
    if(h==S_FALSE&&value.vt==VT_EMPTY){
        // GetValue's empty result also covers mixed/partial ranges. Only the
        // collapsed caret is compatible default; selected text stays ambiguous.
        LONG relation=1;HRESULT collapsed=range->CompareStart(cookie,range,TF_ANCHOR_END,&relation);VariantClear(&value);
        return SUCCEEDED(collapsed)&&relation==0?S_OK:unknown(FAILED(collapsed)?collapsed:E_FAIL);
    }
    if(h!=S_OK||value.vt!=VT_UNKNOWN||!value.punkVal){VariantClear(&value);return unknown(h);}
    ITfInputScope* scope=nullptr;h=value.punkVal->QueryInterface(IID_ITfInputScope,reinterpret_cast<void**>(&scope));VariantClear(&value);
    if(h!=S_OK||!scope){drop(scope);return unknown(h);}
    InputScope* scopes=nullptr;UINT count=0;h=scope->GetInputScopes(&scopes,&count);drop(scope);
    // Bound malformed provider output; do not dereference a successful null list.
    if(h!=S_OK||!scopes||!count||count>64){CoTaskMemFree(scopes);return unknown(h);}
    bool privateValue=false,secret=false;
    for(UINT i=0;i<count;++i){bool protectedValue=scopes[i]==IS_PASSWORD||scopes[i]==IS_NUMERIC_PASSWORD||scopes[i]==IS_NUMERIC_PIN||scopes[i]==IS_ALPHANUMERIC_PIN||scopes[i]==IS_ALPHANUMERIC_PIN_SET;secret|=protectedValue;privateValue|=protectedValue||scopes[i]==IS_PRIVATE;}
    CoTaskMemFree(scopes);if(privateScope)*privateScope=privateValue;
    return secret||(personalized&&privateValue)?E_ACCESSDENIED:S_OK;
}
class VoiceGuard final:public ITfEditSession {
    LONG refs=1;ITfContext* context;
public:
    explicit VoiceGuard(ITfContext* c):context(c){c->AddRef();}
    ~VoiceGuard(){context->Release();}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out) override{if(!out)return E_POINTER;*out=nullptr;if(id!=IID_IUnknown&&id!=IID_ITfEditSession)return E_NOINTERFACE;*out=static_cast<ITfEditSession*>(this);AddRef();return S_OK;}
    ULONG STDMETHODCALLTYPE AddRef() override{return InterlockedIncrement(&refs);}
    ULONG STDMETHODCALLTYPE Release() override{ULONG n=InterlockedDecrement(&refs);if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie) override{TF_SELECTION selection{};ULONG n=0;HRESULT h=context->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&selection,&n);if(FAILED(h)||n!=1||!selection.range){drop(selection.range);return E_FAIL;}h=inputScopeAllows(context,cookie,selection.range,true);selection.range->Release();return h;}
};
class VoicePositionSession final:public ITfEditSession {
 LONG refs=1;ITfContext* context;
public:
 std::shared_ptr<const wangshu::voice_position::Snapshot> snapshot;
 explicit VoicePositionSession(ITfContext* c):context(c){c->AddRef();}
 ~VoicePositionSession(){context->Release();}
 HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out)override{if(!out)return E_POINTER;*out=nullptr;if(id!=IID_IUnknown&&id!=IID_ITfEditSession)return E_NOINTERFACE;*out=this;AddRef();return S_OK;}
 ULONG STDMETHODCALLTYPE AddRef()override{return InterlockedIncrement(&refs);}
 ULONG STDMETHODCALLTYPE Release()override{ULONG n=InterlockedDecrement(&refs);if(!n)delete this;return n;}
 HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie)override{try{snapshot.reset();VoiceGuard guard(context);HRESULT h=guard.DoEditSession(cookie);if(FAILED(h))return h;auto fresh=std::make_shared<wangshu::voice_position::Snapshot>();h=wangshu::voice_position::capture(context,cookie,*fresh);if(FAILED(h))return h;h=guard.DoEditSession(cookie);if(SUCCEEDED(h))snapshot=std::move(fresh);return h;}catch(...){return E_FAIL;}}
};
// Focus cleanup owns one specific old composition. It never replays raw text,
// changes selection, or consults whichever newer composition the service owns.
class EndCompositionSession final:public ITfEditSession {
    LONG refs=1;ITfComposition* owned;
public:
    explicit EndCompositionSession(ITfComposition* value):owned(value){owned->AddRef();}
    ~EndCompositionSession(){owned->Release();}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out) override{if(!out)return E_POINTER;*out=nullptr;if(id!=IID_IUnknown&&id!=IID_ITfEditSession)return E_NOINTERFACE;*out=static_cast<ITfEditSession*>(this);AddRef();return S_OK;}
    ULONG STDMETHODCALLTYPE AddRef() override{return InterlockedIncrement(&refs);}
    ULONG STDMETHODCALLTYPE Release() override{ULONG n=InterlockedDecrement(&refs);if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie) override{return owned->EndComposition(cookie);}
};
class TextService;
struct CandidateProof {
    std::string input,voice,calculation,personalRevision,learningRevision,learningWord;size_t page=0;unsigned modes=0;
    wangshu::context_math::Kind calculationKind=wangshu::context_math::Kind::Value;
    bool association=false,personal=false,readable=false,ranking=false,rankingReadable=false,autoLearn=false;ITfContext* context=nullptr;unsigned long long epoch=0,voiceGeneration=0,calculationGeneration=0;
};
// A separately reference-counted snapshot may outlive its service in a host.
// detach() invalidates callbacks and releases editor references before deactivation.
class CandidateElement final:public ITfCandidateListUIElementBehavior {
    friend class TextService;
    LONG refs=1;TextService* owner;ITfContext* target=nullptr;
    std::vector<std::wstring> values;std::vector<UINT> pages{0};UINT selected=0;DWORD changed=0x3f;BOOL shown=FALSE;CandidateProof proof;
public:
    explicit CandidateElement(TextService* service):owner(service){InterlockedIncrement(&objectCount);}
    ~CandidateElement(){drop(target);InterlockedDecrement(&objectCount);}
    void detach(){owner=nullptr;shown=FALSE;drop(target);values.clear();pages={0};selected=0;changed=0x3f;}
    void snapshot(const std::vector<wangshu::Candidate>& items,ITfContext* context,const CandidateProof& bound){
        proof=bound;
        std::vector<std::wstring> fresh;for(const auto& item:items)fresh.push_back(wide(item.text));
        if(fresh!=values){values=std::move(fresh);selected=0;pages={0};changed|=0x3e;}
        if(target!=context){drop(target);target=context;if(target)target->AddRef();changed|=1;}
    }
    UINT selection()const{return selected;}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out)override{if(!out)return E_POINTER;*out=nullptr;if(id==IID_IUnknown||id==IID_ITfUIElement||id==IID_ITfCandidateListUIElement||id==IID_ITfCandidateListUIElementBehavior)*out=static_cast<ITfCandidateListUIElementBehavior*>(this);else return E_NOINTERFACE;AddRef();return S_OK;}
    ULONG STDMETHODCALLTYPE AddRef()override{return InterlockedIncrement(&refs);}
    ULONG STDMETHODCALLTYPE Release()override{ULONG n=InterlockedDecrement(&refs);if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE GetDescription(BSTR* out)override{if(!out)return E_POINTER;*out=SysAllocString(L"望舒中文与数学候选");return *out?S_OK:E_OUTOFMEMORY;}
    HRESULT STDMETHODCALLTYPE GetGUID(GUID* out)override{if(!out)return E_POINTER;*out={0x3938a9ca,0x225d,0x43d3,{0x92,0x9b,0xa3,0xb2,0x46,0x01,0x5b,0x78}};return S_OK;}
    HRESULT STDMETHODCALLTYPE Show(BOOL value)override;
    HRESULT STDMETHODCALLTYPE IsShown(BOOL* out)override{if(!out)return E_POINTER;*out=shown;return S_OK;}
    HRESULT STDMETHODCALLTYPE GetUpdatedFlags(DWORD* out)override{if(!out)return E_POINTER;*out=changed;changed=0;return S_OK;}
    HRESULT STDMETHODCALLTYPE GetDocumentMgr(ITfDocumentMgr** out)override{if(!out)return E_POINTER;*out=nullptr;return target?target->GetDocumentMgr(out):E_FAIL;}
    HRESULT STDMETHODCALLTYPE GetCount(UINT* out)override{if(!out)return E_POINTER;*out=static_cast<UINT>(values.size());return S_OK;}
    HRESULT STDMETHODCALLTYPE GetSelection(UINT* out)override{if(!out)return E_POINTER;*out=selected;return S_OK;}
    HRESULT STDMETHODCALLTYPE GetString(UINT index,BSTR* out)override{if(!out)return E_POINTER;*out=nullptr;if(index>=values.size())return E_INVALIDARG;*out=SysAllocStringLen(values[index].data(),static_cast<UINT>(values[index].size()));return *out?S_OK:E_OUTOFMEMORY;}
    HRESULT STDMETHODCALLTYPE GetPageIndex(UINT* out,UINT capacity,UINT* count)override{if(!count||(!out&&capacity))return E_POINTER;*count=static_cast<UINT>(pages.size());if(capacity<pages.size())return S_FALSE;std::copy(pages.begin(),pages.end(),out);return S_OK;}
    HRESULT STDMETHODCALLTYPE SetPageIndex(UINT* indices,UINT count)override{if(!indices)return E_POINTER;if(!count||count>values.size()||indices[0]!=0)return E_INVALIDARG;for(UINT i=0;i<count;i++)if(indices[i]>=values.size()||(i&&indices[i]<=indices[i-1]))return E_INVALIDARG;try{pages.assign(indices,indices+count);changed|=0x30;return S_OK;}catch(...){return E_OUTOFMEMORY;}}
    HRESULT STDMETHODCALLTYPE GetCurrentPage(UINT* out)override{if(!out)return E_POINTER;*out=0;for(UINT i=1;i<pages.size();i++)if(pages[i]<=selected)*out=i;return S_OK;}
    HRESULT STDMETHODCALLTYPE SetSelection(UINT index)override;
    HRESULT STDMETHODCALLTYPE Finalize()override;
    HRESULT STDMETHODCALLTYPE Abort()override;
};

class EditSession final:public ITfEditSession {
    LONG refs=1;TextService* service;ITfContext* context;std::wstring text;bool finish,notify,guarded,executed=false;unsigned long long expectedRevision,voiceGeneration=0;bool speech=false;CandidateProof proof;
public:
    EditSession(TextService* s,ITfContext* c,std::wstring t,bool f,bool n,unsigned long long revision,const CandidateProof* candidate=nullptr);
    ~EditSession();
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out) override{if(!out)return E_POINTER;*out=nullptr;if(id==IID_IUnknown||id==IID_ITfEditSession){*out=static_cast<ITfEditSession*>(this);AddRef();return S_OK;}return E_NOINTERFACE;}
    ULONG STDMETHODCALLTYPE AddRef() override{return InterlockedIncrement(&refs);}
    ULONG STDMETHODCALLTYPE Release() override{ULONG n=InterlockedDecrement(&refs);if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie) override;
};

class CalculationSession final:public ITfEditSession {
 LONG refs=1;TextService* service;ITfContext* context;CandidateProof proof;unsigned long long revision;bool executed=false;
public:
 CalculationSession(TextService*,ITfContext*,CandidateProof,unsigned long long);
 ~CalculationSession();
 HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out)override{if(!out)return E_POINTER;*out=nullptr;if(id!=IID_IUnknown&&id!=IID_ITfEditSession)return E_NOINTERFACE;*out=this;AddRef();return S_OK;}
 ULONG STDMETHODCALLTYPE AddRef()override{return InterlockedIncrement(&refs);}
 ULONG STDMETHODCALLTYPE Release()override{ULONG n=InterlockedDecrement(&refs);if(!n)delete this;return n;}
 HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie)override;
};
enum class LearningCommand {Pin,Unpin,Forget,Pause};
class LearningActionSession final:public ITfEditSession {
    LONG refs=1;TextService* service;ITfContext* context;CandidateProof proof;LearningCommand command;std::string word;bool executed=false;
public:
    LearningActionSession(TextService*,ITfContext*,CandidateProof,LearningCommand,std::string);
    ~LearningActionSession();
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out) override{if(!out)return E_POINTER;*out=nullptr;if(id!=IID_IUnknown&&id!=IID_ITfEditSession)return E_NOINTERFACE;*out=static_cast<ITfEditSession*>(this);AddRef();return S_OK;}
    ULONG STDMETHODCALLTYPE AddRef() override{return InterlockedIncrement(&refs);}
    ULONG STDMETHODCALLTYPE Release() override{ULONG n=InterlockedDecrement(&refs);if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie) override;
};
class TextService final:public ITfTextInputProcessorEx,public ITfKeyEventSink,public ITfThreadMgrEventSink,public ITfCompositionSink {
    friend struct TextServiceTestAccess;
    friend struct WindowsPersonalTestAccess;
    friend class EditSession;
    friend class CalculationSession;
    friend class LearningActionSession;
    friend class CandidateElement;
    DWORD activationFlags=0;unsigned long long revision=0,pendingRevision=0;
    LONG refs=1;ITfThreadMgr* manager=nullptr;TfClientId client=0;DWORD sinkCookie=TF_INVALID_COOKIE;bool keysAdvised=false;
    ITfContext* composingContext=nullptr;ITfComposition* composition=nullptr;RECT caretBounds{};bool caretValid=false;
    HWND popup=nullptr;HFONT font=nullptr;std::shared_ptr<const wangshu::Engine> sharedEngine;bool loaded=false,chinese=true,math=false,listening=false,noLearn=false,learningWriteFailed=false,learningTimerActive=false,privateInput=false;
    VoiceProcess localVoice;std::string voiceReview,voiceRecovery;
    std::shared_ptr<const wangshu::voice_position::Snapshot> voicePosition;unsigned long long voiceGeneration=0;bool voiceLastStopPreserved=false;
    bool calculationAssociation=false;std::string associationPersonalRevision,associationLearningRevision;
    std::string calculation;wangshu::context_math::Kind calculationKind=wangshu::context_math::Kind::Value;std::shared_ptr<const wangshu::voice_position::Snapshot> calculationPosition;ITfContext* calculationTarget=nullptr;unsigned long long calculationGeneration=0;
    ITfUIElementMgr* uiManager=nullptr;CandidateElement* uiCandidates=nullptr;DWORD uiId=TF_INVALID_COOKIE;bool hostShowsOwnUI=true,notifyingUI=false;
    std::unique_ptr<wangshu::WindowsPersonalStore> personalStore;
    std::shared_ptr<wangshu::ConfirmedLearningQueue> learningQueue;unsigned long long seenLearningFailure=0,seenLearningCancelled=0;
    CandidateProof displayed;ITfContext* candidateContext=nullptr;unsigned long long focusEpoch=0;
    std::wstring compositionText; // Last successfully written owned composition, never document history.
    std::string buffer;size_t page=0;std::vector<wangshu::Candidate> candidates;std::wstring status;
    ISpRecognizer* recognizer=nullptr;ISpRecoContext* recoContext=nullptr;ISpRecoGrammar* grammar=nullptr;ITfContext* voiceTarget=nullptr;
    const wangshu::Engine& dictionary()const{static const wangshu::Engine empty;return sharedEngine?*sharedEngine:empty;}
    std::vector<wangshu::Candidate> personalLookup(const std::string& input,size_t candidatePage=0,CandidateProof* proof=nullptr){
        if(input.empty()||input[0]=='\\'||input.find('`')!=std::string::npos||privateInput||(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_CONSOLE)))return dictionary().lookup(input,9,candidatePage);
        if(proof)proof->personal=true;
        wangshu::PersonalSnapshot snapshot;
        try{if(!personalStore)personalStore=std::make_unique<wangshu::WindowsPersonalStore>();snapshot=personalStore->read();if(proof){proof->readable=true;proof->personalRevision=snapshot.revision;}if(snapshot.fromBackup&&status.empty())status=L"个人词库正在使用有效备份；请在本地管理程序确认恢复";}
        catch(...){if(status.empty())status=L"个人词库暂不可读，文件保留；内置候选仍可用";return dictionary().lookup(input,9,candidatePage);}
        if(proof)proof->ranking=true;
        try{auto weights=personalStore->readLearningFresh();if(proof){proof->rankingReadable=true;proof->learningRevision=weights.revision;proof->autoLearn=weights.data.enabled&&!noLearn&&!weights.fromBackup;}if(weights.fromBackup&&status.empty())status=L"个人排序使用备份；恢复前暂停自动学习";return weights.data.lookup(dictionary(),snapshot.data,input,candidatePage,wangshu::WindowsPersonalStore::learningNow());}
        catch(...){if(status.empty())status=L"个人排序不可读，未重置；原词库仍可用";return snapshot.data.lookup(dictionary(),input,candidatePage);}
    }
    unsigned candidateModes()const{return activationFlags^(chinese?0x10000000u:0)^(math?0x20000000u:0)^(noLearn?0x40000000u:0)^(privateInput?0x08000000u:0);}
    void captureCandidates(){
        displayed=CandidateProof{};displayed.input=buffer;displayed.voice=voiceReview;displayed.page=page;displayed.modes=candidateModes();displayed.epoch=focusEpoch;displayed.voiceGeneration=voiceGeneration;displayed.calculation=calculation;displayed.calculationGeneration=calculationGeneration;displayed.calculationKind=calculationKind;
        if(calculationAssociation){displayed.association=true;displayed.personal=true;displayed.readable=true;displayed.ranking=true;displayed.rankingReadable=true;displayed.personalRevision=associationPersonalRevision;displayed.learningRevision=associationLearningRevision;}
        if(!voiceReview.empty())candidates={{voiceReview,"语音待核对"}};else if(!calculation.empty())candidates={{calculation,calculationAssociation?"常用语联想":calculationKind==wangshu::context_math::Kind::Substitution?"计算 · 代入":"计算 · 精确结果"}};else candidates=personalLookup(buffer,page,&displayed);
        ITfContext* next=composingContext?composingContext:!voiceReview.empty()?voiceTarget:!calculation.empty()?calculationTarget:nullptr;if(next)next->AddRef();else next=focused();drop(candidateContext);candidateContext=next;displayed.context=next;
    }
    bool validCandidate(ITfContext* context,const CandidateProof& proof){
        if(proof.association&&(noLearn||privateInput||!calculationAssociation))return false;
        if(!manager||!loaded||!context||context!=proof.context||focusEpoch!=proof.epoch||buffer!=proof.input||voiceReview!=proof.voice||calculation!=proof.calculation||(!proof.calculation.empty()&&(calculationGeneration!=proof.calculationGeneration||calculationKind!=proof.calculationKind))||(!proof.voice.empty()&&voiceGeneration!=proof.voiceGeneration)||page!=proof.page||candidateModes()!=proof.modes||disabled(context))return false;
        ITfContext* current=focused();bool scope=current?current==context:composingContext==context||voiceTarget==context;drop(current);if(!scope)return false;
        if(proof.personal){try{if(!personalStore)personalStore=std::make_unique<wangshu::WindowsPersonalStore>();auto now=personalStore->readFresh();if(proof.association&&now.fromBackup)return false;if(!proof.readable||now.revision!=proof.personalRevision)return false;}catch(...){if(proof.readable)return false;}}
        if(proof.ranking){try{auto now=personalStore->readLearningFresh();if(proof.association&&(now.fromBackup||!now.data.phraseAssociations))return false;if(!proof.rankingReadable||now.revision!=proof.learningRevision)return false;}catch(...){if(proof.rankingReadable)return false;}}
        return true;
    }
    bool sameCompositionRange(ITfContext* context,TfEditCookie cookie,ITfRange* range,const std::wstring& expected,size_t maximum=128){
        if(!range||maximum>256||expected.size()>maximum||FAILED(inputScopeAllows(context,cookie,range)))return false;
        WCHAR text[257]{};ULONG count=0;HRESULT h=range->GetText(cookie,0,text,static_cast<ULONG>(expected.size()+1),&count);
        if(FAILED(h)||count!=expected.size()||!std::equal(expected.begin(),expected.end(),text))return false;
        // Context/composition identity does not imply that this remains the user's
        // insertion point. Never overwrite it after a caret/selection or text edit.
        TF_SELECTION selection{};ULONG fetched=0;h=context->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&selection,&fetched);
        auto release=[](ITfRange* value){if(value)value->Release();};
        std::unique_ptr<ITfRange,decltype(release)> selected(selection.range,release);
        if(FAILED(h)||fetched!=1||!selected)return false;
        LONG start=1,end=1;HRESULT first=selected->CompareStart(cookie,range,TF_ANCHOR_END,&start),last=selected->CompareEnd(cookie,range,TF_ANCHOR_END,&end);
        return SUCCEEDED(first)&&SUCCEEDED(last)&&start==0&&end==0;
    }
    bool sameCandidateComposition(ITfContext* context,TfEditCookie cookie,const CandidateProof& proof){
        if(proof.input.empty())return true;
        if(!composition||composingContext!=context)return false;
        auto release=[](ITfRange* value){if(value)value->Release();};
        ITfRange* raw=nullptr;HRESULT h=composition->GetRange(&raw);std::unique_ptr<ITfRange,decltype(release)> range(raw,release);
        return SUCCEEDED(h)&&sameCompositionRange(context,cookie,range.get(),wide(wangshu::mixed::raw(proof.input)));
    }
    bool selectCandidate(ITfContext* context,size_t index,bool async=false,const std::string& suffix=""){
        CandidateProof expected=displayed;
        if(index>=candidates.size()||!validCandidate(context,expected)){status=L"候选或个人词库已变化，请核对刷新后的候选再选择";update();return false;}
        if(!expected.calculation.empty())return selectCalculation(context,expected,async);
        if(expected.autoLearn&&candidates[index].annotation!="常用语")try{wangshu::LearningData::candidateKey(expected.input);wangshu::LearningData::candidateText(candidates[index].text);expected.learningWord=candidates[index].text;}catch(...){expected.autoLearn=false;}
        else expected.autoLearn=false;
        auto value=candidates[index].text+suffix;return commit(context,value,async,&expected);
    }
    void updateInputPrivacy(bool value){if(value&&!privateInput)cancelLearning();privateInput=value;}
    bool personalizationAllowed(ITfContext* context,TfEditCookie cookie){
        TF_SELECTION selection{};ULONG count=0;HRESULT h=context->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&selection,&count);
        auto release=[](ITfRange* value){if(value)value->Release();};std::unique_ptr<ITfRange,decltype(release)> range(selection.range,release);
        if(FAILED(h)||count!=1||!range)return false;
        bool privateValue=false;h=inputScopeAllows(context,cookie,range.get(),true,&privateValue);updateInputPrivacy(privateValue);return SUCCEEDED(h);
    }
    bool learningScope(ITfContext* context,const CandidateProof& proof){
        return !(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_CONSOLE))&&proof.ranking&&proof.rankingReadable&&!proof.input.empty()&&proof.input[0]!='\\'&&validCandidate(context,proof);
    }
    void recordConfirmedSelection(ITfContext* context,const CandidateProof& proof,unsigned long long expectedRevision) noexcept{
        // No file read, write, named-lock wait or worker join inside the edit cookie.
        // The range was confirmed under this cookie; a pure DTO is handed off.
        try{
            if(!proof.autoLearn||proof.learningWord.empty()||noLearn||!personalStore||expectedRevision!=revision||proof.epoch!=focusEpoch||proof.modes!=candidateModes()||buffer!=proof.input||!manager||!loaded||(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_CONSOLE)))return;
            ITfContext* current=focused();bool same=current==context;drop(current);if(!same)return;
            if(!learningQueue)learningQueue=std::make_shared<wangshu::ConfirmedLearningQueue>(+[]{InterlockedIncrement(&objectCount);},+[]{InterlockedDecrement(&objectCount);});
            bool accepted=learningQueue->enqueue({personalStore->path(),proof.input,proof.learningWord,proof.learningRevision});
            if(!accepted)learningWriteFailed=true;else if(popup)learningTimerActive=SetTimer(popup,LearningTimer,100,nullptr)!=0;
        }catch(...){learningWriteFailed=true;}
    }
    bool cancelLearning(){return learningQueue?learningQueue->cancel():false;}
    void finishLearningStatus() noexcept{
        try{if(learningWriteFailed)status=L"文字已输入；个人排序未保存，请在管理页检查";
            else if(learningQueue&&learningQueue->stats().pending)status=L"文字已输入；词频正在后台保存";else status.clear();}
        catch(...){status.clear();}learningWriteFailed=false;
    }
    void pollLearningStatus(){
        if(!learningQueue)return;auto result=learningQueue->stats();auto bad=result.failed+result.overflow+result.conflicts;
        if(bad>seenLearningFailure){seenLearningFailure=bad;status=L"个人排序未保存，部分操作冲突或失败，请刷新后重试";}
        else if(result.cancelled>seenLearningCancelled){status=L"输入环境已变化，未认领的个人排序操作已取消";}
        else if(!result.pending&&!result.callbacks&&status==L"个人排序正在后台保存…")status=L"个人排序处理完成，请刷新候选确认";
        seenLearningCancelled=result.cancelled;
        if(!result.pending&&!result.callbacks){if(popup)KillTimer(popup,LearningTimer);learningTimerActive=false;if(status==L"文字已输入；词频正在后台保存")status.clear();}
    }
    HRESULT applyLearningAction(ITfContext* context,TfEditCookie cookie,const CandidateProof& proof,LearningCommand action,const std::string& word){
        if(!learningScope(context,proof)||!sameCandidateComposition(context,cookie,proof))return E_ABORT;
        if(!personalizationAllowed(context,cookie)){status=L"当前输入框无法安全启用个人排序";update();return E_ACCESSDENIED;}
        if(action==LearningCommand::Pause){noLearn=!noLearn;bool claimed=cancelLearning();invalidatePending();status=noLearn?L"临时不学习：当前输入服务会话不增加词频":L"已退出临时不学习（自动学习仍以设置为准）";if(claimed)status+=L"；此前已认领的保存仍可能完成";update();return S_OK;}
        try{
            wangshu::LearningData::candidateKey(proof.input);wangshu::LearningData::candidateText(word);
            if(!learningQueue)learningQueue=std::make_shared<wangshu::ConfirmedLearningQueue>(+[]{InterlockedIncrement(&objectCount);},+[]{InterlockedDecrement(&objectCount);});
            auto task=action==LearningCommand::Forget?wangshu::LearningJobAction::Forget:action==LearningCommand::Pin?wangshu::LearningJobAction::Pin:wangshu::LearningJobAction::Unpin;
            if(!learningQueue->enqueue({personalStore->path(),proof.input,word,proof.learningRevision,task}))throw std::runtime_error("Ranking queue unavailable");
            invalidatePending();status=L"个人排序正在后台保存…";if(popup)learningTimerActive=SetTimer(popup,LearningTimer,100,nullptr)!=0;update();return S_OK;
        }
        catch(...){status=L"个人排序未保存；请刷新管理程序核对，未重置文件";update();return E_FAIL;}
    }
    HRESULT requestLearningAction(ITfContext* context,size_t index,LearningCommand action,const CandidateProof* expected=nullptr,const std::string* expectedWord=nullptr){
        CandidateProof proof=expected?*expected:displayed;if(!learningScope(context,proof)||index>=candidates.size()||(expectedWord&&candidates[index].text!=*expectedWord))return E_ABORT;
        auto edit=new(std::nothrow)LearningActionSession(this,context,proof,action,candidates[index].text);if(!edit)return E_OUTOFMEMORY;
        HRESULT result=E_FAIL,h=context->RequestEditSession(client,edit,TF_ES_SYNC|TF_ES_READ,&result);edit->Release();return FAILED(h)?h:result;
    }
    void candidateMenu(HWND window,LPARAM location){
        if(!hostShowsOwnUI||buffer.empty()||candidates.empty()||!voiceReview.empty())return;
        POINT screen{GET_X_LPARAM(location),GET_Y_LPARAM(location)};if(screen.x==-1&&screen.y==-1)GetCursorPos(&screen);POINT local=screen;ScreenToClient(window,&local);
        size_t index=local.y>=44?static_cast<size_t>((local.y-44)/30):0;if(index>=candidates.size())index=0;
        auto release=[](ITfContext* value){if(value)value->Release();};std::unique_ptr<ITfContext,decltype(release)> context(focused(),release);
        if(!context||!learningScope(context.get(),displayed))return;
        CandidateProof menuProof=displayed;std::string menuWord=candidates[index].text;
        struct Menu {HMENU value=CreatePopupMenu();~Menu(){if(value)DestroyMenu(value);}} menu;
        if(!menu.value)return;bool valid=false,fixed=false;
        try{wangshu::LearningData::candidateKey(buffer);wangshu::LearningData::candidateText(menuWord);auto fresh=personalStore->readLearningFresh();fixed=fresh.data.pinned(buffer,menuWord);valid=true;}catch(...){}
        AppendMenuW(menu.value,MF_STRING|(valid?0:MF_GRAYED),1,fixed?L"取消固定此候选":L"固定此候选");AppendMenuW(menu.value,MF_STRING|(valid?0:MF_GRAYED),2,L"忘记此词的个人权重（保留词库）");AppendMenuW(menu.value,MF_SEPARATOR,0,nullptr);AppendMenuW(menu.value,MF_STRING|(noLearn?MF_CHECKED:0),3,L"临时不学习（本输入服务会话）");
        UINT selected=TrackPopupMenu(menu.value,TPM_RETURNCMD|TPM_NONOTIFY|TPM_RIGHTBUTTON,screen.x,screen.y,0,window,nullptr);
        if(selected){HRESULT result=requestLearningAction(context.get(),index,selected==3?LearningCommand::Pause:selected==2?LearningCommand::Forget:fixed?LearningCommand::Unpin:LearningCommand::Pin,&menuProof,&menuWord);
            if(result==E_ABORT){status=L"候选已变化；未修改个人排序，请重新选择";update();}}
    }
    static LRESULT CALLBACK WindowProc(HWND w,UINT m,WPARAM a,LPARAM b){
        auto self=reinterpret_cast<TextService*>(GetWindowLongPtrW(w,GWLP_USERDATA));
        if(m==WM_NCCREATE){self=static_cast<TextService*>(reinterpret_cast<CREATESTRUCTW*>(b)->lpCreateParams);SetWindowLongPtrW(w,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(self));}
        if(!self)return DefWindowProcW(w,m,a,b);
        if(m==WM_MOUSEACTIVATE)return MA_NOACTIVATE;
        if(m==WM_CONTEXTMENU){try{self->candidateMenu(w,b);}catch(...){/* Menu failure never inserts text or resets data. */}return 0;}
        if(m==WM_PAINT){self->paint(w);return 0;}
        if(m==WM_LBUTTONUP){int row=(HIWORD(b)-44)/30;if(HIWORD(b)>=44&&row>=0&&static_cast<size_t>(row)<self->candidates.size()){ITfContext* c=self->focused();if(c){self->selectCandidate(c,static_cast<size_t>(row),true);c->Release();}}return 0;}
        if(m==WM_TIMER&&a==LearningTimer){if(self->learningTimerActive)self->update();return 0;}
        if(m==WM_TIMER&&a==VoiceTimer){self->pollVoice();return 0;}
        if(m==VoiceMessage){self->voiceEvents();return 0;}
        return DefWindowProcW(w,m,a,b);
    }
    ITfContext* focused(){ITfDocumentMgr* d=nullptr;ITfContext* c=nullptr;if(manager&&SUCCEEDED(manager->GetFocus(&d))&&d){d->GetTop(&c);d->Release();}return c;}
    void captureCaret(ITfContext* c,TfEditCookie cookie,ITfRange* range){caretValid=false;ITfContextView* view=nullptr;if(SUCCEEDED(c->GetActiveView(&view))&&view){RECT box{};BOOL clipped=FALSE;HRESULT h=view->GetTextExt(cookie,range,&box,&clipped);view->Release();if(SUCCEEDED(h)&&!clipped&&box.bottom>box.top&&box.right>=box.left){caretBounds=box;caretValid=true;}}}
    bool disabled(ITfContext* c){
        if(!c)return true;
        TF_STATUS s{};if(SUCCEEDED(c->GetStatus(&s))&&(s.dwStaticFlags&TS_SD_READONLY))return true;
        ITfCompartmentMgr* cm=nullptr;if(SUCCEEDED(c->QueryInterface(IID_ITfCompartmentMgr,reinterpret_cast<void**>(&cm)))){
            bool result=false;for(const GUID* g:{&GUID_COMPARTMENT_KEYBOARD_DISABLED,&GUID_COMPARTMENT_EMPTYCONTEXT}){ITfCompartment* x=nullptr;VARIANT v;VariantInit(&v);if(SUCCEEDED(cm->GetCompartment(*g,&x))){if(SUCCEEDED(x->GetValue(&v))&&v.vt==VT_I4&&v.lVal)result=true;VariantClear(&v);x->Release();}}
            cm->Release();return result;
        }return false;
    }
    void makeWindow(){
        if(popup||(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_UIELEMENTENABLEDONLY)))return;
        WNDCLASSW wc{};wc.lpfnWndProc=WindowProc;wc.hInstance=moduleHandle;wc.lpszClassName=L"WangshuImeCandidates";wc.hCursor=LoadCursorW(nullptr,IDC_ARROW);RegisterClassW(&wc);
        popup=CreateWindowExW(WS_EX_TOOLWINDOW|WS_EX_NOACTIVATE|WS_EX_TOPMOST,wc.lpszClassName,L"望舒输入法",WS_POPUP|WS_BORDER,0,0,460,100,nullptr,nullptr,moduleHandle,this);
        font=CreateFontW(-20,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Microsoft YaHei UI");
    }
    void paint(HWND w){PAINTSTRUCT p;HDC dc=BeginPaint(w,&p);RECT r;GetClientRect(w,&r);HBRUSH bg=CreateSolidBrush(RGB(245,248,253));FillRect(dc,&r,bg);DeleteObject(bg);auto old=SelectObject(dc,font);SetBkMode(dc,TRANSPARENT);SetTextColor(dc,RGB(20,35,60));
        std::wstring title=L"望舒 · "+wide(buffer)+(math?L"  数学":L"")+(listening?L"  本地聆听中":L"")+(noLearn?L"  不学习":L"")+(privateInput?L"  隐私保护":L"");TextOutW(dc,10,8,title.c_str(),static_cast<int>(title.size()));
        if(!voiceReview.empty()){RECT box{10,44,540,174};auto text=wide(voiceReview);DrawTextW(dc,text.c_str(),static_cast<int>(text.size()),&box,DT_WORDBREAK|DT_NOPREFIX);}
        else for(size_t i=0;i<candidates.size();i++){auto s=std::to_wstring(i+1)+L". "+wide(candidates[i].text)+L"   "+wide(candidates[i].annotation);for(auto& character:s)if(character==L'\n'||character==L'\r'||character==L'\t')character=L' ';RECT row{10,44+static_cast<int>(i)*30,540,74+static_cast<int>(i)*30};DrawTextW(dc,s.c_str(),static_cast<int>(s.size()),&row,DT_SINGLELINE|DT_END_ELLIPSIS|DT_NOPREFIX);}
        auto hint=status.empty()?L"1–9选词 · 空格确认 · PgUp/PgDn翻页 · 右键个人排序":status;TextOutW(dc,10,voiceReview.empty()?48+static_cast<int>(candidates.size())*30:184,hint.c_str(),static_cast<int>(hint.size()));SelectObject(dc,old);EndPaint(w,&p);
    }
    void endCandidateUI(bool invalidateBatch=true){
        if(invalidateBatch){drop(candidateContext);displayed.context=nullptr;}
        CandidateElement* old=uiCandidates;uiCandidates=nullptr;DWORD id=uiId;uiId=TF_INVALID_COOKIE;hostShowsOwnUI=true;
        if(old)old->detach();
        if(uiManager&&id!=TF_INVALID_COOKIE)uiManager->EndUIElement(id);
        if(old)old->Release();
    }
    void syncCandidateUI(){
        if(notifyingUI)return;
        if(!manager||candidates.empty()||(buffer.empty()&&voiceReview.empty()&&calculation.empty())){endCandidateUI();return;}
        if(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_CONSOLE)){endCandidateUI(false);return;}
        if(!uiManager)manager->QueryInterface(IID_ITfUIElementMgr,reinterpret_cast<void**>(&uiManager));
        if(!uiManager)return;
        ITfContext* context=focused();if(!context)return;
        bool starting=!uiCandidates;if(starting)uiCandidates=new CandidateElement(this);
        uiCandidates->snapshot(candidates,context,displayed);context->Release();
        notifyingUI=true;
        if(starting){BOOL show=(activationFlags&TF_TMAE_UIELEMENTENABLEDONLY)?FALSE:TRUE;DWORD id=TF_INVALID_COOKIE;HRESULT h=uiManager->BeginUIElement(uiCandidates,&show,&id);if(SUCCEEDED(h)){uiId=id;hostShowsOwnUI=show!=FALSE&&!(activationFlags&TF_TMAE_UIELEMENTENABLEDONLY);uiCandidates->Show(hostShowsOwnUI);}else{uiCandidates->detach();drop(uiCandidates);hostShowsOwnUI=true;}}
        else uiManager->UpdateUIElement(uiId);
        notifyingUI=false;
    }
    bool showCandidateUI(BOOL show){hostShowsOwnUI=show!=FALSE&&!(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_UIELEMENTENABLEDONLY));if(popup)ShowWindow(popup,hostShowsOwnUI?SW_SHOWNOACTIVATE:SW_HIDE);return hostShowsOwnUI;}
    HRESULT hostCandidateAction(CandidateElement* source,ITfContext* expected,UINT index,bool abort){
        if(source!=uiCandidates||uiId==TF_INVALID_COOKIE||!manager||!loaded)return E_UNEXPECTED;
        ITfContext* current=focused();bool valid=current&&current==expected&&!disabled(current);HRESULT result=E_ACCESSDENIED;
        if(valid){if(abort&&!calculation.empty()){clearCalculation();status.clear();update();result=S_OK;}else if(abort&&!voiceReview.empty()){stopVoice();voiceRecovery.clear();status.clear();update();result=S_OK;}else if(abort)result=commit(current,"",true)?S_OK:E_FAIL;else if(index<candidates.size()&&index<source->values.size()&&wide(candidates[index].text)==source->values[index]&&validCandidate(current,source->proof))result=selectCandidate(current,index,true)?S_OK:E_FAIL;else{status=L"候选已变化，请重新选择";update();result=E_ABORT;}}
        drop(current);return result;
    }
    void updateImpl(){pollLearningStatus();captureCandidates();syncCandidateUI();makeWindow();if(!popup)return;if(!hostShowsOwnUI){ShowWindow(popup,SW_HIDE);return;}if(buffer.empty()&&!listening&&status.empty()){ShowWindow(popup,SW_HIDE);return;}RECT work{};POINT point{};GetCursorPos(&point);int height=voiceReview.empty()?86+static_cast<int>(candidates.size())*30:232;int x=point.x,y=point.y+20;if(caretValid){point={caretBounds.left,caretBounds.bottom};x=point.x;y=point.y+4;}else{GUITHREADINFO gi{};gi.cbSize=sizeof(gi);if(GetGUIThreadInfo(0,&gi)&&gi.hwndCaret){point={gi.rcCaret.left,gi.rcCaret.bottom};ClientToScreen(gi.hwndCaret,&point);x=point.x;y=point.y+4;}}MONITORINFO monitor{};monitor.cbSize=sizeof(monitor);if(GetMonitorInfoW(MonitorFromPoint(point,MONITOR_DEFAULTTONEAREST),&monitor))work=monitor.rcWork;else SystemParametersInfoW(SPI_GETWORKAREA,0,&work,0);x=std::max<int>(work.left,std::min<int>(x,work.right-560));y=std::max<int>(work.top,std::min<int>(y,work.bottom-height));SetWindowPos(popup,HWND_TOPMOST,x,y,560,height,SWP_NOACTIVATE|SWP_SHOWWINDOW);InvalidateRect(popup,nullptr,TRUE);}
    void update() noexcept{try{updateImpl();}catch(...){if(popup)ShowWindow(popup,SW_HIDE);}}
    void invalidatePending(){++revision;if(pendingRevision){pendingRevision=0;status.clear();update();}}
    HRESULT releaseCompositionOnFocusLoss(){
        // Detach before RequestEditSession: ASYNCDONTCARE may execute immediately
        // or after new input. Its sink callback must not clear the newer buffer.
        ITfComposition* old=composition;ITfContext* oldContext=composingContext;
        composition=nullptr;composingContext=nullptr;compositionText.clear();
        if(!old){drop(oldContext);return S_OK;}
        auto finish=new(std::nothrow)EndCompositionSession(old);old->Release();
        if(!finish){drop(oldContext);return E_OUTOFMEMORY;}
        HRESULT result=E_FAIL,h=oldContext?oldContext->RequestEditSession(client,finish,TF_ES_ASYNCDONTCARE|TF_ES_READWRITE,&result):E_UNEXPECTED;
        finish->Release();drop(oldContext);return FAILED(h)?h:result;
    }
    void focusLost(){
        clearCalculation();
        learningTimerActive=false;if(popup)KillTimer(popup,LearningTimer);
        cancelLearning();privateInput=false;invalidatePending();caretValid=false;stopVoice(true);buffer.clear();page=0;status.clear();endCandidateUI();
        HRESULT ended=releaseCompositionOnFocusLoss();
        if(FAILED(ended))try{status=L"旧输入文字已保留；编辑器尚未确认结束组合，请检查原输入框";}catch(...){}
        if(popup)ShowWindow(popup,SW_HIDE);
    }
    HRESULT request(ITfContext* c,const std::wstring& value,bool finish,bool async=false,bool notify=false,const CandidateProof* proof=nullptr){if(!c||disabled(c))return E_FAIL;auto edit=new(std::nothrow)EditSession(this,c,value,finish,notify,revision,proof);if(!edit)return E_OUTOFMEMORY;HRESULT result=E_FAIL;HRESULT h=c->RequestEditSession(client,edit,(async?TF_ES_ASYNCDONTCARE:TF_ES_SYNC)|TF_ES_READWRITE,&result);edit->Release();return FAILED(h)?h:result;}
    void completeAsync(unsigned long long token,HRESULT result){if(token!=revision||token!=pendingRevision)return;pendingRevision=0;if(SUCCEEDED(result)){if(!voiceReview.empty())stopVoice();buffer.clear();page=0;finishLearningStatus();}else if(!voiceRecovery.empty())status=L"语音位置无法核对；结果暂存，Ctrl+Shift+V 重新定位后再确认";else status=L"编辑器拒绝插入，候选和拼音仍保留，可重试";update();}
    bool acceptsAsync(unsigned long long token)const{return token==revision&&token==pendingRevision&&manager&&loaded;}
    bool commit(ITfContext* c,const std::string& value,bool async=false,const CandidateProof* proof=nullptr){
        // A delimiter-only preedit never created a host composition. An empty
        // commit here must clear only local grammar, not the selected document text.
        if(!composition&&!buffer.empty()&&value.empty()&&voiceReview.empty()&&wangshu::mixed::raw(buffer).empty()){buffer.clear();page=0;status.clear();update();return true;}
        learningWriteFailed=false;
        if(async){unsigned long long token=++revision;pendingRevision=token;HRESULT h=request(c,wide(value),true,true,true,proof);if(h==TF_S_ASYNC){if(pendingRevision==token){status=L"正在等待编辑器插入，候选和拼音仍保留";update();}return true;}if(FAILED(h))completeAsync(token,h);return SUCCEEDED(h);}
        HRESULT h=request(c,wide(value),true,false,false,proof);if(SUCCEEDED(h)){if(!voiceReview.empty())stopVoice();buffer.clear();page=0;finishLearningStatus();update();return true;}status=!voiceRecovery.empty()?L"语音位置无法核对；结果暂存，Ctrl+Shift+V 重新定位后再确认":L"编辑器暂时不接受输入；请重新聚焦后重试";update();return false;
    }
    void clearCalculation(){bool had=!calculation.empty();calculation.clear();calculationAssociation=false;associationPersonalRevision.clear();associationLearningRevision.clear();calculationKind=wangshu::context_math::Kind::Value;calculationPosition.reset();drop(calculationTarget);++calculationGeneration;if(had)endCandidateUI();}
    void requestCalculation(ITfContext* c) noexcept{try{
        bool had=!calculation.empty();clearCalculation();if(had)status.clear();
        if(!c||disabled(c)||!buffer.empty()||composition||listening||!voiceReview.empty()||(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_COMLESS|TF_TMAE_CONSOLE))){if(had)update();return;}
        ITfContext* current=focused();bool here=current==c;drop(current);if(!here){if(had)update();return;}
        // Disabled means no optional context read; no-learn also suppresses this
        // explicitly personal feature, without changing deterministic math.
        bool associationsEnabled=false;
        if(!noLearn&&!privateInput){try{if(!personalStore)personalStore=std::make_unique<wangshu::WindowsPersonalStore>();auto setting=personalStore->readLearningFresh();associationsEnabled=setting.data.phraseAssociations&&!setting.fromBackup;}catch(...){}}
        if(!math&&!associationsEnabled){if(had)update();return;}
        auto generation=calculationGeneration,requestRevision=revision;
        auto guard=new(std::nothrow)VoicePositionSession(c);if(!guard){if(had)update();return;}HRESULT result=E_FAIL;
        HRESULT h=c->RequestEditSession(client,guard,TF_ES_SYNC|TF_ES_READ,&result);auto before=guard->snapshot;guard->Release();
        if(FAILED(h)||FAILED(result)||!before){if(had)update();return;}
        auto answer=math?wangshu::numeric::atCaret(before->text,before->caret):wangshu::numeric::Result{};
        bool association=false;std::string personalRevision,learningRevision;
        auto answerKind=wangshu::context_math::Kind::Value;
        if(math&&!answer.ready&&before->caret>0&&before->caret<=before->text.size()){
            // Full bounded capture is kept intact for stale checks. Never truncate
            // earlier definitions to manufacture an unambiguous context.
            std::wstring_view document(before->text);
            auto split=document.find_last_of(L"\n;；",before->caret-1);
            auto from=split==std::wstring_view::npos?0:split+1;
            auto context=split==std::wstring_view::npos?std::wstring_view{}:document.substr(0,split);
            auto expression=document.substr(from,before->caret-from);
            auto suggested=wangshu::context_math::suggest(context,expression);
            if(suggested.status==wangshu::context_math::Status::Ready&&suggested.candidate){
                answer.ready=true;answer.text=std::move(suggested.candidate->text);answerKind=suggested.candidate->kind;
            }
        }
        if(!answer.ready&&!noLearn&&!privateInput&&before->caret==before->text.size()){
            try{
                if(!personalStore)personalStore=std::make_unique<wangshu::WindowsPersonalStore>();
                auto settings=personalStore->readLearningFresh();
                if(settings.data.phraseAssociations&&!settings.fromBackup){
                    auto manual=personalStore->readFresh();
                    if(!manual.fromBackup){std::vector<std::wstring> phrases;for(const auto& value:manual.data.phrases)phrases.push_back(wide(value.text));
                        auto suggestion=wangshu::phrase_association::suggest(before->text,before->caret,phrases);
                        if(suggestion){answer.ready=true;answer.text=*suggestion;association=true;personalRevision=manual.revision;learningRevision=settings.revision;}
                    }
                }
            }catch(...){/* Unreadable settings/data never enable associations. */}
        }
        // Compute from immutable text, then re-read the same host before publication.
        if(answer.ready){
            auto verify=new(std::nothrow)VoicePositionSession(c);if(!verify){if(had)update();return;}HRESULT checked=E_FAIL;
            HRESULT read=c->RequestEditSession(client,verify,TF_ES_SYNC|TF_ES_READ,&checked);auto fresh=verify->snapshot;verify->Release();
            if(FAILED(read)||FAILED(checked)||!fresh||!(*fresh==*before)){if(had)update();return;}
        }
        current=focused();here=current==c;drop(current);
        if(!answer.ready||!here||generation!=calculationGeneration||requestRevision!=revision||!manager||!loaded||(!math&&!association)|| (association&&(noLearn||privateInput))){if(had)update();return;}
        calculationAssociation=association;associationPersonalRevision=std::move(personalRevision);associationLearningRevision=std::move(learningRevision);
        calculation=narrow(answer.text);calculationKind=answerKind;calculationPosition=std::move(before);calculationTarget=c;c->AddRef();
        status=association?L"本地常用语联想：点击候选追加；空格/Enter仍交给输入框":answerKind==wangshu::context_math::Kind::Substitution?L"点击代入候选追加表达式；尚未求出数值，Enter/空格仍交给输入框":L"点击计算候选追加精确结果；Enter/空格仍交给输入框";update();
    }catch(...){clearCalculation();update();}}
    bool selectCalculation(ITfContext* c,const CandidateProof& proof,bool async){
        if(!validCandidate(c,proof)||proof.calculation.empty())return false;
        auto token=++revision;pendingRevision=0;auto session=new(std::nothrow)CalculationSession(this,c,proof,token);if(!session)return false;
        HRESULT result=E_FAIL,h=c->RequestEditSession(client,session,(async?TF_ES_ASYNCDONTCARE:TF_ES_SYNC)|TF_ES_READWRITE,&result);session->Release();
        if(FAILED(h)||FAILED(result)){if(token==revision&&proof.calculationGeneration==calculationGeneration){status=L"编辑器未确认计算结果写入；请核对原文，未自动重试";update();}return false;}
        return true;
    }
    HRESULT applyCalculation(ITfContext* c,TfEditCookie cookie,const CandidateProof& proof,unsigned long long token){
        if(token!=revision||proof.calculationGeneration!=calculationGeneration||!validCandidate(c,proof)||(!math&&!proof.association)||!calculationPosition||c!=calculationTarget||composition||!buffer.empty()||listening||!voiceReview.empty())return E_ABORT;
        VoiceGuard privacy(c);HRESULT h=privacy.DoEditSession(cookie);if(FAILED(h))return h;
        auto before=calculationPosition;wangshu::voice_position::Snapshot now;h=wangshu::voice_position::capture(c,cookie,now);
        if(FAILED(h)||!(now==*before)||token!=revision||proof.calculationGeneration!=calculationGeneration||before!=calculationPosition)return E_ABORT;
        ITfInsertAtSelection* insert=nullptr;ITfRange* raw=nullptr;h=c->QueryInterface(IID_ITfInsertAtSelection,reinterpret_cast<void**>(&insert));
        if(SUCCEEDED(h)){h=insert->InsertTextAtSelection(cookie,TF_IAS_QUERYONLY,L"",0,&raw);insert->Release();}wangshu::voice_position::Range range(raw);if(FAILED(h)||!range)return FAILED(h)?h:E_FAIL;
        // Prove the queried insertion range is exactly the freshly read collapsed caret.
        TF_SELECTION selected{};ULONG count=0;h=c->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&selected,&count);wangshu::voice_position::Range selection(selected.range);LONG first=1,last=1;
        if(FAILED(h)||count!=1||!selection||FAILED(range->CompareStart(cookie,selection.get(),TF_ANCHOR_START,&first))||first||FAILED(range->CompareEnd(cookie,selection.get(),TF_ANCHOR_END,&last))||last)return E_ABORT;
        h=privacy.DoEditSession(cookie);if(FAILED(h))return h;
        if(token!=revision||proof.calculationGeneration!=calculationGeneration||before!=calculationPosition||c!=calculationTarget||(proof.association&&!validCandidate(c,proof)))return E_ABORT;
        auto current=[&]{return token==revision&&proof.calculationGeneration==calculationGeneration&&before==calculationPosition&&c==calculationTarget;};
        auto text=wide(proof.calculation);h=range->SetText(cookie,0,text.c_str(),static_cast<LONG>(text.size()));
        // A confirmed applied write is never replayed, even if the host changed
        // focus/session during it. Do not move old carets or clear newer UI.
        if(!current())return h;
        if(SUCCEEDED(h)){
            bool placed=false;wangshu::voice_position::Range end;if(SUCCEEDED(wangshu::voice_position::clone(range.get(),end))&&SUCCEEDED(end->Collapse(cookie,TF_ANCHOR_END))&&current()){TF_SELECTION position{end.get(),{TF_AE_NONE,FALSE}};placed=SUCCEEDED(c->SetSelection(cookie,1,&position));}
            if(!current())return h;
            clearCalculation();status=placed?(proof.association?L"已追加本地常用语候选":proof.calculationKind==wangshu::context_math::Kind::Substitution?L"已追加代入表达式；未知变量保持原样":L"已追加精确计算结果"):L"计算候选已写入；编辑器未确认光标，请检查原文";update();
        }else{status=L"编辑器未确认计算结果写入；请核对原文，未自动重试";update();}
        return h;
    }
    static bool ctrl(){return (GetKeyState(VK_CONTROL)&0x8000)!=0;}
    static bool shift(){return (GetKeyState(VK_SHIFT)&0x8000)!=0;}
    static char character(WPARAM key){if(key>='A'&&key<='Z')return static_cast<char>((shift()!=((GetKeyState(VK_CAPITAL)&1)!=0))?key:key+('a'-'A'));BYTE state[256];GetKeyboardState(state);wchar_t out[4]{};int n=ToUnicode(static_cast<UINT>(key),MapVirtualKeyW(static_cast<UINT>(key),MAPVK_VK_TO_VSC),state,out,4,4);return n==1&&out[0]>=32&&out[0]<127?static_cast<char>(out[0]):0;}
    bool eats(ITfContext* c,WPARAM k){if(!loaded||disabled(c))return false;if(!voiceReview.empty()&&(k==VK_RETURN||k==VK_SPACE||k==VK_ESCAPE))return true;if(ctrl()&&shift()&&(k=='M'||k=='V'))return true;if(ctrl()||(GetKeyState(VK_MENU)&0x8000))return false;if(k==VK_SPACE&&shift())return true;if(!chinese)return false;if(!buffer.empty()&&(k==VK_SPACE||k==VK_RETURN||k==VK_BACK||k==VK_ESCAPE||k==VK_NEXT||k==VK_PRIOR||(k>='1'&&k<='9')))return true;if(!buffer.empty()&&buffer[0]=='\\'&&k>='0'&&k<='9'&&!dictionary().lookup(buffer+static_cast<char>(k)).empty())return true;char a=character(k);if((buffer.empty()||buffer[0]!='\\')&&(a=='`'||(wangshu::mixed::active(buffer)&&a>=32&&a<=126)))return true;return (a>='a'&&a<='z')||(a>='A'&&a<='Z')||a=='\\'||(!buffer.empty()&&a=='\'')||(a&&std::strchr(",.?!;:",a));}
    HRESULT bindVoice(ITfContext* c){
        if(!c||disabled(c)||composition)return E_ABORT;
        ITfContext* current=focused();bool focusedHere=current==c;drop(current);if(!focusedHere)return E_ABORT;
        auto token=voiceGeneration;auto requestRevision=revision;
        auto guard=new(std::nothrow)VoicePositionSession(c);if(!guard)return E_OUTOFMEMORY;HRESULT result=E_FAIL;
        HRESULT h=c->RequestEditSession(client,guard,TF_ES_SYNC|TF_ES_READ,&result);auto snapshot=guard->snapshot;guard->Release();
        if(FAILED(h)||FAILED(result)||!snapshot){if(result==E_ACCESSDENIED){stopVoice();voiceRecovery.clear();}return FAILED(h)?h:FAILED(result)?result:E_FAIL;}
        current=focused();focusedHere=current==c;drop(current);if(!focusedHere||token!=voiceGeneration||requestRevision!=revision||!manager||!loaded)return E_ABORT;
        voicePosition=std::move(snapshot);drop(voiceTarget);voiceTarget=c;c->AddRef();++voiceGeneration;return S_OK;
    }
    HRESULT checkVoicePosition(ITfContext* c,TfEditCookie cookie,unsigned long long generation){
        if(!voicePosition||c!=voiceTarget||generation!=voiceGeneration)return E_ABORT;
        ITfContext* current=focused();bool same=current==c;drop(current);if(!same)return E_ABORT;
        auto bound=voicePosition;wangshu::voice_position::Snapshot fresh;HRESULT h=wangshu::voice_position::capture(c,cookie,fresh);return FAILED(h)?h:generation==voiceGeneration&&bound==voicePosition&&c==voiceTarget&&fresh==*bound?S_OK:E_ABORT;
    }
    HRESULT checkVoiceRead(){
        if(!voiceTarget||!voicePosition)return E_ABORT;
        auto bound=voicePosition;auto generation=voiceGeneration;auto target=voiceTarget;
        ITfContext* current=focused();bool here=current==voiceTarget;drop(current);if(!here)return E_ABORT;
        auto guard=new(std::nothrow)VoicePositionSession(voiceTarget);if(!guard)return E_OUTOFMEMORY;HRESULT result=E_FAIL;
        HRESULT h=voiceTarget->RequestEditSession(client,guard,TF_ES_SYNC|TF_ES_READ,&result);auto fresh=guard->snapshot;guard->Release();
        return FAILED(h)?h:FAILED(result)?result:generation==voiceGeneration&&target==voiceTarget&&bound==voicePosition&&fresh&&*fresh==*bound?S_OK:E_ABORT;
    }
    void reviewVoice(std::string text){
        // Recognition completion never silently chooses a new insertion position.
        auto value=wide(text);if(text.empty()||value.size()>wangshu::voice_position::MaxUnits||!wangshu::voice_position::textValid(value)){stopVoice();status=L"没有可安全核对的有界语音文字";update();return;}
        const auto generation=voiceGeneration;auto bound=voicePosition;ITfContext* target=voiceTarget;if(target)target->AddRef();
        if(!target||!bound){drop(target);return;}
        HRESULT position=checkVoiceRead();
        if(generation!=voiceGeneration||target!=voiceTarget||bound!=voicePosition){
            // Only a single preserving stop may retain this result; a replacement
            // session or a later explicit cancellation owns the current state.
            bool retain=position!=E_ACCESSDENIED&&voiceGeneration==generation+1&&voiceLastStopPreserved&&!voiceTarget&&!voicePosition&&!listening&&voiceReview.empty()&&voiceRecovery.empty();
            drop(target);if(retain){voiceRecovery=std::move(text);status=L"语音位置已变化，结果暂存内存；Ctrl+Shift+V 重新定位后再确认";update();}return;
        }
        stopVoice();
        if(position==E_ACCESSDENIED){voiceRecovery.clear();status=L"隐私输入框已取消语音";}
        else if(FAILED(position)){voiceRecovery=std::move(text);status=L"语音位置已变化，结果暂存内存；Ctrl+Shift+V 重新定位后再确认";}
        else{voiceTarget=target;target=nullptr;voicePosition=std::move(bound);voiceReview=std::move(text);status=L"请核对：Enter/空格插入 · Esc取消";SetTimer(popup,VoiceTimer,250,nullptr);}
        drop(target);update();
    }
    void retainVoice(){stopVoice(true);status=L"语音位置已变化，结果暂存内存；Ctrl+Shift+V 重新定位后再确认";}
    void stopVoice(bool preserve=false){voiceLastStopPreserved=preserve;if(preserve&&!voiceReview.empty())voiceRecovery=std::move(voiceReview);voiceReview.clear();voicePosition.reset();++voiceGeneration;localVoice.cancel();if(popup)KillTimer(popup,VoiceTimer);listening=false;if(grammar)grammar->SetDictationState(SPRS_INACTIVE);if(recognizer)recognizer->SetRecoState(SPRST_INACTIVE_WITH_PURGE);drop(grammar);drop(recoContext);drop(recognizer);drop(voiceTarget);}
    void startVoice(ITfContext* c){
        if(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_COMLESS|TF_TMAE_CONSOLE|TF_TMAE_UIELEMENTENABLEDONLY)){stopVoice();status=L"当前 Windows 输入上下文仅启用键盘文字，未启动麦克风";update();return;}
        if(localVoice.active()){localVoice.finish();status=L"本地识别中，完成后请核对文字";update();return;}
        if(listening){stopVoice();status.clear();update();return;}if(disabled(c))return;makeWindow();
        stopVoice(true);HRESULT allowed=bindVoice(c);
        if(FAILED(allowed)){status=L"无法完整核对输入位置或当前框不允许语音；未启动麦克风";update();return;}
        if(!voiceRecovery.empty()){voiceReview=std::move(voiceRecovery);voiceRecovery.clear();status=L"已重新定位；请核对文字，再按 Enter/空格插入，Esc取消";SetTimer(popup,VoiceTimer,250,nullptr);update();return;}
        auto base=std::filesystem::path(modulePath()).parent_path()/L"voice";
        if(std::filesystem::is_regular_file(base/L"models"/L"tokens.txt")){
            if(!localVoice.start(base)){status=L"本地语音进程未启动；键盘仍可用";update();return;}
            listening=true;status=L"正在准备本地模型；Ctrl+Shift+V 完成，切换输入框取消";SetTimer(popup,VoiceTimer,100,nullptr);update();return;
        }
        ISpObjectTokenCategory* category=nullptr;IEnumSpObjectTokens* tokens=nullptr;ISpObjectToken* token=nullptr;
        HRESULT h=CoCreateInstance(CLSID_SpObjectTokenCategory,nullptr,CLSCTX_INPROC_SERVER,IID_ISpObjectTokenCategory,reinterpret_cast<void**>(&category));
        if(SUCCEEDED(h))h=category->SetId(SPCAT_RECOGNIZERS,FALSE);
        // Only an already installed Microsoft Chinese desktop recognizer. Never download a model.
        if(SUCCEEDED(h))h=category->EnumTokens(L"Language=804;Vendor=Microsoft",nullptr,&tokens);
        if(SUCCEEDED(h)){ULONG n=0;h=tokens->Next(1,&token,&n);if(n!=1)h=E_FAIL;}
        if(SUCCEEDED(h))h=CoCreateInstance(CLSID_SpInprocRecognizer,nullptr,CLSCTX_INPROC_SERVER,IID_ISpRecognizer,reinterpret_cast<void**>(&recognizer));
        if(SUCCEEDED(h))h=recognizer->SetRecognizer(token);
        if(SUCCEEDED(h))h=recognizer->SetInput(nullptr,TRUE);
        if(SUCCEEDED(h))h=recognizer->CreateRecoContext(&recoContext);
        if(SUCCEEDED(h))h=recoContext->SetNotifyWindowMessage(popup,VoiceMessage,0,0);
        if(SUCCEEDED(h))h=recoContext->SetInterest(SPFEI(SPEI_RECOGNITION),SPFEI(SPEI_RECOGNITION));
        if(SUCCEEDED(h))h=recoContext->CreateGrammar(1,&grammar);
        if(SUCCEEDED(h))h=grammar->LoadDictation(nullptr,SPLO_STATIC);
        if(SUCCEEDED(h))h=grammar->SetDictationState(SPRS_ACTIVE);
        if(SUCCEEDED(h))h=recognizer->SetRecoState(SPRST_ACTIVE_ALWAYS);
        drop(token);drop(tokens);drop(category);
        if(FAILED(h)){stopVoice();status=L"未找到可用的本地中文语音模型或麦克风；未转云识别";}
        else{listening=true;status=L"Ctrl+Shift+V 停止；切换输入框自动停止";SetTimer(popup,VoiceTimer,250,nullptr);}
        update();
    }
    void pollVoice() noexcept{const auto generation=voiceGeneration;try{
        if(!listening&&!localVoice.active()&&voiceReview.empty())return;
        ITfContext* c=focused();bool valid=c&&c==voiceTarget&&!disabled(c);
        if(generation!=voiceGeneration){drop(c);return;}
        bool sensitive=false;if(valid){auto guard=new(std::nothrow)VoiceGuard(c);HRESULT allowed=E_FAIL;HRESULT h=guard?c->RequestEditSession(client,guard,TF_ES_SYNC|TF_ES_READ,&allowed):E_OUTOFMEMORY;if(guard)guard->Release();sensitive=allowed==E_ACCESSDENIED;valid=SUCCEEDED(h)&&SUCCEEDED(allowed);}drop(c);if(generation!=voiceGeneration)return;if(!valid){if(sensitive){stopVoice();voiceRecovery.clear();status.clear();}else retainVoice();update();return;}
        if(!localVoice.active())return;
        localVoice.poll();if(generation!=voiceGeneration)return;
        if(localVoice.state==VoiceProcess::Recording){status=L"本地聆听 · Ctrl+Shift+V 完成（最长30秒）";update();}
        else if(localVoice.state==VoiceProcess::Complete){std::string text=localVoice.text;if(math)text=dictionary().voiceMath(text);reviewVoice(std::move(text));}
        else if(localVoice.state==VoiceProcess::Failed){stopVoice();status=L"本地语音未完成，请检查语音包和麦克风；未转云";update();}
    }catch(...){if(generation==voiceGeneration){stopVoice();status=L"本地语音未完成；键盘仍可用";update();}}}
    void voiceEvents(){
        if(!recoContext||!listening)return;
        const auto generation=voiceGeneration;auto source=recoContext;source->AddRef();
        struct SourceHold{ISpRecoContext* p;~SourceHold(){p->Release();}} sourceHold{source};
        auto currentSession=[&]{return generation==voiceGeneration&&source==recoContext&&listening;};
        struct EventHold{SPEVENT event{};~EventHold(){if(event.elParamType==SPET_LPARAM_IS_OBJECT&&event.lParam)reinterpret_cast<IUnknown*>(event.lParam)->Release();else if((event.elParamType==SPET_LPARAM_IS_POINTER||event.elParamType==SPET_LPARAM_IS_STRING)&&event.lParam)CoTaskMemFree(reinterpret_cast<void*>(event.lParam));}};
        while(currentSession()){
            EventHold owned;auto& event=owned.event;ULONG n=0;
            HRESULT h=source->GetEvents(1,&event,&n);if(FAILED(h)||!n)break;
            if(!currentSession())break;
            if(event.eEventId==SPEI_RECOGNITION&&event.elParamType==SPET_LPARAM_IS_OBJECT&&event.lParam){
                auto result=reinterpret_cast<ISpRecoResult*>(event.lParam);wchar_t* value=nullptr;
                h=result->GetText(SP_GETWHOLEPHRASE,SP_GETWHOLEPHRASE,TRUE,&value,nullptr);
                std::unique_ptr<wchar_t,decltype(&CoTaskMemFree)> textValue(value,&CoTaskMemFree);
                if(SUCCEEDED(h)&&value&&currentSession()){
                    ITfContext* current=focused();bool valid=current&&current==voiceTarget&&!disabled(current);drop(current);
                    if(valid&&currentSession()){auto text=narrow(value);if(math)text=dictionary().voiceMath(text);if(currentSession())reviewVoice(std::move(text));}
                }
            }
        }
    }
public:
    TextService(){InterlockedIncrement(&objectCount);}
    ~TextService(){Deactivate();InterlockedDecrement(&objectCount);}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out) override{if(!out)return E_POINTER;*out=nullptr;if(id==IID_IUnknown||id==IID_ITfTextInputProcessor)*out=static_cast<ITfTextInputProcessor*>(this);else if(id==IID_ITfTextInputProcessorEx)*out=static_cast<ITfTextInputProcessorEx*>(this);else if(id==IID_ITfKeyEventSink)*out=static_cast<ITfKeyEventSink*>(this);else if(id==IID_ITfThreadMgrEventSink)*out=static_cast<ITfThreadMgrEventSink*>(this);else if(id==IID_ITfCompositionSink)*out=static_cast<ITfCompositionSink*>(this);else return E_NOINTERFACE;AddRef();return S_OK;}
    ULONG STDMETHODCALLTYPE AddRef() override{return InterlockedIncrement(&refs);}
    ULONG STDMETHODCALLTYPE Release() override{ULONG n=InterlockedDecrement(&refs);if(!n)delete this;return n;}
    HRESULT activateImpl(ITfThreadMgr* tm,TfClientId id){if(!tm)return E_POINTER;if(manager)return E_UNEXPECTED;manager=tm;tm->AddRef();client=id;auto directory=std::filesystem::path(modulePath()).parent_path()/L"data";sharedEngine=sharedLexicon(directory.wstring());loaded=static_cast<bool>(sharedEngine);if(!loaded){Deactivate();return E_FAIL;}ITfKeystrokeMgr* keys=nullptr;HRESULT h=tm->QueryInterface(IID_ITfKeystrokeMgr,reinterpret_cast<void**>(&keys));if(SUCCEEDED(h)){h=keys->AdviseKeyEventSink(id,this,TRUE);if(SUCCEEDED(h))keysAdvised=true;keys->Release();}if(SUCCEEDED(h)){ITfSource* source=nullptr;h=tm->QueryInterface(IID_ITfSource,reinterpret_cast<void**>(&source));if(SUCCEEDED(h)){h=source->AdviseSink(IID_ITfThreadMgrEventSink,static_cast<ITfThreadMgrEventSink*>(this),&sinkCookie);source->Release();}}if(FAILED(h))Deactivate();return h;}
    HRESULT STDMETHODCALLTYPE ActivateEx(ITfThreadMgr* tm,TfClientId id,DWORD flags) override{HRESULT result=Activate(tm,id);if(SUCCEEDED(result))activationFlags=flags;return result;}
    HRESULT STDMETHODCALLTYPE Activate(ITfThreadMgr* tm,TfClientId id) override{try{return activateImpl(tm,id);}catch(const std::bad_alloc&){Deactivate();return E_OUTOFMEMORY;}catch(...){Deactivate();return E_FAIL;}}
    HRESULT STDMETHODCALLTYPE Deactivate() override{if(learningQueue){learningQueue->close();learningQueue.reset();}seenLearningFailure=seenLearningCancelled=0;++revision;pendingRevision=0;caretValid=false;clearCalculation();stopVoice();voiceRecovery.clear();bool removeKeys=keysAdvised;keysAdvised=false;if(manager){ITfKeystrokeMgr* keys=nullptr;if(removeKeys&&SUCCEEDED(manager->QueryInterface(IID_ITfKeystrokeMgr,reinterpret_cast<void**>(&keys)))){keys->UnadviseKeyEventSink(client);keys->Release();}if(sinkCookie!=TF_INVALID_COOKIE){ITfSource* source=nullptr;if(SUCCEEDED(manager->QueryInterface(IID_ITfSource,reinterpret_cast<void**>(&source)))){source->UnadviseSink(sinkCookie);source->Release();}}}sinkCookie=TF_INVALID_COOKIE;endCandidateUI();drop(uiManager);activationFlags=0;drop(composition);drop(composingContext);compositionText.clear();drop(manager);client=0;if(popup){DestroyWindow(popup);popup=nullptr;}if(font){DeleteObject(font);font=nullptr;}buffer.clear();personalStore.reset();sharedEngine.reset();noLearn=false;learningWriteFailed=false;learningTimerActive=false;privateInput=false;loaded=false;return S_OK;}
    HRESULT edit(ITfContext* c,TfEditCookie cookie,const std::wstring& text,bool finish,bool* confirmed=nullptr,const unsigned long long* expectedVoice=nullptr){
        if(confirmed)*confirmed=false;bool selectionPlaced=false,endAccepted=true;
        if(composition&&composingContext!=c)return E_UNEXPECTED;
        // Allocate before acquiring COM ranges or mutating the host: a failed
        // allocation must not leak references or turn an applied key into retry.
        if(!finish&&text.size()>128)return E_INVALIDARG;
        std::wstring nextCompositionText;if(!finish)nextCompositionText=text;
        ITfRange* range=nullptr;HRESULT h=S_OK;bool started=false;
        if(composition)h=composition->GetRange(&range);
        else{ITfInsertAtSelection* insert=nullptr;h=c->QueryInterface(IID_ITfInsertAtSelection,reinterpret_cast<void**>(&insert));if(SUCCEEDED(h)){h=insert->InsertTextAtSelection(cookie,TF_IAS_QUERYONLY,L"",0,&range);insert->Release();}}
        if(FAILED(h)||!range)return FAILED(h)?h:E_FAIL;
        // A password input scope may be exposed even when keyboard-disabled is unset.
        bool privateValue=false;h=inputScopeAllows(c,cookie,range,expectedVoice!=nullptr,&privateValue);updateInputPrivacy(privateValue);if(FAILED(h)){range->Release();return h;}
        // Guard every existing-composition write, including raw Enter, Escape,
        // backspace and continued typing, not only candidate selection.
        if(composition&&!sameCompositionRange(c,cookie,range,compositionText)){range->Release();return E_ABORT;}
        // Establish ownership before changing any text. A refused composition must be inert.
        if(!finish&&!composition){
            ITfContextComposition* cc=nullptr;
            h=c->QueryInterface(IID_ITfContextComposition,reinterpret_cast<void**>(&cc));
            if(SUCCEEDED(h)){h=cc->StartComposition(cookie,range,this,&composition);cc->Release();}
            if(FAILED(h)||!composition){range->Release();return FAILED(h)?h:E_ACCESSDENIED;}
            composingContext=c;c->AddRef();started=true;
        }
        if(expectedVoice&&(*expectedVoice!=voiceGeneration||c!=voiceTarget||!voicePosition)){range->Release();return E_ABORT;}
        h=range->SetText(cookie,0,text.c_str(),static_cast<LONG>(text.size()));
        if(SUCCEEDED(h)){
            if(!finish&&composition)compositionText.swap(nextCompositionText);
            ITfRange* end=nullptr;
            if(SUCCEEDED(range->Clone(&end))&&end){
                HRESULT collapsed=end->Collapse(cookie,TF_ANCHOR_END);TF_SELECTION selection{end,{TF_AE_NONE,FALSE}};
                // An applied key is consumed even when later caret confirmation fails.
                selectionPlaced=SUCCEEDED(collapsed)&&SUCCEEDED(c->SetSelection(cookie,1,&selection));
                if(!selectionPlaced)try{status=L"文字已写入，编辑器未移动光标，请手动检查位置";}catch(...){}
            }
            if(end)end->Release();
        }
        if(SUCCEEDED(h))captureCaret(c,cookie,range);
        if(composition&&((finish&&SUCCEEDED(h))||(started&&FAILED(h)))){
            auto old=composition;composition=nullptr;endAccepted=SUCCEEDED(old->EndComposition(cookie));old->Release();drop(composingContext);compositionText.clear();
        }
        if(confirmed&&SUCCEEDED(h)&&selectionPlaced&&endAccepted&&personalizationAllowed(c,cookie))*confirmed=sameCompositionRange(c,cookie,range,text,256);
        range->Release();return h;
    }
    HRESULT STDMETHODCALLTYPE OnSetFocus(BOOL foreground) override{if(!foreground)focusLost();return S_OK;}
    HRESULT STDMETHODCALLTYPE OnTestKeyDown(ITfContext* c,WPARAM k,LPARAM,BOOL* eat) override{if(!eat)return E_POINTER;bool hadCalculation=!calculation.empty();clearCalculation();if(hadCalculation){status.clear();update();}invalidatePending();if((listening||!voiceReview.empty())&&!(ctrl()&&shift()&&k=='V')&&(voiceReview.empty()||(k!=VK_RETURN&&k!=VK_SPACE&&k!=VK_ESCAPE))){stopVoice(true);status.clear();update();}*eat=eats(c,k);return S_OK;}
    HRESULT STDMETHODCALLTYPE OnTestKeyUp(ITfContext* c,WPARAM key,LPARAM,BOOL* eat) override{if(!eat)return E_POINTER;*eat=FALSE;if(!ctrl()&&!(GetKeyState(VK_MENU)&0x8000)&&(character(key)||key==VK_SPACE||key==VK_RETURN||key==VK_LEFT||key==VK_RIGHT||key==VK_HOME||key==VK_END||key==VK_BACK||key==VK_DELETE))requestCalculation(c);return S_OK;}
    HRESULT STDMETHODCALLTYPE OnKeyUp(ITfContext*,WPARAM,LPARAM,BOOL* eat) override{if(!eat)return E_POINTER;*eat=FALSE;return S_OK;}
    HRESULT STDMETHODCALLTYPE OnPreservedKey(ITfContext*,REFGUID,BOOL* eat) override{if(!eat)return E_POINTER;*eat=FALSE;return S_OK;}
    HRESULT keyDownImpl(ITfContext* c,WPARAM k,LPARAM,BOOL* eat){
        if(!eat)return E_POINTER;
        if(!calculation.empty()){clearCalculation();status.clear();update();}
        invalidatePending();*eat=eats(c,k);if(!*eat)return S_OK;
        if(ctrl()&&shift()&&k=='V'){if(!buffer.empty()&&!(candidates.empty()?commit(c,wangshu::mixed::raw(buffer)):selectCandidate(c,0)))return S_OK;startVoice(c);return S_OK;}
        if(!voiceReview.empty()){if(k==VK_RETURN||k==VK_SPACE){std::string value=voiceReview;commit(c,value);*eat=TRUE;return S_OK;}if(k==VK_ESCAPE){stopVoice();voiceRecovery.clear();status.clear();update();return S_OK;}stopVoice(true);}
        if(listening)stopVoice();
        if(ctrl()&&shift()&&k=='M'){math=!math;status=math?L"数学模式 · \\alpha 符号 · 算式 = 后点选精确结果":L"";update();return S_OK;}
        if(k==VK_SPACE&&shift()){if(!buffer.empty()&&!commit(c,wangshu::mixed::raw(buffer)))return S_OK;chinese=!chinese;status=chinese?L"中文输入":L"English";update();return S_OK;}
        if(k==VK_NEXT||k==VK_PRIOR){if(k==VK_PRIOR&&page)page--;if(k==VK_NEXT&&!personalLookup(buffer,page+1).empty())page++;update();return S_OK;}
        char mixedKey=character(k);
        if((buffer.empty()||buffer[0]!='\\')&&(mixedKey=='`'||(wangshu::mixed::active(buffer)&&mixedKey>=32&&mixedKey<=126))){
            if(buffer.size()>=128){*eat=TRUE;return S_OK;}buffer.push_back(mixedKey);page=0;status=L"中英分段：反引号切换原文英段；关闭英段后空格确认，Enter原文";if(mixedKey=='`'){update();return S_OK;}HRESULT h=request(c,wide(wangshu::mixed::raw(buffer)),false);if(FAILED(h)){buffer.pop_back();*eat=FALSE;}update();return S_OK;
        }
        if(!buffer.empty()&&buffer[0]=='\\'&&k>='0'&&k<='9'&&!dictionary().lookup(buffer+static_cast<char>(k)).empty()){buffer.push_back(static_cast<char>(k));page=0;*eat=SUCCEEDED(request(c,wide(wangshu::mixed::raw(buffer)),false));if(!*eat)buffer.pop_back();update();return S_OK;}
        if(!buffer.empty()&&k>='1'&&k<='9'){size_t n=static_cast<size_t>(k-'1');if(n<candidates.size())*eat=selectCandidate(c,n);return S_OK;}
        if(k==VK_SPACE||k==VK_RETURN){*eat=k==VK_RETURN||candidates.empty()?commit(c,wangshu::mixed::raw(buffer)):selectCandidate(c,uiCandidates?uiCandidates->selection():0);return S_OK;}
        if(k==VK_ESCAPE){*eat=commit(c,"");return S_OK;}
        if(k==VK_BACK){std::string previous=buffer;size_t previousPage=page;if(!buffer.empty())buffer.pop_back();page=0;if(wangshu::mixed::raw(previous)==wangshu::mixed::raw(buffer)){*eat=TRUE;update();return S_OK;}HRESULT h=request(c,wide(wangshu::mixed::raw(buffer)),buffer.empty());if(FAILED(h)){buffer=std::move(previous);page=previousPage;status=L"编辑器暂时拒绝删除，拼音仍保留，可重试";}*eat=TRUE;update();return S_OK;}
        char a=character(k);std::string punctuation=dictionary().punctuation(a,true);
        if(a&&std::strchr(",.?!;:",a)){if(!buffer.empty()&&!candidates.empty())*eat=selectCandidate(c,0,false,punctuation);else *eat=commit(c,wangshu::mixed::raw(buffer)+punctuation);return S_OK;}
        if(buffer.size()>=128){*eat=TRUE;return S_OK;}buffer.push_back(a);page=0;status.clear();HRESULT h=request(c,wide(wangshu::mixed::raw(buffer)),false);if(FAILED(h)){buffer.pop_back();*eat=FALSE;}update();return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnKeyDown(ITfContext* c,WPARAM k,LPARAM l,BOOL* eat) override{try{return keyDownImpl(c,k,l,eat);}catch(const std::bad_alloc&){if(eat)*eat=FALSE;return E_OUTOFMEMORY;}catch(...){if(eat)*eat=FALSE;return E_FAIL;}}
    HRESULT STDMETHODCALLTYPE OnInitDocumentMgr(ITfDocumentMgr*) override{return S_OK;}
    HRESULT STDMETHODCALLTYPE OnUninitDocumentMgr(ITfDocumentMgr*) override{return S_OK;}
    HRESULT STDMETHODCALLTYPE OnSetFocus(ITfDocumentMgr*,ITfDocumentMgr*) override{focusLost();return S_OK;}
    HRESULT STDMETHODCALLTYPE OnPushContext(ITfContext*) override{clearCalculation();cancelLearning();++focusEpoch;invalidatePending();endCandidateUI();return S_OK;}
    HRESULT STDMETHODCALLTYPE OnPopContext(ITfContext* c) override{clearCalculation();cancelLearning();invalidatePending();if(c==voiceTarget)stopVoice(true);if(uiCandidates&&uiCandidates->target==c)endCandidateUI();return S_OK;}
    HRESULT STDMETHODCALLTYPE OnCompositionTerminated(TfEditCookie,ITfComposition* c) override{if(c==composition){invalidatePending();drop(composition);drop(composingContext);compositionText.clear();buffer.clear();page=0;update();}return S_OK;}
};
HRESULT CandidateElement::Show(BOOL value){shown=owner&&owner->showCandidateUI(value);return owner?S_OK:E_UNEXPECTED;}
HRESULT CandidateElement::SetSelection(UINT index){if(!owner)return E_UNEXPECTED;if(index>=values.size())return E_INVALIDARG;selected=index;changed|=0x24;if(owner->popup)InvalidateRect(owner->popup,nullptr,TRUE);return S_OK;}
HRESULT CandidateElement::Finalize(){if(!owner)return E_UNEXPECTED;AddRef();HRESULT result=owner->hostCandidateAction(this,target,selected,false);Release();return result;}
HRESULT CandidateElement::Abort(){if(!owner)return E_UNEXPECTED;AddRef();HRESULT result=owner->hostCandidateAction(this,target,selected,true);Release();return result;}

EditSession::EditSession(TextService* s,ITfContext* c,std::wstring t,bool f,bool n,unsigned long long revision,const CandidateProof* candidate):service(s),context(c),text(std::move(t)),finish(f),notify(n),guarded(candidate!=nullptr),expectedRevision(revision),proof(candidate?*candidate:CandidateProof{}){speech=!s->voiceReview.empty()||(guarded&&!proof.voice.empty());voiceGeneration=s->voiceGeneration;s->AddRef();c->AddRef();}
EditSession::~EditSession(){context->Release();service->Release();}
HRESULT EditSession::DoEditSession(TfEditCookie cookie){
    if(executed)return E_ABORT;executed=true;
    if(expectedRevision!=service->revision||(notify&&!service->acceptsAsync(expectedRevision)))return E_ABORT;
    HRESULT result=E_FAIL;bool confirmed=false;
    try{
        HRESULT voiceAllowed=S_OK;if(speech){VoiceGuard guard(context);voiceAllowed=guard.DoEditSession(cookie);}
        bool personalScope=speech||!guarded||!proof.ranking||service->personalizationAllowed(context,cookie);
        if(speech&&FAILED(voiceAllowed)){if(voiceAllowed==E_ACCESSDENIED){service->updateInputPrivacy(true);service->stopVoice();service->voiceRecovery.clear();}else service->retainVoice();result=voiceAllowed;}
        else if(!personalScope)result=E_ABORT;
        else if(speech&&FAILED(service->checkVoicePosition(context,cookie,voiceGeneration))){if(voiceGeneration==service->voiceGeneration)service->retainVoice();result=E_ABORT;}
        else result=guarded&&(!service->validCandidate(context,proof)||!service->sameCandidateComposition(context,cookie,proof))?E_ABORT:service->edit(context,cookie,text,finish,guarded&&proof.autoLearn?&confirmed:nullptr,speech?&voiceGeneration:nullptr);
        if(speech&&result==E_ACCESSDENIED&&service->privateInput){service->stopVoice();service->voiceRecovery.clear();}
        if(SUCCEEDED(result)&&confirmed)service->recordConfirmedSelection(context,proof,expectedRevision);
    }catch(const std::bad_alloc&){result=E_OUTOFMEMORY;}catch(...){result=E_FAIL;}
    if(notify)try{service->completeAsync(expectedRevision,result);}catch(...){/* Do not propagate exceptions across COM. */}return result;
}
CalculationSession::CalculationSession(TextService* owner,ITfContext* target,CandidateProof captured,unsigned long long token):service(owner),context(target),proof(std::move(captured)),revision(token){service->AddRef();context->AddRef();}
CalculationSession::~CalculationSession(){context->Release();service->Release();}
HRESULT CalculationSession::DoEditSession(TfEditCookie cookie){if(executed)return E_ABORT;executed=true;try{HRESULT result=service->applyCalculation(context,cookie,proof,revision);if(FAILED(result)&&revision==service->revision&&proof.calculationGeneration==service->calculationGeneration){service->clearCalculation();service->status=L"计算候选已失效或编辑器未确认写入；未自动重试";service->update();}return result;}catch(...){return E_FAIL;}}
LearningActionSession::LearningActionSession(TextService* owner,ITfContext* target,CandidateProof snapshot,LearningCommand action,std::string value):service(owner),context(target),proof(std::move(snapshot)),command(action),word(std::move(value)){service->AddRef();context->AddRef();}
LearningActionSession::~LearningActionSession(){context->Release();service->Release();}
HRESULT LearningActionSession::DoEditSession(TfEditCookie cookie){if(executed)return E_ABORT;executed=true;try{return service->applyLearningAction(context,cookie,proof,command,word);}catch(...){return E_FAIL;}}


class Factory final:public IClassFactory {
    LONG refs=1;
public:
    Factory(){InterlockedIncrement(&objectCount);}~Factory(){InterlockedDecrement(&objectCount);}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** p) override{if(!p)return E_POINTER;*p=nullptr;if(id!=IID_IUnknown&&id!=IID_IClassFactory)return E_NOINTERFACE;*p=static_cast<IClassFactory*>(this);AddRef();return S_OK;}
    ULONG STDMETHODCALLTYPE AddRef() override{return InterlockedIncrement(&refs);}
    ULONG STDMETHODCALLTYPE Release() override{ULONG n=InterlockedDecrement(&refs);if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown* outer,REFIID id,void** p) override{if(outer)return CLASS_E_NOAGGREGATION;auto s=new(std::nothrow)TextService;if(!s)return E_OUTOFMEMORY;HRESULT h=s->QueryInterface(id,p);s->Release();return h;}
    HRESULT STDMETHODCALLTYPE LockServer(BOOL lock) override{if(lock)InterlockedIncrement(&serverLocks);else InterlockedDecrement(&serverLocks);return S_OK;}
};
extern "C" BOOL WINAPI DllMain(HINSTANCE instance,DWORD reason,LPVOID){if(reason==DLL_PROCESS_ATTACH){moduleHandle=instance;DisableThreadLibraryCalls(instance);}return TRUE;}
extern "C" __declspec(dllexport) HRESULT __stdcall DllCanUnloadNow(){return objectCount==0&&serverLocks==0?S_OK:S_FALSE;}
extern "C" __declspec(dllexport) HRESULT __stdcall DllGetClassObject(REFCLSID id,REFIID iid,void** p){if(id!=CLSID_Wangshu)return CLASS_E_CLASSNOTAVAILABLE;auto f=new(std::nothrow)Factory;if(!f)return E_OUTOFMEMORY;HRESULT h=f->QueryInterface(iid,p);f->Release();return h;}
static HRESULT registryValue(const std::wstring& key,const wchar_t* name,const std::wstring& value){HKEY h=nullptr;LSTATUS s=RegCreateKeyExW(HKEY_CURRENT_USER,key.c_str(),0,nullptr,0,KEY_SET_VALUE,nullptr,&h,nullptr);if(s!=ERROR_SUCCESS)return HRESULT_FROM_WIN32(s);s=RegSetValueExW(h,name,0,REG_SZ,reinterpret_cast<const BYTE*>(value.c_str()),static_cast<DWORD>((value.size()+1)*sizeof(wchar_t)));RegCloseKey(h);return HRESULT_FROM_WIN32(s);}
extern "C" __declspec(dllexport) HRESULT __stdcall DllRegisterServer(){
    auto path=modulePath();if(path.empty())return E_FAIL;std::wstring key=L"Software\\Classes\\CLSID\\"+guidString(CLSID_Wangshu);HRESULT h=registryValue(key,nullptr,L"望舒中文与数学输入法");if(SUCCEEDED(h))h=registryValue(key+L"\\InprocServer32",nullptr,path);if(SUCCEEDED(h))h=registryValue(key+L"\\InprocServer32",L"ThreadingModel",L"Apartment");if(FAILED(h))return h;
    HRESULT init=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);ITfInputProcessorProfiles* p=nullptr;h=CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,IID_ITfInputProcessorProfiles,reinterpret_cast<void**>(&p));if(SUCCEEDED(h)){h=p->Register(CLSID_Wangshu);const wchar_t* name=L"望舒拼音 · 数学与本地语音";if(SUCCEEDED(h))h=p->AddLanguageProfile(CLSID_Wangshu,Chinese,PROFILE_Wangshu,name,static_cast<ULONG>(wcslen(name)),path.c_str(),static_cast<ULONG>(path.size()),0);if(SUCCEEDED(h))h=p->EnableLanguageProfile(CLSID_Wangshu,Chinese,PROFILE_Wangshu,TRUE);p->Release();}
    if(SUCCEEDED(h)){ITfCategoryMgr* categories=nullptr;h=CoCreateInstance(CLSID_TF_CategoryMgr,nullptr,CLSCTX_INPROC_SERVER,IID_ITfCategoryMgr,reinterpret_cast<void**>(&categories));if(SUCCEEDED(h)){h=categories->RegisterCategory(CLSID_Wangshu,GUID_TFCAT_TIP_KEYBOARD,CLSID_Wangshu);categories->Release();}}
    if(SUCCEEDED(init))CoUninitialize();
    return h;
}
extern "C" __declspec(dllexport) HRESULT __stdcall DllUnregisterServer(){HRESULT init=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);ITfCategoryMgr* c=nullptr;HRESULT result=S_OK;if(SUCCEEDED(CoCreateInstance(CLSID_TF_CategoryMgr,nullptr,CLSCTX_INPROC_SERVER,IID_ITfCategoryMgr,reinterpret_cast<void**>(&c)))){result=c->UnregisterCategory(CLSID_Wangshu,GUID_TFCAT_TIP_KEYBOARD,CLSID_Wangshu);c->Release();}ITfInputProcessorProfiles* p=nullptr;if(SUCCEEDED(CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,IID_ITfInputProcessorProfiles,reinterpret_cast<void**>(&p)))){HRESULT h=p->Unregister(CLSID_Wangshu);if(FAILED(h))result=h;p->Release();}auto key=L"Software\\Classes\\CLSID\\"+guidString(CLSID_Wangshu);LSTATUS s=RegDeleteTreeW(HKEY_CURRENT_USER,key.c_str());if(s!=ERROR_SUCCESS&&s!=ERROR_FILE_NOT_FOUND)result=HRESULT_FROM_WIN32(s);if(SUCCEEDED(init))CoUninitialize();return result;}
