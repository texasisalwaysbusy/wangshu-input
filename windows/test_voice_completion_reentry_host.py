#!/usr/bin/env python3
"""Exercise exact unmerged TextService::reviewVoice with fake, reentrant session callbacks."""
from pathlib import Path
import hashlib,os,subprocess,sys
header=Path(__file__).resolve().parent/'text_service.cpp';source=header.read_text();start=source.index('    void reviewVoice(std::string text){');brace=source.index('{',start);depth=0
for end in range(brace,len(source)):
 if source[end]=='{':depth+=1
 elif source[end]=='}':
  depth-=1
  if depth==0:break
else:raise SystemExit('reviewVoice not found')
method=source[start:end+1]
body=r'''
#include <cstdint>
#include <iostream>
#include <memory>
#include <string>
using HRESULT=int32_t;constexpr HRESULT S_OK=0,E_ABORT=-1,E_ACCESSDENIED=-2;
#define FAILED(x) ((x)<0)
static std::wstring wide(const std::string& s){return std::wstring(s.begin(),s.end());}
namespace wangshu::voice_position {constexpr size_t MaxUnits=32768;struct Snapshot{int id;};static bool textValid(const std::wstring& s){return !s.empty();}}
struct ITfContext{int id;int refs=1;void AddRef(){refs++;}void Release(){refs--;}};
template<typename T>static void drop(T*& p){if(p)p->Release();p=nullptr;}
static void SetTimer(int,int,int,void*){}
struct TextService{
 ITfContext oldTarget{1},newTarget{2};ITfContext* voiceTarget=&oldTarget;
 std::shared_ptr<const wangshu::voice_position::Snapshot> voicePosition=std::make_shared<wangshu::voice_position::Snapshot>(wangshu::voice_position::Snapshot{1});
 unsigned long long voiceGeneration=1;std::string voiceReview,voiceRecovery;std::wstring status;bool listening=true,voiceLastStopPreserved=false;int popup=1,VoiceTimer=2,mode=0,stopCount=0;
 HRESULT checkVoiceRead(){if(mode==1){voiceTarget=&newTarget;voicePosition=std::make_shared<wangshu::voice_position::Snapshot>(wangshu::voice_position::Snapshot{2});++voiceGeneration;listening=true;return E_ABORT;}if(mode==7){stopVoice(true);return E_ACCESSDENIED;}if(mode==4){stopVoice(true);return E_ABORT;}if(mode==5){stopVoice();return E_ABORT;}if(mode==6){stopVoice(true);stopVoice();return E_ABORT;}if(mode==2)return E_ACCESSDENIED;if(mode==3)return E_ABORT;return S_OK;}
 void stopVoice(bool preserve=false){voiceLastStopPreserved=preserve;++stopCount;++voiceGeneration;voiceTarget=nullptr;voicePosition.reset();voiceReview.clear();listening=false;}
 void update(){}
''' + method + r'''
};
static int pass=0,fail=0;static void check(bool ok,const char* label){if(ok){++pass;std::cout<<"PASS "<<label<<"\n";}else{++fail;std::cout<<"FAIL "<<label<<"\n";}}
int main(){
 {TextService t;t.reviewVoice("sample");check(t.voiceReview=="sample"&&t.voiceTarget==&t.oldTarget,"unchanged session enters explicit review");}
 {TextService t;t.mode=2;t.voiceRecovery="prior";t.reviewVoice("sample");check(t.voiceRecovery.empty()&&t.voiceReview.empty(),"known private scope clears old result");}
 {TextService t;t.mode=3;t.reviewVoice("sample");check(t.voiceRecovery=="sample"&&t.voiceReview.empty(),"unchanged session with invalid position retains for explicit recovery");}
 {TextService t;t.mode=1;t.reviewVoice("late-old");std::cout<<"OBS new_target_preserved="<<(t.voiceTarget==&t.newTarget)<<" new_listening="<<t.listening<<" old_recovery="<<(!t.voiceRecovery.empty())<<"\n";check(t.voiceTarget==&t.newTarget&&t.listening&&t.voiceRecovery.empty(),"reentrant replacement session survives stale completion");}
 {TextService t;t.mode=4;t.reviewVoice("heard");check(t.voiceRecovery=="heard"&&!t.voiceTarget,"single preserving stop retains result for explicit recovery");}
 {TextService t;t.mode=5;t.reviewVoice("cancelled");check(t.voiceRecovery.empty()&&!t.voiceTarget,"explicit cancellation does not resurrect result");}
 {TextService t;t.mode=6;t.reviewVoice("cancelled");check(t.voiceRecovery.empty()&&!t.voiceTarget,"cancellation after preserving stop does not resurrect result");}
 {TextService t;t.mode=7;t.reviewVoice("private");check(t.voiceRecovery.empty()&&!t.voiceTarget,"private result is not retained after preserving stop");}
 std::cout<<"RESULT passed="<<pass<<" failed="<<fail<<"; exact reviewVoice body with fake callback/state; real TSF Windows NOT_RUN\n";return fail?1:0;
}
'''
fd=os.memfd_create('voice-completion-reentry-test',0)
try:
 cmd=['/usr/bin/g++','-std=c++17','-Os','-pipe','-x','c++','-','-o',f'/proc/self/fd/{fd}'];p=subprocess.run(cmd,input=body.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,pass_fds=(fd,),timeout=60)
 if p.returncode:print(p.stderr.decode(errors='replace'));raise SystemExit(p.returncode)
 rd=os.open(f'/proc/self/fd/{fd}',os.O_RDONLY);os.close(fd);fd=-1
 try:r=subprocess.run([f'/proc/self/fd/{rd}'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,pass_fds=(rd,),timeout=15)
 finally:os.close(rd)
 print(r.stdout.decode(errors='replace'),end='');print(r.stderr.decode(errors='replace'),end='',file=sys.stderr);print('SOURCE_SHA256',hashlib.sha256(header.read_bytes()).hexdigest());print('METHOD_SHA256',hashlib.sha256(method.encode()).hexdigest());print('TEST_EXIT',r.returncode);raise SystemExit(r.returncode)
finally:
 if fd>=0:os.close(fd)
