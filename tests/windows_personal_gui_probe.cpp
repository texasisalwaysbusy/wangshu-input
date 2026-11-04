#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <cstdio>
#include <string>
#include <vector>
struct Search {DWORD pid;HWND window=nullptr;};
static BOOL CALLBACK findWindow(HWND window,LPARAM value){auto search=reinterpret_cast<Search*>(value);DWORD pid;GetWindowThreadProcessId(window,&pid);wchar_t name[80];GetClassNameW(window,name,80);if(pid==search->pid&&std::wstring(name)==L"WangshuPersonalManager"){search->window=window;return FALSE;}return TRUE;}
int wmain(int argc,wchar_t** argv){if(argc!=2)return 64;auto command=L"\""+std::wstring(argv[1])+L"\"";std::vector<wchar_t> buffer(command.begin(),command.end());buffer.push_back(0);STARTUPINFOW startup{};startup.cb=sizeof(startup);PROCESS_INFORMATION process{};if(!CreateProcessW(argv[1],buffer.data(),nullptr,nullptr,FALSE,0,nullptr,nullptr,&startup,&process)){std::printf("{\"stage\":\"PROCESS_CREATE_FAILED\",\"win32_error\":%lu}\n",GetLastError());return 1;}WaitForInputIdle(process.hProcess,3000);Search search{process.dwProcessId};for(int i=0;i<10&&!search.window;i++){EnumWindows(findWindow,reinterpret_cast<LPARAM>(&search));if(WaitForSingleObject(process.hProcess,0)==WAIT_OBJECT_0)break;Sleep(200);}bool closed=false;if(search.window){SendMessageW(search.window,WM_CLOSE,0,0);closed=true;}DWORD wait=WaitForSingleObject(process.hProcess,5000),code=259;if(wait==WAIT_OBJECT_0)GetExitCodeProcess(process.hProcess,&code);else{TerminateProcess(process.hProcess,98);WaitForSingleObject(process.hProcess,3000);code=98;}std::printf("{\"stage\":\"%s\",\"owned_window_found\":%s,\"normal_close_sent\":%s,\"process_exit\":%lu,\"real_windows_gui\":\"NOT_RUN\"}\n",search.window?"WINE_WINDOW_CREATED":"WINE_WINDOW_UNAVAILABLE",search.window?"true":"false",closed?"true":"false",code);CloseHandle(process.hThread);CloseHandle(process.hProcess);return search.window&&code==0?0:2;}
