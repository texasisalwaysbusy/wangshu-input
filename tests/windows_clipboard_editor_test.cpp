// Production TSF key, candidate and edit routes against synthetic text stores.
#define main existing_tsf_main
#include "tsf_edit_test.cpp"
#undef main

struct WindowsClipboardTestAccess {
    static void store(TextService* service,std::shared_ptr<wangshu::ClipboardStore> store) {
        service->clipboardStore=std::move(store);
    }
    static void open(TextService* service,ITfContext* context) {service->startClipboard(context);}
    static bool visible(TextService* service) {return static_cast<bool>(service->clipboardPanel);}
    static HRESULT scope(TextService* service,ITfContext* context) {
        return service->runSynchronousEdit(context,TF_ES_READ,[&](TfEditCookie cookie){
            return service->clipboardScope(context,cookie,true);
        });
    }
};

namespace {
std::string fixtureSeal(const std::string& text) {return "FIXTURE:"+text;}
std::string fixtureOpen(const std::string& bytes) {
    if (bytes.rfind("FIXTURE:",0)!=0) throw std::runtime_error("invalid fixture");
    return bytes.substr(8);
}
}

int main(int argc,char** argv) {
    try {
        if (argc!=2) return 2;
        const auto dir=std::filesystem::absolute(std::filesystem::path(argv[1]));
        if (dir.root_name()!=L"E:") return 2;
        const auto now=wangshu::WindowsPersonalStore::learningNow();
        auto history=std::make_shared<wangshu::ClipboardStore>((dir/L"synthetic-clipboard.dat").wstring(),
            wangshu::ClipboardStore::Protection{fixtureSeal,fixtureOpen});
        history->configure("",true,7,now);
        const std::string sample=u8"Hi 中 Hi 👩🏽‍💻\r\n";
        auto record=[&]{return history->record([&]{return std::optional<std::string>(sample);},now);};
        check(record(),"create owned clipboard fixture without system clipboard access");

        moduleHandle=GetModuleHandleW(nullptr);
        auto* service=new TextService;
        auto* manager=new Manager;
        auto* first=new Context;
        auto* second=new Context;
        manager->supportsUI=true;
        auto focus=[&](Context* target) {
            if (manager->document) service->OnSetFocus(FALSE);
            first->documentManager=second->documentManager=nullptr;
            drop(manager->document);
            manager->document=new DocumentManager(target);
            target->documentManager=manager->document;
        };
        focus(first);
        check(SUCCEEDED(service->ActivateEx(manager,411,TF_TMAE_UIELEMENTENABLEDONLY)),
              "activate clipboard route with host-owned candidate interface");
        WindowsClipboardTestAccess::store(service,history);
        auto reset=[&] {
            focus(first);
            first->doc=Document{};
            first->doc.value=L"Hi AZ";
            first->doc.cursor=4;
            first->scope=IS_DEFAULT;
            first->selections=first->fullReads=0;
        };
        auto preflight=[&](Context* context,WPARAM key) {
            BOOL eaten=FALSE;
            if (FAILED(scriptedTestKeyDown(service,context,key,&eaten))) throw std::runtime_error("key preflight failed");
            return eaten!=FALSE;
        };
        auto dispatch=[&](Context* context,WPARAM key) {
            BOOL eaten=FALSE;
            if (FAILED(scriptedKeyDown(service,context,key,&eaten))) throw std::runtime_error("key dispatch failed");
            return eaten!=FALSE;
        };
        auto press=[&](Context* context,WPARAM key){return preflight(context,key)&&dispatch(context,key);};
        auto panel=[&]{WindowsClipboardTestAccess::open(service,first);return WindowsClipboardTestAccess::visible(service);};
        auto candidate=[&] {
            ITfCandidateListUIElementBehavior* element=nullptr;
            if(!manager->element||FAILED(manager->element->QueryInterface(IID_ITfCandidateListUIElementBehavior,
                reinterpret_cast<void**>(&element)))) throw std::runtime_error("candidate interface missing");
            return element;
        };
        auto candidateText=[&](ITfCandidateListUIElementBehavior* element,UINT index) {
            BSTR text=nullptr;
            if (FAILED(element->GetString(index,&text))||!text) throw std::runtime_error("candidate text missing");
            std::wstring result(text,SysStringLen(text));
            SysFreeString(text);
            return result;
        };

        reset();
        bool typed=true;
        for(WPARAM key:std::initializer_list<WPARAM>{VK_OEM_5,VK_OEM_5,'C','L','I','P'})
            typed=press(first,key)&&typed;
        check(typed&&TextServiceTestAccess::pendingText(service)==wangshu::clipboard_edit::Command,
              "scripted keyboard inputs the clipboard alias into the owned composition");
        check(press(first,VK_RETURN)&&WindowsClipboardTestAccess::visible(service)&&
              first->doc.value==L"Hi AZ"&&first->doc.cursor==4,
              "keyboard command opens history and removes only its own composition");
        auto* previous=candidate();
        check(candidateText(previous,0).find(L"插入")!=std::wstring::npos&&
              candidateText(previous,1).find(L"拆词")!=std::wstring::npos,
              "host candidate UI receives whole-insert and split actions");
        check(press(first,'2')&&first->doc.value==L"Hi AZ","split opens without writing text");
        check(previous->Finalize()==E_UNEXPECTED,"retired history candidate cannot select a newly displayed block");
        previous->Release();
        auto* tokens=candidate();
        UINT count=0;
        tokens->GetCount(&count);
        check(count==8&&candidateText(tokens,0)==L"Hi"&&candidateText(tokens,2)==L"中"&&
              candidateText(tokens,6)==L"👩🏽‍💻"&&candidateText(tokens,7)==L"〔换行〕",
              "token list preserves ASCII words, Chinese, emoji sequence and CRLF");
        tokens->Release();
        check(press(first,'1')&&press(first,'7')&&press(first,'5')&&
              first->doc.value==L"Hi AHi👩🏽‍💻HiZ",
              "split inserts blocks in selection order without replacing surrounding text");
        check(press(first,'1')&&first->doc.value==L"Hi A👩🏽‍💻HiZ"&&
              first->doc.cursor==static_cast<LONG>(std::wstring(L"Hi A👩🏽‍💻Hi").size()),
              "deselecting the first duplicate removes its own middle span and restores the caret");
        check(press(first,'1')&&first->doc.value==L"Hi A👩🏽‍💻HiHiZ",
              "selecting a removed token again appends at the new time");
        check(press(first,VK_BACK)&&WindowsClipboardTestAccess::visible(service)&&first->doc.value==L"Hi A👩🏽‍💻HiHiZ",
              "return to history retains already inserted blocks");
        check(press(first,VK_ESCAPE)&&!WindowsClipboardTestAccess::visible(service),
              "closing history preserves committed text");

        reset();
        check(panel()&&press(first,'1')&&first->doc.value==L"Hi AHi 中 Hi 👩🏽‍💻\r\nZ"&&
              first->doc.textWrites==1&&!WindowsClipboardTestAccess::visible(service),
              "whole insertion preserves exact clipboard text, prefix and suffix in one write");
        reset();
        first->doc.selectionEnd=1;
        check(SUCCEEDED(WindowsClipboardTestAccess::scope(service,first)),
              "explicit normal scope can be checked while the host has a selection");
        check(!panel()&&first->doc.textWrites==0,"noncollapsed selection cannot open an unverified insertion session");
        reset();
        first->scope=IS_PASSWORD;
        check(FAILED(WindowsClipboardTestAccess::scope(service,first))&&!panel()&&
              first->fullReads==0&&first->doc.textWrites==0,
              "password scope rejects the panel before any document read");
        reset();
        check(panel(),"open before scope changes");
        first->scope=IS_PRIVATE;
        check(press(first,'1')&&first->doc.textWrites==0&&!WindowsClipboardTestAccess::visible(service),
              "private scope appearing after open blocks insertion");
        reset();
        check(panel()&&preflight(first,'1'),"claim a clipboard key before focus changes");
        const auto secondText=second->doc.value;
        focus(second);
        check(dispatch(second,'1')&&first->doc.textWrites==0&&second->doc.value==secondText&&second->doc.textWrites==0,
              "stale key dispatch is consumed without writing either field");
        reset();
        check(panel()&&press(first,'2')&&press(first,'1'),"open and insert a token before caret moves");
        first->doc.cursor=1;
        check(press(first,'1')&&first->doc.value==L"Hi AHiZ"&&first->doc.textWrites==1,
              "caret move blocks undo even when the same token exists elsewhere");
        reset();
        check(panel(),"open before same-position external document change");
        first->doc.value=L"Hi BZ";
        check(press(first,'1')&&first->doc.value==L"Hi BZ"&&first->doc.textWrites==0,
              "same-offset document change rejects a pending paste");
        reset();
        check(panel(),"open before another instance replaces a history identity");
        auto state=history->read(now);
        history->remove(state.revision,state.items[0].id,now);
        check(record()&&press(first,'1')&&first->doc.textWrites==0,
              "recreated text with a new stable identifier cannot use the old candidate");

        reset();
        check(panel(),"open before the host delays a synchronous transaction");
        first->afterRead=[&]{first->deferSynchronous=true;};
        check(press(first,'1')&&first->flush()==std::vector<HRESULT>{E_ABORT}&&first->doc.textWrites==0,
              "retained asynchronous callback is disarmed without a paste");
        first->deferSynchronous=false;
        reset();
        check(panel(),"open before host partial-write failure");
        first->doc.writeThenFail=true;
        check(press(first,'1')&&first->doc.textWrites==1&&first->selections==0&&
              !WindowsClipboardTestAccess::visible(service),"partial write is never replayed and closes the session");
        reset();
        check(panel(),"open before reentrant focus loss during SetText");
        first->doc.afterWrite=[&]{focus(second);};
        check(press(first,'1')&&first->doc.textWrites==1&&first->selections==0&&
              second->doc.value==secondText&&second->doc.textWrites==0,
              "focus loss inside write cannot move selection or edit the next field");

        service->Deactivate();
        service->Release();
        manager->Release();
        first->Release();
        second->Release();
        check(objectCount==0,"clipboard UI and edit sessions release both synthetic editors");
        std::cout<<"PASS "<<checks<<" clipboard editor assertions\n";
        return 0;
    } catch(const std::exception& e) {
        std::cerr<<"FAIL "<<e.what()<<'\n';
        return 1;
    }
}
