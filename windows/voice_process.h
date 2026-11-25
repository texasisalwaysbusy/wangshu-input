#pragma once
#include <windows.h>
#include <filesystem>
#include <string>
#include <vector>
#include <stdexcept>
#include <algorithm>
#include <utility>

/** Only starts our packaged child; no shell, inherited ambient handles, or text files. */
class VoiceProcess {
    HANDLE process=nullptr,job=nullptr,input=nullptr,output=nullptr;
    std::string pending,finalText;ULONGLONG deadline=0;
    bool stopping=false;
    std::wstring partial;
    static void close(HANDLE& h){if(h&&h!=INVALID_HANDLE_VALUE)CloseHandle(h);h=nullptr;}
    void readPreview(const std::string& line){
        constexpr size_t prefixSize=8;
        partial.clear();
        const auto size=line.size()-prefixSize;
        if(!size||size>2048||line.find_first_of("\r\n\t\0",prefixSize,4)!=std::string::npos)return;
        const char* bytes=line.data()+prefixSize;
        const int units=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,bytes,static_cast<int>(size),nullptr,0);
        if(!units)return;
        partial.resize(units);
        if(!MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,bytes,static_cast<int>(size),partial.data(),units))partial.clear();
    }
    void confirmFinalExit(){
        const DWORD result=WaitForSingleObject(process,0);
        if(result==WAIT_OBJECT_0){
            DWORD code=0;
            if(!GetExitCodeProcess(process,&code)||code!=0){
                fail("Local voice process did not exit successfully");return;
            }
            close(process);close(job);
            text=std::move(finalText);finalText.clear();state=Complete;
        }else if(result!=WAIT_TIMEOUT||GetTickCount64()>deadline){
            fail("Unable to confirm local voice process exit");
        }
    }
public:
    enum State{Idle,Starting,Recording,Finishing,WaitingForExit,Complete,Failed};State state=Idle;
    std::string text,error;
    ~VoiceProcess(){cancel();close(process);}
    VoiceProcess()=default;VoiceProcess(const VoiceProcess&)=delete;VoiceProcess& operator=(const VoiceProcess&)=delete;
    DWORD processId()const{return process?GetProcessId(process):0;}
    bool active()const{return state==Starting||state==Recording||state==Finishing||state==WaitingForExit;}
    const std::wstring& preview()const{return partial;}
    bool stopPending()const{return stopping;}
    bool confirmStopped(){
        if(!process){stopping=false;return true;}
        if(!stopping||WaitForSingleObject(process,0)!=WAIT_OBJECT_0)return false;
        close(process);stopping=false;return true;
    }
    bool cancel(){
        // Discard session data immediately, but retain the owned process handle
        // until the kernel confirms termination. TerminateProcess is asynchronous.
        close(input);close(output);
        state=Idle;pending.clear();finalText.clear();partial.clear();text.clear();error.clear();
        if(process&&!stopping){
            if(WaitForSingleObject(process,0)==WAIT_OBJECT_0){close(process);}
            else{
                if(!job||!TerminateJobObject(job,3))TerminateProcess(process,3);
                stopping=true;
            }
        }
        close(job);
        return confirmStopped();
    }
    bool start(const std::filesystem::path& folder,const std::wstring& fixtureArgs=L""){
        if(!cancel()){
            error="Previous local voice process has not yet stopped";state=Failed;return false;
        }
        HANDLE childInput=nullptr,childOutput=nullptr,nullError=nullptr;LPPROC_THREAD_ATTRIBUTE_LIST attrs=nullptr;PROCESS_INFORMATION info{};bool ok=false;
        SECURITY_ATTRIBUTES security{sizeof(SECURITY_ATTRIBUTES),nullptr,TRUE};
        auto exe=folder/L"wangshu-voice-helper.exe";
        if(!std::filesystem::is_regular_file(exe)){error="Local voice helper absent";state=Failed;return false;}
        do{
            if(!CreatePipe(&childInput,&input,&security,0)||!CreatePipe(&output,&childOutput,&security,0))break;
            if(!SetHandleInformation(input,HANDLE_FLAG_INHERIT,0)||!SetHandleInformation(output,HANDLE_FLAG_INHERIT,0))break;
            nullError=CreateFileW(L"NUL",GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE,&security,OPEN_EXISTING,0,nullptr);if(nullError==INVALID_HANDLE_VALUE)break;
            SIZE_T size=0;InitializeProcThreadAttributeList(nullptr,1,0,&size);std::vector<unsigned char> storage(size);attrs=reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(storage.data());
            if(!InitializeProcThreadAttributeList(attrs,1,0,&size)){attrs=nullptr;break;}
            HANDLE allowed[]={childInput,childOutput,nullError};
            if(!UpdateProcThreadAttribute(attrs,0,PROC_THREAD_ATTRIBUTE_HANDLE_LIST,allowed,sizeof(allowed),nullptr,nullptr)){DeleteProcThreadAttributeList(attrs);attrs=nullptr;break;}
            STARTUPINFOEXW start{};start.StartupInfo.cb=sizeof(start);start.StartupInfo.dwFlags=STARTF_USESTDHANDLES;start.StartupInfo.hStdInput=childInput;start.StartupInfo.hStdOutput=childOutput;start.StartupInfo.hStdError=nullError;start.lpAttributeList=attrs;
            std::wstring command=L"\""+exe.wstring()+L"\""+(fixtureArgs.empty()?L"":L" "+fixtureArgs);
            BOOL created=CreateProcessW(exe.c_str(),command.data(),nullptr,nullptr,TRUE,CREATE_NO_WINDOW|CREATE_SUSPENDED|EXTENDED_STARTUPINFO_PRESENT,nullptr,folder.c_str(),&start.StartupInfo,&info);
            DeleteProcThreadAttributeList(attrs);attrs=nullptr;if(!created)break;process=info.hProcess;
            job=CreateJobObjectW(nullptr,nullptr);if(!job)break;JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};limits.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            if(!SetInformationJobObject(job,JobObjectExtendedLimitInformation,&limits,sizeof(limits))||!AssignProcessToJobObject(job,process))break;
            if(ResumeThread(info.hThread)==static_cast<DWORD>(-1))break;
            ok=true;
        }while(false);
        close(childInput);close(childOutput);close(nullError);close(info.hThread);
        if(!ok){DWORD code=GetLastError();cancel();state=Failed;error="Unable to start owned local voice process ("+std::to_string(code)+")";return false;}
        state=Starting;deadline=GetTickCount64()+120000;return true;
    }
    void finish(){if((state!=Starting&&state!=Recording)||!input)return;DWORD written=0;char c='F';if(!WriteFile(input,&c,1,&written,nullptr)||written!=1){fail("Unable to finish local recording");return;}partial.clear();state=Finishing;deadline=GetTickCount64()+60000;}
    void fail(const std::string& message){cancel();state=Failed;error=message;}
    void poll(){
        if(stopping){confirmStopped();return;}
        if(state==WaitingForExit){confirmFinalExit();return;}
        if(!active()||!output)return;
        if(GetTickCount64()>deadline){fail("Local voice timed out");return;}
        DWORD available=0;BOOL peek=PeekNamedPipe(output,nullptr,0,nullptr,&available,nullptr);
        if(peek&&available){char b[4096];DWORD n=0;if(ReadFile(output,b,std::min<DWORD>(sizeof(b),available),&n,nullptr)){pending.append(b,n);if(pending.size()>32768){fail("Local voice response too large");return;}
            size_t pos=0;while((pos=pending.find('\n'))!=std::string::npos){std::string line=pending.substr(0,pos);pending.erase(0,pos+1);if(!line.empty()&&line.back()=='\r')line.pop_back();
                if(line=="READY"){if(state==Starting){state=Recording;deadline=GetTickCount64()+60000;}}
                else if(state==Recording&&line.rfind("PREVIEW ",0)==0)readPreview(line);
                else if(line.rfind("RESULT ",0)==0){
                    finalText=line.substr(7);partial.clear();pending.clear();
                    close(input);close(output);state=WaitingForExit;
                    deadline=GetTickCount64()+3000;confirmFinalExit();return;
                }
                else if(line.rfind("ERROR ",0)==0){fail(line.substr(6));return;}
            }
        }}
        if(!peek||WaitForSingleObject(process,0)==WAIT_OBJECT_0){
            // A final small write may remain buffered after process exit; drain it next poll.
            if(peek&&available)return;
            fail("Local recognition ended without a result");
        }
    }
};
