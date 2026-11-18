// Production TSF service against the existing synthetic text store.
#define main baseline_tsf_main
#include "../tests/tsf_edit_test.cpp"
#undef main

struct SymbolFixture {
    TextService* service = new TextService;
    Manager* manager = new Manager;
    Context* editor = new Context;

    SymbolFixture() {
        moduleHandle = GetModuleHandleW(nullptr);
        manager->supportsUI = true;
        manager->document = new DocumentManager(editor);
        editor->documentManager = manager->document;
        check(SUCCEEDED(service->ActivateEx(manager, 501, TF_TMAE_UIELEMENTENABLEDONLY)),
              "symbol panel fixture activates without opening a popup");
    }
    ~SymbolFixture() {
        editor->flush();
        service->Deactivate();
        service->Release();
        editor->Release();
        manager->Release();
    }
    void key(WPARAM key) {
        BOOL eaten = FALSE;
        const auto result = scriptedKeyDown(service,editor,key,&eaten);
        if (FAILED(result) || !eaten) {
            const auto layout = LOWORD(reinterpret_cast<ULONG_PTR>(GetKeyboardLayout(0)));
            const auto expectedSlash = VkKeyScanW(L'\\');
            throw std::runtime_error("symbol panel key refused, key=" + std::to_string(key) +
                ", HRESULT=" + std::to_string(result) + ", eaten=" + std::to_string(eaten) +
                ", language=" + std::to_string(layout) + ", slash mapping=" +
                std::to_string(expectedSlash));
        }
    }
    void open() { key(VK_OEM_5); key(VK_OEM_5); }
    void letters(std::string_view text) {
        for (char letter : text) key(static_cast<WPARAM>(letter - 'a' + 'A'));
    }
    std::wstring row(UINT index) {
        BSTR text = nullptr;
        auto* list = candidateList();
        HRESULT read = list->GetString(index, &text);
        list->Release();
        if (FAILED(read) || !text) throw std::runtime_error("symbol candidate unavailable");
        std::wstring result(text, SysStringLen(text));
        SysFreeString(text);
        return result;
    }
    HRESULT finalize() {
        auto* list = candidateList();
        HRESULT result = list->Finalize();
        list->Release();
        return result;
    }
private:
    ITfCandidateListUIElementBehavior* candidateList() {
        ITfCandidateListUIElementBehavior* candidate = nullptr;
        if (!manager->element || FAILED(manager->element->QueryInterface(
                IID_ITfCandidateListUIElementBehavior, reinterpret_cast<void**>(&candidate))) || !candidate)
            throw std::runtime_error("symbol candidate list missing");
        return candidate;
    }
};

int main() {
    try {
        {
            SymbolFixture fixture;
            ScriptedKeyboardState ambientShift(true);
            fixture.open();
            check(fixture.row(0) == L"希腊", "double backslash opens category list");
            check(SUCCEEDED(fixture.finalize()) && fixture.row(0) == L"α",
                  "host selection opens a category without inserting its label");
            fixture.key('1');
            check(fixture.editor->doc.value == L"beforeαafter", "Greek symbol replaces only the owned command");
        }
        {
            SymbolFixture fixture;
            fixture.open(); fixture.key(VK_NEXT);
            check(fixture.row(0) == L"英文标点", "second category page includes English punctuation");
            fixture.key('1'); fixture.key('2');
            check(fixture.editor->doc.value == L"before.after", "English punctuation remains ASCII");
        }
        {
            SymbolFixture fixture;
            fixture.open(); fixture.key(VK_NEXT); fixture.key('4');
            check(fixture.row(3) == L"👩🏽‍💻", "skin tone and ZWJ sequence survive TSF candidate presentation");
            fixture.key('4');
            check(fixture.editor->doc.value == L"before👩🏽‍💻after" && fixture.editor->doc.cursor == 13,
                  "Emoji insertion keeps the full sequence and UTF16 caret");
        }
        {
            SymbolFixture fixture;
            fixture.open(); fixture.key(VK_NEXT); fixture.key(VK_NEXT);
            check(fixture.row(0) == L"颜文字", "last category page reaches kaomoji");
            fixture.key('1'); fixture.key('1');
            check(fixture.editor->doc.value == L"before( 'ω' )after", "kaomoji inserts spaces and punctuation exactly");
        }
        {
            SymbolFixture fixture;
            fixture.open(); fixture.letters("flags"); fixture.key('1');
            check(fixture.editor->doc.value == L"before🇨🇦after", "flag keeps both regional indicators");
            fixture.open(); fixture.letters("emoji"); fixture.key('1');
            check(fixture.editor->doc.value == L"before🇨🇦❤️after", "emoji presentation selector survives insertion");
        }
        {
            SymbolFixture fixture;
            fixture.open(); fixture.letters("greek"); fixture.key(VK_OEM_5); fixture.letters("ga");
            check(fixture.row(0) == L"γ", "category can filter math symbols by an alias prefix");
            fixture.key(VK_ESCAPE);
            check(fixture.editor->doc.value == L"beforeafter", "Escape removes only the owned palette composition");
        }
        {
            SymbolFixture fixture;
            fixture.open(); fixture.key('1');
            fixture.editor->deferred = true;
            auto before = fixture.editor->doc.value;
            check(SUCCEEDED(fixture.finalize()), "host queues symbol insertion through the guarded edit session");
            fixture.service->OnSetFocus(FALSE);
            auto result = fixture.editor->flush();
            check(result.size() == 2 && result[0] == E_ABORT && result[1] == S_OK &&
                      fixture.editor->doc.value == before,
                  "focus loss rejects deferred insertion and only ends the original composition");
        }
        {
            SymbolFixture fixture;
            fixture.open();
            auto before = fixture.editor->doc.value;
            fixture.editor->doc.cursor = 0;
            fixture.key('1');
            fixture.key(VK_SPACE);
            check(fixture.editor->doc.value == before, "changed caret consumes category actions without writing");
        }
        check(objectCount == 0, "symbol panel releases all synthetic TSF references");
        std::cout << "TOTAL " << checks << " PASS; symbol panel, native Windows with mock TSF only\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
