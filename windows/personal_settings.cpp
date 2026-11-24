// Native Win32 personal data manager. Clipboard reads are explicit foreground actions.
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define WIN32_LEAN_AND_MEAN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <commdlg.h>
#include <shellapi.h>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>
#include <cstdio>
#include <algorithm>
#include <iterator>
#include "../core/emoji_catalog.h"
#include "personal_store.h"
#include "clipboard_store.h"
#include "clipboard_source.h"

namespace {
enum Control {Tab=101,List,Key,Text,New,Save,Delete,Import,Export,Refresh,Recover,Status,KeyLabel,TextLabel,LearnEnabled,AssociationsEnabled,PredictionsEnabled,ClipboardEnabled,ClipboardRetention,ClipboardClearAll,CursorPredictions,SpeechFormat,PortableExport,PortableImport,FuzzySettings,NumericPredictions};
enum class FileKind { Personal, Clipboard, Settings };
constexpr unsigned RetentionDays[]={1,7,30};
std::wstring wide(const std::string& s){auto value=wangshu::utf16(s);return {value.begin(),value.end()};}
std::string narrow(const std::wstring& s){return wangshu::utf8(std::u16string(s.begin(),s.end()));}
std::wstring controlText(HWND control){int n=GetWindowTextLengthW(control);std::wstring value(static_cast<size_t>(n)+1,L'\0');GetWindowTextW(control,value.data(),n+1);value.resize(static_cast<size_t>(n));return value;}
std::wstring preview(const std::string& text){auto value=wide(text);for(auto& c:value)if(c==L'\n'||c==L'\r'||c==L'\t')c=L' ';if(value.size()>150){size_t end=150;if(value[end-1]>=0xd800&&value[end-1]<=0xdbff)--end;value.resize(end);value+=L"…";}return value;}
struct App {
    HWND window=nullptr;HFONT font=nullptr;int dpi=96,scrollY=0,virtualHeight=0;bool loading=false,readError=false;int selected=-1;
    std::unique_ptr<wangshu::WindowsPersonalStore> store;
    wangshu::PersonalSnapshot snapshot;
    wangshu::LearningSnapshot learningSnapshot;bool learningReadError=false;
    std::unique_ptr<wangshu::ClipboardStore> clipboard;
    wangshu::ClipboardHistory clipboardHistory;
    std::vector<HWND> children;
    int px(int n)const{return MulDiv(n,dpi,96);}
    HWND item(int id)const{return GetDlgItem(window,id);}
    bool ranking()const{return SendMessageW(item(Tab),CB_GETCURSEL,0,0)==2;}
    bool phrases()const{return SendMessageW(item(Tab),CB_GETCURSEL,0,0)==1;}
    bool clipboardMode()const{return SendMessageW(item(Tab),CB_GETCURSEL,0,0)==3;}
    bool favoritesMode()const{return SendMessageW(item(Tab),CB_GETCURSEL,0,0)==4;}
    HWND create(const wchar_t* type,const wchar_t* label,DWORD style,int id){HWND control=CreateWindowExW(type==std::wstring(L"EDIT")?WS_EX_CLIENTEDGE:0,type,label,WS_CHILD|WS_VISIBLE|(std::wstring(type)==L"STATIC"?0:WS_TABSTOP)|style,0,0,1,1,window,reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)),GetModuleHandleW(nullptr),nullptr);if(!control)throw std::runtime_error("Cannot create native management control");children.push_back(control);return control;}
    void error(const std::exception& e){auto text=wide(e.what());MessageBoxW(window,(text+L"\n\n请刷新核对实际结果；失败不会自动清空个人词库。").c_str(),L"操作未完成",MB_OK|MB_ICONWARNING);}
    void newEntry(){if(clipboardMode()){saveClipboard();return;}SendMessageW(item(Key),EM_SETREADONLY,FALSE,0);SendMessageW(item(Text),EM_SETREADONLY,FALSE,0);if(ranking())SetWindowTextW(item(Save),L"固定候选");selected=-1;SendMessageW(item(List),LB_SETCURSEL,static_cast<WPARAM>(-1),0);SetWindowTextW(item(Key),L"");SetWindowTextW(item(Text),L"");SetFocus(item(Key));}
    void configureMode(){
        for(int id:{ClipboardEnabled,ClipboardRetention,ClipboardClearAll})
            ShowWindow(item(id),clipboardMode()?SW_SHOW:SW_HIDE);
        for(int id:{LearnEnabled,AssociationsEnabled,PredictionsEnabled,CursorPredictions,NumericPredictions,SpeechFormat,PortableExport,PortableImport,FuzzySettings})
            ShowWindow(item(id),clipboardMode()||favoritesMode()?SW_HIDE:SW_SHOW);
        for(int id:{New,Delete})ShowWindow(item(id),favoritesMode()?SW_HIDE:SW_SHOW);
        ShowWindow(item(Import),ranking()?SW_HIDE:SW_SHOW);ShowWindow(item(Export),ranking()?SW_HIDE:SW_SHOW);
        SetWindowTextW(item(Import),clipboardMode()?L"预览恢复…":L"导入合并…");
        SetWindowTextW(item(Export),clipboardMode()?L"备份历史…":L"备份到新文件…");
        SetWindowTextW(item(New),ranking()?L"新增固定项":L"新增");SetWindowTextW(item(Save),ranking()?L"固定候选":L"保存");SetWindowTextW(item(Delete),ranking()?L"忘记个人权重":L"删除");SetWindowTextW(item(Recover),ranking()?L"恢复排序备份":L"恢复有效备份");
        SendMessageW(item(Key),EM_SETREADONLY,FALSE,0);SendMessageW(item(Text),EM_SETREADONLY,FALSE,0);
    }
    const wangshu::emoji_catalog::Entry* selectedFavorite()const{
        const auto& entries=wangshu::emoji_catalog::Entries;
        if(selected<0||static_cast<size_t>(selected)>=std::size(entries))return nullptr;
        return &entries[selected];
    }
    void reloadFavorites(){
        snapshot=store->read();
        readError=false;
        loading=true;
        selected=-1;
        SendMessageW(item(List),LB_RESETCONTENT,0,0);
        LRESULT index=0;
        for(const auto& entry:wangshu::emoji_catalog::Entries){
            std::wstring row=snapshot.data.isFavorite(entry.text)?L"★ ":L"";
            row+=wide(entry.text)+L" · "+wide(entry.description)+L" ("+wide(entry.pinyin)+L")";
            if(SendMessageW(item(List),LB_ADDSTRING,0,reinterpret_cast<LPARAM>(row.c_str()))!=index++)
                throw std::runtime_error("Favorite catalog list could not be loaded");
        }
        loading=false;
        EnableWindow(item(List),TRUE);
        EnableWindow(item(Save),FALSE);
        EnableWindow(item(Export),TRUE);
        EnableWindow(item(Recover),snapshot.fromBackup);
        SendMessageW(item(Key),EM_SETREADONLY,TRUE,0);
        SendMessageW(item(Text),EM_SETREADONLY,TRUE,0);
        SetWindowTextW(item(KeyLabel),L"目录名称");
        SetWindowTextW(item(TextLabel),L"表情或颜文字");
        SetWindowTextW(item(Key),L"");
        SetWindowTextW(item(Text),L"");
        SetWindowTextW(item(Save),L"收藏");
        auto status=L"已收藏 "+std::to_wstring(snapshot.data.favorites.size())+L" / 64 项。";
        status+=snapshot.fromBackup?L"请先确认恢复有效备份。":L"在键盘符号菜单的「收藏表情与颜文字」调用。";
        SetWindowTextW(item(Status),status.c_str());
    }
    void selectFavorite(){
        const auto* entry=selectedFavorite();
        if(!entry)return;
        SetWindowTextW(item(Key),wide(entry->description).c_str());
        SetWindowTextW(item(Text),wide(entry->text).c_str());
        SetWindowTextW(item(Save),snapshot.data.isFavorite(entry->text)?L"取消收藏":L"收藏");
        EnableWindow(item(Save),!snapshot.fromBackup);
    }
    void saveFavorite(){
        const auto* entry=selectedFavorite();
        if(!entry||snapshot.fromBackup)return;
        const bool pin=!snapshot.data.isFavorite(entry->text);
        snapshot=store->modify(snapshot.revision,[&](wangshu::PersonalData& data){data.setFavorite(entry->text,pin);});
        reload();
    }
    std::uint64_t now() const {return wangshu::WindowsPersonalStore::learningNow();}
    const wangshu::ClipboardItem* selectedClipboard() const {
        if(selected<0||static_cast<size_t>(selected)>=clipboardHistory.items.size())return nullptr;
        return &clipboardHistory.items[static_cast<size_t>(selected)];
    }
    void reloadClipboard(){
        SetWindowTextW(item(New),L"保存当前文本");
        SetWindowTextW(item(Save),L"置顶");
        SetWindowTextW(item(Delete),L"删除条目");
        SetWindowTextW(item(Recover),L"清空未置顶");
        SetWindowTextW(item(KeyLabel),L"条目状态");
        SetWindowTextW(item(TextLabel),L"剪贴板文本");
        SendMessageW(item(Key),EM_SETREADONLY,TRUE,0);
        SendMessageW(item(Text),EM_SETREADONLY,TRUE,0);
        selected=-1;
        loading=true;
        SendMessageW(item(List),LB_RESETCONTENT,0,0);
        SetWindowTextW(item(Key),L"");
        SetWindowTextW(item(Text),L"");
        for(int id:{New,Save,Delete,Export,Recover,ClipboardClearAll,ClipboardEnabled,ClipboardRetention})
            EnableWindow(item(id),FALSE);
        EnableWindow(item(Import),TRUE);
        EnableWindow(item(List),TRUE);
        try {
            if(!clipboard)clipboard=std::make_unique<wangshu::ClipboardStore>();
            clipboardHistory=clipboard->read(now());
            for(const auto& entry:clipboardHistory.items){
                const auto label=(entry.pinned?L"置顶 · ":L"")+preview(entry.text);
                const auto result=SendMessageW(item(List),LB_ADDSTRING,0,reinterpret_cast<LPARAM>(label.c_str()));
                if(result<0)throw std::runtime_error("Cannot display clipboard history");
            }
            SendMessageW(item(ClipboardEnabled),BM_SETCHECK,clipboardHistory.recording?BST_CHECKED:BST_UNCHECKED,0);
            const auto days=std::find(std::begin(RetentionDays),std::end(RetentionDays),clipboardHistory.retentionDays);
            SendMessageW(item(ClipboardRetention),CB_SETCURSEL,days-std::begin(RetentionDays),0);
            EnableWindow(item(ClipboardEnabled),TRUE);
            EnableWindow(item(ClipboardRetention),TRUE);
            EnableWindow(item(New),clipboardHistory.recording);
            for(int id:{Export,Recover,ClipboardClearAll})EnableWindow(item(id),!clipboardHistory.items.empty());
            SetWindowTextW(item(Status),clipboardHistory.recording?L"本地记录已开启。点击“保存当前文本”读取系统剪贴板。":L"本地记录已关闭。可查看和管理已有历史。");
        }catch(const std::exception&){
            clipboardHistory={};
            SendMessageW(item(List),LB_RESETCONTENT,0,0);
            SendMessageW(item(ClipboardEnabled),BM_SETCHECK,BST_UNCHECKED,0);
            SendMessageW(item(ClipboardRetention),CB_SETCURSEL,static_cast<WPARAM>(-1),0);
            SetWindowTextW(item(Status),L"剪贴板历史暂不可用。刷新核对，或预览有效备份。");
        }
        loading=false;
    }
    void selectClipboard(){
        const auto* entry=selectedClipboard();
        EnableWindow(item(Save),entry!=nullptr);
        EnableWindow(item(Delete),entry!=nullptr);
        if(!entry)return;
        SetWindowTextW(item(Key),entry->pinned?L"已置顶":L"未置顶");
        SetWindowTextW(item(Text),wide(entry->text).c_str());
        SetWindowTextW(item(Save),entry->pinned?L"取消置顶":L"置顶");
    }
    void configureClipboard(bool settingRecording){
        if(loading||!clipboard)return;
        const auto selectedDays=SendMessageW(item(ClipboardRetention),CB_GETCURSEL,0,0);
        if(selectedDays<0||static_cast<size_t>(selectedDays)>=std::size(RetentionDays))return;
        const auto days=RetentionDays[selectedDays];
        const bool enabled=SendMessageW(item(ClipboardEnabled),BM_GETCHECK,0,0)==BST_CHECKED;
        if((settingRecording&&enabled&&!clipboardHistory.recording&&
            MessageBoxW(window,L"启用后可主动保存当前文本，历史由 Windows 为本用户加密。带隐私标记的内容会跳过。请勿保存密码等敏感文本。是否启用？",L"启用剪贴板记录",MB_YESNO|MB_DEFBUTTON2|MB_ICONQUESTION)!=IDYES)||
           (!settingRecording&&days<clipboardHistory.retentionDays&&
            MessageBoxW(window,L"缩短期限会移除超期的未置顶历史。是否应用？",L"剪贴板保留期限",MB_YESNO|MB_DEFBUTTON2|MB_ICONQUESTION)!=IDYES)){
            reloadClipboard();
            return;
        }
        try{clipboard->configure(clipboardHistory.revision,enabled,days,now());}
        catch(...){reloadClipboard();throw;}
        reloadClipboard();
    }
    void saveClipboard(){
        if(!clipboard)return;
        const bool saved=clipboard->record([&]{return wangshu::readClipboardText(window);},now());
        reloadClipboard();
        SetWindowTextW(item(Status),saved?L"当前文本已保存。":L"未保存。请核对开关、文本格式和隐私标记，或稍后再试。");
    }
    void pinClipboard(){
        const auto* entry=selectedClipboard();
        if(!entry||!clipboard)return;
        clipboard->pin(clipboardHistory.revision,entry->id,!entry->pinned,now());
        reloadClipboard();
    }
    void removeClipboard(){
        const auto* entry=selectedClipboard();
        if(!entry||!clipboard)return;
        const auto id=entry->id,revision=clipboardHistory.revision;
        if(MessageBoxW(window,L"删除所选的望舒历史条目？",L"删除剪贴板条目",MB_YESNO|MB_DEFBUTTON2|MB_ICONQUESTION)!=IDYES)return;
        clipboard->remove(revision,id,now());
        reloadClipboard();
    }
    void clearClipboard(bool includePinned){
        if(!clipboard)return;
        const auto revision=clipboardHistory.revision;
        if(MessageBoxW(window,includePinned?L"清空望舒全部剪贴板历史，包含置顶条目？":L"清空望舒未置顶的剪贴板历史？",
                       L"清空剪贴板历史",MB_YESNO|MB_DEFBUTTON2|MB_ICONQUESTION)!=IDYES)return;
        clipboard->clear(revision,includePinned,now());
        reloadClipboard();
    }
    void importClipboard(){
        if(!clipboard)return;
        const auto source=choose(false,FileKind::Clipboard);
        if(source.empty())return;
        const auto plan=clipboard->previewBackup(source,now());
        auto message=L"新增 "+std::to_wstring(plan.added)+L" 条，跳过重复 "+std::to_wstring(plan.conflicts)+
                     L" 条、超期 "+std::to_wstring(plan.expired)+L" 条。";
        if(plan.repairing)message+=L"\n当前文件不可读，将原样另存。恢复后的记录开关为关闭。";
        if(MessageBoxW(window,message.c_str(),L"剪贴板备份预览 · 确认恢复？",
                       MB_YESNO|MB_DEFBUTTON2|MB_ICONQUESTION)!=IDYES)return;
        clipboard->restoreBackup(plan);
        reloadClipboard();
    }
    void exportClipboard(){
        if(!clipboard)return;
        const auto revision=clipboardHistory.revision;
        const auto destination=choose(true,FileKind::Clipboard);
        if(destination.empty())return;
        clipboard->exportBackup(destination,revision,now());
        SetWindowTextW(item(Status),L"已保存加密备份。恢复需使用原 Windows 用户凭据。");
    }
    void toggleAssociations(int id){
        auto control=item(id);
        bool wangshu::LearningData::* setting=&wangshu::LearningData::phraseAssociations;
        if(id==CursorPredictions)setting=&wangshu::LearningData::cursorPredictions;
        else if(id==PredictionsEnabled)setting=&wangshu::LearningData::builtinPredictions;
        else if(id==NumericPredictions)setting=&wangshu::LearningData::numericPredictions;
        bool wanted=SendMessageW(control,BM_GETCHECK,0,0)==BST_CHECKED;
        try{
            learningSnapshot=store->modifyLearning(learningSnapshot.revision,
                [&](wangshu::LearningData& data){data.*setting=wanted;});
            SetWindowTextW(item(Status),id==CursorPredictions
                ? (wanted?L"方向键移动后更新本地联想":L"已关闭方向键移动联想，正常输入继续更新")
                : id==PredictionsEnabled
                ? (wanted?L"本地补全已开启：停止按键后显示，点击追加，不记录上下文":L"本地补全已关闭")
                : id==NumericPredictions
                ? (wanted?L"数学模式的补全和数字续写已开启":L"数学模式联想已关闭，精确算术仍可用")
                : (wanted?L"本地常用语联想已开启：仅框末，点击候选插入，不记录上下文":L"本地常用语联想已关闭"));
        }catch(...){
            SendMessageW(control,BM_SETCHECK,learningSnapshot.data.*setting?BST_CHECKED:BST_UNCHECKED,0);
            throw;
        }
    }
    void loadLearningControl(){
        auto control=[&](int id,bool value,bool editable){
            SendMessageW(item(id),BM_SETCHECK,value?BST_CHECKED:BST_UNCHECKED,0);
            EnableWindow(item(id),editable);
        };
        try{
            learningSnapshot=store->readLearningFresh();learningReadError=false;
            bool editable=!learningSnapshot.fromBackup;
            control(LearnEnabled,learningSnapshot.data.enabled,editable);
            control(AssociationsEnabled,learningSnapshot.data.phraseAssociations,editable);
            control(PredictionsEnabled,learningSnapshot.data.builtinPredictions,editable);
            control(CursorPredictions,learningSnapshot.data.cursorPredictions,editable);
            control(NumericPredictions,learningSnapshot.data.numericPredictions,editable);
            SendMessageW(item(SpeechFormat),CB_SETCURSEL,static_cast<unsigned>(learningSnapshot.data.speechFormat),0);
            EnableWindow(item(SpeechFormat),editable);
            EnableWindow(item(PortableExport),editable);
            EnableWindow(item(PortableImport),editable);
            EnableWindow(item(FuzzySettings),editable);
            SetWindowTextW(item(FuzzySettings),learningSnapshot.data.fuzzyMask?L"模糊拼音 · 已开启…":L"模糊拼音 · 全部关闭…");
        }catch(...){
            learningReadError=true;
            for(int id:{LearnEnabled,AssociationsEnabled,PredictionsEnabled,CursorPredictions,NumericPredictions})control(id,false,false);
            EnableWindow(item(SpeechFormat),FALSE);
            EnableWindow(item(PortableExport),FALSE);
            EnableWindow(item(PortableImport),FALSE);
            EnableWindow(item(FuzzySettings),FALSE);
        }
    }
    void changeSpeechFormat(){
        const auto choice=SendMessageW(item(SpeechFormat),CB_GETCURSEL,0,0);
        if(choice<0||choice>2)return;
        try{
            learningSnapshot=store->modifyLearning(learningSnapshot.revision,[&](wangshu::LearningData& data){
                data.speechFormat=static_cast<wangshu::voice_format::Mode>(choice);
            });
            SetWindowTextW(item(Status),L"语音格式已保存，下次录音生效");
        }catch(...){
            SendMessageW(item(SpeechFormat),CB_SETCURSEL,static_cast<unsigned>(learningSnapshot.data.speechFormat),0);
            throw;
        }
    }
    void changeFuzzyRules(){
        const auto current=store->readLearningFresh();
        if(current.fromBackup)throw std::runtime_error("Restore the verified local backup before editing fuzzy settings");
        struct Menu {
            HMENU value=CreatePopupMenu();
            ~Menu(){if(value)DestroyMenu(value);}
        } menu;
        if(!menu.value)throw std::runtime_error("Cannot create fuzzy settings menu");
        constexpr UINT Reset=1+sizeof(wangshu::fuzzy_pinyin::Rules)/sizeof(wangshu::fuzzy_pinyin::Rules[0]);
        for(UINT i=0;i<Reset-1;++i){
            const UINT flags=MF_STRING|((current.data.fuzzyMask&(1u<<i))?MF_CHECKED:MF_UNCHECKED);
            if(!AppendMenuW(menu.value,flags,i+1,wangshu::fuzzy_pinyin::Rules[i].label))
                throw std::runtime_error("Cannot display fuzzy rule");
        }
        AppendMenuW(menu.value,MF_SEPARATOR,0,nullptr);
        AppendMenuW(menu.value,MF_STRING,Reset,L"全部关闭");
        RECT box{};
        if(!GetWindowRect(item(FuzzySettings),&box))return;
        const auto selected=TrackPopupMenu(menu.value,TPM_RETURNCMD|TPM_NONOTIFY,box.left,box.bottom,0,window,nullptr);
        if(!selected||selected>Reset)return;
        const auto next=selected==Reset?0u:current.data.fuzzyMask^(1u<<(selected-1));
        store->modifyLearning(current.revision,[&](wangshu::LearningData& data){data.fuzzyMask=next;});
        loadLearningControl();
        SetWindowTextW(item(Status),L"模糊拼音选项已保存。重新输入或翻页后可见。");
    }
    void reloadLearning(){
        if(learningReadError)throw std::runtime_error("Personal ranking is unreadable; no file reset");
        readError=false;loading=true;SendMessageW(item(List),LB_RESETCONTENT,0,0);
        for(const auto& value:learningSnapshot.data.entries){auto line=wide(value.key)+L" · "+preview(value.text)+(value.pinned?L"  [固定]":L"")+L"  [权重 "+std::to_wstring(value.count)+L"]";SendMessageW(item(List),LB_ADDSTRING,0,reinterpret_cast<LPARAM>(line.c_str()));}
        loading=false;selected=-1;SetWindowTextW(item(KeyLabel),L"完整拼音（新增固定项）");SetWindowTextW(item(TextLabel),L"候选短词（最多64字符，不含换行）");SetWindowTextW(item(Key),L"");SetWindowTextW(item(Text),L"");
        EnableWindow(item(List),TRUE);EnableWindow(item(Save),!learningSnapshot.fromBackup);EnableWindow(item(Delete),!learningSnapshot.fromBackup);EnableWindow(item(New),!learningSnapshot.fromBackup);EnableWindow(item(Recover),learningSnapshot.fromBackup);
        auto status=learningSnapshot.fromBackup?L"排序正在使用备份；先确认恢复，损坏原文件保留":L"只保留选中词与权重 · 临时不学习：在本IME候选窗右键菜单开启";SetWindowTextW(item(Status),status);resize();
    }
    void toggleLearning(){
        bool wanted=SendMessageW(item(LearnEnabled),BM_GETCHECK,0,0)==BST_CHECKED;
        try{learningSnapshot=store->modifyLearning(learningSnapshot.revision,[&](wangshu::LearningData& data){data.enabled=wanted;});SetWindowTextW(item(Status),wanted?L"自动学习已开启：仅确认成功的短词选择增加词频，不记录全文或录音":L"自动学习已关闭：已有固定项和个人排序仍保留");}
        catch(...){SendMessageW(item(LearnEnabled),BM_SETCHECK,learningSnapshot.data.enabled?BST_CHECKED:BST_UNCHECKED,0);throw;}
    }
    void saveLearning(){
        int index=selected;std::string key,text;bool fixed=true;
        if(index>=0){if(static_cast<size_t>(index)>=learningSnapshot.data.entries.size())throw std::runtime_error("Ranking entry changed; refresh");const auto& value=learningSnapshot.data.entries[index];key=value.key;text=value.text;fixed=!value.pinned;}
        else{key=narrow(controlText(item(Key)));text=narrow(controlText(item(Text)));}
        learningSnapshot=store->modifyLearning(learningSnapshot.revision,[&](wangshu::LearningData& data){data.pin(key,text,fixed,wangshu::WindowsPersonalStore::learningNow());});reload();
    }
    void removeLearning(){
        if(selected<0||static_cast<size_t>(selected)>=learningSnapshot.data.entries.size()){MessageBoxW(window,L"先选择要忘记的个人排序项。",L"选择条目",MB_OK);return;}
        if(MessageBoxW(window,L"只忘记该词的个人选择次数与固定状态；内置词库和手动词条不会删除。是否继续？",L"忘记个人权重",MB_YESNO|MB_ICONQUESTION)!=IDYES)return;
        auto value=learningSnapshot.data.entries[selected];learningSnapshot=store->modifyLearning(learningSnapshot.revision,[&](wangshu::LearningData& data){data.forget(value.key,value.text);});reload();
    }
    void recover(){
        if(clipboardMode()){clearClipboard(false);return;}
        if(ranking()){if(MessageBoxW(window,L"确认恢复有效个人排序备份？损坏原文件会另存保留，不改变手动词库。",L"恢复排序确认",MB_OKCANCEL|MB_ICONQUESTION)==IDOK){learningSnapshot=store->restoreLearningBackup(learningSnapshot.revision);reload();}}
        else if(MessageBoxW(window,L"将有效备份恢复为当前数据？损坏原文件将另行保留。",L"恢复确认",MB_OKCANCEL|MB_ICONQUESTION)==IDOK){snapshot=store->restoreBackup(snapshot.revision);reload();}
    }
    void select(){if(loading)return;LRESULT index=SendMessageW(item(List),LB_GETCURSEL,0,0);if(index==LB_ERR)return;selected=static_cast<int>(index);if(favoritesMode()){selectFavorite();return;}if(clipboardMode()){selectClipboard();return;}if(ranking()){if(static_cast<size_t>(selected)>=learningSnapshot.data.entries.size())return;const auto& value=learningSnapshot.data.entries[selected];SetWindowTextW(item(Key),wide(value.key).c_str());SetWindowTextW(item(Text),wide(value.text).c_str());SendMessageW(item(Key),EM_SETREADONLY,TRUE,0);SendMessageW(item(Text),EM_SETREADONLY,TRUE,0);SetWindowTextW(item(Save),value.pinned?L"取消固定":L"固定候选");return;}if(phrases()){if(static_cast<size_t>(selected)>=snapshot.data.phrases.size())return;const auto& p=snapshot.data.phrases[selected];SetWindowTextW(item(Key),wide(p.shortcut).c_str());SetWindowTextW(item(Text),wide(p.text).c_str());}else{if(static_cast<size_t>(selected)>=snapshot.data.words.size())return;const auto& w=snapshot.data.words[selected];SetWindowTextW(item(Key),wide(w.pinyin).c_str());SetWindowTextW(item(Text),wide(w.text).c_str());}}
    void reload(){
        configureMode();
        if(favoritesMode()){reloadFavorites();return;}
        if(clipboardMode()){reloadClipboard();return;}
        loadLearningControl();
        if(ranking()){reloadLearning();return;}
        snapshot=store->read();
        readError=false;
        const bool editable=!snapshot.fromBackup;
        EnableWindow(item(New),editable);
        EnableWindow(item(List),TRUE);
        EnableWindow(item(Save),editable);
        EnableWindow(item(Delete),editable);
        EnableWindow(item(Export),TRUE);
        loading=true;
        SendMessageW(item(List),LB_RESETCONTENT,0,0);
        if(phrases()){
            for(const auto& p:snapshot.data.phrases){
                auto line=(p.shortcut.empty()?L"":wide(p.shortcut)+L" · ")+preview(p.text);
                SendMessageW(item(List),LB_ADDSTRING,0,reinterpret_cast<LPARAM>(line.c_str()));
            }
        }else{
            for(const auto& w:snapshot.data.words){
                auto line=wide(w.pinyin)+L" · "+preview(w.text);
                SendMessageW(item(List),LB_ADDSTRING,0,reinterpret_cast<LPARAM>(line.c_str()));
            }
        }
        loading=false;
        selected=-1;
        SetWindowTextW(item(KeyLabel),phrases()?L"可选英文简称（1–32个字母）":L"完整拼音（ü 使用 v）");
        SetWindowTextW(item(TextLabel),phrases()?L"常用语内容（支持多行）":L"词条文字（仅词条使用 NFC 规范化）");
        SetWindowTextW(item(Key),L"");
        SetWindowTextW(item(Text),L"");
        EnableWindow(item(Recover),snapshot.fromBackup);
        auto status=L"本地 · "+std::to_wstring(snapshot.data.words.size())+L" 个词条 / "+
            std::to_wstring(snapshot.data.phrases.size())+L" 条常用语";
        status+=snapshot.fromBackup?L" · 正在使用有效备份，确认恢复后可编辑。":
            L" · 不记录按键、剪贴板或录音";
        SetWindowTextW(item(Status),status.c_str());
    }
    void loadSafely(){try{reload();}catch(const std::exception&){readError=true;loading=true;SendMessageW(item(List),LB_RESETCONTENT,0,0);SetWindowTextW(item(Key),L"");SetWindowTextW(item(Text),L"");loading=false;EnableWindow(item(List),FALSE);EnableWindow(item(Save),FALSE);EnableWindow(item(Delete),FALSE);EnableWindow(item(Export),FALSE);EnableWindow(item(Recover),FALSE);SetWindowTextW(item(Status),L"个人文件不可读，未重置。可刷新重试，或导入有效备份并确认修复；损坏原文件会另存保留。");}}
    void save(){if(favoritesMode()){saveFavorite();return;}if(clipboardMode()){pinClipboard();return;}if(ranking()){saveLearning();return;}std::string key=narrow(controlText(item(Key))),text=narrow(controlText(item(Text)));int index=selected;bool phrase=phrases();snapshot=store->modify(snapshot.revision,[&](wangshu::PersonalData& data){if(phrase){wangshu::PersonalPhrase p{key,text};if(index<0)data.phrases.push_back(std::move(p));else{if(static_cast<size_t>(index)>=data.phrases.size())throw std::runtime_error("Phrase changed; refresh first");data.phrases[index]=std::move(p);}}else{wangshu::PersonalWord w{wangshu::PersonalData::key(key),text};if(index<0)data.words.push_back(std::move(w));else{if(static_cast<size_t>(index)>=data.words.size())throw std::runtime_error("Word changed; refresh first");data.words[index]=std::move(w);}}});reload();}
    void remove(){if(clipboardMode()){removeClipboard();return;}if(ranking()){removeLearning();return;}if(selected<0){MessageBoxW(window,L"先选择要删除的个人条目。",L"选择条目",MB_OK);return;}if(MessageBoxW(window,L"只删除这条个人数据，不影响内置词库。是否继续？",L"删除条目",MB_YESNO|MB_ICONQUESTION)!=IDYES)return;int index=selected;bool phrase=phrases();snapshot=store->modify(snapshot.revision,[&](wangshu::PersonalData& data){if(phrase){if(static_cast<size_t>(index)>=data.phrases.size())throw std::runtime_error("Phrase changed");data.phrases.erase(data.phrases.begin()+index);}else{if(static_cast<size_t>(index)>=data.words.size())throw std::runtime_error("Word changed");data.words.erase(data.words.begin()+index);}});reload();}
    std::wstring choose(bool output,FileKind kind=FileKind::Personal){
        std::vector<wchar_t> path(32768);
        const bool clip=kind==FileKind::Clipboard,settings=kind==FileKind::Settings;
        if(output){
            SYSTEMTIME time{};
            GetLocalTime(&time);
            swprintf(path.data(),path.size(),settings?L"wangshu-portable-settings-%04d%02d%02d-%02d%02d%02d.tsv":
                     clip?L"wangshu-clipboard-%04d%02d%02d-%02d%02d%02d.wsc":
                     L"wangshu-personal-%04d%02d%02d-%02d%02d%02d.json",
                     time.wYear,time.wMonth,time.wDay,time.wHour,time.wMinute,time.wSecond);
        }
        OPENFILENAMEW dialog{};
        dialog.lStructSize=sizeof(dialog);
        dialog.hwndOwner=window;
        dialog.lpstrFile=path.data();
        dialog.nMaxFile=static_cast<DWORD>(path.size());
        dialog.lpstrFilter=settings?L"望舒共用设置 TSV\0*.tsv;*.txt\0\0":clip?L"望舒剪贴板备份\0*.wsc\0\0":
            output?L"个人词库 JSON\0*.json\0\0":L"个人词库 JSON / TSV\0*.json;*.tsv;*.txt\0所有文件\0*.*\0\0";
        dialog.lpstrDefExt=output?(settings?L"tsv":clip?L"wsc":L"json"):nullptr;
        dialog.Flags=OFN_EXPLORER|OFN_NOCHANGEDIR|OFN_PATHMUSTEXIST|(output?0:OFN_FILEMUSTEXIST);
        BOOL ok=output?GetSaveFileNameW(&dialog):GetOpenFileNameW(&dialog);
        if(!ok){
            if(CommDlgExtendedError())throw std::runtime_error("System file picker failed");
            return L"";
        }
        return path.data();
    }
    void exportPortableSettings(){
        auto path=choose(true,FileKind::Settings);
        if(path.empty())return;
        store->exportPortableSettingsNew(path);
        SetWindowTextW(item(Status),L"共用设置已导出。词库、记录和隐私开关分别保存在本机。");
    }
    void importPortableSettings(){
        auto path=choose(false,FileKind::Settings);
        if(path.empty())return;
        const auto incoming=wangshu::PortableSettings::parse(wangshu::WindowsPersonalStore::readExternal(path));
        auto current=store->readLearningFresh();
        if(current.fromBackup)throw std::runtime_error("Restore the verified local backup before importing settings");
        const auto before=wangshu::PortableSettings::from(current.data);
        const wchar_t* formats[]={L"原文",L"数学术语",L"数字与日期"};
        const auto toggle=[](bool value){return value?L"开":L"关";};
        std::wstring changes;
        auto diff=[&](const wchar_t* name,const wchar_t* from,const wchar_t* to){
            if(std::wstring_view(from)!=to)changes+=std::wstring(name)+L"  "+from+L" → "+to+L"\n";
        };
        diff(L"语音格式",formats[static_cast<unsigned>(before.speechFormat)],formats[static_cast<unsigned>(incoming.speechFormat)]);
        diff(L"补全与下一词",toggle(before.builtinPredictions),toggle(incoming.builtinPredictions));
        diff(L"光标联想",toggle(before.cursorPredictions),toggle(incoming.cursorPredictions));
        if(incoming.numericPredictions)
            diff(L"数字面板联想",toggle(current.data.numericPredictions),toggle(*incoming.numericPredictions));
        if(incoming.windowsPhraseAssociations)
            diff(L"Windows 常用语联想",toggle(current.data.phraseAssociations),toggle(*incoming.windowsPhraseAssociations));
        if(changes.empty()){
            SetWindowTextW(item(Status),L"文件中的设置与本机一致，未写入。");
            return;
        }
        changes+=L"\n确认合并这些设置？本机学习开关、词库和剪贴板保持原样。";
        if(MessageBoxW(window,changes.c_str(),L"预览共用设置",MB_OKCANCEL|MB_ICONQUESTION)!=IDOK)return;
        learningSnapshot=store->modifyLearning(current.revision,[&](wangshu::LearningData& data){incoming.apply(data);});
        loadLearningControl();
        SetWindowTextW(item(Status),L"共用设置已保存。语音格式从下次录音生效。");
    }
    void importFile(){
        if(clipboardMode()){importClipboard();return;}
        if(ranking())return;
        auto path=choose(false);
        if(path.empty())return;
        auto incoming=wangshu::WindowsPersonalStore::parseImport(wangshu::WindowsPersonalStore::readExternal(path));
        auto preview=store->previewImport(incoming);
        auto& merged=preview.data();
        const bool repair=preview.repair();
        auto message=L"完成后将有 "+std::to_wstring(merged.words.size())+L" 个词条、"+
            std::to_wstring(merged.phrases.size())+L" 条常用语、"+
            std::to_wstring(merged.favorites.size())+L" 项收藏。\n";
        if(repair)message+=L"将以所选备份修复，损坏文件另存保留。";
        else if(preview.usesLocalBackup())message+=L"将有效本机备份与所选文件合并，损坏主文件另存保留。";
        else message+=L"保留已有内容，重复条目去重。";
        message+=L"\n本机文件变化后，请重新预览。";
        if(MessageBoxW(window,message.c_str(),repair?L"备份修复预览":L"导入预览",MB_OKCANCEL|MB_ICONQUESTION)!=IDOK)return;
        snapshot=store->confirmImport(preview);
        reload();
    }
    void exportFile(){
        if(clipboardMode()){exportClipboard();return;}
        if(ranking())return;
        auto current=store->read();
        if(!current.data.favorites.empty()){
            int mode=MessageBoxW(window,L"备份是否包含收藏？\n是：完整备份。\n否：导出旧版兼容词库和常用语。",
                                 L"个人数据备份",MB_YESNOCANCEL|MB_ICONQUESTION);
            if(mode==IDNO)current.data.favorites.clear();
            else if(mode!=IDYES)return;
        }
        auto path=choose(true);
        if(path.empty())return;
        wangshu::WindowsPersonalStore::exportNew(path,current.data);
        MessageBoxW(window,L"备份已写入所选文件。",L"备份完成",MB_OK|MB_ICONINFORMATION);
    }
    void resize(){
        RECT area{};GetClientRect(window,&area);
        int width=area.right,height=area.bottom,pad=px(20),gap=px(12),top=px(18);
        int available=std::max(px(180),width-2*pad-gap);
        int leftWidth=pad+available*43/100,right=leftWidth+gap,fieldWidth=std::max(1,width-right-pad);
        bool narrow=width<px(620);int mainRows=fieldWidth<px(280)?3:1,footerRows=ranking()?1:narrow?2:1;
        const int listTop=favoritesMode()?72:clipboardMode()?216:492;
        int minimum=px(listTop+28+120+16+mainRows*48+12+footerRows*44+40);
        virtualHeight=std::max(height,minimum);scrollY=std::max(0,std::min(scrollY,virtualHeight-height));
        SCROLLINFO info{};info.cbSize=sizeof(info);info.fMask=SIF_RANGE|SIF_PAGE|SIF_POS;info.nMin=0;info.nMax=virtualHeight-1;info.nPage=height;info.nPos=scrollY;SetScrollInfo(window,SB_VERT,&info,TRUE);
        auto move=[&](int id,int x,int y,int w,int h){MoveWindow(item(id),x,y-scrollY,std::max(1,w),std::max(1,h),TRUE);};
        int footerTop=virtualHeight-px(40+footerRows*44),mainTop=footerTop-px(12+mainRows*48);
        move(Tab,pad,top,leftWidth-pad,px(300));move(LearnEnabled,pad,top+px(44),leftWidth-pad,px(48));move(AssociationsEnabled,pad,top+px(96),leftWidth-pad,px(52));move(PredictionsEnabled,pad,top+px(152),leftWidth-pad,px(52));
        move(CursorPredictions,pad,top+px(208),leftWidth-pad,px(48));
        move(NumericPredictions,pad,top+px(258),leftWidth-pad,px(50));
        move(SpeechFormat,pad,top+px(312),leftWidth-pad,px(175));
        move(PortableExport,pad,top+px(352),leftWidth-pad,px(40));
        move(PortableImport,pad,top+px(398),leftWidth-pad,px(40));
        move(FuzzySettings,pad,top+px(444),leftWidth-pad,px(40));
        move(ClipboardEnabled,pad,top+px(44),leftWidth-pad,px(48));
        move(ClipboardRetention,pad,top+px(98),leftWidth-pad,px(175));
        move(ClipboardClearAll,pad,top+px(152),leftWidth-pad,px(46));
        move(List,pad,top+px(listTop),leftWidth-pad,std::max(px(60),footerTop-px(12)-top-px(listTop)));
        move(KeyLabel,right,top,fieldWidth,px(24));move(Key,right,top+px(30),fieldWidth,px(34));move(TextLabel,right,top+px(80),fieldWidth,px(24));move(Text,right,top+px(110),fieldWidth,mainTop-px(16)-top-px(110));
        for(int i=0;i<3;i++){int unit=mainRows==1?(fieldWidth-2*gap)/3:fieldWidth;move(New+i,right+(mainRows==1?i*(unit+gap):0),mainTop+(mainRows==1?0:i*px(48)),unit,px(42));}
        int columns=ranking()?2:footerRows==1?4:2,unit=(width-2*pad-(columns-1)*gap)/columns;
        if(ranking()){move(Refresh,pad,footerTop,unit,px(38));move(Recover,pad+unit+gap,footerTop,unit,px(38));}
        else for(int i=0;i<4;i++)move(Import+i,pad+(i%columns)*(unit+gap),footerTop+(i/columns)*px(44),unit,px(38));
        move(Status,pad,virtualHeight-px(34),width-2*pad,px(28));
    }
    void scroll(int position){RECT client{};GetClientRect(window,&client);scrollY=std::max(0,std::min(position,virtualHeight-static_cast<int>(client.bottom)));resize();}
    void showFocused(HWND control){if(!control)return;RECT value{},client{};GetWindowRect(control,&value);MapWindowPoints(nullptr,window,reinterpret_cast<POINT*>(&value),2);GetClientRect(window,&client);if(value.top<0)scroll(scrollY+value.top-px(8));else if(value.bottom>client.bottom)scroll(scrollY+value.bottom-client.bottom+px(8));}

    void applyFont(){HFONT previous=font;dpi=GetDpiForWindow(window);if(!dpi)dpi=96;font=CreateFontW(-px(16),0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Microsoft YaHei UI");for(HWND child:children)SendMessageW(child,WM_SETFONT,reinterpret_cast<WPARAM>(font),TRUE);if(previous)DeleteObject(previous);}
    void initialize(){store=std::make_unique<wangshu::WindowsPersonalStore>();create(L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_VSCROLL,Tab);SendMessageW(item(Tab),CB_ADDSTRING,0,reinterpret_cast<LPARAM>(L"个人词库"));SendMessageW(item(Tab),CB_ADDSTRING,0,reinterpret_cast<LPARAM>(L"常用语"));SendMessageW(item(Tab),CB_ADDSTRING,0,reinterpret_cast<LPARAM>(L"个人排序"));SendMessageW(item(Tab),CB_ADDSTRING,0,reinterpret_cast<LPARAM>(L"剪贴板"));SendMessageW(item(Tab),CB_SETCURSEL,0,0);create(L"LISTBOX",L"",LBS_NOTIFY|LBS_NOINTEGRALHEIGHT|WS_VSCROLL|WS_BORDER,List);create(L"STATIC",L"",0,KeyLabel);create(L"EDIT",L"",ES_AUTOHSCROLL,Key);create(L"STATIC",L"",0,TextLabel);create(L"EDIT",L"",ES_MULTILINE|ES_AUTOVSCROLL|ES_WANTRETURN|WS_VSCROLL,Text);for(const auto& entry:std::vector<std::pair<int,const wchar_t*>>{{New,L"新增"},{Save,L"保存"},{Delete,L"删除"},{Import,L"导入合并…"},{Export,L"备份到新文件…"},{Refresh,L"刷新"},{Recover,L"恢复有效备份"}})create(L"BUTTON",entry.second,BS_PUSHBUTTON|BS_NOTIFY,entry.first);create(L"BUTTON",L"自动学习已确认选词（默认关闭）",BS_AUTOCHECKBOX|BS_MULTILINE|BS_NOTIFY,LearnEnabled);create(L"BUTTON",L"本地常用语中英联想（框末，默认关闭）",BS_AUTOCHECKBOX|BS_MULTILINE|BS_NOTIFY,AssociationsEnabled);create(L"BUTTON",L"本地补全与下一词（默认关闭）",BS_AUTOCHECKBOX|BS_MULTILINE|BS_NOTIFY,PredictionsEnabled);create(L"BUTTON",L"允许保存剪贴板文本",BS_AUTOCHECKBOX|BS_MULTILINE|BS_NOTIFY,ClipboardEnabled);
        SendMessageW(item(Tab),CB_ADDSTRING,0,reinterpret_cast<LPARAM>(L"收藏表情"));
        create(L"BUTTON",L"方向键移动后更新本地联想",BS_AUTOCHECKBOX|BS_MULTILINE|BS_NOTIFY,CursorPredictions);
        create(L"BUTTON",L"数学模式的补全和数字续写",BS_AUTOCHECKBOX|BS_MULTILINE|BS_NOTIFY,NumericPredictions);
        create(L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_VSCROLL,SpeechFormat);
        for(const auto* label:{L"语音格式 · 原文",L"语音格式 · 数学术语",L"语音格式 · 数字与日期"})
            SendMessageW(item(SpeechFormat),CB_ADDSTRING,0,reinterpret_cast<LPARAM>(label));
        create(L"BUTTON",L"导出 Android / Windows 共用设置…",BS_PUSHBUTTON|BS_NOTIFY,PortableExport);
        create(L"BUTTON",L"预览并合并共用设置…",BS_PUSHBUTTON|BS_NOTIFY,PortableImport);
        create(L"BUTTON",L"模糊拼音…",BS_PUSHBUTTON|BS_NOTIFY,FuzzySettings);
        create(L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_VSCROLL,ClipboardRetention);
        for(const auto* label:{L"保留 24 小时",L"保留 7 天",L"保留 30 天"})
            SendMessageW(item(ClipboardRetention),CB_ADDSTRING,0,reinterpret_cast<LPARAM>(label));
        create(L"BUTTON",L"清空全部（含置顶）",BS_PUSHBUTTON|BS_NOTIFY,ClipboardClearAll);
        create(L"STATIC",L"",SS_LEFT|SS_ENDELLIPSIS,Status);applyFont();loadSafely();resize();}
    ~App(){if(font)DeleteObject(font);}
};
LRESULT CALLBACK windowProc(HWND window,UINT message,WPARAM wp,LPARAM lp){auto app=reinterpret_cast<App*>(GetWindowLongPtrW(window,GWLP_USERDATA));if(message==WM_NCCREATE){app=static_cast<App*>(reinterpret_cast<CREATESTRUCTW*>(lp)->lpCreateParams);app->window=window;SetWindowLongPtrW(window,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(app));}if(!app)return DefWindowProcW(window,message,wp,lp);try{switch(message){case WM_CREATE:app->initialize();return 0;case WM_SIZE:app->resize();return 0;case WM_DPICHANGED:{auto rect=reinterpret_cast<RECT*>(lp);SetWindowPos(window,nullptr,rect->left,rect->top,rect->right-rect->left,rect->bottom-rect->top,SWP_NOZORDER|SWP_NOACTIVATE);app->applyFont();app->resize();return 0;}case WM_GETMINMAXINFO:{auto info=reinterpret_cast<MINMAXINFO*>(lp);MONITORINFO monitor{};monitor.cbSize=sizeof(monitor);GetMonitorInfoW(MonitorFromWindow(window,MONITOR_DEFAULTTONEAREST),&monitor);int width=monitor.rcWork.right-monitor.rcWork.left,height=monitor.rcWork.bottom-monitor.rcWork.top;info->ptMinTrackSize={std::min(app->px(640),width),std::min(app->px(300),height)};return 0;}case WM_VSCROLL:{SCROLLINFO info{};info.cbSize=sizeof(info);info.fMask=SIF_ALL;GetScrollInfo(window,SB_VERT,&info);int position=app->scrollY;switch(LOWORD(wp)){case SB_LINEUP:position-=app->px(32);break;case SB_LINEDOWN:position+=app->px(32);break;case SB_PAGEUP:position-=static_cast<int>(info.nPage);break;case SB_PAGEDOWN:position+=static_cast<int>(info.nPage);break;case SB_THUMBTRACK:case SB_THUMBPOSITION:position=info.nTrackPos;break;case SB_TOP:position=0;break;case SB_BOTTOM:position=info.nMax;break;}app->scroll(position);return 0;}case WM_MOUSEWHEEL:app->scroll(app->scrollY-static_cast<short>(HIWORD(wp))*app->px(60)/WHEEL_DELTA);return 0;case WM_COMMAND:{int id=LOWORD(wp),notification=HIWORD(wp);if(((id>=New&&id<=Recover)||id==LearnEnabled||id==AssociationsEnabled||id==PredictionsEnabled||id==CursorPredictions||id==NumericPredictions||id==ClipboardEnabled||id==ClipboardClearAll||id==PortableExport||id==PortableImport||id==FuzzySettings)&&notification==BN_SETFOCUS)app->showFocused(reinterpret_cast<HWND>(lp));if((id==Key||id==Text)&&notification==EN_SETFOCUS)app->showFocused(reinterpret_cast<HWND>(lp));if(id==List&&notification==LBN_SETFOCUS)app->showFocused(reinterpret_cast<HWND>(lp));if((id==Tab||id==ClipboardRetention||id==SpeechFormat)&&notification==CBN_SETFOCUS)app->showFocused(reinterpret_cast<HWND>(lp));if(id==Tab&&notification==CBN_SELCHANGE){app->loadSafely();app->resize();}else if(id==SpeechFormat&&notification==CBN_SELCHANGE)app->changeSpeechFormat();else if(id==ClipboardRetention&&notification==CBN_SELCHANGE)app->configureClipboard(false);else if(id==List&&notification==LBN_SELCHANGE)app->select();else if(notification==BN_CLICKED){switch(id){case New:app->newEntry();break;case Save:app->save();break;case Delete:app->remove();break;case Import:app->importFile();break;case Export:app->exportFile();break;case Refresh:app->loadSafely();break;case Recover:app->recover();break;case LearnEnabled:app->toggleLearning();break;case AssociationsEnabled:case PredictionsEnabled:case CursorPredictions:case NumericPredictions:app->toggleAssociations(id);break;case ClipboardEnabled:app->configureClipboard(true);break;case ClipboardClearAll:app->clearClipboard(true);break;case PortableExport:app->exportPortableSettings();break;case PortableImport:app->importPortableSettings();break;case FuzzySettings:app->changeFuzzyRules();break;}}return 0;}case WM_CLOSE:DestroyWindow(window);return 0;case WM_DESTROY:PostQuitMessage(0);return 0;}}catch(const std::exception& e){app->error(e);if(message==WM_CREATE)return -1;return 0;}return DefWindowProcW(window,message,wp,lp);}
}
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE,LPWSTR,int show){SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);WNDCLASSEXW type{};type.cbSize=sizeof(type);type.hInstance=instance;type.lpfnWndProc=windowProc;type.lpszClassName=L"WangshuPersonalManager";type.hCursor=LoadCursorW(nullptr,IDC_ARROW);type.hbrBackground=reinterpret_cast<HBRUSH>(COLOR_WINDOW+1);if(!RegisterClassExW(&type))return 1;App app;RECT work{0,0,GetSystemMetrics(SM_CXSCREEN),GetSystemMetrics(SM_CYSCREEN)};SystemParametersInfoW(SPI_GETWORKAREA,0,&work,0);UINT systemDpi=GetDpiForSystem();if(!systemDpi)systemDpi=96;int initialWidth=std::max(1,std::min(MulDiv(960,systemDpi,96),static_cast<int>(work.right-work.left)-32)),initialHeight=std::max(1,std::min(MulDiv(720,systemDpi,96),static_cast<int>(work.bottom-work.top)-32));HWND window=CreateWindowExW(0,type.lpszClassName,L"望舒 · 本地个人数据",WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN|WS_VSCROLL,CW_USEDEFAULT,CW_USEDEFAULT,initialWidth,initialHeight,nullptr,nullptr,instance,&app);if(!window){std::fprintf(stderr,"Native management window creation failed: %lu; no configuration action dispatched.\n",GetLastError());return 2;}ShowWindow(window,show);MSG message;while(GetMessageW(&message,nullptr,0,0)>0){if(!IsDialogMessageW(window,&message)){TranslateMessage(&message);DispatchMessageW(&message);}}return static_cast<int>(message.wParam);}
