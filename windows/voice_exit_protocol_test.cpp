#include <windows.h>
#include <filesystem>
#include <iostream>
#include <stdexcept>
#include <string>
#include <functional>
#include "voice_process.h"

static int passed=0,failed=0;
static void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
static void test(const char*label,const std::function<void()>&run){
    try{run();++passed;std::cout<<"PASS "<<label<<"\n";}
    catch(const std::exception&error){++failed;std::cout<<"FAIL "<<label<<" :: "<<error.what()<<"\n";}
}
struct OwnedProcess{
    HANDLE handle=nullptr;
    explicit OwnedProcess(DWORD pid):handle(OpenProcess(SYNCHRONIZE|PROCESS_QUERY_LIMITED_INFORMATION,FALSE,pid)){
        require(handle!=nullptr,"open explicit synthetic process handle");
    }
    ~OwnedProcess(){CloseHandle(handle);}
    bool exited(){return WaitForSingleObject(handle,0)==WAIT_OBJECT_0;}
    DWORD code(){DWORD value=STILL_ACTIVE;require(GetExitCodeProcess(handle,&value)!=0,"child exit code");return value;}
};
static void pollUntil(VoiceProcess& process,ULONGLONG duration,const std::function<bool()>&done){
    const auto stop=GetTickCount64()+duration;
    while(!done()&&GetTickCount64()<stop){process.poll();Sleep(10);}
}
static void ready(VoiceProcess&process,const std::filesystem::path& folder,const wchar_t*mode){
    require(process.start(folder,mode),"start synthetic child");
    pollUntil(process,2000,[&]{return process.state!=VoiceProcess::Starting;});
    require(process.state==VoiceProcess::Recording,"synthetic child READY");
}
int wmain(int argc,wchar_t**argv){
    if(argc!=2)return 2;
    const auto folder=std::filesystem::path(argv[1]);
    test("final text withheld until child naturally exits successfully",[&]{
        VoiceProcess p;ready(p,folder,L"--late-exit");OwnedProcess original(p.processId());p.finish();
        // The fixture flushes RESULT immediately but intentionally stays alive.
        pollUntil(p,700,[&]{return !p.active();});
        require(p.active()&&p.text.empty()&&!original.exited(),"RESULT was published or process killed before natural exit");
        pollUntil(p,5000,[&]{return !p.active();});
        require(p.state==VoiceProcess::Complete&&p.text=="pending-exit"&&original.exited()&&original.code()==0,
                "normal exit and final result must be bound together");
    });
    test("child reporting a result then exiting with error never publishes text",[&]{
        VoiceProcess p;ready(p,folder,L"--error-exit");OwnedProcess original(p.processId());p.finish();
        pollUntil(p,5000,[&]{return !p.active();});
        require(p.state==VoiceProcess::Failed&&p.text.empty()&&original.exited()&&original.code()==9,
                "nonzero exit falsely finalized or child killed prematurely");
    });
    test("hung cleanup after result is treated as failure",[&]{
        VoiceProcess p;ready(p,folder,L"--hang-after-result");OwnedProcess original(p.processId());p.finish();
        pollUntil(p,6000,[&]{return !p.active();});
        require(p.state==VoiceProcess::Failed&&p.text.empty(),"hung child was presented as successful speech");
        require(WaitForSingleObject(original.handle,3000)==WAIT_OBJECT_0,"owned hung child did not stop");
    });
    test("cancel while waiting for exit never replays held text into new session",[&]{
        VoiceProcess p;ready(p,folder,L"--late-exit");OwnedProcess original(p.processId());p.finish();
        pollUntil(p,400,[&]{return !p.active();});
        p.cancel();
        require(p.state==VoiceProcess::Idle&&p.text.empty()&&p.preview().empty(),"cancel retained speech");
        const auto deadline=GetTickCount64()+3000;
        bool started=false;
        do{
            started=p.start(folder,L"--late-exit");
            if(started)require(original.exited(),"new child started before old child exited");
            else Sleep(10);
        }while(!started&&GetTickCount64()<deadline);
        require(started&&p.text.empty()&&p.preview().empty(),"new session cannot recover after cancellation");
        p.cancel();
        p.poll();
        require(p.text.empty(),"canceled speech returned");
    });
    std::cout<<"TOTAL "<<passed<<" PASS, "<<failed<<" FAIL; owned synthetic child; microphone NOT_RUN\n";
    return failed?1:0;
}
