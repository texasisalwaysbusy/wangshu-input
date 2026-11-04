// Executes production TSF editing code against a text-store fixture, not a replacement IME.
#include "../windows/text_service.cpp"
#include "tsf_stubs.h"
#include <iostream>
#include <stdexcept>
#include <functional>
struct Document{std::wstring value=L"beforeafter";LONG cursor=6,selectionEnd=-1;bool noFull=false,noRead=false,noSelection=false,ignoreMove=false,writeThenFail=false;std::function<void()> onRead,afterWrite;bool denyText=false,denyComposition=false,denySelection=false,nullComposition=false;};
class Range final:public RangeStub {
public:
    Document& doc;LONG start,end;
    Range(Document& d,LONG a,LONG b):doc(d),start(a),end(b){}
    HRESULT STDMETHODCALLTYPE SetText(TfEditCookie,DWORD,const WCHAR* text,LONG n) override{if(doc.denyText)return E_ACCESSDENIED;if(n<0)return E_INVALIDARG;doc.value.replace(start,end-start,text,n);end=start+n;if(doc.afterWrite){auto fn=std::move(doc.afterWrite);doc.afterWrite={};fn();}return doc.writeThenFail?E_FAIL:S_OK;}
    // Bounded plain-text fixture movement used by exact snapshot verification.
    HRESULT STDMETHODCALLTYPE ShiftStart(TfEditCookie,LONG requested,LONG* actual,const TF_HALTCOND* halt)override{
        if(!actual)return E_POINTER;
        if(halt)return E_NOTIMPL;
        LONG next=static_cast<LONG>(std::max<long long>(0,std::min<long long>(end,static_cast<long long>(start)+requested)));
        *actual=next-start;start=next;return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetText(TfEditCookie,DWORD flags,WCHAR* out,ULONG max,ULONG* count) override{if(doc.onRead){auto fn=std::move(doc.onRead);doc.onRead={};fn();}if(doc.noRead)return E_ACCESSDENIED;auto s=doc.value.substr(start,end-start);*count=std::min<ULONG>(max,s.size());std::copy_n(s.data(),*count,out);if((flags&TF_TF_MOVESTART)&&!doc.ignoreMove)start+=*count;return S_OK;}
    HRESULT STDMETHODCALLTYPE ShiftEndToRange(TfEditCookie,ITfRange* value,TfAnchor anchor) override{auto other=dynamic_cast<Range*>(value);if(!other||&other->doc!=&doc)return E_INVALIDARG;end=anchor==TF_ANCHOR_END?other->end:other->start;if(start>end)start=end;return S_OK;}
    HRESULT STDMETHODCALLTYPE Clone(ITfRange** out) override{*out=new Range(doc,start,end);return S_OK;}
    HRESULT STDMETHODCALLTYPE Collapse(TfEditCookie,TfAnchor anchor) override{if(anchor==TF_ANCHOR_END)start=end;else end=start;return S_OK;}
    HRESULT STDMETHODCALLTYPE CompareStart(TfEditCookie,ITfRange* value,TfAnchor anchor,LONG* result) override{
        if(!result)return E_POINTER;auto other=dynamic_cast<Range*>(value);if(!other||&other->doc!=&doc||(anchor!=TF_ANCHOR_START&&anchor!=TF_ANCHOR_END))return E_INVALIDARG;
        LONG point=anchor==TF_ANCHOR_END?other->end:other->start;*result=start<point?-1:start>point?1:0;return S_OK;
    }
    HRESULT STDMETHODCALLTYPE CompareEnd(TfEditCookie,ITfRange* value,TfAnchor anchor,LONG* result) override{
        if(!result)return E_POINTER;auto other=dynamic_cast<Range*>(value);if(!other||&other->doc!=&doc||(anchor!=TF_ANCHOR_START&&anchor!=TF_ANCHOR_END))return E_INVALIDARG;
        LONG point=anchor==TF_ANCHOR_END?other->end:other->start;*result=end<point?-1:end>point?1:0;return S_OK;
    }
};
class Composition final:public CompositionStub {
    ITfRange* range;ITfCompositionSink* sink;
public:
    Composition(ITfRange* r,ITfCompositionSink* s):range(r),sink(s){r->AddRef();s->AddRef();}
    ~Composition(){range->Release();sink->Release();}
    HRESULT STDMETHODCALLTYPE GetRange(ITfRange** out) override{*out=range;range->AddRef();return S_OK;}
    HRESULT STDMETHODCALLTYPE EndComposition(TfEditCookie cookie) override{return sink->OnCompositionTerminated(cookie,this);}
};
class ScopeProperty final:public ITfReadOnlyProperty,public ITfInputScope {
    LONG refs=1;InputScope scope;
public:
    explicit ScopeProperty(InputScope value):scope(value){}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out) override{if(!out)return E_POINTER;*out=nullptr;if(id==IID_IUnknown||id==IID_ITfReadOnlyProperty)*out=static_cast<ITfReadOnlyProperty*>(this);else if(id==IID_ITfInputScope)*out=static_cast<ITfInputScope*>(this);else return E_NOINTERFACE;AddRef();return S_OK;}
    ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
    ULONG STDMETHODCALLTYPE Release() override{ULONG n=--refs;if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE GetType(GUID* out) override{*out=GUID_PROP_INPUTSCOPE;return S_OK;}
    HRESULT STDMETHODCALLTYPE EnumRanges(TfEditCookie,IEnumTfRanges**,ITfRange*) override{return E_NOTIMPL;}
    HRESULT STDMETHODCALLTYPE GetValue(TfEditCookie,ITfRange*,VARIANT* value) override{VariantInit(value);value->vt=VT_UNKNOWN;value->punkVal=static_cast<ITfInputScope*>(this);AddRef();return S_OK;}
    HRESULT STDMETHODCALLTYPE GetContext(ITfContext**) override{return E_NOTIMPL;}
    HRESULT STDMETHODCALLTYPE GetInputScopes(InputScope** out,UINT* count) override{*out=static_cast<InputScope*>(CoTaskMemAlloc(sizeof(InputScope)));if(!*out)return E_OUTOFMEMORY;**out=scope;*count=1;return S_OK;}
    HRESULT STDMETHODCALLTYPE GetPhrase(BSTR**,UINT*) override{return E_NOTIMPL;}
    HRESULT STDMETHODCALLTYPE GetRegularExpression(BSTR*) override{return E_NOTIMPL;}
    HRESULT STDMETHODCALLTYPE GetSRGS(BSTR*) override{return E_NOTIMPL;}
    HRESULT STDMETHODCALLTYPE GetXML(BSTR*) override{return E_NOTIMPL;}
};
class ContextView final:public ITfContextView {
    LONG refs=1;RECT box;BOOL clipped;HRESULT outcome;
public:
    ContextView(RECT r,BOOL clip,HRESULT result):box(r),clipped(clip),outcome(result){}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out) override{if(!out)return E_POINTER;*out=nullptr;if(id!=IID_IUnknown&&id!=IID_ITfContextView)return E_NOINTERFACE;*out=this;AddRef();return S_OK;}
    ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}ULONG STDMETHODCALLTYPE Release() override{ULONG n=--refs;if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE GetRangeFromPoint(TfEditCookie,const POINT*,DWORD,ITfRange**) override{return E_NOTIMPL;}
    HRESULT STDMETHODCALLTYPE GetTextExt(TfEditCookie,ITfRange*,RECT* out,BOOL* clip) override{*out=box;*clip=clipped;return outcome;}
    HRESULT STDMETHODCALLTYPE GetScreenExt(RECT*) override{return E_NOTIMPL;}HRESULT STDMETHODCALLTYPE GetWnd(HWND*) override{return E_NOTIMPL;}
};
class DocumentManager final:public ITfDocumentMgr {
    LONG refs=1;ITfContext* context;
public:
    explicit DocumentManager(ITfContext* c):context(c){c->AddRef();}
    ~DocumentManager(){context->Release();}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out)override{if(!out)return E_POINTER;*out=nullptr;if(id!=IID_IUnknown&&id!=IID_ITfDocumentMgr)return E_NOINTERFACE;*out=this;AddRef();return S_OK;}
    ULONG STDMETHODCALLTYPE AddRef()override{return ++refs;}ULONG STDMETHODCALLTYPE Release()override{ULONG n=--refs;if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE CreateContext(TfClientId,DWORD,IUnknown*,ITfContext**,TfEditCookie*)override{return E_NOTIMPL;}
    HRESULT STDMETHODCALLTYPE Push(ITfContext*)override{return E_NOTIMPL;}HRESULT STDMETHODCALLTYPE Pop(DWORD)override{return E_NOTIMPL;}
    HRESULT STDMETHODCALLTYPE GetTop(ITfContext** out)override{if(!out)return E_POINTER;*out=context;context->AddRef();return S_OK;}
    HRESULT STDMETHODCALLTYPE GetBase(ITfContext** out)override{return GetTop(out);}
    HRESULT STDMETHODCALLTYPE EnumContexts(IEnumTfContexts**)override{return E_NOTIMPL;}
};

class Context final:public ContextStub {
public:
    ITfDocumentMgr* documentManager=nullptr; // weak, owned by the manager fixture
    HRESULT STDMETHODCALLTYPE GetDocumentMgr(ITfDocumentMgr** out)override{if(!out)return E_POINTER;*out=documentManager;if(*out)(*out)->AddRef();return *out?S_OK:E_FAIL;}
    Document doc;int starts=0,selections=0;InputScope scope=IS_DEFAULT;RECT layout{100,200,140,225};BOOL clipped=FALSE;HRESULT layoutResult=S_OK;bool layoutAvailable=false;bool deferred=false;std::function<void()> afterRead;std::vector<ITfEditSession*> queued;
    std::vector<HRESULT> flush(){auto copy=queued;queued.clear();std::vector<HRESULT> results;for(auto* edit:copy){results.push_back(edit->DoEditSession(99));edit->Release();}return results;}
    HRESULT STDMETHODCALLTYPE GetStart(TfEditCookie,ITfRange** out) override{*out=nullptr;if(doc.noFull)return E_NOTIMPL;*out=new Range(doc,0,0);return S_OK;}
    HRESULT STDMETHODCALLTYPE GetEnd(TfEditCookie,ITfRange** out) override{*out=nullptr;if(doc.noFull)return E_NOTIMPL;*out=new Range(doc,doc.value.size(),doc.value.size());return S_OK;}
    HRESULT STDMETHODCALLTYPE GetActiveView(ITfContextView** out) override{if(!layoutAvailable){*out=nullptr;return E_NOTIMPL;}*out=new ContextView(layout,clipped,layoutResult);return S_OK;}
    HRESULT STDMETHODCALLTYPE GetAppProperty(REFGUID id,ITfReadOnlyProperty** out) override{if(id!=GUID_PROP_INPUTSCOPE)return E_NOTIMPL;*out=new ScopeProperty(scope);return S_OK;}
    HRESULT STDMETHODCALLTYPE GetSelection(TfEditCookie,ULONG,ULONG,TF_SELECTION* out,ULONG* count) override{if(doc.noSelection){out->range=nullptr;*count=0;return E_FAIL;}out->range=new Range(doc,std::min(doc.cursor,doc.selectionEnd<0?doc.cursor:doc.selectionEnd),std::max(doc.cursor,doc.selectionEnd<0?doc.cursor:doc.selectionEnd));out->style={TF_AE_NONE,FALSE};*count=1;return S_OK;}
    HRESULT STDMETHODCALLTYPE RequestEditSession(TfClientId,ITfEditSession* edit,DWORD flags,HRESULT* result) override{if(deferred&&!(flags&TF_ES_SYNC)){edit->AddRef();queued.push_back(edit);*result=TF_S_ASYNC;}else *result=edit->DoEditSession(99);if((flags&TF_ES_READWRITE)==TF_ES_READ&&afterRead){auto fn=std::move(afterRead);afterRead={};fn();}return S_OK;}
    HRESULT STDMETHODCALLTYPE InsertTextAtSelection(TfEditCookie,DWORD flags,const WCHAR*,LONG,ITfRange** out) override{if(!(flags&TF_IAS_QUERYONLY))return E_INVALIDARG;*out=new Range(doc,std::min(doc.cursor,doc.selectionEnd<0?doc.cursor:doc.selectionEnd),std::max(doc.cursor,doc.selectionEnd<0?doc.cursor:doc.selectionEnd));return S_OK;}
    HRESULT STDMETHODCALLTYPE SetSelection(TfEditCookie,ULONG count,const TF_SELECTION* selected) override{if(doc.denySelection)return E_ACCESSDENIED;if(count!=1)return E_INVALIDARG;auto r=static_cast<Range*>(selected->range);doc.cursor=r->end;doc.selectionEnd=-1;selections++;return S_OK;}
    HRESULT STDMETHODCALLTYPE StartComposition(TfEditCookie,ITfRange* range,ITfCompositionSink* sink,ITfComposition** out) override{if(doc.denyComposition)return E_ACCESSDENIED;if(doc.nullComposition){*out=nullptr;return S_OK;}starts++;*out=new Composition(range,sink);return S_OK;}
    HRESULT STDMETHODCALLTYPE GetStatus(TF_STATUS* status) override{*status={};return S_OK;}
};
class Manager final:public ManagerStub,public ITfUIElementMgr {
    IUnknown* events=nullptr;ITfKeyEventSink* keys=nullptr;
public:
    bool supportsUI=false,allowOwnUI=false,failUIBegin=false;int begins=0,updates=0,ends=0;ITfUIElement* element=nullptr;ITfDocumentMgr* document=nullptr;
    ~Manager(){drop(element);drop(document);}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out)override{if(id==IID_ITfUIElementMgr&&supportsUI){if(!out)return E_POINTER;*out=static_cast<ITfUIElementMgr*>(this);AddRef();return S_OK;}return ManagerStub::QueryInterface(id,out);}
    ULONG STDMETHODCALLTYPE AddRef()override{return ManagerStub::AddRef();}ULONG STDMETHODCALLTYPE Release()override{return ManagerStub::Release();}
    HRESULT STDMETHODCALLTYPE GetFocus(ITfDocumentMgr** out)override{if(!out)return E_POINTER;*out=document;if(document)document->AddRef();return document?S_OK:E_FAIL;}
    HRESULT STDMETHODCALLTYPE BeginUIElement(ITfUIElement* e,BOOL* show,DWORD* id)override{begins++;if(failUIBegin)return E_FAIL;element=e;element->AddRef();*show=allowOwnUI;*id=42;return S_OK;}
    HRESULT STDMETHODCALLTYPE UpdateUIElement(DWORD id)override{if(id!=42)return E_INVALIDARG;updates++;return S_OK;}
    HRESULT STDMETHODCALLTYPE EndUIElement(DWORD id)override{if(id!=42)return E_INVALIDARG;ends++;drop(element);return S_OK;}
    HRESULT STDMETHODCALLTYPE GetUIElement(DWORD id,ITfUIElement** out)override{if(!out)return E_POINTER;*out=nullptr;if(id!=42||!element)return E_INVALIDARG;*out=element;element->AddRef();return S_OK;}
    HRESULT STDMETHODCALLTYPE EnumUIElements(IEnumTfUIElements**)override{return E_NOTIMPL;}
    HRESULT STDMETHODCALLTYPE AdviseSink(REFIID,IUnknown* sink,DWORD* cookie) override{events=sink;sink->AddRef();*cookie=1;return S_OK;}
    HRESULT STDMETHODCALLTYPE UnadviseSink(DWORD) override{drop(events);return S_OK;}
    HRESULT STDMETHODCALLTYPE AdviseKeyEventSink(TfClientId,ITfKeyEventSink* sink,BOOL) override{keys=sink;sink->AddRef();return S_OK;}
    HRESULT STDMETHODCALLTYPE UnadviseKeyEventSink(TfClientId) override{drop(keys);return S_OK;}
};
struct TextServiceTestAccess {
    static bool stageVoice(TextService* service,ITfContext* c,const std::string& text){service->stopVoice();service->voiceRecovery.clear();auto manager=dynamic_cast<Manager*>(service->manager);if(manager&&!manager->document)manager->document=new DocumentManager(c);if(FAILED(service->bindVoice(c)))return false;service->reviewVoice(text);return !service->voiceReview.empty();}
    static bool beginVoice(TextService* service,ITfContext* c){service->stopVoice();service->voiceRecovery.clear();return SUCCEEDED(service->bindVoice(c));}
    static void resultVoice(TextService* service,const std::string& text){service->reviewVoice(text);}
    static void resumeVoice(TextService* service,ITfContext* c){service->startVoice(c);}
    static bool recovery(TextService* service){return !service->voiceRecovery.empty();}
    static bool rebind(TextService* service,ITfContext* c){service->startVoice(c);return !service->voiceReview.empty();}
    static bool restrictedVoice(TextService* service,ITfContext* c){service->startVoice(c);return !service->localVoice.active()&&!service->recognizer&&!service->recoContext;}
    static bool click(TextService* service,ITfContext* c,const std::string& value){return service->commit(c,value,true);}
    static std::string pendingText(TextService* service){return service->buffer;}
    static bool caret(TextService* service,RECT expected){return service->caretValid&&EqualRect(&service->caretBounds,&expected);}
    static bool noCaret(TextService* service){return !service->caretValid;}
    static bool sameBuiltInDictionary(TextService* a,TextService* b){return a->sharedEngine&&a->sharedEngine==b->sharedEngine;}
    static bool chineseMode(TextService* service){return service->chinese;}
    static void terminateComposition(TextService* service){if(service->composition)service->OnCompositionTerminated(99,service->composition);}
    static bool ownCandidateUI(TextService* service){return service->hostShowsOwnUI;}
    static bool hasVoice(TextService* service){return !service->voiceReview.empty();}
};
static int checks=0;
static void check(bool ok,const char* label){if(!ok)throw std::runtime_error(label);checks++;std::cout<<"PASS "<<label<<std::endl;}
int main(){try{
    auto service=new TextService;auto context=new Context;
    check(SUCCEEDED(service->edit(context,7,L"n",false))&&context->doc.value==L"beforenafter","start composition preserves surrounding text");
    check(SUCCEEDED(service->edit(context,7,L"nihao",false))&&context->doc.value==L"beforenihaoafter"&&context->starts==1,"updates replace only the owned composition");
    check(SUCCEEDED(service->edit(context,7,L"你好",true))&&context->doc.value==L"before你好after"&&context->doc.cursor==8,"Chinese candidate commit and cursor position");
    check(SUCCEEDED(service->edit(context,7,L"𝕏",true))&&context->doc.value==L"before你好𝕏after"&&context->doc.cursor==10,"UTF16 surrogate pair committed without disturbing suffix");
    check(SUCCEEDED(service->edit(context,7,L"alpha",false))&&SUCCEEDED(service->edit(context,7,L"",true))&&context->doc.value==L"before你好𝕏after","cancel removes only active composition");
    context->doc.denyComposition=true;auto original=context->doc.value;
    check(FAILED(service->edit(context,7,L"x",false))&&context->doc.value==original,"composition refusal must not insert duplicate raw text");context->doc.denyComposition=false;
    context->doc.nullComposition=true;check(FAILED(service->edit(context,7,L"x",false))&&context->doc.value==original,"S_OK with null composition is an owner rejection, not approval");context->doc.nullComposition=false;
    check(SUCCEEDED(service->edit(context,7,L"shi",false)),"composition can restart after refusal");
    context->doc.denyText=true;original=context->doc.value;check(FAILED(service->edit(context,7,L"是",true))&&context->doc.value==original,"write refusal preserves original and composing text");context->doc.denyText=false;
    check(SUCCEEDED(service->edit(context,7,L"是",true))&&context->doc.value==L"before你好𝕏是after","retry after write refusal replaces original composition");
    context->doc.denySelection=true;check(SUCCEEDED(service->edit(context,7,L"好",true))&&context->doc.value==L"before你好𝕏是好after","caret refusal after insertion does not forward an already applied key");context->doc.denySelection=false;
    original=context->doc.value;context->scope=IS_PASSWORD;check(FAILED(service->edit(context,7,L"private",false))&&context->doc.value==original,"password input scope rejects Chinese composition without reading text");
    auto guard=new VoiceGuard(context);check(FAILED(guard->DoEditSession(7)),"password input scope rejects voice before microphone startup");guard->Release();
    context->scope=IS_NUMERIC_PASSWORD;guard=new VoiceGuard(context);check(FAILED(guard->DoEditSession(7)),"numeric password scope also rejects voice");guard->Release();context->scope=IS_DEFAULT;
    guard=new VoiceGuard(context);check(SUCCEEDED(guard->DoEditSession(7)),"ordinary text scope permits local voice preflight");guard->Release();
    context->layoutAvailable=true;check(SUCCEEDED(service->edit(context,7,L"位置",false))&&TextServiceTestAccess::caret(service,context->layout),"production edit caches TSF text bounds for candidate placement without an HWND caret");
    context->clipped=TRUE;service->edit(context,7,L"位置",false);check(TextServiceTestAccess::noCaret(service),"clipped text geometry is not reused as a visible candidate anchor");context->clipped=FALSE;context->layout={};service->edit(context,7,L"位置",false);check(TextServiceTestAccess::noCaret(service),"zero rectangle from minimized or invisible document is rejected");context->layout={100,200,140,225};context->layoutResult=TS_E_NOLAYOUT;service->edit(context,7,L"位置",false);check(TextServiceTestAccess::noCaret(service),"missing TSF layout retains ordinary platform caret fallback");service->edit(context,7,L"",true);
    service->Deactivate();service->Release();context->Release();check(objectCount==0,"composition and service references released");
    moduleHandle=GetModuleHandleW(nullptr);service=new TextService;auto manager=new Manager;context=new Context;
    check(SUCCEEDED(service->Activate(manager,17)),"TSF activation registers sinks and loads full shared data");
    BOOL eat=FALSE;bool digitsStayWithHost=true;auto beforeDigits=context->doc.value;
    for(WPARAM key:{'2','3','2','0','9','1'}){service->OnTestKeyDown(context,key,0,&eat);digitsStayWithHost&=!eat;service->OnKeyDown(context,key,0,&eat);digitsStayWithHost&=!eat;}
    check(digitsStayWithHost,"uncomposed Windows hardware digits remain with the host application");
    check(context->doc.value==beforeDigits&&TextServiceTestAccess::pendingText(service).empty(),"Windows TSF adds no phantom space or composition for direct digits");
    for(WPARAM key:{'N','I','H','A','O'}){service->OnKeyDown(context,key,0,&eat);check(eat,"production key sink consumes a composing letter");}
    service->OnKeyDown(context,VK_SPACE,0,&eat);check(eat&&context->doc.value==L"before你好after","production TSF key routing commits Chinese through edit sessions");
    for(WPARAM key:std::initializer_list<WPARAM>{VK_OEM_5,'S','U','B','9'})service->OnKeyDown(context,key,0,&eat);
    service->OnKeyDown(context,VK_SPACE,0,&eat);check(eat&&context->doc.value==L"before你好₉after","TSF mathematical alias digits are composed before candidate selection");
    auto beforeVoice=context->doc.value;TextServiceTestAccess::stageVoice(service,context,"语音核对");
    check(context->doc.value==beforeVoice,"speech transcript remains staged without automatic insertion");
    service->OnTestKeyDown(context,VK_RETURN,0,&eat);check(eat,"Enter is routed to the staged speech review");service->OnKeyDown(context,VK_RETURN,0,&eat);
    check(eat&&context->doc.value==L"before你好₉语音核对after"&&!TextServiceTestAccess::hasVoice(service),"reviewed voice commits through production TSF transaction and clears pending text");
    beforeVoice=context->doc.value;TextServiceTestAccess::stageVoice(service,context,"不可插入");service->OnKeyDown(context,VK_ESCAPE,0,&eat);check(eat&&context->doc.value==beforeVoice&&!TextServiceTestAccess::hasVoice(service),"Escape discards only pending speech without altering document");
    TextServiceTestAccess::stageVoice(service,context,"旧语音");service->OnKeyDown(context,'N',0,&eat);check(!TextServiceTestAccess::hasVoice(service)&&context->doc.value==L"before你好₉语音核对nafter","manual input cancels speech review before new composition");service->OnKeyDown(context,VK_ESCAPE,0,&eat);
    TextServiceTestAccess::stageVoice(service,context,"保密");context->scope=IS_PASSWORD;service->OnKeyDown(context,VK_RETURN,0,&eat);check(context->doc.value==beforeVoice,"password scope change rejects pending voice insertion");context->scope=IS_DEFAULT;service->OnSetFocus(FALSE);check(!TextServiceTestAccess::hasVoice(service),"focus loss clears pending speech and child process state");
    TextServiceTestAccess::stageVoice(service,context,"待取消语音");service->OnTestKeyDown(context,VK_LEFT,0,&eat);check(!eat&&!TextServiceTestAccess::hasVoice(service),"unhandled navigation cancels pending speech before caret movement while remaining with host");
    service->OnTestKeyDown(context,VK_LEFT,0,&eat);check(!eat,"unhandled navigation key remains with host application");
    service->Deactivate();service->Release();context->Release();manager->Release();check(objectCount==0,"activated sinks and edit sessions release all server objects");
    service=new TextService;manager=new Manager;context=new Context;ITfTextInputProcessorEx* extended=nullptr;
    check(SUCCEEDED(service->QueryInterface(IID_ITfTextInputProcessorEx,reinterpret_cast<void**>(&extended))),"Windows extended TSF activation interface available");
    check(SUCCEEDED(extended->ActivateEx(manager,18,TF_TMAE_SECUREMODE|TF_TMAE_COMLESS)),"extended secure/COM-less activation delegates normal sink ownership");
    check(TextServiceTestAccess::restrictedVoice(service,context),"restricted Windows activation cannot launch microphone or ASR child");
    extended->Release();service->Deactivate();service->Release();context->Release();manager->Release();check(objectCount==0,"extended activation retains no COM references after deactivation");
    service=new TextService;manager=new Manager;context=new Context;check(SUCCEEDED(service->Activate(manager,19)),"async edit fixture activates production service");context->deferred=true;
    for(WPARAM k:{'N','I'})service->OnKeyDown(context,k,0,&eat);auto composed=context->doc.value;
    check(TextServiceTestAccess::click(service,context,"你")&&context->doc.value==composed&&TextServiceTestAccess::pendingText(service)=="ni","TF_S_ASYNC retains composition until actual callback rather than treating queueing as success");
    context->doc.denyText=true;auto deferred=context->flush();check(deferred.size()==1&&FAILED(deferred[0])&&context->doc.value==composed&&TextServiceTestAccess::pendingText(service)=="ni","later editor refusal preserves pending candidate and original composition");context->doc.denyText=false;
    check(TextServiceTestAccess::click(service,context,"你"),"mouse candidate retry can be queued");deferred=context->flush();check(SUCCEEDED(deferred[0])&&context->doc.value==L"before你after"&&TextServiceTestAccess::pendingText(service).empty(),"actual successful async callback commits exactly once and clears pending state");
    for(WPARAM k:{'N','I'})service->OnKeyDown(context,k,0,&eat);TextServiceTestAccess::click(service,context,"你");service->OnKeyDown(context,'H',0,&eat);composed=context->doc.value;deferred=context->flush();check(deferred[0]==E_ABORT&&context->doc.value==composed&&TextServiceTestAccess::pendingText(service)=="nih","new typing invalidates an old queued candidate without deleting newly typed letters");service->OnKeyDown(context,VK_ESCAPE,0,&eat);
    auto beforeDeferredVoice=context->doc.value;TextServiceTestAccess::stageVoice(service,context,"旧语音");TextServiceTestAccess::click(service,context,"旧语音");service->OnSetFocus(FALSE);deferred=context->flush();check(deferred[0]==E_ABORT&&context->doc.value==beforeDeferredVoice,"focus loss prevents queued speech insertion into an obsolete target");
    for(WPARAM k:{'N','I'})service->OnKeyDown(context,k,0,&eat);TextServiceTestAccess::click(service,context,"你");composed=context->doc.value;service->Deactivate();deferred=context->flush();check(deferred[0]==E_ABORT&&context->doc.value==composed,"deactivation cancels outstanding async callbacks before any document mutation");service->Release();context->Release();manager->Release();check(objectCount==0,"queued edit sessions release all service and context references");
    auto first=new TextService;auto second=new TextService;auto firstManager=new Manager;auto secondManager=new Manager;auto firstContext=new Context;auto secondContext=new Context;ULONGLONG firstStart=GetTickCount64();
    check(SUCCEEDED(first->Activate(firstManager,20)),"first independent service loads shared immutable word data");ULONGLONG firstTime=GetTickCount64()-firstStart,secondStart=GetTickCount64();check(SUCCEEDED(second->Activate(secondManager,21))&&TextServiceTestAccess::sameBuiltInDictionary(first,second),"second service reuses the same read-only built-in dictionary");ULONGLONG secondTime=GetTickCount64()-secondStart;
    for(WPARAM k:{'N','I'})first->OnKeyDown(firstContext,k,0,&eat);for(WPARAM k:{'H','A','O'})second->OnKeyDown(secondContext,k,0,&eat);check(firstContext->doc.value==L"beforeniafter"&&secondContext->doc.value==L"beforehaoafter","shared lexicon does not share composition buffers across editor contexts");first->OnKeyDown(firstContext,VK_SPACE,0,&eat);check(firstContext->doc.value==L"before你after"&&secondContext->doc.value==L"beforehaoafter","committing one editor leaves another editor's active composition intact");
    first->Deactivate();first->Release();firstContext->Release();firstManager->Release();second->OnKeyDown(secondContext,VK_SPACE,0,&eat);check(secondContext->doc.value==L"before好after","deactivating one service leaves the remaining shared dictionary valid");second->Deactivate();second->Release();secondContext->Release();secondManager->Release();check(objectCount==0,"both isolated services release COM ownership after shared-word reuse");std::cout<<"METRIC in-process cloud Wine dictionary first/reuse activation ms="<<firstTime<<"/"<<secondTime<<"; not real Windows performance"<<std::endl;

    service=new TextService;manager=new Manager;context=new Context;manager->supportsUI=true;manager->document=new DocumentManager(context);context->documentManager=manager->document;
    check(SUCCEEDED(service->ActivateEx(manager,22,TF_TMAE_UIELEMENTENABLEDONLY)),"host candidate fixture activates UI-element-only mode");
    for(WPARAM k:{'N','I'})service->OnKeyDown(context,k,0,&eat);
    ITfCandidateListUIElementBehavior* candidateUI=nullptr;check(manager->begins==1&&manager->element&&SUCCEEDED(manager->element->QueryInterface(IID_ITfCandidateListUIElementBehavior,reinterpret_cast<void**>(&candidateUI))),"TSF host receives real candidate UIElement and behavior interfaces");
    UINT count=0,selected=99;BOOL shown=TRUE;candidateUI->GetCount(&count);candidateUI->IsShown(&shown);check(count>2&&!shown&&!TextServiceTestAccess::ownCandidateUI(service),"host-owned presentation receives candidates while independent popup stays hidden");
    BSTR candidateText=nullptr;check(SUCCEEDED(candidateUI->GetString(1,&candidateText))&&candidateText&&SysStringLen(candidateText)>0,"host retrieves UTF16 Chinese candidate strings");std::wstring chosen(candidateText,SysStringLen(candidateText));SysFreeString(candidateText);
    ITfDocumentMgr* ownerDocument=nullptr;check(SUCCEEDED(candidateUI->GetDocumentMgr(&ownerDocument))&&ownerDocument==manager->document,"candidate snapshot identifies its actual target document");drop(ownerDocument);
    DWORD flags=0;candidateUI->GetUpdatedFlags(&flags);check((flags&0x3f)==0x3f,"first host query reports complete candidate update flags");candidateUI->GetUpdatedFlags(&flags);check(flags==0,"unchanged candidate query clears consumed update flags");
    check(candidateUI->GetString(count,&candidateText)==E_INVALIDARG&&candidateText==nullptr&&candidateUI->GetCount(nullptr)==E_POINTER,"candidate COM methods validate indices and null outputs");
    UINT starts[]={0,2},pages=0,currentPage=99;check(SUCCEEDED(candidateUI->SetPageIndex(starts,2))&&candidateUI->GetPageIndex(nullptr,0,&pages)==S_FALSE&&pages==2,"host can paginate current candidate batch and query required capacity");
    check(SUCCEEDED(candidateUI->SetSelection(2))&&SUCCEEDED(candidateUI->GetCurrentPage(&currentPage))&&currentPage==1,"host candidate selection updates current page without committing text");
    UINT invalidPages[]={1};check(candidateUI->SetPageIndex(invalidPages,1)==E_INVALIDARG&&candidateUI->SetSelection(count)==E_INVALIDARG,"malformed host page layout and selection are rejected");
    candidateUI->SetSelection(1);candidateUI->GetSelection(&selected);check(selected==1&&context->doc.value==L"beforeniafter","host selection alone leaves the editor composition unchanged");
    context->deferred=true;check(SUCCEEDED(candidateUI->Finalize())&&TextServiceTestAccess::pendingText(service)=="ni","host finalization uses the production asynchronous TSF edit transaction");deferred=context->flush();check(deferred.size()==1&&SUCCEEDED(deferred[0])&&context->doc.value==L"before"+chosen+L"after"&&manager->ends==1,"successful host finalization inserts its chosen candidate and ends UIElement");
    check(candidateUI->Finalize()==E_UNEXPECTED&&candidateUI->Show(TRUE)==E_UNEXPECTED,"retained obsolete candidate interface cannot act on another editor");candidateUI->Release();candidateUI=nullptr;
    context->deferred=false;service->OnKeyDown(context,VK_OEM_5,0,&eat);BYTE originalKeys[256],shiftedKeys[256];GetKeyboardState(originalKeys);std::copy_n(originalKeys,256,shiftedKeys);shiftedKeys[VK_SHIFT]|=0x80;SetKeyboardState(shiftedKeys);service->OnKeyDown(context,'X',0,&eat);SetKeyboardState(originalKeys);check(manager->element!=nullptr,"uppercase mathematical alias produces a hosted candidate list");manager->element->QueryInterface(IID_ITfCandidateListUIElementBehavior,reinterpret_cast<void**>(&candidateUI));candidateUI->GetString(0,&candidateText);check(std::wstring(candidateText,SysStringLen(candidateText))==L"𝕏"&&SysStringLen(candidateText)==2,"host candidate API preserves non-BMP mathematical symbols as UTF16 pairs");SysFreeString(candidateText);auto beforeAbort=L"before"+chosen+L"after";check(SUCCEEDED(candidateUI->Abort())&&context->doc.value==beforeAbort,"host abort removes only its owned active composition");candidateUI->Release();candidateUI=nullptr;
    for(WPARAM k:{'N','I'})service->OnKeyDown(context,k,0,&eat);manager->element->QueryInterface(IID_ITfCandidateListUIElementBehavior,reinterpret_cast<void**>(&candidateUI));service->OnSetFocus(FALSE);check(!manager->element&&candidateUI->Finalize()==E_UNEXPECTED,"focus loss ends host candidate UI and invalidates stale callbacks");candidateUI->Release();
    service->Deactivate();service->Release();context->Release();manager->Release();check(objectCount==0,"host-retained candidate objects release all service and editor ownership");

    service=new TextService;manager=new Manager;context=new Context;check(SUCCEEDED(service->Activate(manager,23)),"editor rejection fixture activates production keyboard");
    for(WPARAM k:{'N','I'})service->OnKeyDown(context,k,0,&eat);auto refusedComposition=context->doc.value;context->doc.denyText=true;service->OnKeyDown(context,VK_BACK,0,&eat);check(eat&&TextServiceTestAccess::pendingText(service)=="ni"&&context->doc.value==refusedComposition,"failed backspace preserves IME buffer and owned composition instead of forwarding a second edit");
    BYTE savedKeyboard[256],shiftKeyboard[256];GetKeyboardState(savedKeyboard);std::copy_n(savedKeyboard,256,shiftKeyboard);shiftKeyboard[VK_SHIFT]|=0x80;SetKeyboardState(shiftKeyboard);service->OnKeyDown(context,VK_SPACE,0,&eat);SetKeyboardState(savedKeyboard);check(TextServiceTestAccess::chineseMode(service)&&TextServiceTestAccess::pendingText(service)=="ni","failed mode-switch commit keeps Chinese mode and recoverable composition");
    context->doc.denyText=false;service->OnKeyDown(context,VK_BACK,0,&eat);check(TextServiceTestAccess::pendingText(service)=="n"&&context->doc.value==L"beforenafter","retry backspace edits the same original composition exactly once");
    context->deferred=true;TextServiceTestAccess::click(service,context,"你");refusedComposition=context->doc.value;TextServiceTestAccess::terminateComposition(service);deferred=context->flush();check(deferred.size()==1&&deferred[0]==E_ABORT&&context->doc.value==refusedComposition,"external composition termination invalidates a queued candidate before stale insertion");
    service->Deactivate();service->Release();context->Release();manager->Release();check(objectCount==0,"rejected edits and externally terminated composition release all COM state");
    std::cout<<"TOTAL "<<checks<<" PASS; native Windows TSF editing with mock text store; real OS integration separate"<<std::endl;return 0;
}catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<std::endl;return 1;}}
