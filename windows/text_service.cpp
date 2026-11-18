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
#include <functional>
#include "../core/engine.h"
#include "voice_process.h"
#include "voice_position.h"
#include "grapheme_backspace.h"
#include "editor_selection.h"
#include "clipboard_edit.h"
#include "clipboard_source.h"
#include "clipboard_store.h"
#include "numeric_candidates.h"
#include "phrase_associations.h"
#include "../core/mixed_composition.h"
#include "context_candidates.h"
#include "personal_store.h"
#include "learning_queue.h"
#include "tsf_extended.h"
#include "symbol_panel.h"
#include "prediction_task.h"

// Some SDK libraries omit the input-scope property defined by inputscope.h.
#ifdef _MSC_VER
extern "C" __declspec(selectany)
#endif
const GUID GUID_PROP_INPUTSCOPE={0x1713dd5a,0x68e7,0x4a5b,{0x9a,0xf6,0x59,0x2a,0x59,0x5c,0x77,0x8d}};
#ifdef __MINGW32__
const IID IID_ITfInputScope={0xfde1eaee,0x6924,0x4cdf,{0x91,0xe7,0xda,0x38,0xcf,0xf5,0x55,0x9d}};
#endif

static HINSTANCE moduleHandle;
static LONG objectCount=0,serverLocks=0;
static void retainLearningCallback(){InterlockedIncrement(&objectCount);}
static void releaseLearningCallback(){InterlockedDecrement(&objectCount);}
// Project-owned, stable identifiers; not identifiers of another input method.
const CLSID CLSID_Wangshu={0x2158928d,0x82f3,0x4d52,{0x91,0x4e,0xa8,0x3a,0x56,0xf9,0xc1,0x6d}};
const GUID PROFILE_Wangshu={0x297331cb,0x1972,0x4d66,{0xb7,0x3b,0xa7,0x64,0x4f,0x28,0x53,0x9d}};
static constexpr LANGID Chinese=MAKELANGID(LANG_CHINESE,SUBLANG_CHINESE_SIMPLIFIED);
static constexpr UINT VoiceMessage=WM_APP+31;
static constexpr UINT VoiceTimer=32;
static constexpr UINT LearningTimer=33;
static constexpr UINT PredictionTimer=34;
static constexpr UINT VoiceUndoTimer=35;
static constexpr wchar_t VoiceUndoHint[]=L"语音已插入 · Ctrl+Shift+Z 撤销本次";
static constexpr wchar_t CursorPanelHint[]=L"← → 移动 · Home/End 首尾 · Enter 选择 · Esc 关闭";
static constexpr wchar_t SelectionPanelHint[]=L"← → 扩展选区 · Home/End 首尾 · Enter/Esc 收拢";
static constexpr UINT BatchDeleteTimer=36;
static constexpr wchar_t BatchDeleteHint[]=L"松开退格键删除光标前全部文字 · Esc 取消";
static constexpr wchar_t BatchUndoHint[]=L"已批量删除 · Ctrl+Shift+Z 撤销本次";
static constexpr wchar_t UppercaseHint[]=L"下一字母大写 · Ctrl+Shift+U 取消";
static constexpr LPARAM PreviousKeyDown=0x40000000;
static constexpr wchar_t PredictionWindowClass[]=L"WangshuImePredictionDispatch";
static std::mutex predictionWindowGate;
static unsigned predictionWindowCount=0;
template<class T> static void drop(T*& value){if(value){value->Release();value=nullptr;}}
static std::wstring wide(const std::string& s){auto u=wangshu::utf16(s);return std::wstring(u.begin(),u.end());}
static std::string narrow(const std::wstring& s){return wangshu::utf8(std::u16string(s.begin(),s.end()));}
static std::wstring modulePath(){std::vector<wchar_t> p(32768);DWORD n=GetModuleFileNameW(moduleHandle,p.data(),static_cast<DWORD>(p.size()));return n&&n<p.size()?std::wstring(p.data(),n):L"";}
// Share only immutable built-in words inside this process; each service owns its composition and audio session.
static std::shared_ptr<const wangshu::Engine> sharedLexicon(const std::wstring& directory){static std::mutex gate;static std::weak_ptr<const wangshu::Engine> cached;static std::wstring cachedDirectory;std::lock_guard<std::mutex> lock(gate);if(directory==cachedDirectory)if(auto existing=cached.lock())return existing;auto fresh=std::make_shared<wangshu::Engine>();if(!fresh->load(narrow(directory)))return {};cachedDirectory=directory;cached=fresh;return fresh;}
static std::wstring guidString(REFGUID id){wchar_t s[40];StringFromGUID2(id,s,40);return s;}
static HRESULT inputScopeAllows(ITfContext* context,TfEditCookie cookie,ITfRange* range,bool personalized=false,bool* privateScope=nullptr,bool prediction=false,bool requireExplicitScope=false){
    if(privateScope)*privateScope=false;
    if(!context||!range)return E_POINTER;
    // Missing legacy metadata is compatible. A failed existing query is not
    // permission for optional context capture or personal ranking/learning.
    // A query denial is unknown, not proof of an explicit PRIVATE scope.
    auto unknown=[&](HRESULT reason=E_FAIL){if(privateScope)*privateScope=true;return personalized?(FAILED(reason)&&reason!=E_ACCESSDENIED?reason:E_FAIL):S_OK;};
    ITfReadOnlyProperty* property=nullptr;
    HRESULT h=context->GetAppProperty(GUID_PROP_INPUTSCOPE,&property);
    if(h==S_FALSE||h==E_NOTIMPL){drop(property);return requireExplicitScope?E_ACCESSDENIED:S_OK;}
    if(h!=S_OK||!property){drop(property);return unknown(h);}
    VARIANT value;VariantInit(&value);h=property->GetValue(cookie,range,&value);drop(property);
    if(h==S_FALSE&&value.vt==VT_EMPTY){
        // GetValue's empty result also covers mixed/partial ranges. Only the
        // collapsed caret is compatible default; selected text stays ambiguous.
        LONG relation=1;HRESULT collapsed=range->CompareStart(cookie,range,TF_ANCHOR_END,&relation);VariantClear(&value);
        return !requireExplicitScope&&SUCCEEDED(collapsed)&&relation==0?S_OK:unknown(FAILED(collapsed)?collapsed:E_FAIL);
    }
    if(h!=S_OK||value.vt!=VT_UNKNOWN||!value.punkVal){VariantClear(&value);return unknown(h);}
    ITfInputScope* scope=nullptr;h=value.punkVal->QueryInterface(IID_ITfInputScope,reinterpret_cast<void**>(&scope));VariantClear(&value);
    if(h!=S_OK||!scope){drop(scope);return unknown(h);}
    InputScope* scopes=nullptr;UINT count=0;h=scope->GetInputScopes(&scopes,&count);drop(scope);
    // Bound malformed provider output; do not dereference a successful null list.
    if(h!=S_OK||!scopes||!count||count>64){CoTaskMemFree(scopes);return unknown(h);}
    bool privateValue=false,secret=false,nonProse=false;
    for(UINT i=0;i<count;++i){
        const auto scope=scopes[i];
        bool protectedValue=scope==IS_PASSWORD||scope==IS_NUMERIC_PASSWORD||scope==IS_NUMERIC_PIN||scope==IS_ALPHANUMERIC_PIN||scope==IS_ALPHANUMERIC_PIN_SET;
        secret|=protectedValue;privateValue|=protectedValue||scope==IS_PRIVATE;
        nonProse|=scope==IS_URL||scope==IS_EMAIL_USERNAME||scope==IS_EMAIL_SMTPEMAILADDRESS||
            scope==IS_EMAILNAME_OR_ADDRESS||scope==IS_DIGITS||scope==IS_NUMBER||scope==IS_NUMBER_FULLWIDTH||
            (scope>=IS_TELEPHONE_FULLTELEPHONENUMBER&&scope<=IS_TELEPHONE_LOCALNUMBER);
    }
    CoTaskMemFree(scopes);if(privateScope)*privateScope=privateValue;
    return secret||(personalized&&privateValue)||(prediction&&nonProse)?E_ACCESSDENIED:S_OK;
}
class VoiceGuard final:public ITfEditSession {
    LONG refs=1;ITfContext* context;bool prediction;
public:
    explicit VoiceGuard(ITfContext* c,bool forPrediction=false):context(c),prediction(forPrediction){c->AddRef();}
    ~VoiceGuard(){context->Release();}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out) override{if(!out)return E_POINTER;*out=nullptr;if(id!=IID_IUnknown&&id!=IID_ITfEditSession)return E_NOINTERFACE;*out=static_cast<ITfEditSession*>(this);AddRef();return S_OK;}
    ULONG STDMETHODCALLTYPE AddRef() override{return InterlockedIncrement(&refs);}
    ULONG STDMETHODCALLTYPE Release() override{ULONG n=InterlockedDecrement(&refs);if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie) override{TF_SELECTION selection{};ULONG n=0;HRESULT h=context->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&selection,&n);if(FAILED(h)||n!=1||!selection.range){drop(selection.range);return E_FAIL;}h=inputScopeAllows(context,cookie,selection.range,true,nullptr,prediction);selection.range->Release();return h;}
};
class VoicePositionSession final:public ITfEditSession {
 LONG refs=1;ITfContext* context;bool prediction;
public:
 std::shared_ptr<const wangshu::voice_position::Snapshot> snapshot;
 explicit VoicePositionSession(ITfContext* c,bool forPrediction=false):context(c),prediction(forPrediction){c->AddRef();}
 ~VoicePositionSession(){context->Release();}
 HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out)override{if(!out)return E_POINTER;*out=nullptr;if(id!=IID_IUnknown&&id!=IID_ITfEditSession)return E_NOINTERFACE;*out=this;AddRef();return S_OK;}
 ULONG STDMETHODCALLTYPE AddRef()override{return InterlockedIncrement(&refs);}
 ULONG STDMETHODCALLTYPE Release()override{ULONG n=InterlockedDecrement(&refs);if(!n)delete this;return n;}
 HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie)override{try{snapshot.reset();VoiceGuard guard(context,prediction);HRESULT h=guard.DoEditSession(cookie);if(FAILED(h))return h;auto fresh=std::make_shared<wangshu::voice_position::Snapshot>();h=wangshu::voice_position::capture(context,cookie,*fresh);if(FAILED(h))return h;h=guard.DoEditSession(cookie);if(SUCCEEDED(h))snapshot=std::move(fresh);return h;}catch(...){return E_FAIL;}}
};

// The edit cookie is usable only inside the synchronous callback. Disarm a
// retained session before returning to the key handler.
class SynchronousEditSession final:public ITfEditSession {
    LONG refs=1;
    std::function<HRESULT(TfEditCookie)> operation;
public:
    explicit SynchronousEditSession(std::function<HRESULT(TfEditCookie)> action):operation(std::move(action)){}
    void disarm(){operation={};}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out)override{
        if(!out)return E_POINTER;
        *out=nullptr;
        if(id!=IID_IUnknown&&id!=IID_ITfEditSession)return E_NOINTERFACE;
        *out=this;AddRef();return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef()override{return InterlockedIncrement(&refs);}
    ULONG STDMETHODCALLTYPE Release()override{ULONG n=InterlockedDecrement(&refs);if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie)override{
        auto action=std::move(operation);
        disarm();
        if(!action)return E_ABORT;
        try{return action(cookie);}catch(...){return E_FAIL;}
    }
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
    bool association=false,builtin=false,personal=false,readable=false,ranking=false,rankingReadable=false,autoLearn=false,fuzzy=false,fuzzyShortcut=false;ITfContext* context=nullptr;unsigned long long epoch=0,voiceGeneration=0,calculationGeneration=0;
    std::uint64_t clipboardGeneration=0;
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
 LONG refs=1;TextService* service;ITfContext* context;CandidateProof proof;std::string value;unsigned long long revision;bool executed=false;
public:
 CalculationSession(TextService*,ITfContext*,CandidateProof,std::string,unsigned long long);
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
    friend struct WindowsClipboardTestAccess;
    friend class EditSession;
    friend class CalculationSession;
    friend class LearningActionSession;
    friend class CandidateElement;
    DWORD activationFlags=0;unsigned long long revision=0,pendingRevision=0;
    LONG refs=1;ITfThreadMgr* manager=nullptr;TfClientId client=0;DWORD sinkCookie=TF_INVALID_COOKIE;bool keysAdvised=false;
    ITfContext* composingContext=nullptr;ITfComposition* composition=nullptr;RECT caretBounds{};bool caretValid=false;
    HWND popup=nullptr;HFONT font=nullptr;std::shared_ptr<const wangshu::Engine> sharedEngine;bool loaded=false,chinese=true,math=false,listening=false,noLearn=false,learningWriteFailed=false,learningTimerActive=false,privateInput=false;
    VoiceProcess localVoice;std::string voiceReview,voiceRecovery;
    wangshu::voice_format::Mode voiceFormat=wangshu::voice_format::Mode::Raw;
    struct VoiceUndo {
        std::shared_ptr<ITfContext> owner;
        wangshu::voice_position::Snapshot inserted;
        wangshu::clipboard_edit::Edit reverse;
        ULONGLONG expires;
    };
    std::unique_ptr<VoiceUndo> voiceUndo;
    unsigned long long voiceUndoGeneration=0;
    struct VoiceUndoClaim {LPARAM message;unsigned long long generation;};
    std::optional<VoiceUndoClaim> voiceUndoClaim;
    bool voiceUndoKeyDown=false;
    struct UppercaseLatch {
        std::shared_ptr<ITfContext> owner;
        unsigned long long epoch;
        bool directEnglish;
    };
    struct UppercaseKey {
        WPARAM key;
        LPARAM message;
        std::shared_ptr<const UppercaseLatch> latch;
        std::optional<wangshu::voice_position::Snapshot> position;
        std::string input;
        wchar_t letter=0;
    };
    std::shared_ptr<const UppercaseLatch> uppercaseLatch;
    std::optional<UppercaseKey> uppercaseClaim;
    std::shared_ptr<const wangshu::voice_position::Snapshot> voicePosition;unsigned long long voiceGeneration=0;bool voiceLastStopPreserved=false;
    bool calculationAssociation=false;std::string associationPersonalRevision,associationLearningRevision;
    std::string calculation;wangshu::context_math::Kind calculationKind=wangshu::context_math::Kind::Value;std::shared_ptr<const wangshu::voice_position::Snapshot> calculationPosition;ITfContext* calculationTarget=nullptr;unsigned long long calculationGeneration=0;
    struct PredictionRequest {
        std::shared_ptr<ITfContext> target;
        std::shared_ptr<const wangshu::voice_position::Snapshot> position;
        std::string settings;
        unsigned long long generation, revision, epoch, due, expires;
        unsigned modes;
        bool english, numericPanel;
    };
    std::shared_ptr<PredictionRequest> predictionRequest;
    std::shared_ptr<wangshu::PredictionTask> predictionWork;
    std::vector<wangshu::Candidate> builtinCandidates;
    HWND predictionWindow=nullptr;
    struct BackspaceClaim {
        std::shared_ptr<ITfContext> owner;
        wangshu::grapheme_backspace::Plan plan;
        unsigned long long revision, epoch;
        LPARAM message;
    };
    std::unique_ptr<BackspaceClaim> backspaceClaim;
    bool expiredBackspaceClaim=false;
    struct EditorPanel {
        std::shared_ptr<ITfContext> owner;
        wangshu::editor_selection::Snapshot expected;
        bool selecting;
    };
    struct EditorKeyClaim {
        ITfContext* owner;
        WPARAM key;
        LPARAM message;
        unsigned long long stamp, epoch;
        bool opening, dispatched=false;
    };
    std::shared_ptr<EditorPanel> editorPanel;
    std::optional<EditorKeyClaim> editorKeyClaim;
    struct BatchDelete {
        std::shared_ptr<ITfContext> owner;
        wangshu::voice_position::Snapshot expected;
        std::optional<wangshu::clipboard_edit::Edit> undo;
        unsigned long long stamp, epoch, expires;
        bool prompt=false;
    };
    std::shared_ptr<BatchDelete> batchDelete;
    unsigned long long batchGeneration=0;
    std::optional<LPARAM> batchBackClaim, batchUndoClaim, batchEscapeClaim;
    bool batchBackDown=false, batchUndoDown=false;
    struct ClipboardPanel {
        std::shared_ptr<ITfContext> owner;
        wangshu::voice_position::Snapshot expected;
        wangshu::ClipboardHistory history;
        std::optional<wangshu::ClipboardItem> splitEntry;
        wangshu::clipboard_edit::SplitSelection split;
        std::uint64_t generation=0,epoch=0;
        size_t page=0;
    };
    struct ClipboardKeyClaim {
        std::weak_ptr<ClipboardPanel> panel;
        std::uint64_t generation;
        WPARAM key;
        LPARAM message;
    };
    std::shared_ptr<wangshu::ClipboardStore> clipboardStore;
    std::shared_ptr<ClipboardPanel> clipboardPanel;
    std::optional<ClipboardKeyClaim> clipboardKeyClaim;
    std::uint64_t clipboardGeneration=0,clipboardMouseGeneration=0;
    size_t clipboardMouseRow=0;
    bool clipboardListening=false;
    DWORD clipboardSequence=0;
    bool clipboardContext(ITfContext* context,std::uint64_t epoch);
    bool clipboardCurrent(const std::shared_ptr<ClipboardPanel>& panel,std::uint64_t generation);
    HRESULT clipboardScope(ITfContext* context,TfEditCookie cookie,bool requireExplicit=false);
    bool verifyClipboard(const std::shared_ptr<ClipboardPanel>& panel,std::uint64_t generation);
    void startClipboard(ITfContext* context);
    void clearClipboard() noexcept;
    void publishClipboard(const std::shared_ptr<ClipboardPanel>& panel);
    void clipboardCandidates();
    bool selectClipboard(const CandidateProof& expected,size_t index);
    bool applyClipboard(const std::shared_ptr<ClipboardPanel>& panel,std::uint64_t generation,
                        const wangshu::clipboard_edit::Edit& edit,bool& attempted);
    void syncClipboardListener() noexcept;
    void clipboardChanged() noexcept;
    static bool clipboardKey(WPARAM key);
    void clipboardKeyDown(const std::shared_ptr<ClipboardPanel>& panel,WPARAM key);
    ITfUIElementMgr* uiManager=nullptr;CandidateElement* uiCandidates=nullptr;DWORD uiId=TF_INVALID_COOKIE;bool hostShowsOwnUI=true,notifyingUI=false;
    std::unique_ptr<wangshu::WindowsPersonalStore> personalStore;
    std::shared_ptr<wangshu::ConfirmedLearningQueue> learningQueue;unsigned long long seenLearningFailure=0,seenLearningCancelled=0;
    CandidateProof displayed;ITfContext* candidateContext=nullptr;unsigned long long focusEpoch=0;
    std::wstring compositionText; // Last successfully written owned composition, never document history.
    std::string buffer;size_t page=0;std::vector<wangshu::Candidate> candidates;std::wstring status;
    ISpRecognizer* recognizer=nullptr;ISpRecoContext* recoContext=nullptr;ISpRecoGrammar* grammar=nullptr;ITfContext* voiceTarget=nullptr;
    const wangshu::Engine& dictionary()const{static const wangshu::Engine empty;return sharedEngine?*sharedEngine:empty;}
    static constexpr size_t QuickFuzzyPage=static_cast<size_t>(-1);
    std::vector<wangshu::Candidate> pinyinPage(const std::string& input,size_t requested,
            const wangshu::PersonalData& personal,const wangshu::LearningData& learning,CandidateProof* proof){
        constexpr size_t PageSize=9,MaxScanPages=16;
        const auto now=wangshu::WindowsPersonalStore::learningNow();
        auto exact=[&](size_t index){return learning.lookup(dictionary(),personal,input,index,now);};
        const bool quick=requested==QuickFuzzyPage;
        auto current=quick?std::vector<wangshu::Candidate>{}:exact(requested);
        const bool fuzzyEnabled=chinese&&!math&&learning.fuzzyMask!=0;
        if(proof)proof->fuzzyShortcut=fuzzyEnabled;
        if(!fuzzyEnabled||(!quick&&(!current.empty()||requested>MaxScanPages)))return current;
        const auto variants=wangshu::fuzzy_pinyin::variants(input,learning.fuzzyMask);
        if(variants.empty())return current;
        std::set<std::string> seen;
        size_t skip=0;
        if(quick){
            for(const auto& word:exact(0))seen.insert(word.text);
        }else{
            size_t firstFuzzy=0;
            for(size_t i=0;i<requested;++i){
                auto prior=exact(i);
                if(prior.empty())break;
                for(const auto& word:prior)seen.insert(word.text);
                firstFuzzy=i+1;
                if(prior.size()<PageSize)break;
            }
            skip=(requested-firstFuzzy)*PageSize;
        }
        size_t accepted=0;
        for(const auto& key:variants){
            const auto normalized=wangshu::LearningData::candidateKey(key);
            size_t variantCount=0;
            auto append=[&](const std::string& word,int score){
                if(!seen.insert(word).second)return;
                ++variantCount;
                if(accepted++>=skip)current.push_back({word,"模糊 · "+key,score});
            };
            for(const auto& term:personal.words){
                if(term.pinyin==normalized)append(term.text,0);
                if(variantCount>=PageSize||current.size()>=PageSize)break;
            }
            if(variantCount<PageSize&&current.size()<PageSize){
                for(const auto& word:dictionary().lookup(key,PageSize)){
                    append(word.text,word.score);
                    if(variantCount>=PageSize||current.size()>=PageSize)break;
                }
            }
            if(current.size()>=PageSize)break;
        }
        if(proof&&!current.empty()){proof->fuzzy=true;proof->autoLearn=false;}
        return current;
    }
    std::vector<wangshu::Candidate> personalLookup(const std::string& input,size_t candidatePage=0,CandidateProof* proof=nullptr){
        if(wangshu::symbol_panel::active(input))
            return candidatePage==QuickFuzzyPage?std::vector<wangshu::Candidate>{}:wangshu::symbol_panel::lookup(dictionary(),input,candidatePage);
        if(input.empty()||input[0]=='\\'||input.find('`')!=std::string::npos||privateInput||(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_CONSOLE))){
            if(candidatePage==QuickFuzzyPage)return {};
            return dictionary().lookup(input,9,candidatePage);
        }
        if(proof)proof->personal=true;
        wangshu::PersonalSnapshot snapshot;
        try{if(!personalStore)personalStore=std::make_unique<wangshu::WindowsPersonalStore>();snapshot=personalStore->read();if(proof){proof->readable=true;proof->personalRevision=snapshot.revision;}if(snapshot.fromBackup&&status.empty())status=L"个人词库正在使用有效备份；请在本地管理程序确认恢复";}
        catch(...){if(status.empty())status=L"个人词库暂不可读，文件保留；内置候选仍可用";if(candidatePage==QuickFuzzyPage)return {};return dictionary().lookup(input,9,candidatePage);}
        if(proof)proof->ranking=true;
        try{auto weights=personalStore->readLearningFresh();if(proof){proof->rankingReadable=true;proof->learningRevision=weights.revision;proof->autoLearn=weights.data.enabled&&!noLearn&&!weights.fromBackup;}if(weights.fromBackup&&status.empty())status=L"个人排序使用备份；恢复前暂停自动学习";return pinyinPage(input,candidatePage,snapshot.data,weights.data,proof);}
        catch(...){if(status.empty())status=L"个人排序不可读，未重置；原词库仍可用";if(candidatePage==QuickFuzzyPage)return {};return snapshot.data.lookup(dictionary(),input,candidatePage);}
    }
    unsigned candidateModes()const{return activationFlags^(chinese?0x10000000u:0)^(math?0x20000000u:0)^(noLearn?0x40000000u:0)^(privateInput?0x08000000u:0);}
    void captureCandidates(){
        if(localVoice.active()){
            candidates.clear();
            displayed=CandidateProof{};
            drop(candidateContext);
            return;
        }
        if(clipboardPanel){clipboardCandidates();return;}
        displayed=CandidateProof{};displayed.input=buffer;displayed.voice=voiceReview;displayed.page=page;displayed.modes=candidateModes();displayed.epoch=focusEpoch;displayed.voiceGeneration=voiceGeneration;displayed.calculation=calculation;displayed.calculationGeneration=calculationGeneration;displayed.calculationKind=calculationKind;
        if(calculationAssociation){displayed.association=true;displayed.personal=true;displayed.readable=true;displayed.ranking=true;displayed.rankingReadable=true;displayed.personalRevision=associationPersonalRevision;displayed.learningRevision=associationLearningRevision;}
        if(!builtinCandidates.empty()){
            displayed.builtin=true;displayed.ranking=true;displayed.rankingReadable=true;displayed.learningRevision=associationLearningRevision;
        }
        if(!voiceReview.empty())candidates={{voiceReview,"语音待核对"}};
        else if(displayed.builtin)candidates=builtinCandidates;
        else if(!calculation.empty())candidates={{calculation,calculationAssociation?"常用语联想":calculationKind==wangshu::context_math::Kind::Substitution?"计算 · 代入":"计算 · 精确结果"}};
        else{
            candidates=personalLookup(buffer,page,&displayed);
            if(page==QuickFuzzyPage&&candidates.empty()){
                page=0;
                captureCandidates();
                return;
            }
        }
        ITfContext* next=composingContext?composingContext:!voiceReview.empty()?voiceTarget:!calculation.empty()?calculationTarget:nullptr;if(next)next->AddRef();else next=focused();drop(candidateContext);candidateContext=next;displayed.context=next;
    }
    bool validCandidate(ITfContext* context,const CandidateProof& proof){
        if(localVoice.active())return false;
        if(proof.clipboardGeneration)
            return clipboardPanel&&context==proof.context&&clipboardCurrent(clipboardPanel,proof.clipboardGeneration);
        if(proof.association&&(noLearn||privateInput||!calculationAssociation))return false;
        if(proof.builtin&&(noLearn||privateInput||builtinCandidates.empty()))return false;
        if(!manager||!loaded||!context||context!=proof.context||focusEpoch!=proof.epoch||buffer!=proof.input||voiceReview!=proof.voice||calculation!=proof.calculation||(!proof.calculation.empty()&&(calculationGeneration!=proof.calculationGeneration||calculationKind!=proof.calculationKind))||(!proof.voice.empty()&&voiceGeneration!=proof.voiceGeneration)||page!=proof.page||candidateModes()!=proof.modes||disabled(context))return false;
        ITfContext* current=focused();bool scope=current?current==context:composingContext==context||voiceTarget==context;drop(current);if(!scope)return false;
        if(proof.personal){try{if(!personalStore)personalStore=std::make_unique<wangshu::WindowsPersonalStore>();auto now=personalStore->readFresh();if(proof.association&&now.fromBackup)return false;if(!proof.readable||now.revision!=proof.personalRevision)return false;}catch(...){if(proof.readable)return false;}}
        if(proof.ranking){try{
            if(!personalStore)return false;
            auto now=personalStore->readLearningFresh();
            if(proof.association&&(now.fromBackup||!now.data.phraseAssociations))return false;
            if(proof.builtin&&(now.fromBackup||!now.data.builtinPredictions))return false;
            if(!proof.rankingReadable||now.revision!=proof.learningRevision)return false;
        }catch(...){if(proof.rankingReadable)return false;}}
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
        std::string selected=expected.builtin&&index<candidates.size()?candidates[index].text:expected.calculation;
        if(index>=candidates.size()||!validCandidate(context,expected)){status=L"候选或个人词库已变化，请核对刷新后的候选再选择";update();return false;}
        if(expected.clipboardGeneration)return suffix.empty()&&selectClipboard(expected,index);
        if(!expected.calculation.empty())return selectCalculation(context,expected,std::move(selected),async);
        if(expected.input==wangshu::symbol_panel::Prefix){
            if(!suffix.empty())return false;
            const auto command=candidates[index].annotation;
            const auto commandText=wide(command);
            const auto token=revision;
            HRESULT opened=request(context,commandText,false,false,false,&expected);
            if(FAILED(opened)||revision!=token||buffer!=expected.input||
                    focusEpoch!=expected.epoch||composingContext!=context||compositionText!=commandText)
                return false;
            buffer=command;
            page=0;
            status.clear();
            update();
            return true;
        }
        if(expected.autoLearn&&candidates[index].annotation!="常用语")try{wangshu::LearningData::candidateKey(expected.input);wangshu::LearningData::candidateText(candidates[index].text);expected.learningWord=candidates[index].text;}catch(...){expected.autoLearn=false;}
        else expected.autoLearn=false;
        auto value=candidates[index].text+suffix;return commit(context,value,async,&expected);
    }
    void updateInputPrivacy(bool value){if(value&&!privateInput){cancelLearning();clearClipboard();clearVoiceUndo();clearUppercase();}privateInput=value;}
    bool personalizationAllowed(ITfContext* context,TfEditCookie cookie){
        TF_SELECTION selection{};ULONG count=0;HRESULT h=context->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&selection,&count);
        auto release=[](ITfRange* value){if(value)value->Release();};std::unique_ptr<ITfRange,decltype(release)> range(selection.range,release);
        if(FAILED(h)||count!=1||!range)return false;
        bool privateValue=false;h=inputScopeAllows(context,cookie,range.get(),true,&privateValue);updateInputPrivacy(privateValue);return SUCCEEDED(h);
    }
    bool learningScope(ITfContext* context,const CandidateProof& proof){
        return !(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_CONSOLE))&&!proof.fuzzy&&proof.ranking&&proof.rankingReadable&&!proof.input.empty()&&proof.input[0]!='\\'&&validCandidate(context,proof);
    }
    void recordConfirmedSelection(ITfContext* context,const CandidateProof& proof,unsigned long long expectedRevision) noexcept{
        // No file read, write, named-lock wait or worker join inside the edit cookie.
        // The range was confirmed under this cookie; a pure DTO is handed off.
        try{
            if(!proof.autoLearn||proof.learningWord.empty()||noLearn||!personalStore||expectedRevision!=revision||proof.epoch!=focusEpoch||proof.modes!=candidateModes()||buffer!=proof.input||!manager||!loaded||(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_CONSOLE)))return;
            ITfContext* current=focused();bool same=current==context;drop(current);if(!same)return;
            if(!learningQueue)learningQueue=std::make_shared<wangshu::ConfirmedLearningQueue>(
                retainLearningCallback,releaseLearningCallback);
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
            if(!learningQueue)learningQueue=std::make_shared<wangshu::ConfirmedLearningQueue>(
                retainLearningCallback,releaseLearningCallback);
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
        if(w==self->predictionWindow){
            if(m==WM_TIMER&&a==PredictionTimer){
                self->AddRef();self->pollPrediction();self->Release();return 0;
            }
            return DefWindowProcW(w,m,a,b);
        }
        if(m==WM_MOUSEACTIVATE)return MA_NOACTIVATE;
        if(m==WM_CLIPBOARDUPDATE){self->AddRef();self->clipboardChanged();self->Release();return 0;}
        if(m==WM_LBUTTONDOWN&&self->clipboardPanel){
            const auto row=(GET_Y_LPARAM(b)-44)/30;
            self->clipboardMouseGeneration=GET_Y_LPARAM(b)>=44&&static_cast<size_t>(row)<self->candidates.size()
                ?self->clipboardPanel->generation:0;
            self->clipboardMouseRow=static_cast<size_t>(row);
            return 0;
        }
        if(m==WM_LBUTTONDOWN)self->clipboardMouseGeneration=0;
        if(m==WM_CONTEXTMENU){try{self->candidateMenu(w,b);}catch(...){/* Menu failure never inserts text or resets data. */}return 0;}
        if(m==WM_PAINT){self->paint(w);return 0;}
        if(m==WM_LBUTTONUP){
            const auto row=(GET_Y_LPARAM(b)-44)/30;
            const auto generation=std::exchange(self->clipboardMouseGeneration,0);
            if(self->clipboardPanel||generation){
                try{
                    if(self->clipboardPanel&&generation==self->clipboardPanel->generation&&
                        GET_Y_LPARAM(b)>=44&&self->clipboardMouseRow==static_cast<size_t>(row))
                        self->selectClipboard(self->displayed,static_cast<size_t>(row));
                }catch(...){}
                return 0;
            }
            if(GET_Y_LPARAM(b)>=44&&static_cast<size_t>(row)<self->candidates.size()){
                ITfContext* c=self->focused();
                if(c){self->selectCandidate(c,static_cast<size_t>(row),true);c->Release();}
            }
            return 0;
        }
        if(m==WM_TIMER&&a==LearningTimer){if(self->learningTimerActive)self->update();return 0;}
        if(m==WM_TIMER&&a==VoiceTimer){self->pollVoice();return 0;}
        if(m==WM_TIMER&&a==VoiceUndoTimer){
            if(!self->voiceUndo||GetTickCount64()>=self->voiceUndo->expires){self->clearVoiceUndo();self->update();}
            return 0;
        }
        if(m==WM_TIMER&&a==BatchDeleteTimer){
            if(!self->batchDelete||GetTickCount64()>=self->batchDelete->expires){
                self->clearBatchDelete();
                self->update();
            }
            return 0;
        }
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
        std::wstring title=editorPanel?(editorPanel->selecting?L"望舒 · 选择文字":L"望舒 · 光标编辑"):
            clipboardPanel?(clipboardPanel->splitEntry?L"望舒 · 剪贴板拆词":L"望舒 · 剪贴板历史"):
            L"望舒 · "+wide(buffer)+(math?L"  数学":L"")+(listening?L"  本地聆听中":L"")+(noLearn?L"  不学习":L"")+(privateInput?L"  隐私保护":L"");TextOutW(dc,10,8,title.c_str(),static_cast<int>(title.size()));
        const auto& preview=localVoice.preview();
        const bool showSpeech=!preview.empty()||!voiceReview.empty();
        if(showSpeech){
            RECT box{10,44,540,174};
            auto text=preview.empty()?wide(voiceReview):L"实时预览："+preview;
            DrawTextW(dc,text.c_str(),static_cast<int>(text.size()),&box,DT_WORDBREAK|DT_NOPREFIX);
        }
        else for(size_t i=0;i<candidates.size();i++){
            auto label=wide(candidates[i].text)+L"   "+wide(candidates[i].annotation);
            if(builtinCandidates.empty())label=std::to_wstring(i+1)+L". "+label;
            for(auto& character:label)if(character==L'\n'||character==L'\r'||character==L'\t')character=L' ';
            RECT row{10,44+static_cast<int>(i)*30,540,74+static_cast<int>(i)*30};
            DrawTextW(dc,label.c_str(),static_cast<int>(label.size()),&row,DT_SINGLELINE|DT_END_ELLIPSIS|DT_NOPREFIX);
        }
        std::wstring hint=status;
        if(hint.empty()){
            if(buffer=="\\")hint=L"再按一次 \\ 打开符号分类";
            else if(buffer==wangshu::symbol_panel::Prefix)hint=L"1–9打开分类 · 输入 clip 再按 Enter 打开剪贴板";
            else if(wangshu::symbol_panel::active(buffer))hint=L"1–9插入符号 · PgUp/PgDn翻页 · Esc取消";
            else if(page==QuickFuzzyPage)hint=L"模糊候选 · 1–9选词 · PgUp返回首组";
            else if(displayed.fuzzyShortcut)hint=L"1–9选词 · PgUp/PgDn翻页 · Shift+PgDn查看模糊";
            else hint=L"1–9选词 · 空格确认 · PgUp/PgDn翻页 · 右键个人排序";
        }
        TextOutW(dc,10,showSpeech?184:48+static_cast<int>(candidates.size())*30,hint.c_str(),static_cast<int>(hint.size()));SelectObject(dc,old);EndPaint(w,&p);
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
        if(!manager||candidates.empty()||(buffer.empty()&&voiceReview.empty()&&calculation.empty()&&!clipboardPanel)){endCandidateUI();return;}
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
    bool showCandidateUI(BOOL show){hostShowsOwnUI=show!=FALSE&&!(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_UIELEMENTENABLEDONLY));if(popup)ShowWindow(popup,hostShowsOwnUI?SW_SHOWNOACTIVATE:SW_HIDE);syncClipboardListener();return hostShowsOwnUI;}
    HRESULT hostCandidateAction(CandidateElement* source,ITfContext* expected,UINT index,bool abort){
        if(source!=uiCandidates||uiId==TF_INVALID_COOKIE||!manager||!loaded)return E_UNEXPECTED;
        ITfContext* current=focused();bool valid=current&&current==expected&&!disabled(current);HRESULT result=E_ACCESSDENIED;
        if(valid){if(abort&&clipboardPanel){clearClipboard();status.clear();update();result=S_OK;}else if(abort&&!calculation.empty()){clearCalculation();status.clear();update();result=S_OK;}else if(abort&&!voiceReview.empty()){stopVoice();voiceRecovery.clear();status.clear();update();result=S_OK;}else if(abort)result=commit(current,"",true)?S_OK:E_FAIL;else if(index<candidates.size()&&index<source->values.size()&&wide(candidates[index].text)==source->values[index]&&validCandidate(current,source->proof))result=selectCandidate(current,index,true)?S_OK:E_FAIL;else{status=L"候选已变化，请重新选择";update();result=E_ABORT;}}
        drop(current);return result;
    }
    void updateImpl(){pollLearningStatus();captureCandidates();syncCandidateUI();makeWindow();if(!popup)return;if(!hostShowsOwnUI){ShowWindow(popup,SW_HIDE);syncClipboardListener();return;}if(buffer.empty()&&!listening&&status.empty()&&!clipboardPanel){ShowWindow(popup,SW_HIDE);syncClipboardListener();return;}RECT work{};POINT point{};GetCursorPos(&point);int height=voiceReview.empty()&&localVoice.preview().empty()?86+static_cast<int>(candidates.size())*30:232;int x=point.x,y=point.y+20;if(caretValid){point={caretBounds.left,caretBounds.bottom};x=point.x;y=point.y+4;}else{GUITHREADINFO gi{};gi.cbSize=sizeof(gi);if(GetGUIThreadInfo(0,&gi)&&gi.hwndCaret){point={gi.rcCaret.left,gi.rcCaret.bottom};ClientToScreen(gi.hwndCaret,&point);x=point.x;y=point.y+4;}}MONITORINFO monitor{};monitor.cbSize=sizeof(monitor);if(GetMonitorInfoW(MonitorFromPoint(point,MONITOR_DEFAULTTONEAREST),&monitor))work=monitor.rcWork;else SystemParametersInfoW(SPI_GETWORKAREA,0,&work,0);x=std::max<int>(work.left,std::min<int>(x,work.right-560));y=std::max<int>(work.top,std::min<int>(y,work.bottom-height));SetWindowPos(popup,HWND_TOPMOST,x,y,560,height,SWP_NOACTIVATE|SWP_SHOWWINDOW);syncClipboardListener();InvalidateRect(popup,nullptr,TRUE);}
    void update() noexcept{try{updateImpl();}catch(...){if(popup)ShowWindow(popup,SW_HIDE);}}
    void expireBackspace(){
        if(backspaceClaim){
            backspaceClaim.reset();
            expiredBackspaceClaim=true;
        }
    }
    void invalidatePending(){clearEditorPanel();clearBatchDelete();expireBackspace();++revision;if(pendingRevision){pendingRevision=0;status.clear();update();}}
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
        ++focusEpoch;
        clearUppercase();
        clearVoiceUndo();
        clearClipboard();
        clearCalculation();
        learningTimerActive=false;if(popup)KillTimer(popup,LearningTimer);
        cancelLearning();privateInput=false;invalidatePending();caretValid=false;stopVoice(true);buffer.clear();page=0;status.clear();endCandidateUI();
        HRESULT ended=releaseCompositionOnFocusLoss();
        if(FAILED(ended))try{status=L"旧输入文字已保留；编辑器尚未确认结束组合，请检查原输入框";}catch(...){}
        if(popup)ShowWindow(popup,SW_HIDE);
    }
    HRESULT request(ITfContext* c,const std::wstring& value,bool finish,bool async=false,bool notify=false,const CandidateProof* proof=nullptr){if(!c||disabled(c))return E_FAIL;auto edit=new(std::nothrow)EditSession(this,c,value,finish,notify,revision,proof);if(!edit)return E_OUTOFMEMORY;HRESULT result=E_FAIL;HRESULT h=c->RequestEditSession(client,edit,(async?TF_ES_ASYNCDONTCARE:TF_ES_SYNC)|TF_ES_READWRITE,&result);edit->Release();return FAILED(h)?h:result;}
    void completeAsync(unsigned long long token,HRESULT result){if(token!=revision||token!=pendingRevision)return;pendingRevision=0;if(SUCCEEDED(result)){if(!voiceReview.empty())stopVoice();buffer.clear();page=0;finishLearningStatus();if(voiceUndo)status=VoiceUndoHint;}else if(!voiceRecovery.empty())status=L"语音位置无法核对；结果暂存，Ctrl+Shift+V 重新定位后再确认";else status=L"编辑器拒绝插入，候选和拼音仍保留，可重试";update();}
    bool acceptsAsync(unsigned long long token)const{return token==revision&&token==pendingRevision&&manager&&loaded;}
    bool commit(ITfContext* c,const std::string& value,bool async=false,const CandidateProof* proof=nullptr){
        // A delimiter-only preedit never created a host composition. An empty
        // commit here must clear only local grammar, not the selected document text.
        if(!composition&&!buffer.empty()&&value.empty()&&voiceReview.empty()&&wangshu::mixed::raw(buffer).empty()){buffer.clear();page=0;status.clear();update();return true;}
        learningWriteFailed=false;
        if(async){unsigned long long token=++revision;pendingRevision=token;HRESULT h=request(c,wide(value),true,true,true,proof);if(h==TF_S_ASYNC){if(pendingRevision==token){status=L"正在等待编辑器插入，候选和拼音仍保留";update();}return true;}if(FAILED(h))completeAsync(token,h);return SUCCEEDED(h);}
        HRESULT h=request(c,wide(value),true,false,false,proof);if(SUCCEEDED(h)){if(!voiceReview.empty())stopVoice();buffer.clear();page=0;finishLearningStatus();if(voiceUndo)status=VoiceUndoHint;update();return true;}status=!voiceRecovery.empty()?L"语音位置无法核对；结果暂存，Ctrl+Shift+V 重新定位后再确认":L"编辑器暂时不接受输入；请重新聚焦后重试";update();return false;
    }
    void clearCalculation(){
        bool had=!calculation.empty();
        calculation.clear();builtinCandidates.clear();calculationAssociation=false;
        associationPersonalRevision.clear();associationLearningRevision.clear();
        calculationKind=wangshu::context_math::Kind::Value;
        calculationPosition.reset();drop(calculationTarget);++calculationGeneration;
        stopPrediction();
        if(had)endCandidateUI();
    }
    void stopPrediction(){
        predictionRequest.reset();
        if(predictionWindow)KillTimer(predictionWindow,PredictionTimer);
        if(predictionWork&&predictionWork->ready())predictionWork.reset();
    }
    void destroyPredictionWindow(){
        if(!predictionWindow)return;
        std::lock_guard<std::mutex> lock(predictionWindowGate);
        DestroyWindow(predictionWindow);
        predictionWindow=nullptr;
        if(--predictionWindowCount==0)UnregisterClassW(PredictionWindowClass,moduleHandle);
    }
    bool predictionCurrent(const PredictionRequest& request){
        auto current=[&]{
            return manager&&loaded&&predictionRequest.get()==&request&&
                request.generation==calculationGeneration&&request.revision==revision&&
                request.epoch==focusEpoch&&request.modes==candidateModes()&&
                GetTickCount64()<=request.expires&&!composition&&buffer.empty()&&
                !listening&&voiceReview.empty()&&!noLearn&&!privateInput;
        };
        if(!current()||disabled(request.target.get()))return false;
        ITfContext* target=focused();
        bool matches=target==request.target.get();
        drop(target);
        return matches&&current();
    }
    bool predictionSettingsCurrent(const PredictionRequest& request){
        if(!personalStore)return false;
        auto settings=personalStore->readLearningFresh();
        return !settings.fromBackup&&settings.data.builtinPredictions&&settings.revision==request.settings;
    }
    void requestPrediction(ITfContext* context,std::string settings,unsigned long long generation,
                           unsigned long long requestRevision){
        if(settings.empty()||generation!=calculationGeneration||requestRevision!=revision||
                !manager||!loaded||noLearn||privateInput)return;
        if(!predictionWindow){
            std::lock_guard<std::mutex> lock(predictionWindowGate);
            WNDCLASSW window{};
            window.lpfnWndProc=WindowProc;window.hInstance=moduleHandle;
            window.lpszClassName=PredictionWindowClass;
            if(!predictionWindowCount&&!RegisterClassW(&window))return;
            predictionWindow=CreateWindowExW(0,window.lpszClassName,L"",0,0,0,0,0,HWND_MESSAGE,
                                             nullptr,moduleHandle,this);
            if(predictionWindow)++predictionWindowCount;
            else if(!predictionWindowCount)UnregisterClassW(PredictionWindowClass,moduleHandle);
        }
        if(!predictionWindow||generation!=calculationGeneration||requestRevision!=revision)return;
        context->AddRef();
        auto owner=std::shared_ptr<ITfContext>(context,[](ITfContext* value){value->Release();});
        const auto now=GetTickCount64();
        predictionRequest=std::make_shared<PredictionRequest>(PredictionRequest{
            std::move(owner),{},std::move(settings),generation,requestRevision,focusEpoch,
            now+60,now+750,candidateModes(),!chinese,math});
        if(!SetTimer(predictionWindow,PredictionTimer,30,nullptr))stopPrediction();
    }
    std::shared_ptr<const wangshu::voice_position::Snapshot> predictionPosition(ITfContext* context){
        auto read=new(std::nothrow)VoicePositionSession(context,true);
        if(!read)return {};
        HRESULT checked=E_FAIL;
        HRESULT result=context->RequestEditSession(client,read,TF_ES_SYNC|TF_ES_READ,&checked);
        auto snapshot=read->snapshot;
        read->Release();
        return SUCCEEDED(result)&&SUCCEEDED(checked)?snapshot:nullptr;
    }
    void pollPrediction() noexcept{
        auto request=predictionRequest;
        auto stop=[&]{if(predictionRequest==request)stopPrediction();};
        try{
            if(!request||!predictionCurrent(*request)){stop();return;}
            if(GetTickCount64()<request->due||(predictionWork&&!predictionWork->ready()))return;
            if(!predictionSettingsCurrent(*request)){stop();return;}
            auto fresh=predictionPosition(request->target.get());
            if(!fresh||!predictionCurrent(*request)){stop();return;}
            if(!request->position){
                auto before=std::wstring_view(fresh->text).substr(0,fresh->caret);
                auto lastLine=before.find_last_of(L"\r\n");
                if(lastLine!=std::wstring_view::npos)before.remove_prefix(lastLine+1);
                if(before.empty()||before.size()>256){stop();return;}
                auto after=std::wstring_view(fresh->text).substr(fresh->caret,256);
                if(!after.empty()&&after.back()>=0xd800&&after.back()<=0xdbff)after.remove_suffix(1);
                request->position=std::move(fresh);
                predictionWork=wangshu::PredictionTask::start(sharedEngine,
                    narrow(std::wstring(before)),narrow(std::wstring(after)),request->english,request->numericPanel);
                if(!predictionWork)stop();
                return;
            }
            if(!predictionWork||!(*fresh==*request->position)||
                    !predictionSettingsCurrent(*request)||!predictionCurrent(*request)){stop();return;}
            auto items=predictionWork->take();
            if(items.empty()){stop();return;}
            std::string first=items.front().text;
            std::wstring hint=L"本地补全与下一词 · 点击候选追加";
            stop();
            calculation=std::move(first);
            builtinCandidates=std::move(items);
            associationLearningRevision=std::move(request->settings);
            calculationPosition=std::move(fresh);
            calculationTarget=request->target.get();calculationTarget->AddRef();
            status=std::move(hint);update();
        }catch(...){stop();}
    }
    void requestCalculation(ITfContext* c,bool cursorMovement) noexcept{try{
        bool had=!calculation.empty();clearCalculation();if(had)status.clear();
        if(!c||disabled(c)||!buffer.empty()||composition||listening||!voiceReview.empty()||(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_COMLESS|TF_TMAE_CONSOLE))){if(had)update();return;}
        ITfContext* current=focused();bool here=current==c;drop(current);if(!here){if(had)update();return;}
        // Disabled means no optional context read; no-learn also suppresses this
        // explicitly personal feature, without changing deterministic math.
        auto generation=calculationGeneration,requestRevision=revision;
        bool associationsEnabled=false;
        std::string builtinSettings;
        if(!noLearn&&!privateInput){try{
            if(!personalStore)personalStore=std::make_unique<wangshu::WindowsPersonalStore>();
            auto settings=personalStore->readLearningFresh();
            if(!settings.fromBackup&&(!cursorMovement||settings.data.cursorPredictions)){
                associationsEnabled=settings.data.phraseAssociations;
                if(settings.data.builtinPredictions)builtinSettings=std::move(settings.revision);
            }
        }catch(...){}}
        if(!math&&!associationsEnabled){
            requestPrediction(c,std::move(builtinSettings),generation,requestRevision);
            if(had)update();return;
        }
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
        if(!answer.ready&&associationsEnabled&&!noLearn&&!privateInput&&before->caret==before->text.size()){
            try{
                if(!personalStore)personalStore=std::make_unique<wangshu::WindowsPersonalStore>();
                auto settings=personalStore->readLearningFresh();
                if(settings.data.phraseAssociations&&!settings.fromBackup&&(!cursorMovement||settings.data.cursorPredictions)){
                    auto manual=personalStore->readFresh();
                    if(!manual.fromBackup){std::vector<std::wstring> phrases;for(const auto& value:manual.data.phrases)phrases.push_back(wide(value.text));
                        auto suggestion=wangshu::phrase_association::suggest(before->text,before->caret,phrases);
                        if(suggestion){answer.ready=true;answer.text=*suggestion;association=true;personalRevision=manual.revision;learningRevision=settings.revision;}
                    }
                }
            }catch(...){/* Unreadable settings/data never enable associations. */}
        }
        if(!answer.ready){
            requestPrediction(c,std::move(builtinSettings),generation,requestRevision);
            if(had)update();return;
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
    bool selectCalculation(ITfContext* c,const CandidateProof& proof,std::string value,bool async){
        if(value.empty()||!validCandidate(c,proof)||proof.calculationGeneration!=calculationGeneration)return false;
        auto token=++revision;pendingRevision=0;auto session=new(std::nothrow)CalculationSession(this,c,proof,std::move(value),token);if(!session)return false;
        HRESULT result=E_FAIL,h=c->RequestEditSession(client,session,(async?TF_ES_ASYNCDONTCARE:TF_ES_SYNC)|TF_ES_READWRITE,&result);session->Release();
        if(FAILED(h)||FAILED(result)){if(token==revision&&proof.calculationGeneration==calculationGeneration){status=L"编辑器未确认计算结果写入；请核对原文，未自动重试";update();}return false;}
        return true;
    }
    HRESULT applyCalculation(ITfContext* c,TfEditCookie cookie,const CandidateProof& proof,
                             const std::string& value,unsigned long long token){
        auto before=calculationPosition;
        auto current=[&]{
            return before&&token==revision&&proof.calculationGeneration==calculationGeneration&&
                before==calculationPosition&&c==calculationTarget&&!composition&&buffer.empty()&&
                !listening&&voiceReview.empty();
        };
        if(!current()||(!math&&!proof.association&&!proof.builtin)||!validCandidate(c,proof)||!current())return E_ABORT;
        VoiceGuard privacy(c,proof.builtin);HRESULT h=privacy.DoEditSession(cookie);if(FAILED(h))return h;
        if(!current())return E_ABORT;
        wangshu::voice_position::Snapshot now;h=wangshu::voice_position::capture(c,cookie,now);
        if(FAILED(h)||!current()||!(now==*before))return E_ABORT;
        ITfInsertAtSelection* insert=nullptr;ITfRange* raw=nullptr;h=c->QueryInterface(IID_ITfInsertAtSelection,reinterpret_cast<void**>(&insert));
        if(SUCCEEDED(h)){h=insert->InsertTextAtSelection(cookie,TF_IAS_QUERYONLY,L"",0,&raw);insert->Release();}wangshu::voice_position::Range range(raw);if(FAILED(h)||!range)return FAILED(h)?h:E_FAIL;
        // Prove the queried insertion range is exactly the freshly read collapsed caret.
        TF_SELECTION selected{};ULONG count=0;h=c->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&selected,&count);wangshu::voice_position::Range selection(selected.range);LONG first=1,last=1;
        if(FAILED(h)||count!=1||!selection||FAILED(range->CompareStart(cookie,selection.get(),TF_ANCHOR_START,&first))||first||FAILED(range->CompareEnd(cookie,selection.get(),TF_ANCHOR_END,&last))||last)return E_ABORT;
        h=privacy.DoEditSession(cookie);if(FAILED(h))return h;
        if(!current()||!validCandidate(c,proof)||!current())return E_ABORT;
        auto text=wide(value);h=range->SetText(cookie,0,text.c_str(),static_cast<LONG>(text.size()));
        // A confirmed applied write is never replayed, even if the host changed
        // focus/session during it. Do not move old carets or clear newer UI.
        if(!current())return h;
        if(SUCCEEDED(h)){
            bool placed=false;wangshu::voice_position::Range end;if(SUCCEEDED(wangshu::voice_position::clone(range.get(),end))&&SUCCEEDED(end->Collapse(cookie,TF_ANCHOR_END))&&current()){TF_SELECTION position{end.get(),{TF_AE_NONE,FALSE}};placed=SUCCEEDED(c->SetSelection(cookie,1,&position));}
            if(!current())return h;
            clearCalculation();status=placed?(proof.builtin?L"已追加本地联想":proof.association?L"已追加本地常用语候选":proof.calculationKind==wangshu::context_math::Kind::Substitution?L"已追加代入表达式；未知变量保持原样":L"已追加精确计算结果"):L"候选已写入；编辑器未确认光标，请检查原文";update();
        }else{status=L"编辑器未确认候选写入；请核对原文，未自动重试";update();}
        return h;
    }
    static bool ctrl(){return (GetKeyState(VK_CONTROL)&0x8000)!=0;}
    static bool shift(){return (GetKeyState(VK_SHIFT)&0x8000)!=0;}
    bool backspaceCurrent(ITfContext* context,unsigned long long stamp,unsigned long long epoch) {
        auto current=[&]{
            return manager&&loaded&&revision==stamp&&focusEpoch==epoch&&!composition&&buffer.empty()&&
                !listening&&voiceReview.empty()&&!privateInput&&
                !(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_COMLESS|TF_TMAE_CONSOLE));
        };
        if(!context||!current()||disabled(context))return false;
        auto* target=focused();
        const bool focusedContext=target==context;
        drop(target);
        return focusedContext&&current();
    }
    static bool plainBackspace(){
        return !ctrl()&&!shift()&&!(GetKeyState(VK_MENU)&0x8000);
    }
    HRESULT runSynchronousEdit(ITfContext* context,DWORD access,std::function<HRESULT(TfEditCookie)> action){
        auto* session=new(std::nothrow)SynchronousEditSession(std::move(action));
        if(!session)return E_OUTOFMEMORY;
        HRESULT result=E_FAIL;
        auto requested=context->RequestEditSession(client,session,TF_ES_SYNC|access,&result);
        session->disarm();
        session->Release();
        if(FAILED(requested))return requested;
        return result==TF_S_ASYNC?E_ABORT:result;
    }
    std::unique_ptr<BackspaceClaim> prepareBackspace(ITfContext* context,LPARAM message) noexcept {
        try{
            const auto stamp=revision, epoch=focusEpoch;
            if(!plainBackspace()||!backspaceCurrent(context,stamp,epoch))return {};
            context->AddRef();
            auto owner=std::shared_ptr<ITfContext>(context,[](ITfContext* value){value->Release();});
            std::optional<wangshu::grapheme_backspace::Plan> plan;
            auto result=runSynchronousEdit(context,TF_ES_READ,[&](TfEditCookie cookie)->HRESULT{
                VoiceGuard guard(context,true);
                auto current=[&]{return backspaceCurrent(context,stamp,epoch);};
                if(!current()||FAILED(guard.DoEditSession(cookie))||!current())return E_ABORT;
                auto caret=wangshu::grapheme_backspace::collapsedCaret(context,cookie);
                if(!caret||!wangshu::grapheme_backspace::complexNeighbor(cookie,caret.get())||
                    !current()||FAILED(guard.DoEditSession(cookie))||!current())return E_ABORT;
                plan=wangshu::grapheme_backspace::prepare(context,cookie,caret.get());
                if(!plan||FAILED(guard.DoEditSession(cookie)))return E_ABORT;
                return S_OK;
            });
            if(FAILED(result)||!plan||!backspaceCurrent(context,stamp,epoch))return {};
            return std::make_unique<BackspaceClaim>(BackspaceClaim{
                std::move(owner),std::move(*plan),stamp,epoch,message});
        }catch(...){return {};}
    }
    void applyBackspace(ITfContext* context,LPARAM message,const BackspaceClaim& claim) noexcept {
        try{
            auto current=[&]{return backspaceCurrent(context,claim.revision,claim.epoch);};
            if(context!=claim.owner.get()||message!=claim.message||!plainBackspace()||!current())return;
            const auto& plan=claim.plan;
            const auto result=runSynchronousEdit(context,TF_ES_READWRITE,[&](TfEditCookie cookie)->HRESULT{
                VoiceGuard guard(context,true);
                if(!current()||FAILED(guard.DoEditSession(cookie))||!current())return E_ABORT;
                auto caret=wangshu::grapheme_backspace::collapsedCaret(context,cookie);
                if(!caret||plan.insideCluster)return E_ABORT;
                auto range=wangshu::grapheme_backspace::deletionRange(cookie,caret.get(),plan);
                if(!range)return E_ABORT;
                wangshu::voice_position::Snapshot before;
                if(FAILED(wangshu::voice_position::capture(context,cookie,before))||!(before==plan.before)||
                    !current()||FAILED(guard.DoEditSession(cookie))||
                    !wangshu::grapheme_backspace::sameCaret(context,cookie,caret.get())||!current())return E_ABORT;
                auto expected=before.text;
                expected.erase(plan.begin,before.caret-plan.begin);
                const auto result=range->SetText(cookie,0,L"",0);
                // Once a write was attempted, the key is consumed even if the host rejects or partly applies it.
                if(FAILED(result)||!current())return E_ABORT;
                if(FAILED(range->Collapse(cookie,TF_ANCHOR_START))||!current())return E_ABORT;
                TF_SELECTION selection{range.get(),{TF_AE_NONE,FALSE}};
                if(FAILED(context->SetSelection(cookie,1,&selection))||!current()||
                    FAILED(guard.DoEditSession(cookie)))return E_ABORT;
                wangshu::voice_position::Snapshot after;
                if(FAILED(wangshu::voice_position::capture(context,cookie,after))||
                    after.text!=expected||after.caret!=plan.begin||!current())return E_ABORT;
                return S_OK;
            });
            if(!current())return;
            clearCalculation();
            invalidatePending();
            status=SUCCEEDED(result)?L"":plan.insideCluster?L"请将光标移到字符边界后退格":L"无法确认整簇退格，请核对原文";
            update();
        }catch(...){/* The claim was already consumed. */}
    }
#include "batch_delete.inc"
#include "editor_panel.inc"
    static char character(WPARAM key){if(key>='A'&&key<='Z')return static_cast<char>((shift()!=((GetKeyState(VK_CAPITAL)&1)!=0))?key:key+('a'-'A'));BYTE state[256];GetKeyboardState(state);wchar_t out[4]{};int n=ToUnicode(static_cast<UINT>(key),MapVirtualKeyW(static_cast<UINT>(key),MAPVK_VK_TO_VSC),state,out,4,4);return n==1&&out[0]>=32&&out[0]<127?static_cast<char>(out[0]):0;}
    static bool uppercaseShortcut(WPARAM key){
        return key=='U'&&ctrl()&&shift()&&!(GetKeyState(VK_MENU)&0x8000);
    }
    static wchar_t uppercaseLetter(WPARAM key){
        if(key<'A'||key>'Z')return 0;
        BYTE state[256]{};
        wchar_t result[4]{};
        if(!GetKeyboardState(state)||ToUnicode(static_cast<UINT>(key),MapVirtualKeyW(static_cast<UINT>(key),MAPVK_VK_TO_VSC),
                                             state,result,4,4)!=1)return 0;
        const auto letter=result[0];
        if(letter>=L'a'&&letter<=L'z')return static_cast<wchar_t>(letter-(L'a'-L'A'));
        return letter>=L'A'&&letter<=L'Z'?letter:0;
    }
    void clearUppercase(){
        uppercaseLatch.reset();
        // A claimed keyboard event must still be consumed after a focus change.
        // Discard the old document and context while retaining only its key identity.
        if(uppercaseClaim){uppercaseClaim->latch.reset();uppercaseClaim->position.reset();uppercaseClaim->input.clear();}
        if(status==UppercaseHint)status.clear();
    }
    bool uppercaseEligible(ITfContext* context){
        if(!context||!loaded||math||privateInput||clipboardPanel||listening||!voiceReview.empty()||
           (activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_COMLESS|TF_TMAE_CONSOLE)))return false;
        if(chinese?(!wangshu::mixed::active(buffer)||buffer.size()>=128):(!buffer.empty()||composition))return false;
        return !disabled(context);
    }
    bool uppercaseCurrent(ITfContext* context,const UppercaseLatch& latch){
        if(context!=latch.owner.get()||latch.epoch!=focusEpoch||latch.directEnglish==chinese||
           !uppercaseEligible(context))return false;
        auto* top=focused();
        const bool same=top==context;
        drop(top);
        return same&&latch.epoch==focusEpoch&&latch.directEnglish!=chinese;
    }
    HRESULT uppercasePosition(ITfContext* context,TfEditCookie cookie,wangshu::voice_position::Snapshot& value){
        VoiceGuard scope(context,true);
        auto allowed=scope.DoEditSession(cookie);
        if(FAILED(allowed))return allowed;
        auto snapshot=wangshu::voice_position::capture(context,cookie,value);
        return FAILED(snapshot)?snapshot:scope.DoEditSession(cookie);
    }
    bool canArmUppercase(ITfContext* context){
        return runSynchronousEdit(context,TF_ES_READ,[&](TfEditCookie cookie)->HRESULT{
            VoiceGuard guard(context,true);
            if(FAILED(guard.DoEditSession(cookie)))return E_ACCESSDENIED;
            return wangshu::grapheme_backspace::collapsedCaret(context,cookie)?S_OK:E_ABORT;
        })==S_OK;
    }
    bool claimUppercase(ITfContext* context,WPARAM key,LPARAM message) noexcept {
        try{
            if(uppercaseClaim&&uppercaseClaim->key==key&&uppercaseClaim->message==message)return true;
            uppercaseClaim.reset();
            const auto stamp=revision;
            if(uppercaseShortcut(key)){
                if(!uppercaseEligible(context))return false;
                if(auto armed=uppercaseLatch){
                    if(uppercaseCurrent(context,*armed)&&revision==stamp){
                        uppercaseClaim=UppercaseKey{key,message,std::move(armed),{},buffer};
                        return true;
                    }
                    clearUppercase();
                }
                context->AddRef();
                std::shared_ptr<ITfContext> owner(context,[](ITfContext* value){value->Release();});
                auto latch=std::make_shared<UppercaseLatch>(UppercaseLatch{std::move(owner),focusEpoch,!chinese});
                if(!uppercaseCurrent(context,*latch)||revision!=stamp)return false;
                if(!canArmUppercase(context)||!uppercaseCurrent(context,*latch)||revision!=stamp)return false;
                uppercaseClaim=UppercaseKey{key,message,std::move(latch),{},buffer};
                return true;
            }
            auto latch=uppercaseLatch;
            if(!latch)return false;
            if(!uppercaseCurrent(context,*latch)||key==VK_ESCAPE||key==VK_CAPITAL||key==VK_TAB||
               key==VK_RETURN||key==VK_LEFT||key==VK_RIGHT||key==VK_UP||key==VK_DOWN||
               key==VK_HOME||key==VK_END||key==VK_PRIOR||key==VK_NEXT||
               (!voiceModifier(key)&&(ctrl()||(GetKeyState(VK_MENU)&0x8000)||(key==VK_SPACE&&shift())))){
                clearUppercase();update();return false;
            }
            const auto letter=uppercaseLetter(key);
            if(!letter)return false;
            wangshu::voice_position::Snapshot position;
            const auto input=buffer;
            const auto read=runSynchronousEdit(context,TF_ES_READ,[&](TfEditCookie cookie){
                return uppercasePosition(context,cookie,position);
            });
            if(read!=S_OK||position.text.size()>=wangshu::voice_position::MaxUnits||
               uppercaseLatch!=latch||revision!=stamp||buffer!=input||!uppercaseCurrent(context,*latch)){
                if(uppercaseLatch==latch){clearUppercase();update();}
                return false;
            }
            uppercaseClaim=UppercaseKey{key,message,std::move(latch),std::move(position),input,letter};
            return true;
        }catch(...){clearUppercase();return false;}
    }
    void applyUppercase(ITfContext* context,const UppercaseKey& claimed) noexcept {
        try{
            const auto& latch=claimed.latch;
            if(!latch||!uppercaseCurrent(context,*latch)||buffer!=claimed.input)return;
            if(!claimed.position){
                if(!uppercaseShortcut(claimed.key)||(claimed.message&PreviousKeyDown))return;
                if(uppercaseLatch==latch)clearUppercase();
                else{
                    const auto stamp=revision;
                    if(!canArmUppercase(context)||!uppercaseCurrent(context,*latch)||revision!=stamp)return;
                    uppercaseLatch=latch;
                    status=UppercaseHint;
                }
                update();return;
            }
            if(uppercaseLatch!=latch||ctrl()||(GetKeyState(VK_MENU)&0x8000))return;
            clearUppercase();clearCalculation();invalidatePending();
            const auto stamp=revision;
            const char letter=static_cast<char>(claimed.letter);
            const auto next=buffer+letter;
            const auto value=latch->directEnglish?std::wstring(1,static_cast<wchar_t>(letter)):wide(wangshu::mixed::raw(next));
            auto expected=*claimed.position;
            expected.text.insert(expected.caret,1,static_cast<wchar_t>(letter));
            ++expected.caret;
            auto current=[&]{return revision==stamp&&buffer==claimed.input&&uppercaseCurrent(context,*latch)&&revision==stamp;};
            const auto applied=runSynchronousEdit(context,TF_ES_READWRITE,[&](TfEditCookie cookie)->HRESULT{
                if(!current())return E_ABORT;
                const auto result=edit(context,cookie,value,latch->directEnglish,nullptr,nullptr,&claimed,&stamp);
                if(FAILED(result)||!current())return E_ABORT;
                wangshu::voice_position::Snapshot actual;
                if(FAILED(uppercasePosition(context,cookie,actual))||!(actual==expected)||!current())return E_ABORT;
                return S_OK;
            });
            if(!current())return;
            if(applied==S_OK&&!latch->directEnglish){buffer=next;page=0;}
            status=applied==S_OK?L"":L"未能确认单次大写写入，请核对原文";
            update();
        }catch(...){/* A claimed key never falls back after an uncertain write. */}
    }
    static bool voiceModifier(WPARAM key){
        return key==VK_CONTROL||key==VK_LCONTROL||key==VK_RCONTROL||
               key==VK_SHIFT||key==VK_LSHIFT||key==VK_RSHIFT;
    }
    static bool voiceUndoShortcut(WPARAM key){
        return key=='Z'&&ctrl()&&shift()&&!(GetKeyState(VK_MENU)&0x8000);
    }
    void clearVoiceUndo(){
        ++voiceUndoGeneration;
        voiceUndo.reset();
        if(popup)KillTimer(popup,VoiceUndoTimer);
        if(status==VoiceUndoHint)status.clear();
    }
    void rememberVoiceWrite(ITfContext* context,TfEditCookie cookie,const std::wstring& inserted,
                            unsigned long long generation,unsigned long long stamp) noexcept {
        try{
            auto bound=voicePosition;
            if(!bound||composition||generation!=voiceGeneration||stamp!=revision||context!=voiceTarget)return;
            auto edit=wangshu::clipboard_edit::insert(*bound,inserted);
            if(!edit)return;
            const auto epoch=focusEpoch;
            auto current=[&]{return epoch==focusEpoch&&stamp==revision&&generation==voiceGeneration&&
                                    context==voiceTarget&&bound==voicePosition;};
            VoiceGuard guard(context);
            wangshu::voice_position::Snapshot actual;
            if(FAILED(guard.DoEditSession(cookie))||!current()||
               FAILED(wangshu::voice_position::capture(context,cookie,actual))||!(actual==edit->after)||
               !current()||FAILED(guard.DoEditSession(cookie))||!current())return;
            auto* focusedContext=focused();
            const bool same=focusedContext==context;
            drop(focusedContext);
            if(!same||!current())return;
            makeWindow();
            const auto expires=GetTickCount64()+20000;
            if(!popup||!SetTimer(popup,VoiceUndoTimer,20000,nullptr)||!current())return;
            context->AddRef();
            std::shared_ptr<ITfContext> owner(context,[](ITfContext* value){value->Release();});
            wangshu::clipboard_edit::Edit reverse{bound->caret,actual.caret,L"",*bound};
            voiceUndo=std::make_unique<VoiceUndo>(VoiceUndo{
                std::move(owner),std::move(actual),std::move(reverse),expires});
            ++voiceUndoGeneration;
        }catch(...){}
    }
    void undoVoiceWrite(ITfContext* context) noexcept {
        try{
            auto undo=std::move(voiceUndo);
            clearVoiceUndo();
            if(!undo||context!=undo->owner.get()||GetTickCount64()>undo->expires)return;
            const auto stamp=revision,epoch=focusEpoch;
            auto current=[&]{
                if(epoch!=focusEpoch||stamp!=revision||!loaded||listening||composition||!buffer.empty())return false;
                auto* top=focused();const bool same=top==context;drop(top);
                return same&&!disabled(context)&&epoch==focusEpoch&&stamp==revision;
            };
            if(!current())return;
            bool attempted=false;
            const auto result=runSynchronousEdit(context,TF_ES_READWRITE,[&](TfEditCookie cookie)->HRESULT{
                VoiceGuard guard(context);
                if(!current()||FAILED(guard.DoEditSession(cookie))||!current())return E_ABORT;
                auto caret=wangshu::grapheme_backspace::collapsedCaret(context,cookie);
                wangshu::voice_position::Snapshot before;
                if(!caret||FAILED(wangshu::voice_position::capture(context,cookie,before))||
                   !(before==undo->inserted)||!current())return E_ABORT;
                auto range=wangshu::clipboard_edit::editRange(caret.get(),cookie,before,undo->reverse);
                if(!range||FAILED(guard.DoEditSession(cookie))||
                   !wangshu::grapheme_backspace::sameCaret(context,cookie,caret.get())||!current())return E_ABORT;
                attempted=true;
                if(FAILED(range->SetText(cookie,0,L"",0))||!current()||FAILED(guard.DoEditSession(cookie))||!current())return E_FAIL;
                auto placed=wangshu::clipboard_edit::expectedCaret(context,cookie,undo->reverse.after);
                if(!placed||!current())return E_FAIL;
                TF_SELECTION selection{placed.get(),{TF_AE_NONE,FALSE}};
                if(FAILED(context->SetSelection(cookie,1,&selection))||!current()||
                   FAILED(guard.DoEditSession(cookie))||!current())return E_FAIL;
                wangshu::voice_position::Snapshot after;
                return SUCCEEDED(wangshu::voice_position::capture(context,cookie,after))&&
                       after==undo->reverse.after&&current()?S_OK:E_FAIL;
            });
            if(!current())return;
            status=result==S_OK?L"已撤销本次语音":attempted?
                L"编辑器未确认撤销结果，请核对正文":L"正文或位置已变化，本次语音未撤销";
            update();
        }catch(...){}
    }
    bool eats(ITfContext* c,WPARAM k){if(!loaded||disabled(c))return false;if(listening&&k==VK_ESCAPE)return true;if(!voiceReview.empty()&&(k==VK_RETURN||k==VK_SPACE||k==VK_ESCAPE))return true;if(ctrl()&&shift()&&(k=='M'||k=='V'))return true;if(ctrl()||(GetKeyState(VK_MENU)&0x8000))return false;if(k==VK_SPACE&&shift())return true;if(!chinese)return false;if(!buffer.empty()&&(k==VK_SPACE||k==VK_RETURN||k==VK_BACK||k==VK_ESCAPE||k==VK_NEXT||k==VK_PRIOR||(k>='1'&&k<='9')))return true;if(!buffer.empty()&&buffer[0]=='\\'&&k>='0'&&k<='9'&&!dictionary().lookup(buffer+static_cast<char>(k)).empty())return true;char a=character(k);if((buffer.empty()||buffer[0]!='\\')&&(a=='`'||(wangshu::mixed::active(buffer)&&a>=32&&a<=126)))return true;return (a>='a'&&a<='z')||(a>='A'&&a<='Z')||a=='\\'||(!buffer.empty()&&a=='\'')||(a&&std::strchr(",.?!;:",a));}
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
    void recognizeVoice(std::string text){
        if(voiceFormat==wangshu::voice_format::Mode::Math)text=dictionary().voiceMath(text);
        else if(voiceFormat==wangshu::voice_format::Mode::Numbers)text=narrow(wangshu::voice_format::numbers(wide(text)));
        reviewVoice(std::move(text));
    }
    void loadVoiceFormat(){
        voiceFormat=wangshu::voice_format::Mode::Raw;
        try{
            if(!personalStore)personalStore=std::make_unique<wangshu::WindowsPersonalStore>();
            voiceFormat=personalStore->readLearningFresh().data.speechFormat;
        }catch(...){/* The original text remains available when settings cannot be read. */}
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
        clearVoiceUndo();
        if(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_COMLESS|TF_TMAE_CONSOLE|TF_TMAE_UIELEMENTENABLEDONLY)){stopVoice();status=L"当前 Windows 输入上下文仅启用键盘文字，未启动麦克风";update();return;}
        if(localVoice.active()){
            localVoice.finish();
            if(localVoice.active())status=L"本地识别中，完成后请核对文字";
            else{stopVoice();status=L"本地录音已停止；未取得最终结果";}
            update();return;
        }
        if(listening){stopVoice();status.clear();update();return;}if(disabled(c))return;makeWindow();
        stopVoice(true);HRESULT allowed=bindVoice(c);
        if(FAILED(allowed)){status=L"无法完整核对输入位置或当前框不允许语音；未启动麦克风";update();return;}
        if(!voiceRecovery.empty()){voiceReview=std::move(voiceRecovery);voiceRecovery.clear();status=L"已重新定位；请核对文字，再按 Enter/空格插入，Esc取消";SetTimer(popup,VoiceTimer,250,nullptr);update();return;}
        loadVoiceFormat();
        auto base=std::filesystem::path(modulePath()).parent_path()/L"voice";
        if(std::filesystem::is_regular_file(base/L"models"/L"tokens.txt")){
            if(!popup||!SetTimer(popup,VoiceTimer,100,nullptr)||!localVoice.start(base)){
                stopVoice();status=L"本地语音进程未启动；键盘仍可用";update();return;
            }
            listening=true;status=L"正在准备本地模型；Ctrl+Shift+V 完成，切换输入框取消";update();return;
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
        const auto previousState=localVoice.state;
        const auto previousPreview=localVoice.preview();
        localVoice.poll();if(generation!=voiceGeneration)return;
        if(localVoice.state==VoiceProcess::Recording&&(previousState!=VoiceProcess::Recording||previousPreview!=localVoice.preview())){
            status=L"本地聆听 · Ctrl+Shift+V 完成（最长30秒）";update();
        }
        else if(localVoice.state==VoiceProcess::Complete)recognizeVoice(localVoice.text);
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
                    if(valid&&currentSession())recognizeVoice(narrow(value));
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
    HRESULT STDMETHODCALLTYPE Deactivate() override{
        clearEditorPanel();editorKeyClaim.reset();
        clearBatchDelete();batchBackClaim.reset();batchUndoClaim.reset();batchEscapeClaim.reset();
        batchBackDown=batchUndoDown=false;
        clearUppercase();uppercaseClaim.reset();clearVoiceUndo();voiceUndoClaim.reset();voiceUndoKeyDown=false;clearClipboard();clipboardKeyClaim.reset();clipboardStore.reset();if(learningQueue){learningQueue->close();learningQueue.reset();}seenLearningFailure=seenLearningCancelled=0;backspaceClaim.reset();expiredBackspaceClaim=false;++revision;pendingRevision=0;caretValid=false;clearCalculation();destroyPredictionWindow();stopVoice();voiceRecovery.clear();bool removeKeys=keysAdvised;keysAdvised=false;if(manager){ITfKeystrokeMgr* keys=nullptr;if(removeKeys&&SUCCEEDED(manager->QueryInterface(IID_ITfKeystrokeMgr,reinterpret_cast<void**>(&keys)))){keys->UnadviseKeyEventSink(client);keys->Release();}if(sinkCookie!=TF_INVALID_COOKIE){ITfSource* source=nullptr;if(SUCCEEDED(manager->QueryInterface(IID_ITfSource,reinterpret_cast<void**>(&source)))){source->UnadviseSink(sinkCookie);source->Release();}}}sinkCookie=TF_INVALID_COOKIE;endCandidateUI();drop(uiManager);activationFlags=0;drop(composition);drop(composingContext);compositionText.clear();drop(manager);client=0;if(popup){DestroyWindow(popup);popup=nullptr;}if(font){DeleteObject(font);font=nullptr;}buffer.clear();personalStore.reset();sharedEngine.reset();noLearn=false;learningWriteFailed=false;learningTimerActive=false;privateInput=false;loaded=false;return S_OK;
    }
    HRESULT edit(ITfContext* c,TfEditCookie cookie,const std::wstring& text,bool finish,bool* confirmed=nullptr,
                 const unsigned long long* expectedVoice=nullptr,const UppercaseKey* uppercase=nullptr,
                 const unsigned long long* uppercaseRevision=nullptr){
        if(confirmed)*confirmed=false;bool selectionPlaced=false,endAccepted=true;
        if(composition&&composingContext!=c)return E_UNEXPECTED;
        auto currentUppercase=[&]{
            return !uppercase||(revision==*uppercaseRevision&&uppercaseCurrent(c,*uppercase->latch)&&
                                buffer==uppercase->input&&revision==*uppercaseRevision);
        };
        if(!currentUppercase())return E_ABORT;
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
        auto matchesUppercase=[&]{
            if(!uppercase)return true;
            wangshu::voice_position::Snapshot actual;
            return currentUppercase()&&SUCCEEDED(uppercasePosition(c,cookie,actual))&&actual==*uppercase->position&&
                   (composition?sameCompositionRange(c,cookie,range,compositionText):
                    wangshu::grapheme_backspace::sameCaret(c,cookie,range))&&currentUppercase();
        };
        if(!matchesUppercase()){range->Release();return E_ABORT;}
        // Establish ownership before changing any text. A refused composition must be inert.
        if(!finish&&!composition){
            ITfContextComposition* cc=nullptr;
            ITfComposition* owned=nullptr;
            h=c->QueryInterface(IID_ITfContextComposition,reinterpret_cast<void**>(&cc));
            if(SUCCEEDED(h)){h=cc->StartComposition(cookie,range,this,uppercase?&owned:&composition);cc->Release();}
            if(uppercase){
                if(FAILED(h)||!owned||composition||!matchesUppercase()){
                    if(owned){owned->EndComposition(cookie);owned->Release();}
                    range->Release();
                    return FAILED(h)?h:E_ABORT;
                }
                composition=owned;
            }
            if(FAILED(h)||!composition){range->Release();return FAILED(h)?h:E_ACCESSDENIED;}
            composingContext=c;c->AddRef();started=true;
        }
        if(expectedVoice&&(*expectedVoice!=voiceGeneration||c!=voiceTarget||!voicePosition)){range->Release();return E_ABORT;}
        h=range->SetText(cookie,0,text.c_str(),static_cast<LONG>(text.size()));
        if(!currentUppercase()){range->Release();return E_ABORT;}
        if(SUCCEEDED(h)){
            if(!finish&&composition)compositionText.swap(nextCompositionText);
            ITfRange* end=nullptr;
            if(SUCCEEDED(range->Clone(&end))&&end){
                HRESULT collapsed=end->Collapse(cookie,TF_ANCHOR_END);TF_SELECTION selection{end,{TF_AE_NONE,FALSE}};
                // An applied key is consumed even when later caret confirmation fails.
                selectionPlaced=SUCCEEDED(collapsed)&&currentUppercase()&&SUCCEEDED(c->SetSelection(cookie,1,&selection));
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
    HRESULT STDMETHODCALLTYPE OnTestKeyDown(ITfContext* c,WPARAM k,LPARAM message,BOOL* eat) override{
        if(!eat)return E_POINTER;
        if(claimEditorKey(c,k,message)){*eat=TRUE;return S_OK;}
        if(editorPanel&&voiceModifier(k)){*eat=FALSE;return S_OK;}
        if(claimBatchKey(c,k,message)){*eat=TRUE;return S_OK;}
        if(k=='Z'){
            // A repeated preflight call for the same dispatch retains its
            // original ownership even if focus or the undo slot has changed.
            if(voiceUndoClaim&&voiceUndoClaim->message==message){*eat=TRUE;return S_OK;}
            voiceUndoClaim.reset();
            if(!(message&PreviousKeyDown))voiceUndoKeyDown=false;
            if(voiceUndoShortcut(k)&&((voiceUndo&&voiceUndo->owner.get()==c)||voiceUndoKeyDown)){
                voiceUndoClaim=VoiceUndoClaim{message,voiceUndoGeneration};
                voiceUndoKeyDown=true;
                *eat=TRUE;
                return S_OK;
            }
        }
        if(voiceUndo&&!voiceModifier(k)){clearVoiceUndo();update();}
        clipboardKeyClaim.reset();
        if(auto panel=clipboardPanel){
            if(c==panel->owner.get()&&clipboardCurrent(panel,panel->generation)){
                if(clipboardKey(k)){
                    clipboardKeyClaim=ClipboardKeyClaim{panel,panel->generation,k,message};
                    *eat=TRUE;
                    return S_OK;
                }
                if(ctrl()&&!shift()&&!(GetKeyState(VK_MENU)&0x8000)&&(k=='C'||k==VK_INSERT)){
                    *eat=FALSE;
                    return S_OK;
                }
            }
            clearClipboard();
            status.clear();
            update();
        }
        backspaceClaim.reset();expiredBackspaceClaim=false;
        bool hadCalculation=!calculation.empty();clearCalculation();
        if(hadCalculation){status.clear();update();}
        invalidatePending();
        if((listening||!voiceReview.empty())&&!voiceModifier(k)&&k!=VK_ESCAPE&&!(ctrl()&&shift()&&k=='V')&&
            (voiceReview.empty()||(k!=VK_RETURN&&k!=VK_SPACE))){
            stopVoice(true);status.clear();update();
        }
        if(claimUppercase(c,k,message)){*eat=TRUE;return S_OK;}
        *eat=eats(c,k);
        if(!*eat&&k==VK_BACK){backspaceClaim=prepareBackspace(c,message);*eat=backspaceClaim?TRUE:FALSE;}
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnTestKeyUp(ITfContext* c,WPARAM key,LPARAM,BOOL* eat) override{
        if(!eat)return E_POINTER;
        if(editorKeyClaim&&editorKeyClaim->key==key)editorKeyClaim.reset();
        if(key==VK_BACK&&(batchBackDown||batchBackClaim)){*eat=TRUE;return S_OK;}
        if(key=='Z'){batchUndoClaim.reset();batchUndoDown=false;}
        if(voiceModifier(key)&&batchDelete&&!batchDelete->undo){clearBatchDelete();update();}
        if(uppercaseClaim&&uppercaseClaim->key==key)uppercaseClaim.reset();
        if(key=='Z'){voiceUndoClaim.reset();voiceUndoKeyDown=false;}
        if(key==VK_BACK)expireBackspace();
        *eat=FALSE;
        if(clipboardPanel||editorPanel)return S_OK;
        const bool movement=key==VK_LEFT||key==VK_RIGHT||key==VK_UP||key==VK_DOWN||key==VK_HOME||key==VK_END;
        if(!ctrl()&&!(GetKeyState(VK_MENU)&0x8000)&&
                (movement||character(key)||key==VK_SPACE||key==VK_RETURN||key==VK_BACK||key==VK_DELETE))
            requestCalculation(c,movement);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnKeyUp(ITfContext* c,WPARAM key,LPARAM,BOOL* eat) override{
        if(!eat)return E_POINTER;
        if(key==VK_BACK&&(batchBackDown||batchBackClaim)){
            const bool wasDown=std::exchange(batchBackDown,false);
            batchBackClaim.reset();
            if(wasDown&&batchShortcut()&&batchDelete&&batchDelete->prompt&&status==BatchDeleteHint&&popup&&IsWindowVisible(popup))
                applyBatchDelete(c,false);
            else if(batchDelete&&!batchDelete->undo){clearBatchDelete();update();}
            *eat=TRUE;
            return S_OK;
        }
        if(key=='Z'){batchUndoClaim.reset();batchUndoDown=false;}
        if(voiceModifier(key)&&batchDelete&&!batchDelete->undo){clearBatchDelete();update();}
        if(uppercaseClaim&&uppercaseClaim->key==key)uppercaseClaim.reset();
        if(key=='Z'){voiceUndoClaim.reset();voiceUndoKeyDown=false;}
        *eat=FALSE;return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnPreservedKey(ITfContext*,REFGUID,BOOL* eat) override{if(!eat)return E_POINTER;*eat=FALSE;return S_OK;}
    HRESULT keyDownImpl(ITfContext* c,WPARAM k,LPARAM message,BOOL* eat){
        if(!eat)return E_POINTER;
        const bool hadCalculation=!calculation.empty();
        if(hadCalculation||predictionRequest)clearCalculation();
        if(hadCalculation){status.clear();update();}
        invalidatePending();*eat=eats(c,k);if(!*eat)return S_OK;
        if((k==VK_RETURN||k==VK_SPACE)&&buffer==wangshu::clipboard_edit::Command&&!ctrl()&&!shift()){
            auto proof=displayed;
            // This command has no preedit candidate row. Bind the existing
            // composition proof to the dispatch context before it is cleared.
            proof.context=c;
            const auto epoch=focusEpoch;
            if(commit(c,"",false,&proof)&&focusEpoch==epoch)startClipboard(c);
            *eat=TRUE;
            return S_OK;
        }
        if(ctrl()&&shift()&&k=='V'){
            if(message&PreviousKeyDown)return S_OK;
            if(!buffer.empty()&&!(candidates.empty()?commit(c,wangshu::mixed::raw(buffer)):selectCandidate(c,0)))return S_OK;
            startVoice(c);return S_OK;
        }
        if(listening&&k==VK_ESCAPE){stopVoice();voiceRecovery.clear();status.clear();update();return S_OK;}
        if(!voiceReview.empty()){if(k==VK_RETURN||k==VK_SPACE){std::string value=voiceReview;commit(c,value);*eat=TRUE;return S_OK;}if(k==VK_ESCAPE){stopVoice();voiceRecovery.clear();status.clear();update();return S_OK;}stopVoice(true);}
        if(listening)stopVoice();
        if(ctrl()&&shift()&&k=='M'){math=!math;status=math?L"数学模式 · \\alpha 符号 · 算式 = 后点选精确结果":L"";update();return S_OK;}
        if(k==VK_SPACE&&shift()){if(!buffer.empty()&&!commit(c,wangshu::mixed::raw(buffer)))return S_OK;chinese=!chinese;status=chinese?L"中文输入":L"English";update();return S_OK;}
        if(k==VK_NEXT||k==VK_PRIOR){
            if(page==QuickFuzzyPage)page=0;
            else if(k==VK_NEXT&&shift()&&displayed.fuzzyShortcut)page=QuickFuzzyPage;
            else if(k==VK_PRIOR&&page)page--;
            else if(k==VK_NEXT&&!personalLookup(buffer,page+1).empty())page++;
            update();
            return S_OK;
        }
        char mixedKey=character(k);
        if((buffer.empty()||buffer[0]!='\\')&&(mixedKey=='`'||(wangshu::mixed::active(buffer)&&mixedKey>=32&&mixedKey<=126))){
            if(buffer.size()>=128){*eat=TRUE;return S_OK;}buffer.push_back(mixedKey);page=0;status=L"中英分段：反引号切换原文英段；关闭英段后空格确认，Enter原文";if(mixedKey=='`'){update();return S_OK;}HRESULT h=request(c,wide(wangshu::mixed::raw(buffer)),false);if(FAILED(h)){buffer.pop_back();*eat=FALSE;}update();return S_OK;
        }
        if(!buffer.empty()&&buffer[0]=='\\'&&k>='0'&&k<='9'&&!dictionary().lookup(buffer+static_cast<char>(k)).empty()){buffer.push_back(static_cast<char>(k));page=0;*eat=SUCCEEDED(request(c,wide(wangshu::mixed::raw(buffer)),false));if(!*eat)buffer.pop_back();update();return S_OK;}
        if(!buffer.empty()&&k>='1'&&k<='9'){
            size_t n=static_cast<size_t>(k-'1');
            bool openingCategory=buffer==wangshu::symbol_panel::Prefix;
            if(n<candidates.size())*eat=selectCandidate(c,n)||openingCategory;
            return S_OK;
        }
        if(k==VK_SPACE||k==VK_RETURN){
            bool openingCategory=k==VK_SPACE&&buffer==wangshu::symbol_panel::Prefix&&!candidates.empty();
            *eat=k==VK_RETURN||candidates.empty()?commit(c,wangshu::mixed::raw(buffer)):
                selectCandidate(c,uiCandidates?uiCandidates->selection():0)||openingCategory;
            return S_OK;
        }
        if(k==VK_ESCAPE){*eat=commit(c,"");return S_OK;}
        if(k==VK_BACK){std::string previous=buffer;size_t previousPage=page;if(!buffer.empty())buffer.pop_back();page=0;if(wangshu::mixed::raw(previous)==wangshu::mixed::raw(buffer)){*eat=TRUE;update();return S_OK;}HRESULT h=request(c,wide(wangshu::mixed::raw(buffer)),buffer.empty());if(FAILED(h)){buffer=std::move(previous);page=previousPage;status=L"编辑器暂时拒绝删除，拼音仍保留，可重试";}*eat=TRUE;update();return S_OK;}
        char a=character(k);std::string punctuation=dictionary().punctuation(a,true);
        if(a&&std::strchr(",.?!;:",a)){
            if(!buffer.empty()&&!candidates.empty()&&buffer!=wangshu::symbol_panel::Prefix)
                *eat=selectCandidate(c,0,false,punctuation);
            else *eat=commit(c,wangshu::mixed::raw(buffer)+punctuation);
            return S_OK;
        }
        if(buffer.size()>=128){*eat=TRUE;return S_OK;}buffer.push_back(a);page=0;status.clear();HRESULT h=request(c,wide(wangshu::mixed::raw(buffer)),false);if(FAILED(h)){buffer.pop_back();*eat=FALSE;}update();return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnKeyDown(ITfContext* c,WPARAM k,LPARAM l,BOOL* eat) override{
        if(!eat)return E_POINTER;
        if(dispatchEditorKey(c,k,l)){*eat=TRUE;return S_OK;}
        if(editorPanel&&voiceModifier(k)){*eat=FALSE;return S_OK;}
        if(dispatchBatchKey(c,k,l)){*eat=TRUE;return S_OK;}
        if(k=='Z'){
            const auto undoClaim=std::exchange(voiceUndoClaim,std::nullopt);
            if(undoClaim&&undoClaim->message==l){
                *eat=TRUE;
                if(!(l&PreviousKeyDown)&&voiceUndoShortcut(k)&&undoClaim->generation==voiceUndoGeneration)
                    undoVoiceWrite(c);
                return S_OK;
            }
        }
        if(voiceUndo&&!voiceModifier(k)){clearVoiceUndo();update();}
        const auto clipboardClaim=std::exchange(clipboardKeyClaim,std::nullopt);
        if(clipboardClaim&&clipboardClaim->key==k&&clipboardClaim->message==l){
            *eat=TRUE;
            try{
                if(auto panel=clipboardClaim->panel.lock())
                    if(c==panel->owner.get()&&clipboardCurrent(panel,clipboardClaim->generation))clipboardKeyDown(panel,k);
            }catch(...){clearClipboard();status=L"剪贴板操作已停止，请核对正文";update();}
            return S_OK;
        }
        if(uppercaseClaim&&uppercaseClaim->key==k&&uppercaseClaim->message==l){
            auto claim=std::exchange(uppercaseClaim,std::nullopt);
            *eat=TRUE;
            applyUppercase(c,*claim);
            return S_OK;
        }
        if(k==VK_BACK){
            auto claim=std::move(backspaceClaim);
            const bool expired=expiredBackspaceClaim;
            expiredBackspaceClaim=false;
            if(claim||expired){
                *eat=TRUE;
                if(claim)applyBackspace(c,l,*claim);
                return S_OK;
            }
        }else{
            backspaceClaim.reset();expiredBackspaceClaim=false;
        }
        try{return keyDownImpl(c,k,l,eat);}
        catch(const std::bad_alloc&){*eat=FALSE;return E_OUTOFMEMORY;}
        catch(...){*eat=FALSE;return E_FAIL;}
    }
    HRESULT STDMETHODCALLTYPE OnInitDocumentMgr(ITfDocumentMgr*) override{return S_OK;}
    HRESULT STDMETHODCALLTYPE OnUninitDocumentMgr(ITfDocumentMgr*) override{return S_OK;}
    HRESULT STDMETHODCALLTYPE OnSetFocus(ITfDocumentMgr*,ITfDocumentMgr*) override{focusLost();return S_OK;}
    HRESULT STDMETHODCALLTYPE OnPushContext(ITfContext* c) override{clearUppercase();clearVoiceUndo();if(clipboardPanel){clearClipboard();status.clear();if(popup)ShowWindow(popup,SW_HIDE);}if(c!=voiceTarget&&(listening||!voiceReview.empty())){stopVoice(true);status.clear();if(popup)ShowWindow(popup,SW_HIDE);}clearCalculation();cancelLearning();++focusEpoch;invalidatePending();endCandidateUI();return S_OK;}
    HRESULT STDMETHODCALLTYPE OnPopContext(ITfContext* c) override{clearUppercase();clearVoiceUndo();if(clipboardPanel){clearClipboard();status.clear();endCandidateUI();if(popup)ShowWindow(popup,SW_HIDE);}clearCalculation();cancelLearning();invalidatePending();if(c==voiceTarget){stopVoice(true);status.clear();if(popup)ShowWindow(popup,SW_HIDE);}if(uiCandidates&&uiCandidates->target==c)endCandidateUI();return S_OK;}
    HRESULT STDMETHODCALLTYPE OnCompositionTerminated(TfEditCookie,ITfComposition* c) override{if(c==composition){clearUppercase();invalidatePending();drop(composition);drop(composingContext);compositionText.clear();buffer.clear();page=0;update();}return S_OK;}
};
#include "clipboard_panel.inc"

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
        if(SUCCEEDED(result)&&speech)service->rememberVoiceWrite(context,cookie,text,voiceGeneration,expectedRevision);
        if(SUCCEEDED(result)&&confirmed)service->recordConfirmedSelection(context,proof,expectedRevision);
    }catch(const std::bad_alloc&){result=E_OUTOFMEMORY;}catch(...){result=E_FAIL;}
    if(notify)try{service->completeAsync(expectedRevision,result);}catch(...){/* Do not propagate exceptions across COM. */}return result;
}
CalculationSession::CalculationSession(TextService* owner,ITfContext* target,CandidateProof captured,std::string selected,unsigned long long token):service(owner),context(target),proof(std::move(captured)),value(std::move(selected)),revision(token){service->AddRef();context->AddRef();}
CalculationSession::~CalculationSession(){context->Release();service->Release();}
HRESULT CalculationSession::DoEditSession(TfEditCookie cookie){if(executed)return E_ABORT;executed=true;try{HRESULT result=service->applyCalculation(context,cookie,proof,value,revision);if(FAILED(result)&&revision==service->revision&&proof.calculationGeneration==service->calculationGeneration){service->clearCalculation();service->status=L"候选已失效或编辑器未确认写入；未自动重试";service->update();}return result;}catch(...){return E_FAIL;}}
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
#ifdef _MSC_VER
// Match the Windows SDK declarations. MSVC exports these names via the linker.
#define WANGSHU_COM_ENTRY STDAPI
#else
#define WANGSHU_COM_ENTRY extern "C" __declspec(dllexport) HRESULT __stdcall
#endif
WANGSHU_COM_ENTRY DllCanUnloadNow(){return objectCount==0&&serverLocks==0?S_OK:S_FALSE;}
WANGSHU_COM_ENTRY DllGetClassObject(REFCLSID id,REFIID iid,void** p){if(id!=CLSID_Wangshu)return CLASS_E_CLASSNOTAVAILABLE;auto f=new(std::nothrow)Factory;if(!f)return E_OUTOFMEMORY;HRESULT h=f->QueryInterface(iid,p);f->Release();return h;}
#undef WANGSHU_COM_ENTRY
static HRESULT registryValue(const std::wstring& key,const wchar_t* name,const std::wstring& value){HKEY h=nullptr;LSTATUS s=RegCreateKeyExW(HKEY_CURRENT_USER,key.c_str(),0,nullptr,0,KEY_SET_VALUE,nullptr,&h,nullptr);if(s!=ERROR_SUCCESS)return HRESULT_FROM_WIN32(s);s=RegSetValueExW(h,name,0,REG_SZ,reinterpret_cast<const BYTE*>(value.c_str()),static_cast<DWORD>((value.size()+1)*sizeof(wchar_t)));RegCloseKey(h);return HRESULT_FROM_WIN32(s);}
extern "C" __declspec(dllexport) HRESULT __stdcall DllRegisterServer(){
    auto path=modulePath();if(path.empty())return E_FAIL;std::wstring key=L"Software\\Classes\\CLSID\\"+guidString(CLSID_Wangshu);HRESULT h=registryValue(key,nullptr,L"望舒中文与数学输入法");if(SUCCEEDED(h))h=registryValue(key+L"\\InprocServer32",nullptr,path);if(SUCCEEDED(h))h=registryValue(key+L"\\InprocServer32",L"ThreadingModel",L"Apartment");if(FAILED(h))return h;
    HRESULT init=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);ITfInputProcessorProfiles* p=nullptr;h=CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,IID_ITfInputProcessorProfiles,reinterpret_cast<void**>(&p));if(SUCCEEDED(h)){h=p->Register(CLSID_Wangshu);const wchar_t* name=L"望舒拼音 · 数学与本地语音";if(SUCCEEDED(h))h=p->AddLanguageProfile(CLSID_Wangshu,Chinese,PROFILE_Wangshu,name,static_cast<ULONG>(wcslen(name)),path.c_str(),static_cast<ULONG>(path.size()),0);if(SUCCEEDED(h))h=p->EnableLanguageProfile(CLSID_Wangshu,Chinese,PROFILE_Wangshu,TRUE);p->Release();}
    if(SUCCEEDED(h)){ITfCategoryMgr* categories=nullptr;h=CoCreateInstance(CLSID_TF_CategoryMgr,nullptr,CLSCTX_INPROC_SERVER,IID_ITfCategoryMgr,reinterpret_cast<void**>(&categories));if(SUCCEEDED(h)){h=categories->RegisterCategory(CLSID_Wangshu,GUID_TFCAT_TIP_KEYBOARD,CLSID_Wangshu);categories->Release();}}
    if(SUCCEEDED(init))CoUninitialize();
    return h;
}
extern "C" __declspec(dllexport) HRESULT __stdcall DllUnregisterServer(){HRESULT init=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);ITfCategoryMgr* c=nullptr;HRESULT result=S_OK;if(SUCCEEDED(CoCreateInstance(CLSID_TF_CategoryMgr,nullptr,CLSCTX_INPROC_SERVER,IID_ITfCategoryMgr,reinterpret_cast<void**>(&c)))){result=c->UnregisterCategory(CLSID_Wangshu,GUID_TFCAT_TIP_KEYBOARD,CLSID_Wangshu);c->Release();}ITfInputProcessorProfiles* p=nullptr;if(SUCCEEDED(CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,IID_ITfInputProcessorProfiles,reinterpret_cast<void**>(&p)))){HRESULT h=p->Unregister(CLSID_Wangshu);if(FAILED(h))result=h;p->Release();}auto key=L"Software\\Classes\\CLSID\\"+guidString(CLSID_Wangshu);LSTATUS s=RegDeleteTreeW(HKEY_CURRENT_USER,key.c_str());if(s!=ERROR_SUCCESS&&s!=ERROR_FILE_NOT_FOUND)result=HRESULT_FROM_WIN32(s);if(SUCCEEDED(init))CoUninitialize();return result;}
