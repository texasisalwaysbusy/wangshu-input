#pragma once
#include <msctf.h>
#include <string>
#include <memory>
// Only in-memory, short-lived confirmation state. Never serialize or diagnose text.
namespace wangshu::voice_position {
constexpr size_t MaxUnits=32768;
struct Snapshot {std::wstring text;size_t caret=0;bool operator==(const Snapshot& rhs)const{return caret==rhs.caret&&text==rhs.text;}};
struct ReleaseRange {void operator()(ITfRange* p)const{if(p)p->Release();}};
using Range=std::unique_ptr<ITfRange,ReleaseRange>;
inline HRESULT clone(ITfRange* from,Range& out){ITfRange* p=nullptr;HRESULT h=from->Clone(&p);out.reset(p);return FAILED(h)?h:p?S_OK:E_FAIL;}
inline bool textValid(const std::wstring& text){
 for(size_t i=0;i<text.size();++i){unsigned c=text[i];if(!c||c==0xfffc)return false;if(c>=0xd800&&c<=0xdbff){if(++i==text.size()||text[i]<0xdc00||text[i]>0xdfff)return false;}else if(c>=0xdc00&&c<=0xdfff)return false;}return true;
}
// TSF moving reads must prove progress and reach the exact known end. No IGNOREEND.
inline HRESULT read(TfEditCookie cookie,ITfRange* source,std::wstring& out){
 Range cursor;if(FAILED(clone(source,cursor)))return E_FAIL;out.clear();
 for(unsigned call=0;call<64;++call){LONG relation=0;if(FAILED(cursor->CompareStart(cookie,source,TF_ANCHOR_END,&relation)))return E_FAIL;if(relation==0)return textValid(out)?S_OK:E_FAIL;if(relation>0)return E_FAIL;
  Range before;if(FAILED(clone(cursor.get(),before)))return E_FAIL;
  WCHAR chunk[2048];ULONG n=0;HRESULT h=cursor->GetText(cookie,TF_TF_MOVESTART,chunk,2048,&n);if(FAILED(h))return h;
  if(!n||n>2048||out.size()+n>MaxUnits)return E_FAIL;
  LONG progress=0,overshoot=0;if(FAILED(cursor->CompareStart(cookie,before.get(),TF_ANCHOR_START,&progress))||progress<=0||FAILED(cursor->CompareStart(cookie,source,TF_ANCHOR_END,&overshoot))||overshoot>0)return E_FAIL;
  // Independently move a clone by the returned UTF-16 unit count. A moving
  // GetText that silently skips units must not create a trustworthy snapshot.
  LONG shifted=0,exact=1;
  if(FAILED(before->ShiftStart(cookie,static_cast<LONG>(n),&shifted,nullptr))||shifted!=static_cast<LONG>(n)||FAILED(cursor->CompareStart(cookie,before.get(),TF_ANCHOR_START,&exact))||exact)return E_FAIL;
  out.append(chunk,n);
 }return E_FAIL;
}
// Caller must hold a TSF read/write cookie and reject sensitive scopes BEFORE capture.
inline HRESULT capture(ITfContext* context,TfEditCookie cookie,Snapshot& out){
 if(!context)return E_POINTER;
 TF_SELECTION selected{};ULONG count=0;HRESULT h=context->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&selected,&count);Range selection(selected.range);
 if(FAILED(h)||count!=1||!selection||selected.style.fInterimChar)return E_FAIL;
 LONG relation=1;if(FAILED(selection->CompareStart(cookie,selection.get(),TF_ANCHOR_END,&relation))||relation)return E_ABORT;
 ITfRange *a=nullptr,*b=nullptr;h=context->GetStart(cookie,&a);Range start(a);if(FAILED(h)||!start)return E_FAIL;
 h=context->GetEnd(cookie,&b);Range end(b);if(FAILED(h)||!end)return E_FAIL;
 if(FAILED(start->CompareStart(cookie,selection.get(),TF_ANCHOR_START,&relation))||relation>0||FAILED(end->CompareEnd(cookie,selection.get(),TF_ANCHOR_END,&relation))||relation<0)return E_FAIL;
 Range full,prefix;if(FAILED(clone(start.get(),full))||FAILED(clone(start.get(),prefix)))return E_FAIL;
 if(FAILED(full->ShiftEndToRange(cookie,end.get(),TF_ANCHOR_END))||FAILED(prefix->ShiftEndToRange(cookie,selection.get(),TF_ANCHOR_START)))return E_FAIL;
 Snapshot fresh;std::wstring before;if(FAILED(read(cookie,full.get(),fresh.text))||FAILED(read(cookie,prefix.get(),before)))return E_FAIL;
 fresh.caret=before.size();if(fresh.caret>fresh.text.size()||fresh.text.compare(0,before.size(),before)!=0)return E_FAIL;
 // The cookie is the host's consistency lock; still reject inconsistent selection providers.
 TF_SELECTION after{};count=0;h=context->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&after,&count);Range again(after.range);
 if(FAILED(h)||count!=1||!again||after.style.fInterimChar||FAILED(again->CompareStart(cookie,selection.get(),TF_ANCHOR_START,&relation))||relation||FAILED(again->CompareEnd(cookie,selection.get(),TF_ANCHOR_END,&relation))||relation)return E_FAIL;
 out=std::move(fresh);return S_OK;
}
}
