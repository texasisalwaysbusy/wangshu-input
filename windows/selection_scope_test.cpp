// Production Windows TSF exercised through COM/text-store fixtures, never registration or a keyboard hook.
#define main unused_legacy_tsf_main
#include "../tests/tsf_edit_test.cpp"
#undef main
#include <functional>

struct SelectionDocument {
    std::wstring text=L"beforeafter";LONG start=6,end=6;
    int textWrites=0,selectionWrites=0,selectionReads=0,startCompares=0,endCompares=0;
    bool noSelection=false,nullSelection=false,wrongCount=false,denyStartCompare=false,denyEndCompare=false;
};
class SelectionRange final:public RangeStub {
public:
    SelectionDocument& document;LONG first,last;
    SelectionRange(SelectionDocument& value,LONG start,LONG end):document(value),first(start),last(end){}
    HRESULT STDMETHODCALLTYPE SetText(TfEditCookie,DWORD,const WCHAR* value,LONG count)override{
        if(count<0||first<0||last<first||static_cast<size_t>(last)>document.text.size())return E_INVALIDARG;
        document.text.replace(static_cast<size_t>(first),static_cast<size_t>(last-first),value,static_cast<size_t>(count));last=first+count;document.textWrites++;return S_OK;
    }
    // Bounded plain-text fixture movement used by exact snapshot verification.
    HRESULT STDMETHODCALLTYPE ShiftStart(TfEditCookie,LONG requested,LONG* actual,const TF_HALTCOND* halt)override{
        if(!actual)return E_POINTER;
        if(halt)return E_NOTIMPL;
        LONG next=static_cast<LONG>(std::max<long long>(0,std::min<long long>(last,static_cast<long long>(first)+requested)));
        *actual=next-first;first=next;return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetText(TfEditCookie,DWORD,WCHAR* out,ULONG maximum,ULONG* count)override{
        if(!count||(!out&&maximum)||first<0||last<first||static_cast<size_t>(last)>document.text.size())return E_INVALIDARG;
        *count=std::min<ULONG>(maximum,static_cast<ULONG>(last-first));std::copy_n(document.text.data()+first,*count,out);return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Clone(ITfRange** out)override{if(!out)return E_POINTER;*out=new SelectionRange(document,first,last);return S_OK;}
    HRESULT STDMETHODCALLTYPE Collapse(TfEditCookie,TfAnchor anchor)override{if(anchor==TF_ANCHOR_END)first=last;else last=first;return S_OK;}
    HRESULT compare(ITfRange* value,TfAnchor anchor,LONG* result,bool atEnd){
        if(atEnd)document.endCompares++;else document.startCompares++;
        if(atEnd?document.denyEndCompare:document.denyStartCompare)return E_NOTIMPL;
        auto other=dynamic_cast<SelectionRange*>(value);if(!other||&other->document!=&document||!result)return E_INVALIDARG;
        LONG left=atEnd?last:first,right=anchor==TF_ANCHOR_END?other->last:other->first;*result=left<right?-1:left>right?1:0;return S_OK;
    }
    HRESULT STDMETHODCALLTYPE CompareStart(TfEditCookie,ITfRange* value,TfAnchor anchor,LONG* result)override{return compare(value,anchor,result,false);}
    HRESULT STDMETHODCALLTYPE CompareEnd(TfEditCookie,ITfRange* value,TfAnchor anchor,LONG* result)override{return compare(value,anchor,result,true);}
};
class SelectionContext final:public ContextStub {
public:
    int compositionStarts=0;bool denyEditRequest=false;
    SelectionDocument document;ITfDocumentMgr* documentManager=nullptr;InputScope scope=IS_DEFAULT;bool deferred=false;std::vector<ITfEditSession*> queued;
    ~SelectionContext(){for(auto* edit:queued)edit->Release();}
    HRESULT STDMETHODCALLTYPE GetDocumentMgr(ITfDocumentMgr** out)override{if(!out)return E_POINTER;*out=documentManager;if(*out)(*out)->AddRef();return *out?S_OK:E_FAIL;}
    HRESULT STDMETHODCALLTYPE GetAppProperty(REFGUID id,ITfReadOnlyProperty** out)override{if(id!=GUID_PROP_INPUTSCOPE)return E_NOTIMPL;*out=new ScopeProperty(scope);return S_OK;}
    HRESULT STDMETHODCALLTYPE GetSelection(TfEditCookie,ULONG,ULONG,TF_SELECTION* out,ULONG* count)override{
        document.selectionReads++;if(!out||!count)return E_POINTER;*count=0;out->range=nullptr;if(document.noSelection)return E_ACCESSDENIED;
        out->style={document.start<=document.end?TF_AE_END:TF_AE_START,FALSE};if(!document.nullSelection)out->range=new SelectionRange(document,std::min(document.start,document.end),std::max(document.start,document.end));*count=document.wrongCount?2:1;return S_OK;
    }
    HRESULT STDMETHODCALLTYPE SetSelection(TfEditCookie,ULONG count,const TF_SELECTION* value)override{if(count!=1||!value)return E_INVALIDARG;auto range=dynamic_cast<SelectionRange*>(value->range);if(!range||&range->document!=&document)return E_INVALIDARG;document.start=range->first;document.end=range->last;document.selectionWrites++;return S_OK;}
    HRESULT STDMETHODCALLTYPE RequestEditSession(TfClientId,ITfEditSession* edit,DWORD flags,HRESULT* result)override{if(!result)return E_POINTER;if(denyEditRequest){*result=E_ACCESSDENIED;return E_ACCESSDENIED;}if(deferred&&!(flags&TF_ES_SYNC)){edit->AddRef();queued.push_back(edit);*result=TF_S_ASYNC;}else *result=edit->DoEditSession(101);return S_OK;}
    HRESULT STDMETHODCALLTYPE InsertTextAtSelection(TfEditCookie,DWORD flags,const WCHAR*,LONG,ITfRange** out)override{if(!(flags&TF_IAS_QUERYONLY)||!out)return E_INVALIDARG;*out=new SelectionRange(document,std::min(document.start,document.end),std::max(document.start,document.end));return S_OK;}
    HRESULT STDMETHODCALLTYPE StartComposition(TfEditCookie,ITfRange* range,ITfCompositionSink* sink,ITfComposition** out)override{if(!out)return E_POINTER;++compositionStarts;*out=new Composition(range,sink);return S_OK;}
    HRESULT STDMETHODCALLTYPE GetStatus(TF_STATUS* out)override{if(!out)return E_POINTER;*out={};return S_OK;}
    std::vector<HRESULT> flush(){auto pending=queued;queued.clear();std::vector<HRESULT> result;for(auto* edit:pending){result.push_back(edit->DoEditSession(101));edit->Release();}return result;}
};
struct WindowsPersonalTestAccess {
    static void personal(TextService* service,const std::wstring& path){service->personalStore=std::make_unique<wangshu::WindowsPersonalStore>(path);}
};
struct SelectionFixture {
    TextService* service=new TextService;Manager* manager=new Manager;SelectionContext* context=new SelectionContext;
    ITfCandidateListUIElementBehavior* candidates=nullptr;
    SelectionFixture(){
        manager->supportsUI=true;manager->document=new DocumentManager(context);context->documentManager=manager->document;
        GUID guid{};CoCreateGuid(&guid);wchar_t id[40];StringFromGUID2(guid,id,40);auto directory=std::filesystem::path(modulePath()).parent_path()/L"selection-fixtures"/id;std::filesystem::create_directories(directory);
        auto path=(directory/L"personal.json").wstring();wangshu::WindowsPersonalStore store(path);wangshu::PersonalData data;data.words={{"nihao","selected-word"}};store.merge(data);
        if(FAILED(service->ActivateEx(manager,92,TF_TMAE_UIELEMENTENABLEDONLY)))throw std::runtime_error("Production activation failed");WindowsPersonalTestAccess::personal(service,path);
        BOOL eaten=FALSE;for(WPARAM key:{'N','I','H','A','O'})service->OnKeyDown(context,key,0,&eaten);
        if(!manager->element||FAILED(manager->element->QueryInterface(IID_ITfCandidateListUIElementBehavior,reinterpret_cast<void**>(&candidates))))throw std::runtime_error("Actual production UIElement absent");
        candidates->SetSelection(0);context->document.textWrites=context->document.selectionWrites=0;
    }
    ~SelectionFixture(){if(candidates)candidates->Release();service->Deactivate();service->Release();context->Release();manager->Release();}
};
static int selectionPassed=0,selectionFailed=0;
static void requireSelection(bool value,const char* message){if(!value)throw std::runtime_error(message);}
static void selectionCase(const char* label,const std::function<void()>& body){try{body();selectionPassed++;std::cout<<"PASS "<<label<<std::endl;}catch(const std::exception& failure){selectionFailed++;std::cout<<"FAIL "<<label<<" :: "<<failure.what()<<std::endl;}}
static void unchangedAfterRefusal(SelectionFixture& fixture,const std::wstring& before,LONG start,LONG end,HRESULT result){auto& doc=fixture.context->document;requireSelection(FAILED(result)&&doc.text==before&&doc.start==start&&doc.end==end&&doc.textWrites==0&&doc.selectionWrites==0,"Old candidate changed text or current host selection");}
#ifndef WANGSHU_SELECTION_FIXTURES_ONLY
int main(){
    selectionCase("unchanged collapsed end caret accepts current candidate",[]{SelectionFixture f;auto h=f.candidates->Finalize();requireSelection(SUCCEEDED(h)&&f.context->document.text==L"beforeselected-wordafter"&&f.context->document.textWrites==1,"Valid candidate did not commit exactly once");});
    for(auto position:std::vector<std::pair<const char*,LONG>>{{"caret before owned composition refuses old candidate",1},{"caret inside owned composition refuses old candidate",8},{"caret after owned composition refuses old candidate",13}}){
        selectionCase(position.first,[position]{SelectionFixture f;auto& doc=f.context->document;doc.start=doc.end=position.second;auto before=doc.text;unchangedAfterRefusal(f,before,doc.start,doc.end,f.candidates->Finalize());});
    }
    selectionCase("new forward noncollapsed selection is preserved",[]{SelectionFixture f;auto& doc=f.context->document;doc.start=1;doc.end=4;auto before=doc.text;unchangedAfterRefusal(f,before,1,4,f.candidates->Finalize());});
    selectionCase("new reverse selection is preserved",[]{SelectionFixture f;auto& doc=f.context->document;doc.start=9;doc.end=7;auto before=doc.text;unchangedAfterRefusal(f,before,9,7,f.candidates->Finalize());});
    selectionCase("caret move after async queue is checked at actual edit execution",[]{SelectionFixture f;auto& doc=f.context->document;f.context->deferred=true;requireSelection(SUCCEEDED(f.candidates->Finalize()),"Did not queue");doc.start=doc.end=2;auto before=doc.text;auto results=f.context->flush();requireSelection(results.size()==1,"Wrong queued edit count");unchangedAfterRefusal(f,before,2,2,results.front());});
    selectionCase("exact current selection restored before async execution remains usable",[]{SelectionFixture f;auto& doc=f.context->document;f.context->deferred=true;requireSelection(SUCCEEDED(f.candidates->Finalize()),"Did not queue");doc.start=doc.end=2;doc.start=doc.end=11;auto result=f.context->flush();requireSelection(result.size()==1&&SUCCEEDED(result.front())&&doc.text==L"beforeselected-wordafter","Restored exact current state incorrectly refused");});
    selectionCase("unreadable current selection fails closed before any write",[]{SelectionFixture f;auto& doc=f.context->document;doc.noSelection=true;auto before=doc.text;unchangedAfterRefusal(f,before,11,11,f.candidates->Finalize());});
    selectionCase("null host selection range fails closed",[]{SelectionFixture f;auto& doc=f.context->document;doc.nullSelection=true;auto before=doc.text;unchangedAfterRefusal(f,before,11,11,f.candidates->Finalize());});
    selectionCase("malformed multiple selection count fails closed",[]{SelectionFixture f;auto& doc=f.context->document;doc.wrongCount=true;auto before=doc.text;unchangedAfterRefusal(f,before,11,11,f.candidates->Finalize());});
    selectionCase("unsupported start comparison fails closed",[]{SelectionFixture f;auto& doc=f.context->document;doc.denyStartCompare=true;auto before=doc.text;unchangedAfterRefusal(f,before,11,11,f.candidates->Finalize());});
    selectionCase("unsupported end comparison fails closed",[]{SelectionFixture f;auto& doc=f.context->document;doc.denyEndCompare=true;auto before=doc.text;unchangedAfterRefusal(f,before,11,11,f.candidates->Finalize());});
    selectionCase("existing composition-content guard still refuses changed text",[]{SelectionFixture f;auto& doc=f.context->document;doc.text.replace(6,5,L"fresh");auto before=doc.text;unchangedAfterRefusal(f,before,11,11,f.candidates->Finalize());});
    selectionCase("password transition still refuses current candidate",[]{SelectionFixture f;auto& doc=f.context->document;f.context->scope=IS_PASSWORD;auto before=doc.text;unchangedAfterRefusal(f,before,11,11,f.candidates->Finalize());});
    selectionCase("all independent scopes release COM ownership",[]{requireSelection(objectCount==0,"Fixture leaked service/candidate ownership");});
    std::cout<<"RESULT passed="<<selectionPassed<<" failed="<<selectionFailed<<" total="<<(selectionPassed+selectionFailed)<<"; actual PE production TSF with selection-aware mock host, real Windows GUI/IME registration/microphone NOT_RUN"<<std::endl;
    return selectionFailed?1:0;
}

#endif // WANGSHU_SELECTION_FIXTURES_ONLY
