#pragma once
#include <windows.h>
#include <filesystem>
#include <string>
#include <vector>
#include <stdexcept>
#include <algorithm>

/** Only starts our packaged child; no shell, inherited ambient handles, or text files. */
class VoiceProcess {
    HANDLE process=nullptr,job=nullptr,input=nullptr,output=nullptr;
    std::string pending;ULONGLONG deadline=0;
    static void close(HANDLE& h){if(h&&h!=INVALID_HANDLE_VALUE)CloseHandle(h);h=nullptr;}
public:
    enum State{Idle,Starting,Recording,Finishing,Complete,Failed};State state=Idle;
    std::string text,error;
    ~VoiceProcess(){cancel();}
    VoiceProcess()=default;VoiceProcess(const VoiceProcess&)=delete;VoiceProcess& operator=(const VoiceProcess&)=delete;
    DWORD processId()const{return process?GetProcessId(process):0;}
    bool active()const{return state==Starting||state==Recording||state==Finishing;}
    void cancel(){if(job)TerminateJobObject(job,3);if(process&&WaitForSingleObject(process,0)==WAIT_TIMEOUT)TerminateProcess(process,3);close(input);close(output);close(process);close(job);state=Idle;pending.clear();text.clear();error.clear();}
    bool start(const std::filesystem::path& folder,const std::wstring& fixtureArgs=L""){
        cancel();HANDLE childInput=nullptr,childOutput=nullptr,nullError=nullptr;LPPROC_THREAD_ATTRIBUTE_LIST attrs=nullptr;PROCESS_INFORMATION info{};bool ok=false;
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
    void finish(){if((state!=Starting&&state!=Recording)||!input)return;DWORD written=0;char c='F';if(!WriteFile(input,&c,1,&written,nullptr)||written!=1){fail("Unable to finish local recording");return;}state=Finishing;deadline=GetTickCount64()+60000;}
    void fail(const std::string& message){cancel();state=Failed;error=message;}
    void poll(){
        if(!active()||!output)return;
        if(GetTickCount64()>deadline){fail("Local voice timed out; recording stopped");return;}
        DWORD available=0;BOOL peek=PeekNamedPipe(output,nullptr,0,nullptr,&available,nullptr);
        if(peek&&available){char b[4096];DWORD n=0;if(ReadFile(output,b,std::min<DWORD>(sizeof(b),available),&n,nullptr)){pending.append(b,n);if(pending.size()>32768){fail("Local voice response too large");return;}
            size_t pos=0;while((pos=pending.find('\n'))!=std::string::npos){std::string line=pending.substr(0,pos);pending.erase(0,pos+1);if(!line.empty()&&line.back()=='\r')line.pop_back();
                if(line=="READY"){if(state!=Finishing){state=Recording;deadline=GetTickCount64()+60000;}}
                else if(line.rfind("RESULT ",0)==0){std::string value=line.substr(7);cancel();text=value;state=Complete;return;}
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
