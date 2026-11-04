#!/usr/bin/env python3
"""Extract the actual pollVoice body; exercise fake COM reentry without a PE binary."""
from pathlib import Path
import hashlib, os, subprocess, sys
p=Path(__file__).resolve().parent/'text_service.cpp'; source=p.read_text(); a=source.index('    void pollVoice() noexcept{'); brace=source.index('{',a); depth=0
for end in range(brace,len(source)):
 if source[end]=='{': depth+=1
 elif source[end]=='}':
  depth-=1
  if depth==0: break
else: raise SystemExit('pollVoice body not found')
method=source[a:end+1]
code=r'''
#include <cstdint>
#include <functional>
#include <iostream>
#include <memory>
#include <string>
using HRESULT=int32_t; constexpr HRESULT S_OK=0,E_FAIL=-1,E_ACCESSDENIED=-2,E_OUTOFMEMORY=-3;
#define SUCCEEDED(h) ((h)>=0)
constexpr int TF_ES_SYNC=1,TF_ES_READ=2;
struct ITfContext{std::function<void()> callback;int result=S_OK;void Release(){} int RequestEditSession(int,void*,int,int* allowed){if(callback)callback();*allowed=result;return S_OK;}};
template<class T>void drop(T*&p){p=nullptr;}
struct VoiceGuard {explicit VoiceGuard(ITfContext*){} void Release(){delete this;}};
struct VoiceProcess{enum State{Idle,Recording,Complete,Failed}; State state=Recording;std::string text="old";bool active()const{return state==Recording;}void poll(){} };
struct Dictionary{std::string voiceMath(std::string s){return s;}};
struct TextService{
 ITfContext oldTarget,newTarget;ITfContext* voiceTarget=&oldTarget;std::shared_ptr<int> voicePosition=std::make_shared<int>(1);unsigned long long voiceGeneration=1;
 VoiceProcess localVoice;bool listening=true,math=false;std::string voiceReview,voiceRecovery;std::wstring status;int client=0,stops=0,retains=0,updates=0;bool sensitive=false;Dictionary dict;
 ITfContext* focused(){return voiceTarget;}bool disabled(ITfContext*){return false;}void stopVoice(){stops++;listening=false;voiceTarget=nullptr;voicePosition.reset();++voiceGeneration;}void retainVoice(){retains++;stopVoice();}void update(){updates++;}Dictionary& dictionary(){return dict;}void reviewVoice(std::string){}
''' + method + r'''
};
int main(){int pass=0,fail=0;auto check=[&](bool v,const char* n){std::cout<<(v?"PASS ":"FAIL ")<<n<<"\n";(v?pass:fail)++;};
 {TextService t;t.pollVoice();check(t.listening&&t.stops==0,"valid voice remains active");}
 {TextService t;t.oldTarget.result=E_ACCESSDENIED;t.pollVoice();check(!t.listening&&t.stops==1,"current private scope stops voice");}
 {TextService t;t.oldTarget.result=E_ACCESSDENIED;t.oldTarget.callback=[&]{t.voiceTarget=&t.newTarget;t.voicePosition=std::make_shared<int>(2);t.voiceGeneration++;t.listening=true;};t.pollVoice();check(t.listening&&t.voiceTarget==&t.newTarget&&t.stops==0,"stale private result cannot cancel replacement voice");}
 {TextService t;t.oldTarget.callback=[&]{t.voiceTarget=&t.newTarget;t.voicePosition=std::make_shared<int>(2);t.voiceGeneration++;t.listening=true;};t.pollVoice();check(t.listening&&t.voiceTarget==&t.newTarget&&t.updates==0,"stale success cannot operate on replacement voice");}
 std::cout<<"RESULT passed="<<pass<<" failed="<<fail<<"; fake COM callback; real Windows TSF NOT_RUN\n";return fail?1:0;}
'''
fd=os.memfd_create('voice-poll-reentry',0)
try:
 r=subprocess.run(['/usr/bin/g++','-std=c++17','-Os','-pipe','-x','c++','-','-o',f'/proc/self/fd/{fd}'],input=code.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,pass_fds=(fd,),timeout=60)
 if r.returncode: print(r.stderr.decode(),file=sys.stderr);raise SystemExit(r.returncode)
 rd=os.open(f'/proc/self/fd/{fd}',os.O_RDONLY);os.close(fd);fd=-1
 try:r=subprocess.run([f'/proc/self/fd/{rd}'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,pass_fds=(rd,),timeout=15)
 finally:os.close(rd)
 print(r.stdout.decode(),end='');print('SOURCE_SHA256',hashlib.sha256(p.read_bytes()).hexdigest());print('METHOD_SHA256',hashlib.sha256(method.encode()).hexdigest());print('TEST_EXIT',r.returncode);raise SystemExit(r.returncode)
finally:
 if fd>=0:os.close(fd)
