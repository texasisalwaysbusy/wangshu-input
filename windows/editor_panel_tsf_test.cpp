// Production keyboard callbacks and a synthetic TSF text store.
#define main unused_tsf_main
#include "../tests/tsf_edit_test.cpp"
#undef main

namespace {
void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}

struct Keyboard {
    BYTE previous[256]{};
    explicit Keyboard(bool chord) {
        BYTE keys[256]{};
        if (chord) keys[VK_CONTROL] = keys[VK_SHIFT] = 0x80;
        require(GetKeyboardState(previous) && SetKeyboardState(keys), "keyboard");
    }
    ~Keyboard() { SetKeyboardState(previous); }
};

struct Fixture {
    TextService* service = new TextService;
    Manager* manager = new Manager;
    Context* context = new Context;

    explicit Fixture(DWORD flags = 0) {
        moduleHandle = GetModuleHandleW(nullptr);
        manager->document = new DocumentManager(context);
        context->documentManager = manager->document;
        context->preserveSelectionRange = true;
        require(SUCCEEDED(service->ActivateEx(manager, 917, flags)), "activate");
    }
    ~Fixture() {
        context->flush();
        service->Deactivate();
        service->Release();
        context->Release();
        manager->Release();
    }
    bool test(WPARAM key, LPARAM message = 1, ITfContext* owner = nullptr) {
        BOOL used = FALSE;
        require(SUCCEEDED(service->OnTestKeyDown(owner ? owner : context, key, message, &used)), "preflight");
        return used != FALSE;
    }
    bool dispatch(WPARAM key, LPARAM message = 1, ITfContext* owner = nullptr) {
        BOOL used = FALSE;
        require(SUCCEEDED(service->OnKeyDown(owner ? owner : context, key, message, &used)), "dispatch");
        return used != FALSE;
    }
    bool key(WPARAM key, bool chord = false, LPARAM message = 1) {
        Keyboard keyboard(chord);
        const bool accepted = test(key, message) && dispatch(key, message);
        BOOL used = FALSE;
        service->OnTestKeyUp(context, key, 2, &used);
        return accepted;
    }
    bool open() { return key('E', true); }
    void focus(Context* next) {
        drop(manager->document);
        manager->document = new DocumentManager(next);
        next->documentManager = manager->document;
        service->OnSetFocus(FALSE);
    }
    void expect(LONG start, LONG end, TfActiveSelEnd active) {
        require(context->doc.cursor == start && context->doc.selectionEnd == end &&
            context->selectionStyle.ase == active && context->doc.textWrites == 0, "selection or document differs");
    }
};
}

int main() {
    int passed = 0;
    auto scenario = [&](const char* name, auto body) {
        body();
        ++passed;
        std::cout << "PASS " << name << "\n";
    };
    try {
        scenario("right endpoint collapse leaves editor open", []{
            Fixture f;
            require(!f.key(VK_RIGHT), "ordinary arrow remains with host");
            require(f.open() && f.key(VK_RETURN) && f.key(VK_RIGHT), "select right");
            f.expect(6, 7, TF_AE_END);
            require(f.key(VK_RETURN), "fold");
            f.expect(7, -1, TF_AE_NONE);
            require(f.key(VK_RIGHT), "panel still open");
            f.expect(8, -1, TF_AE_NONE);
            require(f.key(VK_ESCAPE) && !f.key(VK_RIGHT), "second stage closes");
        });
        scenario("left endpoint and direction reversal", []{
            Fixture f;
            require(f.open() && f.key(VK_RETURN) && f.key(VK_RIGHT) &&
                    f.key(VK_LEFT) && f.key(VK_LEFT), "reverse through anchor");
            f.expect(5, 6, TF_AE_START);
            require(f.key(VK_ESCAPE), "fold left");
            f.expect(5, -1, TF_AE_NONE);
            require(f.key(VK_END), "panel still open after escape");
            f.expect(11, -1, TF_AE_NONE);
        });
        scenario("grapheme selection and document boundaries", []{
            Fixture f;
            f.context->doc.value = L"A\U0001f469\U0001f3fd\x200d\U0001f4bb" L"e\x301Z";
            f.context->doc.cursor = 1;
            require(f.open() && f.key(VK_RETURN) && f.key(VK_RIGHT) && f.key(VK_RIGHT), "select two clusters");
            f.expect(1, 10, TF_AE_END);
            require(f.key(VK_HOME), "select to start");
            f.expect(0, 1, TF_AE_START);
            require(f.key(VK_RETURN), "fold at start");
            f.expect(0, -1, TF_AE_NONE);
        });
        scenario("existing selection consults the host active direction", []{
            Fixture f;
            f.context->doc.cursor = 3;
            f.context->doc.selectionEnd = 7;
            f.context->selectionStyle.ase = TF_AE_START;
            require(f.open() && f.key(VK_RETURN), "fold existing range");
            f.expect(3, -1, TF_AE_NONE);
        });
        scenario("stale pointer or body cannot move a selection", []{
            Fixture f;
            require(f.open() && f.test(VK_LEFT), "claim");
            f.context->doc.cursor = 2;
            require(f.dispatch(VK_LEFT) && f.context->selections == 0, "external caret movement");
            require(f.open() && f.test(VK_LEFT), "reopen");
            f.context->doc.value[0] = L'B';
            require(f.dispatch(VK_LEFT) && f.context->selections == 0, "external body change");
        });
        scenario("late key after document switch is consumed without write", []{
            Fixture f;
            auto* other = new Context;
            require(f.open() && f.test(VK_LEFT), "claim");
            f.focus(other);
            require(f.test(VK_LEFT, 1, other) && f.dispatch(VK_LEFT, 1, other) &&
                    f.context->selections == 0 && other->selections == 0, "late selection blocked");
            other->Release();
        });
        scenario("scope switches, unknown active end and unavailable host", []{
            Fixture f;
            f.context->scope = IS_PASSWORD;
            require(!f.open() && f.context->fullReads == 0, "password");
            f.context->scope = IS_DEFAULT;
            f.context->doc.selectionEnd = 8;
            require(f.open() && !f.key(VK_LEFT), "unknown active end");
            require(f.context->selections == 0, "no guessed endpoint");
            Fixture hidden(TF_TMAE_UIELEMENTENABLEDONLY);
            require(!hidden.open() && !hidden.context->fullReads, "host-only UI");
        });
        scenario("provider adjustment invalidates the panel", []{
            Fixture f;
            require(f.open() && f.key(VK_RETURN), "select");
            f.context->overrideSelectionStyle = true;
            require(f.key(VK_RIGHT) && f.context->selections == 1 &&
                    !f.key(VK_RIGHT) && f.context->doc.textWrites == 0, "no retry after altered selection style");
        });
        scenario("no duplicate mutation for repeated dispatch", []{
            Fixture f;
            require(f.open() && f.test(VK_RIGHT) && f.dispatch(VK_RIGHT) && f.dispatch(VK_RIGHT), "same dispatch");
            require(f.context->selections == 1, "exactly one selection movement");
            require(f.key(VK_RIGHT, false, 0x40000001) && f.context->selections == 2, "real repeat allowed");
        });
        scenario("retained async edit is disarmed", []{
            Fixture f;
            require(f.open(), "open");
            f.context->deferSynchronous = true;
            require(f.key(VK_LEFT) && f.context->selections == 0, "no speculative move");
            require(f.context->flush() == std::vector<HRESULT>{E_ABORT} && f.context->selections == 0,
                    "late callback after refusal");
        });
        std::cout << passed << " selection panel scenarios passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << "\n";
        return 1;
    }
}
