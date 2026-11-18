    static bool batchShortcut() {
        return ctrl() && shift() && !(GetKeyState(VK_MENU) & 0x8000);
    }

    bool batchCurrent(ITfContext* context, unsigned long long stamp, unsigned long long epoch) {
        auto visible = [&]{ return !clipboardPanel && hostShowsOwnUI &&
                                  !(activationFlags & TF_TMAE_UIELEMENTENABLEDONLY); };
        return visible() && backspaceCurrent(context, stamp, epoch) && visible();
    }

    void clearBatchDelete() {
        ++batchGeneration;
        if (!batchDelete) return;
        batchDelete.reset();
        if (popup) KillTimer(popup, BatchDeleteTimer);
        if (status == BatchDeleteHint || status == BatchUndoHint) status.clear();
    }

    std::shared_ptr<BatchDelete> prepareBatchDelete(ITfContext* context) {
        const auto stamp = revision, epoch = focusEpoch, generation = batchGeneration;
        auto current = [&]{ return batchCurrent(context, stamp, epoch) && generation == batchGeneration; };
        if (!current()) return {};
        using wangshu::voice_position::Snapshot;
        Snapshot before;
        const auto result = runSynchronousEdit(context, TF_ES_READ, [&](TfEditCookie cookie) -> HRESULT {
            VoiceGuard guard(context, true);
            if (!current() || FAILED(guard.DoEditSession(cookie)) || !current()) return E_ABORT;
            if (FAILED(wangshu::voice_position::capture(context, cookie, before)) || !before.caret ||
                !wangshu::clipboard_edit::boundary(before) || !current() ||
                FAILED(guard.DoEditSession(cookie)) || !current()) return E_ABORT;
            return S_OK;
        });
        if (result != S_OK || !current()) return {};
        context->AddRef();
        auto owner = std::shared_ptr<ITfContext>(context, [](ITfContext* value){ value->Release(); });
        auto prepared = std::make_shared<BatchDelete>(BatchDelete{
            std::move(owner), std::move(before), std::nullopt, stamp, epoch, GetTickCount64() + 10000});
        makeWindow();
        if (!current() || !popup || !SetTimer(popup, BatchDeleteTimer, 250, nullptr)) return {};
        return prepared;
    }

    bool claimBatchKey(ITfContext* context, WPARAM key, LPARAM message) noexcept {
        try {
            const bool repeat = (message & PreviousKeyDown) != 0;
            if (key == VK_BACK) {
                if (batchBackClaim && *batchBackClaim == message) return true;
                batchBackClaim.reset();
                if (repeat && batchBackDown) {
                    batchBackClaim = message;
                    return true;
                }
                if (!repeat) batchBackDown = false;
                if (!repeat && batchShortcut()) {
                    if (batchDelete) { clearBatchDelete(); update(); }
                    if (auto prepared = prepareBatchDelete(context)) {
                        batchDelete = std::move(prepared);
                        batchBackClaim = message;
                        return true;
                    }
                }
            }
            if (key == 'Z') {
                if (batchUndoClaim && *batchUndoClaim == message) return true;
                batchUndoClaim.reset();
                if (!repeat) batchUndoDown = false;
                if (batchShortcut() &&
                    ((repeat && batchUndoDown) || (!repeat && batchDelete && batchDelete->undo &&
                    batchDelete->owner.get() == context && GetTickCount64() < batchDelete->expires))) {
                    batchUndoClaim = message;
                    return true;
                }
            }
            if (key == VK_ESCAPE) {
                if (batchEscapeClaim && *batchEscapeClaim == message) return true;
                batchEscapeClaim.reset();
                if (batchBackDown && batchDelete && !batchDelete->undo) {
                    batchEscapeClaim = message;
                    return true;
                }
            }
            if (batchDelete && !voiceModifier(key)) {
                clearBatchDelete();
                update();
            }
        } catch (...) {
            clearBatchDelete();
        }
        return false;
    }

    bool dispatchBatchKey(ITfContext* context, WPARAM key, LPARAM message) noexcept {
        if (key == VK_BACK && batchBackClaim && *batchBackClaim == message) {
            batchBackClaim.reset();
            if (message & PreviousKeyDown) return true;
            batchBackDown = true;
            try {
                auto operation = batchDelete;
                if (operation && !operation->undo && operation->owner.get() == context && batchShortcut() &&
                    batchCurrent(context, operation->stamp, operation->epoch) && batchDelete == operation) {
                    clearUppercase();
                    clearVoiceUndo();
                    clearCalculation();
                    if (batchDelete == operation && popup) {
                        status = BatchDeleteHint;
                        operation->prompt = true;
                        update();
                        if (popup) UpdateWindow(popup);
                        if (batchDelete == operation && popup && IsWindowVisible(popup) &&
                            status == BatchDeleteHint) return true;
                    }
                }
            } catch (...) {}
            clearBatchDelete();
            update();
            return true;
        }
        if (key == 'Z' && batchUndoClaim && *batchUndoClaim == message) {
            batchUndoClaim.reset();
            batchUndoDown = true;
            if (!(message & PreviousKeyDown) && batchShortcut()) applyBatchDelete(context, true);
            return true;
        }
        if (key == VK_ESCAPE && batchEscapeClaim && *batchEscapeClaim == message) {
            batchEscapeClaim.reset();
            clearBatchDelete();
            update();
            return true;
        }
        if (batchDelete && !voiceModifier(key)) {
            clearBatchDelete();
            update();
        }
        return false;
    }

    void applyBatchDelete(ITfContext* context, bool undo) noexcept {
        auto operation = batchDelete;
        clearBatchDelete();
        const auto generation = batchGeneration;
        try {
            if (!operation || operation->owner.get() != context || undo != operation->undo.has_value() ||
                GetTickCount64() >= operation->expires) { update(); return; }
            if (!batchCurrent(context, operation->stamp, operation->epoch)) { update(); return; }
            auto current = [&]{
                return batchCurrent(context, operation->stamp, operation->epoch) &&
                    generation == batchGeneration && GetTickCount64() < operation->expires;
            };
            using wangshu::clipboard_edit::Edit;
            using wangshu::voice_position::Snapshot;
            const auto& expected = operation->expected;
            const Edit edit = undo ? std::move(*operation->undo) :
                Edit{0, expected.caret, L"", {expected.text.substr(expected.caret), 0}};
            std::optional<Edit> reverse;
            if (!undo) reverse = wangshu::clipboard_edit::insert(edit.after, expected.text.substr(0, expected.caret));
            if (!undo && !reverse) { update(); return; }
            bool attempted = false;
            const auto result = runSynchronousEdit(context, TF_ES_READWRITE, [&](TfEditCookie cookie) -> HRESULT {
                VoiceGuard guard(context, true);
                if (!current() || FAILED(guard.DoEditSession(cookie)) || !current()) return E_ABORT;
                auto caret = wangshu::grapheme_backspace::collapsedCaret(context, cookie);
                Snapshot before;
                if (!caret || FAILED(wangshu::voice_position::capture(context, cookie, before)) ||
                    !(before == expected) || !current()) return E_ABORT;
                auto range = wangshu::clipboard_edit::editRange(caret.get(), cookie, before, edit);
                if (!range || FAILED(guard.DoEditSession(cookie)) ||
                    !wangshu::grapheme_backspace::sameCaret(context, cookie, caret.get()) || !current()) return E_ABORT;
                attempted = true;
                if (FAILED(range->SetText(cookie, 0, edit.replacement.c_str(), static_cast<LONG>(edit.replacement.size()))) ||
                    !current() || FAILED(guard.DoEditSession(cookie)) || !current()) return E_FAIL;
                auto placed = wangshu::clipboard_edit::expectedCaret(context, cookie, edit.after);
                if (!placed || !current()) return E_FAIL;
                TF_SELECTION selection{placed.get(), {TF_AE_NONE, FALSE}};
                if (FAILED(context->SetSelection(cookie, 1, &selection)) || !current() ||
                    FAILED(guard.DoEditSession(cookie)) || !current()) return E_FAIL;
                Snapshot after;
                return SUCCEEDED(wangshu::voice_position::capture(context, cookie, after)) &&
                    after == edit.after && current() ? S_OK : E_FAIL;
            });
            if (!batchCurrent(context, operation->stamp, operation->epoch) || generation != batchGeneration) return;
            invalidatePending();
            if (result == S_OK && !undo && popup && SetTimer(popup, BatchDeleteTimer, 250, nullptr)) {
                batchDelete = std::make_shared<BatchDelete>(BatchDelete{
                    std::move(operation->owner), edit.after, std::move(reverse),
                    revision, focusEpoch, GetTickCount64() + 20000});
                status = BatchUndoHint;
            } else if (result == S_OK) {
                status = undo ? L"已撤销本次批量删除" : L"已批量删除";
            } else {
                status = attempted ? L"编辑器未确认操作结果，请核对正文" : L"正文或光标已变化，操作已取消";
            }
            update();
        } catch (...) {
            update();
        }
    }
