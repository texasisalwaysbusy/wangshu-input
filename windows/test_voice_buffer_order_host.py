#!/usr/bin/env python3
"""Exercise exact Microphone::record source with a synthetic waveIn queue, no audio device."""
from pathlib import Path
import hashlib,os,subprocess,sys
header=Path(__file__).resolve().parent/'voice_helper.cpp'
source=header.read_text();start=source.index(' void record(Asr& asr){');brace=source.index('{',start);depth=0
for end in range(brace,len(source)):
 if source[end]=='{':depth+=1
 elif source[end]=='}':
  depth-=1
  if depth==0:break
else:raise SystemExit('record method not found')
record=source[start:end+1]
body=r'''
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <vector>
using ULONGLONG=unsigned long long;using HWAVEIN=int;
constexpr unsigned WHDR_DONE=1,MMSYSERR_NOERROR=0;
struct WAVEHDR{char* lpData=nullptr;unsigned dwBufferLength=0,dwBytesRecorded=0,dwFlags=0;};
static std::atomic<bool> finishRecording{false},cancelled{false};
struct Microphone;static Microphone* mic=nullptr;static int ticks=0,scenario=0,requeues=0;
static ULONGLONG GetTickCount64();static void Sleep(unsigned){};
static unsigned waveInAddBuffer(HWAVEIN,WAVEHDR*,unsigned);static unsigned waveInStop(HWAVEIN){return 0;}static unsigned waveInReset(HWAVEIN);
struct Asr{std::vector<int> seen;void accept(const float* sample,int n){if(n!=1)throw std::runtime_error("fixture sample size");seen.push_back(int(std::lround(sample[0]*32768)));if(((scenario==0||scenario==5)&&seen.size()==5)||((scenario==1||scenario==2||scenario==3)&&seen.size()==3))finishRecording=true;if(scenario==3&&seen.size()==3)cancelled=true;}};
struct Microphone{
 HWAVEIN handle=1;std::array<WAVEHDR,4> headers{};std::array<short,4> samples{};
 void set(size_t i,int sample){samples[i]=sample;headers[i].lpData=reinterpret_cast<char*>(&samples[i]);headers[i].dwBufferLength=2;headers[i].dwBytesRecorded=2;headers[i].dwFlags=WHDR_DONE;}
 void start(){mic=this;set(0,1);set(1,2);set(2,3);headers[3].lpData=reinterpret_cast<char*>(&samples[3]);headers[3].dwBufferLength=2;}
''' + record + r'''
};
static ULONGLONG GetTickCount64(){++ticks;if(scenario==0&&ticks==3){mic->set(3,4);mic->set(0,5);}if(scenario==5&&ticks==3)mic->set(0,5);if(scenario==5&&ticks==4)mic->set(3,4);return ticks>25?40000:ticks;}
static unsigned waveInAddBuffer(HWAVEIN,WAVEHDR* h,unsigned){++requeues;if(scenario==4&&requeues==2)return 1;h->dwFlags=0;return MMSYSERR_NOERROR;}
static unsigned waveInReset(HWAVEIN){if(scenario==1||scenario==3){mic->set(3,4);mic->set(0,5);}return 0;}
static int failures=0;
static void check(const std::vector<int>& got,const std::vector<int>& want,const char* name){std::cout<<(got==want?"PASS ":"FAIL ")<<name<<" observed=";for(int x:got)std::cout<<x<<",";std::cout<<"\n";if(got!=want)++failures;}
int main(){
 for(scenario=0;scenario<6;++scenario){ticks=0;requeues=0;finishRecording=false;cancelled=false;Microphone microphone;Asr asr;bool failed=false;try{microphone.record(asr);}catch(const std::runtime_error&){failed=true;}if(scenario==4){if(!failed){std::cout<<"FAIL requeue error must propagate\n";++failures;}else std::cout<<"PASS requeue error propagates\n";continue;}if(failed){std::cout<<"FAIL unexpected error\n";++failures;continue;}if(scenario==0)check(asr.seen,{1,2,3,4,5},"live completion across ring wrap preserves capture order");if(scenario==1)check(asr.seen,{1,2,3,4,5},"final reset drain across wrap preserves capture order");if(scenario==2)check(asr.seen,{1,2,3},"normal prefix emits each buffer once");if(scenario==3)check(asr.seen,{1,2,3},"cancellation prevents reset drain");if(scenario==5)check(asr.seen,{1,2,3,4,5},"newer completion waits for older queued buffer");}
 std::cout<<"RESULT failures="<<failures<<"; extracted production record; fake waveIn, no real device\n";return failures?1:0;
}
'''
fd=os.memfd_create('voice-buffer-order-test',0)
try:
 cmd=['/usr/bin/g++','-std=c++17','-Os','-pipe','-x','c++','-','-o',f'/proc/self/fd/{fd}']
 build=subprocess.run(cmd,input=body.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,pass_fds=(fd,),timeout=60)
 if build.returncode:print(build.stderr.decode(errors='replace'));raise SystemExit(build.returncode)
 rd=os.open(f'/proc/self/fd/{fd}',os.O_RDONLY);os.close(fd);fd=-1
 try:run=subprocess.run([f'/proc/self/fd/{rd}'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,pass_fds=(rd,),timeout=15)
 finally:os.close(rd)
 print(run.stdout.decode(errors='replace'),end='');print(run.stderr.decode(errors='replace'),end='',file=sys.stderr)
 print('SOURCE_SHA256',hashlib.sha256(header.read_bytes()).hexdigest());print('RECORD_SHA256',hashlib.sha256(record.encode()).hexdigest());print('TEST_EXIT',run.returncode);raise SystemExit(run.returncode)
finally:
 if fd>=0:os.close(fd)
