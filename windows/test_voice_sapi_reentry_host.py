#!/usr/bin/env python3
"""Extract actual voiceEvents and model reentrant SAPI callbacks on Linux."""
from pathlib import Path
import hashlib, os, subprocess,sys
p=Path(__file__).resolve().parent/'text_service.cpp';s=p.read_text();a=s.index('    void voiceEvents(){');b=s.index('{',a);d=0
for e in range(b,len(s)):
 if s[e]=='{': d+=1
 elif s[e]=='}':
  d-=1
  if d==0:break
else:raise SystemExit('missing method')
method=s[a:e+1]
code=r'''
#include <cstdint>
#include <cstdlib>
#include <cwchar>
#include <functional>
#include <iostream>
#include <memory>
#include <string>
using ULONG=unsigned long;using HRESULT=int;
#define SUCCEEDED(x) ((x)>=0)
#define FAILED(x) ((x)<0)
#define TRUE 1
const int SPEI_RECOGNITION=1,SPET_LPARAM_IS_OBJECT=2,SPET_LPARAM_IS_POINTER=3,SPET_LPARAM_IS_STRING=4,SP_GETWHOLEPHRASE=0;
struct IUnknown{virtual ~IUnknown(){};virtual void Release(){}};
struct ISpRecoResult: IUnknown{int releases=0;void Release()override{releases++;}std::function<void()> callback;HRESULT GetText(int,int,int,wchar_t** out,void*){if(callback)callback();*out=(wchar_t*)std::malloc(6*sizeof(wchar_t));std::wcscpy(*out,L"stale");return 0;}};
struct SPEVENT{int eEventId=0,elParamType=0;intptr_t lParam=0;};
struct ISpRecoContext{ISpRecoResult* result;bool sent=false;int refs=1;void AddRef(){refs++;}void Release(){refs--;}std::function<void()> callback;int GetEvents(int,SPEVENT* e,ULONG* n){if(callback)callback();if(!sent){sent=true;e->eEventId=SPEI_RECOGNITION;e->elParamType=SPET_LPARAM_IS_OBJECT;e->lParam=(intptr_t)result;*n=1;}else *n=0;return 0;}};
struct ITfContext{int id;};template<class T>void drop(T*&p){p=nullptr;}void CoTaskMemFree(void*p){std::free(p);}std::string narrow(const wchar_t*s){return std::string(s,s+std::wcslen(s));}
struct Dictionary{std::string voiceMath(std::string s){return s;}};
struct TextService{ITfContext a{1},b{2};ITfContext* voiceTarget=&a;unsigned long long voiceGeneration=1;ISpRecoResult result;ISpRecoContext old{&result},next{&result};ISpRecoContext* recoContext=&old;bool listening=true,math=false;int reviews=0;Dictionary dict;
 ITfContext* focused(){return voiceTarget;}bool disabled(ITfContext*){return false;}Dictionary& dictionary(){return dict;}void reviewVoice(std::string){reviews++;}
''' + method + r'''
};
int main(){int pass=0,fail=0;auto check=[&](bool v,const char*n){std::cout<<(v?"PASS ":"FAIL ")<<n<<"\n";(v?pass:fail)++;};
 {TextService t;t.voiceEvents();check(t.reviews==1,"current SAPI result reaches review");}
 {TextService t;t.result.callback=[&]{t.voiceTarget=&t.b;t.voiceGeneration++;t.recoContext=&t.next;};t.voiceEvents();check(t.reviews==0&&t.voiceTarget==&t.b&&t.old.refs==1&&t.result.releases==1,"old GetText result discarded and context/event released");}
 {TextService t;t.old.callback=[&]{t.voiceTarget=&t.b;t.voiceGeneration++;t.recoContext=&t.next;};t.voiceEvents();check(t.reviews==0&&t.voiceTarget==&t.b&&t.old.refs==1&&t.result.releases==1,"stale fetched event discarded and context/event released");}
 std::cout<<"RESULT passed="<<pass<<" failed="<<fail<<"; fake COM only; real Windows TSF/SAPI NOT_RUN\n";return fail?1:0;}
'''
fd=os.memfd_create('voice-sapi-reentry',0)
try:
 r=subprocess.run(['/usr/bin/g++','-std=c++17','-Os','-pipe','-x','c++','-','-o',f'/proc/self/fd/{fd}'],input=code.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,pass_fds=(fd,),timeout=60)
 if r.returncode: print(r.stderr.decode(),file=sys.stderr);raise SystemExit(r.returncode)
 rd=os.open(f'/proc/self/fd/{fd}',os.O_RDONLY);os.close(fd);fd=-1
 try:r=subprocess.run([f'/proc/self/fd/{rd}'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,pass_fds=(rd,),timeout=15)
 finally:os.close(rd)
 print(r.stdout.decode(),end='');print('SOURCE_SHA256',hashlib.sha256(p.read_bytes()).hexdigest());print('METHOD_SHA256',hashlib.sha256(method.encode()).hexdigest());print('TEST_EXIT',r.returncode);raise SystemExit(r.returncode)
finally:
 if fd>=0:os.close(fd)
