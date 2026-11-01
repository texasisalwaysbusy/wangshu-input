// Native Win32 personal dictionary / phrase manager. No registration, network or monitoring.
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
#include "personal_store.h"

namespace {
enum Control {Tab=101,List,Key,Text,New,Save,Delete,Import,Export,Refresh,Recover,Status,KeyLabel,TextLabel,LearnEnabled};
std::wstring wide(const std::string& s){auto value=wangshu::utf16(s);return {value.begin(),value.end()};}
std::string narrow(const std::wstring& s){return wangshu::utf8(std::u16string(s.begin(),s.end()));}
std::wstring controlText(HWND control){int n=GetWindowTextLengthW(control);std::wstring value(static_cast<size_t>(n)+1,L'\0');GetWindowTextW(control,value.data(),n+1);value.resize(static_cast<size_t>(n));return value;}
std::wstring preview(const std::string& text){auto value=wide(text);for(auto& c:value)if(c==L'\n'||c==L'\r'||c==L'\t')c=L' ';if(value.size()>150){size_t end=150;if(value[end-1]>=0xd800&&value[end-1]<=0xdbff)--end;value.resize(end);value+=L"…";}return value;}
struct App {
    HWND window=nullptr;HFONT font=nullptr;int dpi=96,scrollY=0,virtualHeight=0;bool loading=false,readError=false;int selected=-1;
    std::unique_ptr<wangshu::WindowsPersonalStore> store;
    wangshu::PersonalSnapshot snapshot;
    wangshu::LearningSnapshot learningSnapshot;bool learningReadError=false;
    std::vector<HWND> children;
    int px(int n)const{return MulDiv(n,dpi,96);}
    HWND item(int id)const{return GetDlgItem(window,id);}
    bool ranking()const{return SendMessageW(item(Tab),CB_GETCURSEL,0,0)==2;}
    bool phrases()const{return SendMessageW(item(Tab),CB_GETCURSEL,0,0)==1;}
    HWND create(const wchar_t* type,const wchar_t* label,DWORD style,int id){HWND control=CreateWindowExW(type==std::wstring(L"EDIT")?WS_EX_CLIENTEDGE:0,type,label,WS_CHILD|WS_VISIBLE|(std::wstring(type)==L"STATIC"?0:WS_TABSTOP)|style,0,0,1,1,window,reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)),GetModuleHandleW(nullptr),nullptr);if(!control)throw std::runtime_error("Cannot create native management control");children.push_back(control);return control;}
    void error(const std::exception& e){auto text=wide(e.what());MessageBoxW(window,(text+L"\n\n请刷新核对实际结果；失败不会自动清空个人词库。").c_str(),L"操作未完成",MB_OK|MB_ICONWARNING);}
    void newEntry(){SendMessageW(item(Key),EM_SETREADONLY,FALSE,0);SendMessageW(item(Text),EM_SETREADONLY,FALSE,0);if(ranking())SetWindowTextW(item(Save),L"固定候选");selected=-1;SendMessageW(item(List),LB_SETCURSEL,static_cast<WPARAM>(-1),0);SetWindowTextW(item(Key),L"");SetWindowTextW(item(Text),L"");SetFocus(item(Key));}
    void configureMode(){
        ShowWindow(item(Import),ranking()?SW_HIDE:SW_SHOW);ShowWindow(item(Export),ranking()?SW_HIDE:SW_SHOW);
        SetWindowTextW(item(New),ranking()?L"新增固定项":L"新增");SetWindowTextW(item(Save),ranking()?L"固定候选":L"保存");SetWindowTextW(item(Delete),ranking()?L"忘记个人权重":L"删除");SetWindowTextW(item(Recover),ranking()?L"恢复排序备份":L"恢复有效备份");
        SendMessageW(item(Key),EM_SETREADONLY,FALSE,0);SendMessageW(item(Text),EM_SETREADONLY,FALSE,0);
    }
    void loadLearningControl(){
        try{learningSnapshot=store->readLearningFresh();learningReadError=false;SendMessageW(item(LearnEnabled),BM_SETCHECK,learningSnapshot.data.enabled?BST_CHECKED:BST_UNCHECKED,0);EnableWindow(item(LearnEnabled),!learningSnapshot.fromBackup);}
        catch(...){learningReadError=true;SendMessageW(item(LearnEnabled),BM_SETCHECK,BST_UNCHECKED,0);EnableWindow(item(LearnEnabled),FALSE);}
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
        if(ranking()){if(MessageBoxW(window,L"确认恢复有效个人排序备份？损坏原文件会另存保留，不改变手动词库。",L"恢复排序确认",MB_OKCANCEL|MB_ICONQUESTION)==IDOK){learningSnapshot=store->restoreLearningBackup(learningSnapshot.revision);reload();}}
        else if(MessageBoxW(window,L"将有效备份恢复为当前数据？损坏原文件将另行保留。",L"恢复确认",MB_OKCANCEL|MB_ICONQUESTION)==IDOK){snapshot=store->restoreBackup(snapshot.revision);reload();}
    }
    void select(){if(loading)return;LRESULT index=SendMessageW(item(List),LB_GETCURSEL,0,0);if(index==LB_ERR)return;selected=static_cast<int>(index);if(ranking()){if(static_cast<size_t>(selected)>=learningSnapshot.data.entries.size())return;const auto& value=learningSnapshot.data.entries[selected];SetWindowTextW(item(Key),wide(value.key).c_str());SetWindowTextW(item(Text),wide(value.text).c_str());SendMessageW(item(Key),EM_SETREADONLY,TRUE,0);SendMessageW(item(Text),EM_SETREADONLY,TRUE,0);SetWindowTextW(item(Save),value.pinned?L"取消固定":L"固定候选");return;}if(phrases()){if(static_cast<size_t>(selected)>=snapshot.data.phrases.size())return;const auto& p=snapshot.data.phrases[selected];SetWindowTextW(item(Key),wide(p.shortcut).c_str());SetWindowTextW(item(Text),wide(p.text).c_str());}else{if(static_cast<size_t>(selected)>=snapshot.data.words.size())return;const auto& w=snapshot.data.words[selected];SetWindowTextW(item(Key),wide(w.pinyin).c_str());SetWindowTextW(item(Text),wide(w.text).c_str());}}
    void reload(){configureMode();loadLearningControl();if(ranking()){reloadLearning();return;}EnableWindow(item(New),TRUE);auto fresh=store->read();snapshot=std::move(fresh);readError=false;EnableWindow(item(List),TRUE);EnableWindow(item(Save),TRUE);EnableWindow(item(Delete),TRUE);EnableWindow(item(Export),TRUE);loading=true;SendMessageW(item(List),LB_RESETCONTENT,0,0);if(phrases()){for(const auto& p:snapshot.data.phrases){auto line=(p.shortcut.empty()?L"":wide(p.shortcut)+L" · ")+preview(p.text);SendMessageW(item(List),LB_ADDSTRING,0,reinterpret_cast<LPARAM>(line.c_str()));}}else for(const auto& w:snapshot.data.words){auto line=wide(w.pinyin)+L" · "+preview(w.text);SendMessageW(item(List),LB_ADDSTRING,0,reinterpret_cast<LPARAM>(line.c_str()));}loading=false;selected=-1;SetWindowTextW(item(KeyLabel),phrases()?L"可选英文简称（1–32个字母）":L"完整拼音（ü 使用 v）");SetWindowTextW(item(TextLabel),phrases()?L"常用语内容（支持多行）":L"词条文字（仅词条使用 NFC 规范化）");SetWindowTextW(item(Key),L"");SetWindowTextW(item(Text),L"");EnableWindow(item(Recover),snapshot.fromBackup);auto status=L"本地 · "+std::to_wstring(snapshot.data.words.size())+L" 个词条 / "+std::to_wstring(snapshot.data.phrases.size())+L" 条常用语";if(snapshot.fromBackup)status+=L" · 正在使用有效备份，原文件保留；可点“恢复有效备份”";else status+=L" · 不记录按键、剪贴板或录音";SetWindowTextW(item(Status),status.c_str());}
    void loadSafely(){try{reload();}catch(const std::exception&){readError=true;loading=true;SendMessageW(item(List),LB_RESETCONTENT,0,0);SetWindowTextW(item(Key),L"");SetWindowTextW(item(Text),L"");loading=false;EnableWindow(item(List),FALSE);EnableWindow(item(Save),FALSE);EnableWindow(item(Delete),FALSE);EnableWindow(item(Export),FALSE);EnableWindow(item(Recover),FALSE);SetWindowTextW(item(Status),L"个人文件不可读，未重置。可刷新重试，或导入有效备份并确认修复；损坏原文件会另存保留。");}}
    void save(){if(ranking()){saveLearning();return;}std::string key=narrow(controlText(item(Key))),text=narrow(controlText(item(Text)));int index=selected;bool phrase=phrases();snapshot=store->modify(snapshot.revision,[&](wangshu::PersonalData& data){if(phrase){wangshu::PersonalPhrase p{key,text};if(index<0)data.phrases.push_back(std::move(p));else{if(static_cast<size_t>(index)>=data.phrases.size())throw std::runtime_error("Phrase changed; refresh first");data.phrases[index]=std::move(p);}}else{wangshu::PersonalWord w{wangshu::PersonalData::key(key),text};if(index<0)data.words.push_back(std::move(w));else{if(static_cast<size_t>(index)>=data.words.size())throw std::runtime_error("Word changed; refresh first");data.words[index]=std::move(w);}}});reload();}
    void remove(){if(ranking()){removeLearning();return;}if(selected<0){MessageBoxW(window,L"先选择要删除的个人条目。",L"选择条目",MB_OK);return;}if(MessageBoxW(window,L"只删除这条个人数据，不影响内置词库。是否继续？",L"删除条目",MB_YESNO|MB_ICONQUESTION)!=IDYES)return;int index=selected;bool phrase=phrases();snapshot=store->modify(snapshot.revision,[&](wangshu::PersonalData& data){if(phrase){if(static_cast<size_t>(index)>=data.phrases.size())throw std::runtime_error("Phrase changed");data.phrases.erase(data.phrases.begin()+index);}else{if(static_cast<size_t>(index)>=data.words.size())throw std::runtime_error("Word changed");data.words.erase(data.words.begin()+index);}});reload();}
    std::wstring choose(bool output){std::vector<wchar_t> path(32768);if(output){SYSTEMTIME time;GetLocalTime(&time);swprintf(path.data(),path.size(),L"wangshu-personal-%04d%02d%02d-%02d%02d%02d.json",time.wYear,time.wMonth,time.wDay,time.wHour,time.wMinute,time.wSecond);}OPENFILENAMEW dialog{};dialog.lStructSize=sizeof(dialog);dialog.hwndOwner=window;dialog.lpstrFile=path.data();dialog.nMaxFile=static_cast<DWORD>(path.size());dialog.lpstrFilter=output?L"个人词库 JSON\0*.json\0\0":L"个人词库 JSON / TSV\0*.json;*.tsv;*.txt\0所有文件\0*.*\0\0";dialog.lpstrDefExt=output?L"json":nullptr;dialog.Flags=OFN_EXPLORER|OFN_NOCHANGEDIR|OFN_PATHMUSTEXIST|(output?0:OFN_FILEMUSTEXIST);BOOL ok=output?GetSaveFileNameW(&dialog):GetOpenFileNameW(&dialog);if(!ok){if(CommDlgExtendedError())throw std::runtime_error("System file picker failed");return L"";}return path.data();}
    void importFile(){if(ranking())return;auto path=choose(false);if(path.empty())return;auto incoming=wangshu::WindowsPersonalStore::parseImport(wangshu::WindowsPersonalStore::readExternal(path));auto preview=store->previewImport(incoming);auto& merged=preview.data;bool repair=preview.repair;auto message=L"完成后将有 "+std::to_wstring(merged.words.size())+L" 个词条、"+std::to_wstring(merged.phrases.size())+L" 条常用语。\n";message+=repair?L"当前文件无法读取，不能合并。将以所选备份修复，损坏原文件另存保留。\n\n确认修复？":L"保留已有内容，重复条目去重。尚未写入。\n\n确认合并？";if(MessageBoxW(window,message.c_str(),repair?L"备份修复预览":L"导入预览",MB_OKCANCEL|MB_ICONQUESTION)!=IDOK)return;snapshot=repair?store->repairFromImport(incoming):store->merge(incoming);reload();}
    void exportFile(){if(ranking())return;auto path=choose(true);if(path.empty())return;auto current=store->read();wangshu::WindowsPersonalStore::exportNew(path,current.data);MessageBoxW(window,L"个人词库与常用语已保存。备份不包含剪贴板、录音或设置。",L"备份完成",MB_OK|MB_ICONINFORMATION);}
    void resize(){
        RECT area{};GetClientRect(window,&area);
        int width=area.right,height=area.bottom,pad=px(20),gap=px(12),top=px(18);
        int available=std::max(px(180),width-2*pad-gap);
        int leftWidth=pad+available*43/100,right=leftWidth+gap,fieldWidth=std::max(1,width-right-pad);
        bool narrow=width<px(620);int mainRows=fieldWidth<px(280)?3:1,footerRows=ranking()?1:narrow?2:1;
        int minimum=px(128+120+16+mainRows*48+12+footerRows*44+40);
        virtualHeight=std::max(height,minimum);scrollY=std::max(0,std::min(scrollY,virtualHeight-height));
        SCROLLINFO info{};info.cbSize=sizeof(info);info.fMask=SIF_RANGE|SIF_PAGE|SIF_POS;info.nMin=0;info.nMax=virtualHeight-1;info.nPage=height;info.nPos=scrollY;SetScrollInfo(window,SB_VERT,&info,TRUE);
        auto move=[&](int id,int x,int y,int w,int h){MoveWindow(item(id),x,y-scrollY,std::max(1,w),std::max(1,h),TRUE);};
        int footerTop=virtualHeight-px(40+footerRows*44),mainTop=footerTop-px(12+mainRows*48);
        move(Tab,pad,top,leftWidth-pad,px(300));move(LearnEnabled,pad,top+px(44),leftWidth-pad,px(48));move(List,pad,top+px(104),leftWidth-pad,std::max(px(60),footerTop-px(12)-top-px(104)));
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
    void initialize(){store=std::make_unique<wangshu::WindowsPersonalStore>();create(L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_VSCROLL,Tab);SendMessageW(item(Tab),CB_ADDSTRING,0,reinterpret_cast<LPARAM>(L"个人词库"));SendMessageW(item(Tab),CB_ADDSTRING,0,reinterpret_cast<LPARAM>(L"常用语"));SendMessageW(item(Tab),CB_ADDSTRING,0,reinterpret_cast<LPARAM>(L"个人排序"));SendMessageW(item(Tab),CB_SETCURSEL,0,0);create(L"LISTBOX",L"",LBS_NOTIFY|LBS_NOINTEGRALHEIGHT|WS_VSCROLL|WS_BORDER,List);create(L"STATIC",L"",0,KeyLabel);create(L"EDIT",L"",ES_AUTOHSCROLL,Key);create(L"STATIC",L"",0,TextLabel);create(L"EDIT",L"",ES_MULTILINE|ES_AUTOVSCROLL|ES_WANTRETURN|WS_VSCROLL,Text);for(const auto& entry:std::vector<std::pair<int,const wchar_t*>>{{New,L"新增"},{Save,L"保存"},{Delete,L"删除"},{Import,L"导入合并…"},{Export,L"备份到新文件…"},{Refresh,L"刷新"},{Recover,L"恢复有效备份"}})create(L"BUTTON",entry.second,BS_PUSHBUTTON|BS_NOTIFY,entry.first);create(L"BUTTON",L"自动学习已确认选词（默认关闭）",BS_AUTOCHECKBOX|BS_MULTILINE|BS_NOTIFY,LearnEnabled);create(L"STATIC",L"",SS_LEFT|SS_ENDELLIPSIS,Status);applyFont();loadSafely();resize();}
    ~App(){if(font)DeleteObject(font);}
};
LRESULT CALLBACK windowProc(HWND window,UINT message,WPARAM wp,LPARAM lp){auto app=reinterpret_cast<App*>(GetWindowLongPtrW(window,GWLP_USERDATA));if(message==WM_NCCREATE){app=static_cast<App*>(reinterpret_cast<CREATESTRUCTW*>(lp)->lpCreateParams);app->window=window;SetWindowLongPtrW(window,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(app));}if(!app)return DefWindowProcW(window,message,wp,lp);try{switch(message){case WM_CREATE:app->initialize();return 0;case WM_SIZE:app->resize();return 0;case WM_DPICHANGED:{auto rect=reinterpret_cast<RECT*>(lp);SetWindowPos(window,nullptr,rect->left,rect->top,rect->right-rect->left,rect->bottom-rect->top,SWP_NOZORDER|SWP_NOACTIVATE);app->applyFont();app->resize();return 0;}case WM_GETMINMAXINFO:{auto info=reinterpret_cast<MINMAXINFO*>(lp);MONITORINFO monitor{};monitor.cbSize=sizeof(monitor);GetMonitorInfoW(MonitorFromWindow(window,MONITOR_DEFAULTTONEAREST),&monitor);int width=monitor.rcWork.right-monitor.rcWork.left,height=monitor.rcWork.bottom-monitor.rcWork.top;info->ptMinTrackSize={std::min(app->px(640),width),std::min(app->px(300),height)};return 0;}case WM_VSCROLL:{SCROLLINFO info{};info.cbSize=sizeof(info);info.fMask=SIF_ALL;GetScrollInfo(window,SB_VERT,&info);int position=app->scrollY;switch(LOWORD(wp)){case SB_LINEUP:position-=app->px(32);break;case SB_LINEDOWN:position+=app->px(32);break;case SB_PAGEUP:position-=static_cast<int>(info.nPage);break;case SB_PAGEDOWN:position+=static_cast<int>(info.nPage);break;case SB_THUMBTRACK:case SB_THUMBPOSITION:position=info.nTrackPos;break;case SB_TOP:position=0;break;case SB_BOTTOM:position=info.nMax;break;}app->scroll(position);return 0;}case WM_MOUSEWHEEL:app->scroll(app->scrollY-static_cast<short>(HIWORD(wp))*app->px(60)/WHEEL_DELTA);return 0;case WM_COMMAND:{int id=LOWORD(wp),notification=HIWORD(wp);if(((id>=New&&id<=Recover)||id==LearnEnabled)&&notification==BN_SETFOCUS)app->showFocused(reinterpret_cast<HWND>(lp));if((id==Key||id==Text)&&notification==EN_SETFOCUS)app->showFocused(reinterpret_cast<HWND>(lp));if(id==List&&notification==LBN_SETFOCUS)app->showFocused(reinterpret_cast<HWND>(lp));if(id==Tab&&notification==CBN_SETFOCUS)app->showFocused(reinterpret_cast<HWND>(lp));if(id==Tab&&notification==CBN_SELCHANGE){app->loadSafely();app->resize();}else if(id==List&&notification==LBN_SELCHANGE)app->select();else if(notification==BN_CLICKED){switch(id){case New:app->newEntry();break;case Save:app->save();break;case Delete:app->remove();break;case Import:app->importFile();break;case Export:app->exportFile();break;case Refresh:app->loadSafely();break;case Recover:app->recover();break;case LearnEnabled:app->toggleLearning();break;}}return 0;}case WM_CLOSE:DestroyWindow(window);return 0;case WM_DESTROY:PostQuitMessage(0);return 0;}}catch(const std::exception& e){app->error(e);if(message==WM_CREATE)return -1;return 0;}return DefWindowProcW(window,message,wp,lp);}
}
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE,LPWSTR,int show){SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);WNDCLASSEXW type{};type.cbSize=sizeof(type);type.hInstance=instance;type.lpfnWndProc=windowProc;type.lpszClassName=L"WangshuPersonalManager";type.hCursor=LoadCursorW(nullptr,IDC_ARROW);type.hbrBackground=reinterpret_cast<HBRUSH>(COLOR_WINDOW+1);if(!RegisterClassExW(&type))return 1;App app;RECT work{0,0,GetSystemMetrics(SM_CXSCREEN),GetSystemMetrics(SM_CYSCREEN)};SystemParametersInfoW(SPI_GETWORKAREA,0,&work,0);UINT systemDpi=GetDpiForSystem();if(!systemDpi)systemDpi=96;int initialWidth=std::max(1,std::min(MulDiv(960,systemDpi,96),static_cast<int>(work.right-work.left)-32)),initialHeight=std::max(1,std::min(MulDiv(720,systemDpi,96),static_cast<int>(work.bottom-work.top)-32));HWND window=CreateWindowExW(0,type.lpszClassName,L"望舒 · 本地个人词库与常用语",WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN|WS_VSCROLL,CW_USEDEFAULT,CW_USEDEFAULT,initialWidth,initialHeight,nullptr,nullptr,instance,&app);if(!window){std::fprintf(stderr,"Native management window creation failed: %lu; no configuration action dispatched.\n",GetLastError());return 2;}ShowWindow(window,show);MSG message;while(GetMessageW(&message,nullptr,0,0)>0){if(!IsDialogMessageW(window,&message)){TranslateMessage(&message);DispatchMessageW(&message);}}return static_cast<int>(message.wParam);}
