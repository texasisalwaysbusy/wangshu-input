#ifndef UNICODE
#define UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <filesystem>
#include <string>
#include <vector>
#include <iostream>
#include <stdexcept>
static int checks=0;
static void check(bool value,const char* why){if(!value)throw std::runtime_error(why);++checks;std::cout<<"PASS "<<why<<std::endl;}
static std::wstring mutexName(const std::wstring& path){std::vector<wchar_t> full(32768);DWORD n=GetFullPathNameW(path.c_str(),full.size(),full.data(),nullptr);if(!n||n>=full.size())throw std::runtime_error("Owned path unavailable");unsigned long long hash=1469598103934665603ULL;for(DWORD i=0;i<n;++i){auto c=full[i];if(c>=L'A'&&c<=L'Z')c+=32;if(c==L'/')c=L'\\';hash^=static_cast<unsigned short>(c);hash*=1099511628211ULL;}return L"Local\\WangshuImePersonal-"+std::to_wstring(hash);}
int wmain(int argc,wchar_t** argv){if(argc!=3)return 2;HMODULE module=nullptr;HANDLE mutex=nullptr;try{
    auto path=std::filesystem::path(argv[2])/L"module-owner.json";std::filesystem::create_directories(path.parent_path());
    module=LoadLibraryW(argv[1]);check(module!=nullptr,"actual test DLL loads without registration");
    auto prepare=reinterpret_cast<BOOL(WINAPI*)(LPCWSTR)>(GetProcAddress(module,"PrepareFixture"));auto submit=reinterpret_cast<BOOL(WINAPI*)()>(GetProcAddress(module,"SubmitFixture"));auto outstanding=reinterpret_cast<DWORD(WINAPI*)()>(GetProcAddress(module,"OutstandingFixture"));check(prepare&&submit&&outstanding&&prepare(path.c_str()),"test DLL uses same production queue and native store");
    auto learning=path.wstring()+L".learning-v1.json";mutex=CreateMutexW(nullptr,FALSE,mutexName(learning).c_str());check(mutex&&WaitForSingleObject(mutex,1000)==WAIT_OBJECT_0,"owned mutex holds worker before publication");
    check(submit()&&outstanding()>=1,"real threadpool callback is outstanding with module pin");
    FreeLibrary(module);module=nullptr;
    check(GetModuleHandleW(argv[1])!=nullptr,"dropping caller loader reference does not unload executing queue code");
    ReleaseMutex(mutex);CloseHandle(mutex);mutex=nullptr;
    auto until=GetTickCount64()+6500;while(GetModuleHandleW(argv[1])&&GetTickCount64()<until)Sleep(5);
    check(GetModuleHandleW(argv[1])==nullptr,"system callback-after-return release unloads DLL after queue drains");
    HANDLE input=CreateFileW(learning.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);std::string bytes(128*1024,'\0');DWORD count=0;bool read=input!=INVALID_HANDLE_VALUE&&ReadFile(input,bytes.data(),static_cast<DWORD>(bytes.size()),&count,nullptr);if(input!=INVALID_HANDLE_VALUE)CloseHandle(input);bytes.resize(count);check(read&&bytes.find("module-term")!=std::string::npos,"confirmed selected term was saved before deferred DLL unload");
    std::cout<<"RESULT passed="<<checks<<" failed=0 total="<<checks<<"; actual PE loader + production queue in test-only DLL under Wine; real Windows desktop NOT_RUN"<<std::endl;return 0;
}catch(const std::exception& e){if(mutex){ReleaseMutex(mutex);CloseHandle(mutex);}if(module)FreeLibrary(module);std::cerr<<"FAIL "<<e.what()<<std::endl;return 1;}}
