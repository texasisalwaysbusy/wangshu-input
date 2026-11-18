    static bool editorShortcut(WPARAM key) {
        return key == 'E' && batchShortcut();
    }

    void clearEditorPanel() {
        if (!editorPanel) return;
        editorPanel.reset();
        if (status == CursorPanelHint || status == SelectionPanelHint) status.clear();
    }

    bool editorContext(ITfContext* context, unsigned long long stamp, unsigned long long epoch) {
        return !(activationFlags & TF_TMAE_UIELEMENTENABLEDONLY) && hostShowsOwnUI &&
            !clipboardPanel && !batchBackDown && !pendingRevision && backspaceCurrent(context, stamp, epoch);
    }

    static bool editorControl(WPARAM key) {
        return key == VK_LEFT || key == VK_RIGHT || key == VK_HOME || key == VK_END ||
            key == VK_RETURN || key == VK_ESCAPE;
    }

    bool claimEditorKey(ITfContext* context, WPARAM key, LPARAM message) noexcept {
        try {
            if (editorKeyClaim && editorKeyClaim->key == key && editorKeyClaim->message == message &&
                !editorKeyClaim->dispatched) return true;
            const bool shortcut = editorShortcut(key);
            const bool control = editorPanel && editorControl(key) &&
                !ctrl() && !shift() && !(GetKeyState(VK_MENU) & 0x8000);
            if (shortcut || control) {
                const auto stamp = revision, epoch = focusEpoch;
                const bool opening = !editorPanel;
                if (opening && (!shortcut || !editorContext(context, stamp, epoch))) return false;
                if (opening) {
                    const auto allowed = runSynchronousEdit(context, TF_ES_READ, [&](TfEditCookie cookie) {
                        VoiceGuard guard(context, true);
                        return guard.DoEditSession(cookie);
                    });
                    if (allowed != S_OK || !editorContext(context, stamp, epoch)) return false;
                }
                editorKeyClaim = EditorKeyClaim{context, key, message, stamp, epoch, opening};
                return true;
            }
            if (editorPanel && !voiceModifier(key)) { clearEditorPanel(); update(); }
        } catch (...) {
            clearEditorPanel();
        }
        return false;
    }

    void updateEditorPanel() {
        status = editorPanel->selecting ? SelectionPanelHint : CursorPanelHint;
        update();
    }

    bool dispatchEditorKey(ITfContext* context, WPARAM key, LPARAM message) noexcept {
        if (!editorKeyClaim || editorKeyClaim->key != key || editorKeyClaim->message != message) return false;
        if (editorKeyClaim->dispatched) return true;
        editorKeyClaim->dispatched = true;
        const auto claim = *editorKeyClaim;
        const auto panel = editorPanel;
        try {
            if (claim.owner != context || (!claim.opening && (!panel || panel->owner.get() != context))) return true;
            if ((key == 'E' && !editorShortcut(key)) ||
                (key != 'E' && (ctrl() || shift() || (GetKeyState(VK_MENU) & 0x8000)))) return true;
            const bool toggle = key == VK_ESCAPE || key == VK_RETURN || key == 'E';
            if (toggle && (message & PreviousKeyDown)) return true;
            auto current = [&]{
                return editorContext(context, claim.stamp, claim.epoch) && editorPanel == panel;
            };
            if (!current()) return true;
            if (panel && !panel->selecting && (key == VK_ESCAPE || key == 'E')) {
                clearEditorPanel();
                update();
                return true;
            }
            using namespace wangshu::editor_selection;
            std::optional<Snapshot> confirmed;
            const auto result = runSynchronousEdit(context, panel ? TF_ES_READWRITE : TF_ES_READ,
                [&](TfEditCookie cookie) -> HRESULT {
                    VoiceGuard guard(context, true);
                    if (!current() || guard.DoEditSession(cookie) != S_OK || !current()) return E_ABORT;
                    auto before = capture(context, cookie);
                    if (!before || (panel && !(*before == panel->expected)) || !current()) return E_ABORT;
                    const auto units = std::u16string(before->text.begin(), before->text.end());
                    const auto boundaries = wangshu::grapheme::boundaries(units);
                    if (!boundaries || !std::binary_search(boundaries->begin(), boundaries->end(), before->position.start) ||
                        !std::binary_search(boundaries->begin(), boundaries->end(), before->position.end))
                        return E_ABORT;
                    if (!panel) {
                        confirmed = std::move(before);
                        return S_OK;
                    }
                    const auto next = destination(*boundaries, before->position, key, !toggle && panel->selecting);
                    if (!(next == before->position)) {
                        auto range = makeRange(context, cookie, next);
                        auto oldRange = makeRange(context, cookie, before->position);
                        if (!range || !oldRange || guard.DoEditSession(cookie) != S_OK ||
                            !sameSelection(context, cookie, oldRange.get(), before->position) || !current()) return E_ABORT;
                        const TF_SELECTION request{range.get(), {next.active, FALSE}};
                        if (context->SetSelection(cookie, 1, &request) != S_OK || !current() ||
                            guard.DoEditSession(cookie) != S_OK || !current()) return E_ABORT;
                        auto after = capture(context, cookie);
                        if (!after || !(after->position == next) || after->text != before->text || !current())
                            return E_ABORT;
                        confirmed = std::move(after);
                    } else confirmed = std::move(before);
                    return S_OK;
                });
            if (!current()) return true;
            if (result != S_OK || !confirmed) {
                clearEditorPanel();
                status = L"未能确认正文或光标，请核对后重新打开编辑";
                update();
                return true;
            }
            if (panel) {
                panel->expected = std::move(*confirmed);
                if (toggle) panel->selecting = !panel->selecting;
            } else {
                clearUppercase();
                clearVoiceUndo();
                clearCalculation();
                if (!current()) return true;
                invalidatePending();
                context->AddRef();
                auto owner = std::shared_ptr<ITfContext>(context, [](ITfContext* value){ value->Release(); });
                const bool selecting = confirmed->position.start != confirmed->position.end;
                editorPanel = std::make_shared<EditorPanel>(EditorPanel{std::move(owner), std::move(*confirmed), selecting});
            }
            updateEditorPanel();
            if (editorPanel && (!popup || !IsWindowVisible(popup))) clearEditorPanel();
        } catch (...) {
            clearEditorPanel();
            update();
        }
        return true;
    }
