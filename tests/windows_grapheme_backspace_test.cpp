// Use the real TSF key path with bounded in-memory editor fixtures.
#define main existing_tsf_tests_main
#include "tsf_edit_test.cpp"
#undef main

int main() {
    try {
        const ScriptedKeyboardState keyState;
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
        check(SUCCEEDED(service->ActivateEx(manager,410,TF_TMAE_UIELEMENTENABLEDONLY)),
              "activate production TSF key service in the fixture");

        constexpr LPARAM message=0x000e0001;
        auto preflight=[&](Context* context) {
            BOOL eaten=FALSE;
            if (FAILED(service->OnTestKeyDown(context,VK_BACK,message,&eaten)))
                throw std::runtime_error("backspace preflight failed");
            return eaten!=FALSE;
        };
        auto dispatch=[&](Context* context) {
            BOOL eaten=FALSE;
            if (FAILED(service->OnKeyDown(context,VK_BACK,message,&eaten)))
                throw std::runtime_error("backspace dispatch failed");
            return eaten!=FALSE;
        };
        auto set=[&](std::wstring text,LONG caret) {
            first->doc=Document{};
            first->doc.value=std::move(text);
            first->doc.cursor=caret;
            first->scope=IS_DEFAULT;
            first->selections=0;
            first->fullReads=0;
        };
        auto complex=[&] {set(L"Ae\u0301Z",3);};
        const std::wstring accent=L"Ae\u0301Z";
        auto deletes=[&](const std::wstring& cluster,const char* label) {
            const std::wstring original=L"A"+cluster+L"Z";
            set(original,static_cast<LONG>(1+cluster.size()));
            check(preflight(first)&&first->doc.textWrites==0&&first->doc.value==original,
                  "TSF preflight claims a grapheme without changing text");
            check(dispatch(first)&&first->doc.value==L"AZ"&&first->doc.cursor==1&&
                  first->doc.textWrites==1&&first->selections==1,label);
        };
        deletes(L"e\u0301","combining accent is deleted as one cluster and the suffix is preserved");
        deletes(L"\U0001f469\U0001f3fd\u200d\U0001f4bb","emoji modifier and ZWJ sequence are deleted together");
        deletes(L"\U0001f1e8\U0001f1e6","regional-indicator flag is deleted as one cluster");
        deletes(L"\r\n","CRLF is deleted as one cluster");
        deletes(L"\u1100\u1161\u11a8","Hangul Jamo cluster is deleted together");
        deletes(L"\u0915\u094d\u0937","Indic conjunct is deleted together");

        set(L"ABC",2);
        first->doc.noFull=true;
        check(!preflight(first)&&!dispatch(first)&&first->doc.textWrites==0&&first->fullReads==0,
              "ordinary ASCII backspace remains owned by the editor without a full document read");
        set(L"A中Z",2);
        check(!preflight(first)&&!dispatch(first)&&first->doc.textWrites==0&&first->fullReads==0,
              "ordinary Han backspace remains owned by the editor without a full document read");
        complex();
        first->doc.selectionEnd=1;
        check(!preflight(first)&&!dispatch(first)&&first->doc.value==accent,
              "noncollapsed selection remains owned by the editor");
        complex();
        {
            const ScriptedKeyboardState modified(true);
            check(!preflight(first)&&!dispatch(first)&&first->doc.value==accent,
                  "modified backspace remains owned by the editor");
        }
        complex();
        first->scope=IS_PASSWORD;
        unsigned privateReads=0;
        first->doc.onRead=[&] {++privateReads;};
        check(!preflight(first)&&!dispatch(first)&&privateReads==0&&first->doc.textWrites==0,
              "password scope delegates before reading document text");
        complex();
        first->doc.noFull=true;
        check(!preflight(first)&&!dispatch(first)&&first->doc.textWrites==0,
              "unavailable full snapshot delegates without an IME write");
        set(accent+std::wstring(32768,L'x'),3);
        check(!preflight(first)&&!dispatch(first)&&first->doc.textWrites==0,
              "text beyond the proof limit delegates without an IME write");
        complex();
        first->doc.ignoreMove=true;
        check(!preflight(first)&&!dispatch(first)&&first->doc.textWrites==0,
              "inconsistent moving read delegates without an IME write");
        set(std::wstring(L"Ae\u0301")+static_cast<wchar_t>(0xd800)+L"Z",3);
        check(!preflight(first)&&!dispatch(first)&&first->doc.textWrites==0,
              "malformed UTF-16 is never used to authorize a write");
        set(accent,2);
        check(preflight(first)&&dispatch(first)&&first->doc.value==accent&&first->doc.textWrites==0,
              "caret inside a combining cluster blocks partial deletion");
        set(L"A\U0001f4bbZ",2);
        check(preflight(first)&&dispatch(first)&&first->doc.value==L"A\U0001f4bbZ"&&first->doc.textWrites==0,
              "caret inside a surrogate pair blocks partial deletion");

        complex();
        check(preflight(first),"claim before moving the caret");
        first->doc.cursor=1;
        check(dispatch(first)&&first->doc.value==accent&&first->doc.textWrites==0,
              "selection move expires the edit proof without replaying backspace");
        complex();
        check(preflight(first),"claim before replacing surrounding text");
        first->doc.value=L"Be\u0301Z";
        check(dispatch(first)&&first->doc.value==L"Be\u0301Z"&&first->doc.textWrites==0,
              "same-offset document change invalidates the pending edit");
        complex();
        check(preflight(first),"claim before input scope changes");
        first->scope=IS_PASSWORD;
        first->doc.onRead=[&] {++privateReads;};
        check(dispatch(first)&&first->doc.value==accent&&first->doc.textWrites==0&&privateReads==0,
              "scope change after preflight stops the edit before any text read");
        complex();
        check(preflight(first),"claim before changing the focused editor");
        focus(second);
        const auto untouched=second->doc.value;
        check(dispatch(second)&&first->doc.value==accent&&second->doc.value==untouched&&
              first->doc.textWrites==0&&second->doc.textWrites==0,
              "stale key cannot edit either old or new editor");
        focus(first);
        complex();
        first->doc.onRead=[&] {focus(second);};
        check(!preflight(first)&&first->doc.value==accent&&second->doc.value==untouched&&first->fullReads==0,
              "focus lost during the neighborhood probe prevents the full document read");
        focus(first);

        complex();
        first->doc.denyText=true;
        check(preflight(first)&&dispatch(first)&&first->doc.value==accent&&first->doc.textWrites==0,
              "host write refusal consumes the claim without a fallback write");
        complex();
        first->doc.writeThenFail=true;
        check(preflight(first)&&dispatch(first)&&first->doc.value==L"AZ"&&first->doc.textWrites==1&&
              first->selections==0,"partial host success is not replayed or followed by a selection change");
        complex();
        first->doc.denySelection=true;
        check(preflight(first)&&dispatch(first)&&first->doc.value==L"AZ"&&first->doc.textWrites==1&&
              first->selections==0,"selection refusal after deletion never repeats the text write");
        complex();
        first->doc.afterWrite=[&] {focus(second);};
        check(preflight(first)&&dispatch(first)&&first->doc.value==L"AZ"&&first->doc.textWrites==1&&
              first->selections==0&&second->doc.value==untouched,
              "focus lost during write never sends a selection to the old or new editor");
        focus(first);
        complex();
        first->deferSynchronous=true;
        check(!preflight(first)&&first->flush()==std::vector<HRESULT>{E_ABORT}&&first->doc.textWrites==0,
              "a deferred read callback is disarmed");
        first->deferSynchronous=false;
        check(preflight(first),"claim before host delays the write callback");
        first->deferSynchronous=true;
        check(dispatch(first)&&first->flush()==std::vector<HRESULT>{E_ABORT}&&first->doc.value==accent&&
              first->doc.textWrites==0,"a deferred write callback cannot mutate the document");
        first->deferSynchronous=false;

        service->Deactivate();
        service->Release();
        manager->Release();
        first->Release();
        second->Release();
        check(objectCount==0,"TSF service releases ownership of both fixture editors");
        std::cout<<"PASS "<<checks<<" production TSF grapheme backspace assertions\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr<<"FAIL "<<error.what()<<'\n';
        return 1;
    }
}
