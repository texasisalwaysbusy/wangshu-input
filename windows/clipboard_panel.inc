// Included by text_service.cpp. Clipboard state belongs to one TSF service.
namespace {
std::string clipboardLabel(std::wstring text) {
    if (text==L" ") return "〔空格〕";
    if (text==L"\n"||text==L"\r"||text==L"\r\n") return "〔换行〕";
    if (text==L"\t") return "〔制表符〕";
    for (auto& ch:text) if (ch==L'\n'||ch==L'\r'||ch==L'\t') ch=L' ';
    if (text.size()>64) {
        const auto end=text[63]>=0xd800&&text[63]<=0xdbff?63:64;
        text.resize(end);
        text+=L"…";
    }
    return narrow(text);
}

HWND clipboardForeground(ITfContext* context) {
    ITfContextView* view=nullptr;
    if (FAILED(context->GetActiveView(&view))||!view) {drop(view);return nullptr;}
    HWND window=nullptr;
    const auto result=view->GetWnd(&window);
    view->Release();
    if (FAILED(result)||!window) return nullptr;
    const auto root=GetAncestor(window,GA_ROOT);
    return root&&root==GetForegroundWindow()?root:nullptr;
}
}

bool TextService::clipboardContext(ITfContext* context,std::uint64_t epoch) {
    auto state=[&] {
        return context&&manager&&loaded&&focusEpoch==epoch&&!composition&&buffer.empty()&&
            !privateInput&&!listening&&voiceReview.empty()&&
            !(activationFlags&(TF_TMAE_SECUREMODE|TF_TMAE_COMLESS|TF_TMAE_CONSOLE));
    };
    if (!state()||disabled(context)) return false;
    auto* target=focused();
    const bool same=target==context;
    drop(target);
    return same&&state();
}

bool TextService::clipboardCurrent(const std::shared_ptr<ClipboardPanel>& panel,std::uint64_t generation) {
    return panel&&clipboardPanel==panel&&panel->generation==generation&&
        clipboardContext(panel->owner.get(),panel->epoch)&&clipboardPanel==panel&&panel->generation==generation;
}

HRESULT TextService::clipboardScope(ITfContext* context,TfEditCookie cookie,bool requireExplicit) {
    TF_SELECTION selection{};
    ULONG count=0;
    const auto result=context->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&selection,&count);
    wangshu::voice_position::Range range(selection.range);
    if (FAILED(result)||count!=1||!range||selection.style.fInterimChar) return E_ABORT;
    return inputScopeAllows(context,cookie,range.get(),true,nullptr,true,requireExplicit);
}

bool TextService::verifyClipboard(const std::shared_ptr<ClipboardPanel>& panel,std::uint64_t generation) {
    auto current=[&]{return clipboardCurrent(panel,generation);};
    if (!current()) return false;
    auto* context=panel->owner.get();
    const auto result=runSynchronousEdit(context,TF_ES_READ,[&](TfEditCookie cookie) {
        if (!current()||FAILED(clipboardScope(context,cookie))||!current()) return E_ABORT;
        wangshu::voice_position::Snapshot fresh;
        if (FAILED(wangshu::voice_position::capture(context,cookie,fresh))||
            !current()||!(fresh==panel->expected)) return E_ABORT;
        return S_OK;
    });
    return result==S_OK&&current();
}

void TextService::startClipboard(ITfContext* context) {
    const auto epoch=focusEpoch,stamp=revision;
    auto current=[&]{return revision==stamp&&clipboardContext(context,epoch)&&revision==stamp;};
    auto report=[&](const wchar_t* message){
        if(current()){status=message;update();}
    };
    if (!current()) return;
    auto panel=std::make_shared<ClipboardPanel>();
    context->AddRef();
    panel->owner=std::shared_ptr<ITfContext>(context,[](ITfContext* value){value->Release();});
    panel->epoch=epoch;
    if (runSynchronousEdit(context,TF_ES_READ,[&](TfEditCookie cookie) {
            if (!current()||FAILED(clipboardScope(context,cookie))||!current()||
                FAILED(wangshu::voice_position::capture(context,cookie,panel->expected))||!current()||
                !wangshu::clipboard_edit::boundary(panel->expected)) return E_ABORT;
            return S_OK;
        })!=S_OK||!current()) {
        report(L"当前编辑器无法确认剪贴板插入位置");
        return;
    }
    try {
        if (!clipboardStore) clipboardStore=std::make_shared<wangshu::ClipboardStore>();
        const auto store=clipboardStore;
        panel->history=store->read(wangshu::WindowsPersonalStore::learningNow());
    } catch (...) {
        report(L"本地剪贴板历史不可用，请在个人数据管理程序中检查");
        return;
    }
    if (!current()) return;
    clipboardPanel=panel;
    publishClipboard(panel);
}

void TextService::clearClipboard() noexcept {
    clipboardPanel.reset();
    if (clipboardListening&&popup) RemoveClipboardFormatListener(popup);
    clipboardListening=false;
    clipboardSequence=0;
}

void TextService::publishClipboard(const std::shared_ptr<ClipboardPanel>& panel) {
    if (clipboardPanel!=panel) return;
    panel->generation=++clipboardGeneration;
    endCandidateUI(false);
    if (clipboardPanel!=panel) return;
    if (panel->splitEntry) status=L"选中即插入 · 再选撤回此块 · PgUp/PgDn 翻页 · 退格返回";
    else if (panel->history.items.empty())
        status=panel->history.recording?L"暂无历史 · 可在此编辑器复制纯文本":L"暂无历史 · 在个人数据管理程序中开启";
    else status=L"1–9 选择操作 · PgUp/PgDn 翻页 · Esc 关闭";
    update();
}

void TextService::clipboardCandidates() {
    const auto panel=clipboardPanel;
    displayed=CandidateProof{};
    displayed.clipboardGeneration=panel->generation;
    displayed.context=panel->owner.get();
    candidates.clear();
    if (panel->splitEntry) {
        const auto start=panel->page*wangshu::clipboard_edit::SplitPage;
        const auto end=std::min(start+wangshu::clipboard_edit::SplitPage,panel->split.tokens.size());
        for (size_t index=start;index<end;++index) {
            const bool selected=panel->split.selected(index);
            auto label=clipboardLabel(panel->split.tokens[index]);
            if (selected) label="✓ "+label;
            candidates.push_back({std::move(label),selected?"撤回此块":"插入此块"});
        }
    } else {
        const auto start=panel->page*wangshu::clipboard_edit::HistoryPage;
        const auto end=std::min(start+wangshu::clipboard_edit::HistoryPage,panel->history.items.size());
        for (size_t index=start;index<end;++index) {
            auto label=clipboardLabel(wide(panel->history.items[index].text));
            candidates.push_back({"插入 · "+label,"剪贴板"});
            candidates.push_back({"拆词 · "+label,"剪贴板"});
        }
    }
    auto* context=panel->owner.get();
    context->AddRef();
    drop(candidateContext);
    candidateContext=context;
}

bool TextService::applyClipboard(const std::shared_ptr<ClipboardPanel>& panel,std::uint64_t generation,
                                const wangshu::clipboard_edit::Edit& edit,bool& attempted) {
    auto current=[&]{return clipboardCurrent(panel,generation);};
    if (!current()) return false;
    auto* context=panel->owner.get();
    const auto result=runSynchronousEdit(context,TF_ES_READWRITE,[&](TfEditCookie cookie) {
        if (!current()||FAILED(clipboardScope(context,cookie))||!current()) return E_ABORT;
        const auto caret=wangshu::grapheme_backspace::collapsedCaret(context,cookie);
        wangshu::voice_position::Snapshot before;
        if (!caret||FAILED(wangshu::voice_position::capture(context,cookie,before))||
            !(before==panel->expected)||!current()) return E_ABORT;
        const auto range=wangshu::clipboard_edit::editRange(caret.get(),cookie,before,edit);
        if (!range||!current()||FAILED(clipboardScope(context,cookie))||
            !wangshu::grapheme_backspace::sameCaret(context,cookie,caret.get())||!current()) return E_ABORT;
        attempted=true;
        if (FAILED(range->SetText(cookie,0,edit.replacement.c_str(),static_cast<LONG>(edit.replacement.size())))||
            !current()||FAILED(clipboardScope(context,cookie))||!current()) return E_ABORT;
        const auto placed=wangshu::clipboard_edit::expectedCaret(context,cookie,edit.after);
        if (!placed||!current()) return E_ABORT;
        TF_SELECTION selection{placed.get(),{TF_AE_NONE,FALSE}};
        if (FAILED(context->SetSelection(cookie,1,&selection))||!current()||
            FAILED(clipboardScope(context,cookie))||!current()) return E_ABORT;
        wangshu::voice_position::Snapshot after;
        if (FAILED(wangshu::voice_position::capture(context,cookie,after))||
            !(after==edit.after)||!current()) return E_ABORT;
        return S_OK;
    });
    return result==S_OK&&current();
}

bool TextService::selectClipboard(const CandidateProof& expected,size_t index) {
    auto panel=clipboardPanel;
    const auto generation=expected.clipboardGeneration;
    if (!panel||index>=candidates.size()||expected.context!=panel->owner.get()||
        !clipboardCurrent(panel,generation)) return false;
    auto close=[&](const wchar_t* message) {
        if (clipboardPanel==panel&&panel->generation==generation) {
            clearClipboard();
            status=message;
            update();
        }
    };
    try {
        if (!verifyClipboard(panel,generation)) {
            close(L"编辑位置已变化，请重新打开剪贴板");
            return false;
        }
        const auto itemIndex=panel->page*wangshu::clipboard_edit::HistoryPage+index/2;
        if (!panel->splitEntry&&itemIndex>=panel->history.items.size()) return false;
        const auto& item=panel->splitEntry?*panel->splitEntry:panel->history.items[itemIndex];
        const auto store=clipboardStore;
        if (!store) return false;
        const auto latest=store->read(wangshu::WindowsPersonalStore::learningNow());
        if (!clipboardCurrent(panel,generation)) return false;
        const auto found=std::find_if(latest.items.begin(),latest.items.end(),[&](const auto& entry) {
            return entry.id==item.id&&entry.text==item.text;
        });
        if (found==latest.items.end()) {
            close(L"剪贴板条目已变化，请重新打开");
            return false;
        }
        if (!panel->splitEntry&&index%2) {
            auto text=wide(item.text);
            auto tokens=wangshu::clipboard_edit::split(text);
            if (tokens.empty()||!wangshu::voice_position::textValid(text)) return false;
            panel->splitEntry=item;
            panel->split={std::move(tokens),{},panel->expected.caret};
            panel->page=0;
            publishClipboard(panel);
            return true;
        }
        auto next=panel->split;
        auto edit=panel->splitEntry
            ?next.toggle(panel->page*wangshu::clipboard_edit::SplitPage+index,panel->expected)
            :wangshu::clipboard_edit::insert(panel->expected,wide(item.text));
        bool attempted=false;
        if (!edit||!applyClipboard(panel,generation,*edit,attempted)) {
            close(attempted?L"剪贴板写入结果未确认，请核对正文":L"编辑器无法确认剪贴板操作");
            return false;
        }
        if (!panel->splitEntry) {
            close(L"");
            return true;
        }
        panel->expected=std::move(edit->after);
        panel->split=std::move(next);
        publishClipboard(panel);
        return true;
    } catch (...) {
        close(L"剪贴板操作已停止，请核对正文");
        return false;
    }
}

void TextService::syncClipboardListener() noexcept {
    const bool wanted=popup&&clipboardPanel&&!clipboardPanel->splitEntry&&clipboardPanel->history.recording&&
                      hostShowsOwnUI&&IsWindowVisible(popup);
    if (!wanted) {
        if (clipboardListening&&popup) RemoveClipboardFormatListener(popup);
        clipboardListening=false;
        clipboardSequence=0;
    } else if (!clipboardListening) {
        clipboardSequence=GetClipboardSequenceNumber();
        clipboardListening=clipboardSequence&&AddClipboardFormatListener(popup);
    }
}

void TextService::clipboardChanged() noexcept {
    try {
        auto panel=clipboardPanel;
        const auto store=clipboardStore;
        if (!clipboardListening||!panel||panel->splitEntry||!store) return;
        const auto sequence=GetClipboardSequenceNumber();
        if (!sequence||sequence==clipboardSequence) return;
        clipboardSequence=sequence;
        const auto generation=panel->generation;
        auto current=[&]{return clipboardCurrent(panel,generation);};
        auto* context=panel->owner.get();
        if (!current()) return;
        auto foreground=clipboardForeground(context);
        if (!foreground||!current()) return;
        auto allowed=[&] {
            if (!current()||clipboardForeground(context)!=foreground||!current()||
                GetClipboardSequenceNumber()!=sequence) return false;
            return runSynchronousEdit(context,TF_ES_READ,[&](TfEditCookie cookie) {
                return current()&&SUCCEEDED(clipboardScope(context,cookie,true))&&current()?S_OK:E_ABORT;
            })==S_OK&&current()&&GetClipboardSequenceNumber()==sequence;
        };
        if (!allowed()) return;
        const auto now=wangshu::WindowsPersonalStore::learningNow();
        const bool recorded=store->record([&]() -> std::optional<std::string> {
            if (!allowed()) return {};
            auto text=wangshu::readClipboardText(foreground,true);
            if (!text||!allowed()) return {};
            return text;
        },now);
        if (!current()) return;
        auto updated=store->read(now);
        if (!current()) return;
        const bool refresh=recorded||updated.revision!=panel->history.revision;
        if (refresh) {
            panel->history=std::move(updated);
            panel->page=0;
            publishClipboard(panel);
        }
    } catch (...) {
        if (clipboardListening&&popup) RemoveClipboardFormatListener(popup);
        clipboardListening=false;
    }
}

bool TextService::clipboardKey(WPARAM key) {
    if (ctrl()||shift()||(GetKeyState(VK_MENU)&0x8000)) return false;
    return (key>='1'&&key<='9')||key==VK_RETURN||key==VK_SPACE||key==VK_NEXT||
           key==VK_PRIOR||key==VK_ESCAPE||key==VK_BACK;
}

void TextService::clipboardKeyDown(const std::shared_ptr<ClipboardPanel>& panel,WPARAM key) {
    if (key==VK_ESCAPE||(key==VK_BACK&&!panel->splitEntry)) {
        clearClipboard();
        status.clear();
        update();
        return;
    }
    if (key==VK_BACK) {
        panel->splitEntry.reset();
        panel->split={};
        panel->page=0;
        publishClipboard(panel);
        return;
    }
    if (key==VK_NEXT||key==VK_PRIOR) {
        const auto count=panel->splitEntry?panel->split.tokens.size():panel->history.items.size();
        const auto step=panel->splitEntry?wangshu::clipboard_edit::SplitPage:wangshu::clipboard_edit::HistoryPage;
        if (key==VK_NEXT&&(panel->page+1)*step<count) ++panel->page;
        else if (key==VK_PRIOR&&panel->page) --panel->page;
        else return;
        publishClipboard(panel);
        return;
    }
    const auto index=key>='1'&&key<='9'?static_cast<size_t>(key-'1'):
        static_cast<size_t>(uiCandidates?uiCandidates->selection():0);
    selectClipboard(displayed,index);
}
