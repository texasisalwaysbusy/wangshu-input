#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <msctf.h>
#include "tsf_extended.h"
#include <cstdio>
#include <stdexcept>
static int checks=0;
static void check(bool ok,const char* name){if(!ok)throw std::runtime_error(name);checks++;std::printf("PASS %s\n",name);std::fflush(stdout);}
int wmain(int argc,wchar_t** argv){try{
    check(argc==2||(argc==3&&std::wstring(argv[2])==L"--wine-tsf"),"DLL path provided");HMODULE dll=LoadLibraryW(argv[1]);check(dll!=nullptr,"actual PE DLL loads with Windows system imports");
    auto factory=reinterpret_cast<HRESULT(__stdcall*)(REFCLSID,REFIID,void**)>(GetProcAddress(dll,"DllGetClassObject"));
    auto unload=reinterpret_cast<HRESULT(__stdcall*)()>(GetProcAddress(dll,"DllCanUnloadNow"));
    check(factory&&unload&&GetProcAddress(dll,"DllRegisterServer")&&GetProcAddress(dll,"DllUnregisterServer"),"all COM server entry points available");
    check(unload()==S_OK,"initially no live server objects");
    CLSID clsid={0x2158928d,0x82f3,0x4d52,{0x91,0x4e,0xa8,0x3a,0x56,0xf9,0xc1,0x6d}};IClassFactory* f=nullptr;
    check(factory(CLSID_NULL,IID_IClassFactory,reinterpret_cast<void**>(&f))==CLASS_E_CLASSNOTAVAILABLE,"unrelated COM class rejected");
    check(SUCCEEDED(factory(clsid,IID_IClassFactory,reinterpret_cast<void**>(&f)))&&f,"project class factory created");
    check(unload()==S_FALSE,"live factory prevents unloading");
    ITfTextInputProcessor* service=nullptr;check(f->CreateInstance(f,IID_ITfTextInputProcessor,reinterpret_cast<void**>(&service))==CLASS_E_NOAGGREGATION,"COM aggregation rejected");
    check(SUCCEEDED(f->CreateInstance(nullptr,IID_ITfTextInputProcessor,reinterpret_cast<void**>(&service)))&&service,"native TSF input processor created");
    for(const IID* iid:{&IID_ITfTextInputProcessorEx,&IID_ITfKeyEventSink,&IID_ITfThreadMgrEventSink,&IID_ITfCompositionSink}){IUnknown* sink=nullptr;check(SUCCEEDED(service->QueryInterface(*iid,reinterpret_cast<void**>(&sink)))&&sink,"required TSF sink interface available");sink->Release();}
    void* unknown=nullptr;check(service->QueryInterface(IID_IStream,&unknown)==E_NOINTERFACE,"unsupported interface rejected");
    check(service->Activate(nullptr,1)==E_POINTER,"invalid activation rejected without crash");
    if(argc==3){
        check(GetProcAddress(GetModuleHandleW(L"ntdll.dll"),"wine_get_version"),"actual TSF manager test restricted to cloud Wine");HRESULT h=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);check(SUCCEEDED(h),"actual COM apartment initialized");
        ITfThreadMgr* manager=nullptr;h=CoCreateInstance(CLSID_TF_ThreadMgr,nullptr,CLSCTX_INPROC_SERVER,IID_ITfThreadMgr,reinterpret_cast<void**>(&manager));check(SUCCEEDED(h)&&manager,"actual Wine TSF thread manager created");TfClientId client=0;h=manager->Activate(&client);check(SUCCEEDED(h),"actual TSF thread activated");h=service->Activate(manager,client);std::printf("ACTIVATE actual-manager HRESULT=0x%08lx\n",static_cast<unsigned long>(h));check(SUCCEEDED(h),"production service binds actual TSF key and thread sinks");service->Deactivate();manager->Deactivate();manager->Release();CoUninitialize();
    }
    check(SUCCEEDED(service->Deactivate()),"inactive service cleanup safe");service->Release();f->Release();check(unload()==S_OK,"all COM references released, DLL can unload");FreeLibrary(dll);
    std::printf("TOTAL %d PASS; DLL loader/COM only, not real Windows application integration\n",checks);return 0;
}catch(const std::exception& e){std::fprintf(stderr,"FAIL %s; Win32 error %lu\n",e.what(),GetLastError());return 1;}}
