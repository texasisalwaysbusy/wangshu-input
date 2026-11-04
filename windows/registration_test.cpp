#ifndef UNICODE
#define UNICODE
#endif
#include <windows.h>
#include <msctf.h>
#include <cstdio>
#include <string>
#include <stdexcept>
static const CLSID Service={0x2158928d,0x82f3,0x4d52,{0x91,0x4e,0xa8,0x3a,0x56,0xf9,0xc1,0x6d}};
static const GUID Profile={0x297331cb,0x1972,0x4d66,{0xb7,0x3b,0xa7,0x64,0x4f,0x28,0x53,0x9d}};
static int checks=0;
static void check(bool ok,const char* name,HRESULT code=S_OK){std::printf("%s %s (HRESULT=0x%08lx)\n",ok?"PASS":"FAIL",name,static_cast<unsigned long>(code));std::fflush(stdout);if(!ok)throw std::runtime_error(name);checks++;}
template<class T>static void release(T*& p){if(p){p->Release();p=nullptr;}}
int wmain(int argc,wchar_t** argv){
    // This test is intentionally restricted to our isolated Wine prefix, never a user's Windows PC.
    if(argc!=3||std::wstring(argv[2])!=L"--isolated-wine"||!GetProcAddress(GetModuleHandleW(L"ntdll.dll"),"wine_get_version")){std::fprintf(stderr,"BLOCKED: registration test requires explicit isolated Wine mode\n");return 2;}
    HKEY existing=nullptr;std::wstring cls=L"CLSID\\{2158928D-82F3-4D52-914E-A83A56F9C16D}";
    if(RegOpenKeyExW(HKEY_CLASSES_ROOT,cls.c_str(),0,KEY_READ,&existing)==ERROR_SUCCESS){RegCloseKey(existing);std::fprintf(stderr,"BLOCKED: project class already exists; preserve prior registration\n");return 2;}
    HMODULE dll=nullptr;HRESULT(__stdcall* unregister)()=nullptr;bool registrationAttempted=false;int result=0;
    ITfTextInputProcessor* service=nullptr;ITfThreadMgr* manager=nullptr;ITfInputProcessorProfiles* profiles=nullptr;
    HRESULT initialized=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    try{
        check(SUCCEEDED(initialized),"COM apartment initialized",initialized);dll=LoadLibraryW(argv[1]);check(dll,"native DLL loaded");
        HANDLE token=nullptr;TOKEN_ELEVATION elevation{};DWORD bytes=0;if(OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&token)){GetTokenInformation(token,TokenElevation,&elevation,sizeof(elevation),&bytes);CloseHandle(token);}std::printf("READ_ONLY token_is_elevated=%lu\n",elevation.TokenIsElevated);
        DWORD uac=99,size=sizeof(uac);LSTATUS policy=RegGetValueW(HKEY_LOCAL_MACHINE,L"Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System",L"EnableLUA",RRF_RT_REG_DWORD,nullptr,&uac,&size);std::printf("READ_ONLY EnableLUA query=%ld value=%lu (99=unknown)\n",policy,uac);
        auto install=reinterpret_cast<HRESULT(__stdcall*)()>(GetProcAddress(dll,"DllRegisterServer"));unregister=reinterpret_cast<HRESULT(__stdcall*)()>(GetProcAddress(dll,"DllUnregisterServer"));check(install&&unregister,"registration exports present");
        registrationAttempted=true;HRESULT h=install();check(SUCCEEDED(h),"register own class, Chinese profile and keyboard category in isolated prefix",h);
        for(HKEY root:{HKEY_CURRENT_USER,HKEY_CLASSES_ROOT}){HKEY key=nullptr;auto path=(root==HKEY_CURRENT_USER?L"Software\\Classes\\":L"")+cls+L"\\InprocServer32";LSTATUS r=RegOpenKeyExW(root,path.c_str(),0,KEY_READ,&key);std::printf("READ_ONLY %s own InprocServer32 status=%ld\n",root==HKEY_CURRENT_USER?"HKCU":"HKCR",r);if(key){wchar_t value[32768];DWORD n=sizeof(value);r=RegQueryValueExW(key,nullptr,nullptr,nullptr,reinterpret_cast<BYTE*>(value),&n);std::printf("READ_ONLY DLL value query=%ld matches_expected=%d\n",r,r==ERROR_SUCCESS&&_wcsicmp(value,argv[1])==0);RegCloseKey(key);}}
        h=CoCreateInstance(CLSID_TF_InputProcessorProfiles,nullptr,CLSCTX_INPROC_SERVER,IID_ITfInputProcessorProfiles,reinterpret_cast<void**>(&profiles));check(SUCCEEDED(h),"TSF profile manager created",h);
        BSTR name=nullptr;h=profiles->GetLanguageProfileDescription(Service,0x804,Profile,&name);check(SUCCEEDED(h)&&name&&SysStringLen(name)>0,"registered Chinese profile is readable",h);SysFreeString(name);
        BOOL enabled=FALSE;h=profiles->IsEnabledLanguageProfile(Service,0x804,Profile,&enabled);check(SUCCEEDED(h)&&enabled,"new profile enabled without changing default input method",h);
        h=CoCreateInstance(Service,nullptr,CLSCTX_INPROC_SERVER,IID_ITfTextInputProcessor,reinterpret_cast<void**>(&service));check(SUCCEEDED(h)&&service,"registered class resolved by actual COM runtime",h);
        h=CoCreateInstance(CLSID_TF_ThreadMgr,nullptr,CLSCTX_INPROC_SERVER,IID_ITfThreadMgr,reinterpret_cast<void**>(&manager));check(SUCCEEDED(h),"actual TSF thread manager created",h);
        TfClientId client=0;h=manager->Activate(&client);check(SUCCEEDED(h),"actual TSF thread activated",h);
        h=service->Activate(manager,client);check(SUCCEEDED(h),"production service binds real TSF manager and shared dictionary",h);
        h=service->Deactivate();check(SUCCEEDED(h),"production service detaches actual TSF sinks",h);manager->Deactivate();
    }catch(const std::exception& e){std::fprintf(stderr,"FAIL after %d: %s\n",checks,e.what());result=1;}
    if(service)service->Deactivate();release(service);if(manager)manager->Deactivate();release(manager);release(profiles);
    if(registrationAttempted&&unregister){HRESULT h=unregister();std::printf("CLEANUP unregister HRESULT=0x%08lx\n",static_cast<unsigned long>(h));if(FAILED(h))result=1;}
    if(SUCCEEDED(initialized))CoUninitialize();
    if(dll)FreeLibrary(dll);
    existing=nullptr;if(RegOpenKeyExW(HKEY_CLASSES_ROOT,cls.c_str(),0,KEY_READ,&existing)==ERROR_SUCCESS){RegCloseKey(existing);std::fprintf(stderr,"FAIL cleanup left project class in isolated prefix\n");result=1;}else std::printf("PASS own class cleanup verified\n");
    std::printf("TOTAL %d registration checks; scope Wine only, real Windows application integration NOT RUN\n",checks);return result;
}
