#!/usr/bin/env python3
"""Exercise the exact VoiceProcess::poll body using fake clock/pipe; no device or files containing user text."""
from pathlib import Path
import hashlib,json,os,subprocess,sys

base=Path(__file__).resolve().parent
header=base/'voice_process.h'
source=header.read_text()
start=source.index('    void poll(){')
brace=source.index('{',start)
depth=0
for end in range(brace,len(source)):
    if source[end]=='{':depth+=1
    elif source[end]=='}':
        depth-=1
        if depth==0:break
else:raise SystemExit('poll method end not found')
poll=source[start:end+1]
finish_start=source.index('    void finish(){')
finish_end=source.index('    void fail(',finish_start)
finish=source[finish_start:finish_end]
body=r'''
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
using DWORD=unsigned long;using BOOL=int;using HANDLE=int;using ULONGLONG=unsigned long long;
constexpr DWORD WAIT_OBJECT_0=0,WAIT_TIMEOUT=258;
static ULONGLONG clockNow=0;static std::string wire;static unsigned finishWrites=0;
static ULONGLONG GetTickCount64(){return clockNow;}
static BOOL PeekNamedPipe(HANDLE,void*,DWORD,DWORD*,DWORD* available,DWORD*){*available=wire.size();return 1;}
static BOOL ReadFile(HANDLE,void* target,DWORD requested,DWORD* count,void*){*count=std::min<DWORD>(requested,wire.size());std::memcpy(target,wire.data(),*count);wire.erase(0,*count);return 1;}
static DWORD WaitForSingleObject(HANDLE,DWORD){return WAIT_TIMEOUT;}
static BOOL WriteFile(HANDLE,const void* data,DWORD size,DWORD* written,void*){if(size!=1||*static_cast<const char*>(data)!='F')return 0;++finishWrites;*written=1;return 1;}
struct VoiceProcess {
 enum State{Idle,Starting,Recording,Finishing,Complete,Failed}; State state=Idle;
 HANDLE process=1,input=1,output=1;std::string pending,text,error;ULONGLONG deadline=0;
 bool active()const{return state==Starting||state==Recording||state==Finishing;}
 void cancel(){state=Idle;pending.clear();text.clear();error.clear();}
 void fail(const std::string& message){cancel();state=Failed;error=message;}
''' + finish + poll + r'''
};
static unsigned passed=0;
static void test(bool condition,const char* name){if(!condition)throw std::runtime_error(name);std::cout<<"PASS "<<name<<"\n";++passed;}
int main(){try{
 VoiceProcess a;a.state=VoiceProcess::Finishing;a.deadline=60000;clockNow=59000;wire="READY\n";a.poll();
 std::cout<<"OBS finishing deadline after READY="<<a.deadline<<"\n";
 test(a.deadline==60000&&a.state==VoiceProcess::Finishing,"READY while finishing preserves deadline and state");
 clockNow=60001;wire.clear();a.poll();test(a.state==VoiceProcess::Failed&&a.error=="Local voice timed out; recording stopped","finish times out at original bound");
 VoiceProcess b;b.state=VoiceProcess::Starting;b.deadline=120000;clockNow=100;wire="READY\n";b.poll();test(b.state==VoiceProcess::Recording&&b.deadline==60100,"first READY starts recording and timeout");
 VoiceProcess c;c.state=VoiceProcess::Finishing;c.deadline=90000;clockNow=1000;wire="READY\nRESULT sample\n";c.poll();test(c.state==VoiceProcess::Complete&&c.text=="sample","READY then RESULT completes while finishing");
 VoiceProcess d;d.state=VoiceProcess::Finishing;d.deadline=90000;clockNow=1000;wire="ERROR synthetic\n";d.poll();test(d.state==VoiceProcess::Failed&&d.error=="synthetic","ERROR preserves failure semantics");
 VoiceProcess e;e.state=VoiceProcess::Starting;clockNow=100;finishWrites=0;e.finish();for(int i=0;i<64;i++)e.finish();clockNow=59000;wire="READY\n";e.poll();test(finishWrites==1&&e.state==VoiceProcess::Finishing&&e.deadline==60100,"repeated finish plus late READY keep one command and original deadline");
 clockNow=60101;wire.clear();e.poll();test(e.state==VoiceProcess::Failed,"late READY cannot prevent finish timeout");
 std::cout<<"TOTAL "<<passed<<" PASS; extracted production poll; fake pipe/clock, no Windows microphone or actual OS\n";return 0;
}catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<"\n";return 1;}}
'''
fd=os.memfd_create('voice-ready-deadline-test',0)
try:
 command=['/usr/bin/g++','-std=c++17','-Os','-pipe','-x','c++','-','-o',f'/proc/self/fd/{fd}']
 build=subprocess.run(command,input=body.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,pass_fds=(fd,),timeout=60)
 if build.returncode:print(build.stderr.decode(errors='replace'));raise SystemExit(build.returncode)
 rd=os.open(f'/proc/self/fd/{fd}',os.O_RDONLY);os.close(fd);fd=-1
 try:
  run=subprocess.run([f'/proc/self/fd/{rd}'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,pass_fds=(rd,),timeout=15)
 finally:os.close(rd)
 print(run.stdout.decode(errors='replace'),end='');print(run.stderr.decode(errors='replace'),end='',file=sys.stderr)
 print('HEADER_SHA256',hashlib.sha256(header.read_bytes()).hexdigest());print('POLL_SHA256',hashlib.sha256(poll.encode()).hexdigest());print('FINISH_SHA256',hashlib.sha256(finish.encode()).hexdigest());print('TEST_EXIT',run.returncode)
 raise SystemExit(run.returncode)
finally:
 if fd>=0:os.close(fd)
