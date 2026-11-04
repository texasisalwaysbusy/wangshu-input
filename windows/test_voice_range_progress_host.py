#!/usr/bin/env python3
"""Exercise exact voice-position read using fault-injected TSF-like ranges, not Windows TSF."""
from pathlib import Path
import hashlib,os,subprocess,sys
header=Path(__file__).resolve().parent/'voice_position.h';source=header.read_text()
def extract(prefix):
 start=source.index(prefix);brace=source.index('{',start);depth=0
 for end in range(brace,len(source)):
  if source[end]=='{':depth+=1
  elif source[end]=='}':
   depth-=1
   if depth==0:return source[start:end+1]
 raise RuntimeError('method not found')
validate=extract('inline bool textValid(');read=extract('inline HRESULT read(')
body=r'''
#include <cstdint>
#include <iostream>
#include <memory>
#include <string>
#include <vector>
using HRESULT=int32_t;using LONG=int32_t;using ULONG=uint32_t;using WCHAR=wchar_t;using TfEditCookie=int;
constexpr HRESULT S_OK=0,S_FALSE=1,E_FAIL=-1;constexpr int TF_ANCHOR_START=0,TF_ANCHOR_END=1,TF_TF_MOVESTART=1;
#define FAILED(x) ((x)<0)
struct ITfRange{virtual ~ITfRange()=default;virtual HRESULT Clone(ITfRange**)=0;virtual unsigned Release()=0;virtual HRESULT CompareStart(TfEditCookie,ITfRange*,int,LONG*)=0;virtual HRESULT GetText(TfEditCookie,int,WCHAR*,ULONG,ULONG*)=0;virtual HRESULT ShiftStart(TfEditCookie,LONG,LONG*,const void*)=0;};
struct FakeRange:ITfRange{
 std::shared_ptr<std::wstring> text;size_t start=0,end=0;int advance=1;bool partial=false,zero=false;
 FakeRange(std::shared_ptr<std::wstring> x,int step=1,bool sfalse=false):text(x),end(x->size()),advance(step),partial(sfalse){}
 HRESULT Clone(ITfRange** out)override{*out=new FakeRange(*this);return S_OK;}
 unsigned Release()override{delete this;return 0;}
 HRESULT CompareStart(TfEditCookie,ITfRange* other,int anchor,LONG* order)override{auto* r=static_cast<FakeRange*>(other);auto b=anchor==TF_ANCHOR_END?r->end:r->start;*order=start<b?-1:start>b?1:0;return S_OK;}
 HRESULT ShiftStart(TfEditCookie,LONG count,LONG* shifted,const void*)override{auto old=start;start=std::min(end,start+static_cast<size_t>(count));*shifted=static_cast<LONG>(start-old);return S_OK;}
 HRESULT GetText(TfEditCookie,int,WCHAR* out,ULONG,ULONG* n)override{if(start>=end){*n=0;return S_FALSE;}out[0]=(*text)[start];*n=zero?0:1;start=std::min(end,start+advance);return partial?S_FALSE:S_OK;}
};
namespace wangshu::voice_position{
constexpr size_t MaxUnits=32768;
struct ReleaseRange{void operator()(ITfRange*p)const{if(p)p->Release();}};using Range=std::unique_ptr<ITfRange,ReleaseRange>;
inline HRESULT clone(ITfRange* from,Range& out){ITfRange* p=nullptr;HRESULT h=from->Clone(&p);out.reset(p);return FAILED(h)?h:p?S_OK:E_FAIL;}
''' + validate + '\n' + read + r'''
}
static unsigned pass=0,fail=0;
static void test(bool yes,const char* label){if(yes){++pass;std::cout<<"PASS "<<label<<"\n";}else{++fail;std::cout<<"FAIL "<<label<<"\n";}}
static HRESULT run(const std::wstring& value,std::wstring& out,int step=1,bool partial=false,bool zero=false){auto text=std::make_shared<std::wstring>(value);FakeRange r(text,step,partial);r.zero=zero;return wangshu::voice_position::read(0,&r,out);}
int main(){std::wstring a,b;
 test(run(L"ABCDEF",a)==S_OK&&a==L"ABCDEF","normal partial chunk covers all characters");
 test(run(L"ABCDEF",a,1,true)==S_OK&&a==L"ABCDEF","S_FALSE with positive partial text remains readable");
 test(run(std::wstring{wchar_t(0xd83d),wchar_t(0xde00),L'e',wchar_t(0x301)},a)==S_OK&&a.size()==4,"split surrogate pair and combining mark survive full read in synthetic WCHAR model");
 test(FAILED(run(L"ABCDEF",a,0)),"nonadvancing host is rejected");
 test(FAILED(run(L"ABCDEF",a,1,false,true)),"zero returned characters are rejected");
 HRESULT x=run(L"ABCDEF",a,2),y=run(L"AZCDEF",b,2);std::cout<<"OBS faulty overadvance statuses="<<x<<","<<y<<" same_snapshot="<<(a==b)<<"\n";
 test(FAILED(x)&&FAILED(y),"faulty host skipping returned characters is rejected");
 std::cout<<"RESULT passed="<<pass<<" failed="<<fail<<"; extracted production read; fake TSF range, Windows NOT_RUN\n";return fail?1:0;}
'''
fd=os.memfd_create('voice-range-progress-test',0)
try:
 cmd=['/usr/bin/g++','-std=c++17','-Os','-pipe','-x','c++','-','-o',f'/proc/self/fd/{fd}'];p=subprocess.run(cmd,input=body.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,pass_fds=(fd,),timeout=60)
 if p.returncode:print(p.stderr.decode(errors='replace'));raise SystemExit(p.returncode)
 rd=os.open(f'/proc/self/fd/{fd}',os.O_RDONLY);os.close(fd);fd=-1
 try:r=subprocess.run([f'/proc/self/fd/{rd}'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,pass_fds=(rd,),timeout=15)
 finally:os.close(rd)
 print(r.stdout.decode(errors='replace'),end='');print(r.stderr.decode(errors='replace'),end='',file=sys.stderr);print('HEADER_SHA256',hashlib.sha256(header.read_bytes()).hexdigest());print('READ_SHA256',hashlib.sha256(read.encode()).hexdigest());print('TEST_EXIT',r.returncode);raise SystemExit(r.returncode)
finally:
 if fd>=0:os.close(fd)
