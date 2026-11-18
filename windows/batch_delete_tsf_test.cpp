// Production TSF key routing against synthetic text, without IME registration.
#define main unused_basic_tsf
#include "../tests/tsf_edit_test.cpp"
#undef main

namespace {
struct Keyboard {
    BYTE saved[256]{};
    explicit Keyboard(bool chord, bool ctrlOnly = false) {
        BYTE keys[256]{};
        if (chord) keys[VK_SHIFT] = 0x80;
        if (chord || ctrlOnly) keys[VK_CONTROL] = 0x80;
        if (!GetKeyboardState(saved) || !SetKeyboardState(keys)) throw std::runtime_error("keyboard fixture");
    }
    ~Keyboard() { SetKeyboardState(saved); }
};

struct Fixture {
    TextService* service = new TextService;
    Manager* manager = new Manager;
    Context* context = new Context;

    explicit Fixture(DWORD flags = 0) {
        moduleHandle = GetModuleHandleW(nullptr);
        manager->document = new DocumentManager(context);
        context->documentManager = manager->document;
        if (FAILED(service->ActivateEx(manager, 917, flags))) throw std::runtime_error("activation");
    }
    ~Fixture() {
        context->flush();
        service->Deactivate();
        service->Release();
        context->Release();
        manager->Release();
    }
    bool preflight(WPARAM key, LPARAM message = 1, ITfContext* owner = nullptr) {
        BOOL consumed = FALSE;
        check(SUCCEEDED(service->OnTestKeyDown(owner ? owner : context, key, message, &consumed)), "key test");
        return consumed != FALSE;
    }
    bool dispatch(WPARAM key, LPARAM message = 1, ITfContext* owner = nullptr) {
        BOOL consumed = FALSE;
        check(SUCCEEDED(service->OnKeyDown(owner ? owner : context, key, message, &consumed)), "key down");
        return consumed != FALSE;
    }
    bool press(WPARAM key = VK_BACK, bool chord = true, LPARAM message = 1) {
        Keyboard keyboard(chord);
        return preflight(key, message) && dispatch(key, message);
    }
    bool preflightUp(WPARAM key, ITfContext* owner = nullptr) {
        BOOL consumed = FALSE;
        check(SUCCEEDED(service->OnTestKeyUp(owner ? owner : context, key, 2, &consumed)), "key up test");
        return consumed != FALSE;
    }
    bool release(bool chord = true, ITfContext* owner = nullptr, WPARAM key = VK_BACK) {
        Keyboard keyboard(chord);
        BOOL consumed = FALSE;
        if (!preflightUp(key, owner)) return false;
        check(SUCCEEDED(service->OnKeyUp(owner ? owner : context, key, 2, &consumed)), "key up");
        return consumed != FALSE;
    }
    void focus(Context* other) {
        drop(manager->document);
        manager->document = new DocumentManager(other);
        other->documentManager = manager->document;
        service->OnSetFocus(FALSE);
    }
};
}

int main() {
    int passed = 0;
    auto test = [&](const char* name, auto checkBehavior) {
        checkBehavior();
        ++passed;
        std::cout << "PASS " << name << "\n";
    };
    try {
        test("preview, repeat and key-up preflight are read-only", []{
            Fixture f;
            Keyboard keyboard(true);
            check(f.preflight(VK_BACK) && f.preflight(VK_BACK) && f.dispatch(VK_BACK), "claim preview");
            check(f.press(VK_BACK, true, 0x40000001) && f.preflightUp(VK_BACK) &&
                  f.context->doc.textWrites == 0 && f.context->doc.value == L"beforeafter", "no preview write");
            check(f.release() && f.context->doc.value == L"after" && f.context->doc.cursor == 0 &&
                  f.context->doc.textWrites == 1 && !f.release(), "release deletes once");
        });
        test("undo restores only the removed prefix and original cursor", []{
            Fixture f;
            const auto prefix = std::wstring(L"A\U0001f469\U0001f3fd\x200d\U0001f4bb" L"e\x301");
            f.context->doc.value = prefix + L"tail";
            f.context->doc.cursor = static_cast<LONG>(prefix.size());
            check(f.press() && f.release() && f.context->doc.value == L"tail", "whole UTF-16 prefix removal");
            check(f.press('Z') && f.context->doc.value == prefix + L"tail" &&
                  f.context->doc.cursor == static_cast<LONG>(prefix.size()), "restore snapshot");
            check(f.press('Z', true, 0x40000001) && f.context->doc.textWrites == 2, "undo repeat absorbed");
            f.release(true, nullptr, 'Z');
            check(!f.press('Z') && f.context->doc.textWrites == 2, "undo is single-use");
        });
        test("ordinary Backspace, word deletion and application undo stay with the host", []{
            Fixture f;
            check(!f.press(VK_BACK, false), "ordinary plain text backspace");
            Keyboard ctrlOnly(false, true);
            check(!f.preflight(VK_BACK) && !f.preflight('Z'), "word deletion and application undo routes retained");
        });
        test("Escape and releasing a modifier first both cancel", []{
            Fixture f;
            check(f.press() && f.press(VK_ESCAPE, false) && f.release() && !f.context->doc.textWrites, "escape");
            check(f.press() && !f.release(false, nullptr, VK_SHIFT) && f.release(false) &&
                  !f.context->doc.textWrites, "modifier key-up");
        });
        test("another key cancels preview and an intervening edit invalidates undo", []{
            Fixture f;
            check(f.press() && f.press('A', false) && f.release() && f.context->doc.textWrites == 1, "ordinary input only");
            check(f.press(VK_RETURN, false), "finish composition");
            check(f.press() && f.release(), "new delete after composition");
            const int writes = f.context->doc.textWrites;
            f.press(VK_LEFT, false);
            check(!f.press('Z') && f.context->doc.textWrites == writes, "movement invalidates undo");
        });
        test("focus changes before dispatch or while held leave both fields intact", []{
            Fixture f;
            auto* other = new Context;
            Keyboard keyboard(true);
            check(f.preflight(VK_BACK), "capture first field");
            f.focus(other);
            check(f.preflight(VK_BACK, 1, other) && f.dispatch(VK_BACK, 1, other) &&
                  f.release(true, other) && !other->doc.textWrites && !f.context->doc.textWrites, "late dispatch blocked");
            check(f.preflight(VK_BACK, 3, other) && f.dispatch(VK_BACK, 3, other), "arm second field");
            f.focus(f.context);
            check(f.release() && !other->doc.textWrites && !f.context->doc.textWrites, "late release blocked");
            other->Release();
        });
        test("changed text, moved caret and noncollapsed selection block release", []{
            Fixture f;
            check(f.press(), "arm before host changes text");
            f.context->doc.value[0] = L'Z';
            check(f.release() && !f.context->doc.textWrites, "body changed");
            check(f.press(), "rearm after change");
            --f.context->doc.cursor;
            check(f.release() && !f.context->doc.textWrites, "caret moved");
            check(f.press(), "rearm for selection");
            f.context->doc.selectionEnd = 1;
            check(f.release() && !f.context->doc.textWrites, "selection changed");
        });
        test("undo rejects an external edit or stale focus", []{
            Fixture f;
            check(f.press() && f.release(), "batch delete before external body edit");
            f.context->doc.value[0] = L'Z';
            check(f.press('Z') && f.context->doc.textWrites == 1 && f.context->doc.value == L"Zfter", "no overwrite");
            f.release(true, nullptr, 'Z');
            f.context->doc.cursor = 1;
            check(f.press() && f.release(), "new batch delete");
            Keyboard keyboard(true);
            check(f.preflight('Z', 3), "claim undo");
            auto* other = new Context;
            f.focus(other);
            check(f.dispatch('Z', 3, other) && !other->doc.textWrites && f.context->doc.textWrites == 2,
                  "late undo cannot write either field");
            other->Release();
        });
        test("privacy, incompatible UI and missing snapshot refuse the operation", []{
            Fixture f;
            f.context->scope = IS_PASSWORD;
            check(!f.press() && !f.context->doc.textWrites && !f.context->fullReads, "no password snapshot");
            f.context->scope = IS_DEFAULT;
            f.context->doc.noFull = true;
            check(!f.press() && !f.context->doc.textWrites, "unavailable full text");
            f.context->doc.noFull = false;
            check(f.press(), "arm in ordinary context");
            f.context->scope = IS_PRIVATE;
            check(f.release() && !f.context->doc.textWrites, "scope changed while held");
            Fixture noPopup(TF_TMAE_UIELEMENTENABLEDONLY);
            check(!noPopup.press() && !noPopup.context->fullReads, "host-only UI refuses before text capture");
        });
        test("empty prefix, mid-cluster caret and initial selection refuse preview", []{
            Fixture f;
            f.context->doc.cursor = 0;
            check(!f.press(), "no prefix");
            f.context->doc.value = L"Ae\x301" L"Z";
            f.context->doc.cursor = 2;
            check(!f.press(), "inside combining cluster");
            f.context->doc.cursor = 3;
            f.context->doc.selectionEnd = 4;
            check(!f.press() && !f.context->doc.textWrites, "initial range");
        });
        test("failed write or caret placement cannot generate an undo", []{
            for (bool afterWrite : {false, true}) {
                Fixture f;
                check(f.press(), "arm before host failure");
                f.context->doc.denySelection = !afterWrite;
                f.context->doc.writeThenFail = afterWrite;
                check(f.release() && f.context->doc.textWrites == 1 && !f.press('Z') &&
                      !f.release(), "uncertain write never repeated or reversible");
            }
        });
        test("unexpected asynchronous write is disarmed", []{
            Fixture f;
            check(f.press(), "initial synchronous read");
            f.context->deferSynchronous = true;
            check(f.release() && f.context->doc.textWrites == 0, "refuse deferred write");
            check(f.context->flush() == std::vector<HRESULT>{E_ABORT} && !f.context->doc.textWrites,
                  "retained callback cannot edit later");
        });
        test("reentrant focus loss during release prevents a pending write", []{
            Fixture f;
            check(f.press(), "start with stable context");
            f.context->doc.onRead = [&]{ f.service->OnSetFocus(FALSE); };
            check(f.release() && !f.context->doc.textWrites && !f.press('Z'), "read invalidated before mutation");
        });
        std::cout << passed << " scoped batch deletion checks passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << "\n";
        return 1;
    }
}
