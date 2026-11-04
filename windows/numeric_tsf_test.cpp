// Actual production TSF against generated host text; no calculator replaces the OS IME.
#define main unused_baseline_main
#include "../tests/tsf_edit_test.cpp"
#undef main
#include <functional>
struct WindowsPersonalTestAccess {static void mathMode(TextService* s,bool enabled){s->math=enabled;}static std::string annotation(TextService* s){return s->candidates.empty()?"":s->candidates.front().annotation;}};
static int passed=0,failed=0;
static void expect(bool ok){if(!ok)throw std::runtime_error("numeric TSF assertion");}
static void test(const char* label,const std::function<void()>& body){try{body();++passed;std::cout<<"PASS "<<label<<std::endl;}catch(const std::exception& e){++failed;std::cout<<"FAIL "<<label<<" :: "<<e.what()<<std::endl;}}
struct NumericFixture {
 TextService* s=new TextService;Manager* m=new Manager;Context* c=new Context;
 NumericFixture(){moduleHandle=GetModuleHandleW(nullptr);m->supportsUI=true;m->document=new DocumentManager(c);c->documentManager=m->document;expect(SUCCEEDED(s->ActivateEx(m,303,TF_TMAE_UIELEMENTENABLEDONLY)));WindowsPersonalTestAccess::mathMode(s,true);c->doc.value=L"1/3+1/6=tail";c->doc.cursor=8;}
 ~NumericFixture(){c->flush();s->Deactivate();s->Release();c->Release();m->Release();}
 void release(WPARAM key=VK_OEM_PLUS){BOOL eaten=TRUE;s->OnTestKeyUp(c,key,0,&eaten);expect(!eaten);}
 ITfCandidateListUIElementBehavior* ui(){ITfCandidateListUIElementBehavior* out=nullptr;expect(m->element&&SUCCEEDED(m->element->QueryInterface(IID_ITfCandidateListUIElementBehavior,reinterpret_cast<void**>(&out))));return out;}
 HRESULT choose(){auto p=ui();auto h=p->Finalize();p->Release();return h;}
 std::wstring first(){auto p=ui();BSTR text=nullptr;expect(SUCCEEDED(p->GetString(0,&text)));std::wstring answer(text,SysStringLen(text));SysFreeString(text);p->Release();return answer;}
};
static int runNumericChecks(){
 test("equal release is read only and remains a host key",[]{NumericFixture f;auto before=f.c->doc.value;f.release();expect(f.c->doc.value==before&&f.c->doc.cursor==8);});
 test("math mode exposes exact rational candidate in real TSF UIElement",[]{NumericFixture f;f.release();expect(f.first()==L"1/2");});
 test("explicit Finalize appends result and preserves expression and suffix",[]{NumericFixture f;f.release();expect(SUCCEEDED(f.choose())&&f.c->doc.value==L"1/3+1/6=1/2tail"&&f.c->doc.cursor==11);});
 test("math mode off exposes no implicit calculator",[]{NumericFixture f;WindowsPersonalTestAccess::mathMode(f.s,false);f.release();expect(!f.m->element&&f.c->doc.value==L"1/3+1/6=tail");});
 test("moved caret refuses old numeric candidate",[]{NumericFixture f;f.release();auto p=f.ui();f.c->doc.cursor=1;auto before=f.c->doc.value;expect(FAILED(p->Finalize())&&f.c->doc.value==before&&f.c->doc.cursor==1);p->Release();});
 test("Abort is read only even when the host has a new selection",[]{NumericFixture f;f.release();auto p=f.ui();f.c->doc.cursor=1;f.c->doc.selectionEnd=4;auto before=f.c->doc.value;auto h=p->Abort();p->Release();expect(SUCCEEDED(h)&&f.c->doc.value==before&&f.c->doc.cursor==1&&f.c->doc.selectionEnd==4);});
 test("two queued choices cannot clear a newer pending calculation",[]{NumericFixture f;f.release();auto p=f.ui();f.c->deferred=true;auto first=p->Finalize(),second=p->Finalize();p->Release();auto values=f.c->flush();expect(SUCCEEDED(first)&&SUCCEEDED(second)&&values.size()==2&&FAILED(values[0])&&SUCCEEDED(values[1])&&f.c->doc.value==L"1/3+1/6=1/2tail");});
 test("same-length host edit refuses old result",[]{NumericFixture f;f.release();f.c->doc.value[0]=L'2';auto before=f.c->doc.value;expect(FAILED(f.choose())&&f.c->doc.value==before);});
 test("new reverse selection cannot be overwritten",[]{NumericFixture f;f.release();f.c->doc.cursor=9;f.c->doc.selectionEnd=7;auto before=f.c->doc.value;expect(FAILED(f.choose())&&f.c->doc.value==before&&f.c->doc.cursor==9&&f.c->doc.selectionEnd==7);});
 test("async completion validates actual current caret",[]{NumericFixture f;f.release();f.c->deferred=true;expect(SUCCEEDED(f.choose()));f.c->doc.cursor=2;auto before=f.c->doc.value;auto results=f.c->flush();expect(results.size()==1&&FAILED(results[0])&&f.c->doc.value==before);});
 test("host focus loss cancels queued calculation",[]{NumericFixture f;f.release();f.c->deferred=true;expect(SUCCEEDED(f.choose()));f.s->OnSetFocus(FALSE);auto before=f.c->doc.value;auto results=f.c->flush();expect(results.size()==1&&FAILED(results[0])&&f.c->doc.value==before);});
 test("later query replaces old UI proof",[]{NumericFixture f;f.release();auto old=f.ui();f.c->doc.value=L"2+2=tail";f.c->doc.cursor=4;f.release();auto before=f.c->doc.value;auto refused=old->Finalize();old->Release();expect(FAILED(refused)&&f.c->doc.value==before&&f.first()==L"4"&&SUCCEEDED(f.choose())&&f.c->doc.value==L"2+2=4tail");});
 test("repeated DoEditSession cannot repeat numeric insertion",[]{NumericFixture f;f.release();f.c->deferred=true;expect(SUCCEEDED(f.choose())&&f.c->queued.size()==1);auto callback=f.c->queued.front();callback->AddRef();auto first=callback->DoEditSession(99),second=callback->DoEditSession(99);callback->Release();f.c->flush();expect(SUCCEEDED(first)&&FAILED(second)&&f.c->doc.value==L"1/3+1/6=1/2tail");});
 test("unreadable full context refuses numeric preview",[]{NumericFixture f;f.c->doc.noFull=true;f.release();expect(!f.m->element);});
 test("unreadable after preview refuses numeric insertion",[]{NumericFixture f;f.release();f.c->doc.noRead=true;auto before=f.c->doc.value;expect(FAILED(f.choose())&&f.c->doc.value==before);});
 test("PRIVATE input never exposes numeric preview",[]{NumericFixture f;f.c->scope=IS_PRIVATE;f.release();expect(!f.m->element);});
 test("password transition rejects numeric commit",[]{NumericFixture f;f.release();f.c->scope=IS_PASSWORD;auto before=f.c->doc.value;expect(FAILED(f.choose())&&f.c->doc.value==before);});
 test("PRIVATE transition during preview capture fails closed",[]{NumericFixture f;f.c->doc.onRead=[&]{f.c->scope=IS_PRIVATE;};f.release();expect(!f.m->element);});
 test("read callback focus reentry cannot publish preview",[]{NumericFixture f;f.c->afterRead=[&]{f.s->OnSetFocus(FALSE);};f.release();expect(!f.m->element);});
 test("write snapshot focus reentry cannot insert",[]{NumericFixture f;f.release();f.c->doc.onRead=[&]{f.s->OnSetFocus(FALSE);};auto before=f.c->doc.value;expect(FAILED(f.choose())&&f.c->doc.value==before);});
 test("refused write does not auto retry",[]{NumericFixture f;f.release();f.c->doc.denyText=true;auto before=f.c->doc.value;expect(FAILED(f.choose())&&f.c->doc.value==before&&!f.m->element);});
 test("uncertain applied write cannot be duplicated",[]{NumericFixture f;f.release();auto old=f.ui();f.c->doc.writeThenFail=true;auto result=old->Finalize();f.c->doc.writeThenFail=false;auto second=old->Finalize();old->Release();expect(FAILED(result)&&FAILED(second)&&f.c->doc.value==L"1/3+1/6=1/2tail");});
 test("caret refusal after applied value is not replayed",[]{NumericFixture f;f.release();f.c->doc.denySelection=true;expect(SUCCEEDED(f.choose())&&f.c->doc.value==L"1/3+1/6=1/2tail"&&!f.m->element);});
 test("Enter remains with host without auto accepting result",[]{NumericFixture f;f.release();BOOL eaten=TRUE;f.s->OnTestKeyDown(f.c,VK_RETURN,0,&eaten);auto passed=!eaten;f.s->OnKeyDown(f.c,VK_RETURN,0,&eaten);expect(passed&&!eaten&&f.c->doc.value==L"1/3+1/6=tail");});
 test("Space remains with host without auto accepting result",[]{NumericFixture f;f.release();BOOL eaten=TRUE;f.s->OnTestKeyDown(f.c,VK_SPACE,0,&eaten);expect(!eaten&&!f.m->element&&f.c->doc.value==L"1/3+1/6=tail");});
 test("cursor-key release can request result at existing equals",[]{NumericFixture f;f.release(VK_LEFT);expect(f.first()==L"1/2"&&f.c->doc.value==L"1/3+1/6=tail");});
 test("prior context and suffix remain exact",[]{NumericFixture f;f.c->doc.value=L"a=j b=1 c=h\n(2+3)*4=tail";f.c->doc.cursor=23;f.c->doc.cursor=f.c->doc.value.find(L"tail");f.release();expect(f.first()==L"20"&&SUCCEEDED(f.choose())&&f.c->doc.value==L"a=j b=1 c=h\n(2+3)*4=20tail");});
 test("unknown symbols without definitions do not guess variable values",[]{NumericFixture f;f.c->doc.value=L"a²+b²−c=tail";f.c->doc.cursor=f.c->doc.value.find(L"tail");f.release();expect(!f.m->element);});
 for(auto fixture:std::vector<std::pair<std::wstring,std::wstring>>{{L"2+3*4=",L"14"},{L"(2+3)*4=",L"20"},{L"1/3+1/6=",L"1/2"},{L"0.1+0.2=",L"3/10"},{L"2^-3=",L"1/8"},{L"-2^2=",L"-4"},{L"(-2)^3=",L"-8"},{L"2^3^2=",L"512"},{L"2²^3=",L"64"},{L"2^3²=",L"512"},{L"3²+2³=",L"17"},{L"12÷4×3−1=",L"8"},{L"9007199254740991=",L"9007199254740991"}}){test("bounded provider exact arithmetic",[fixture]{auto result=wangshu::numeric::suggest(fixture.first);expect(result.ready&&result.text==fixture.second);});}
 for(auto expression:std::vector<std::wstring>{L"1/0=",L"0^0=",L"2^(1/2)=",L"10^16=",L"9007199254740991+1=",L"2^17=",L"2(3)=",L"a+1=",L"2²³=",L"system(1)=",L"1e3=",L"1+2",std::wstring(129,L'1')+L"=",std::wstring(25,L'(')+L"1"+std::wstring(25,L')')+L"="}){test("bounded provider refuses unsupported input without a value",[expression]{auto result=wangshu::numeric::suggest(expression);expect(!result.ready&&result.text.empty());});}
 test("host update before preview publication rejects obsolete number",[]{NumericFixture f;f.c->afterRead=[&]{f.c->doc.value=L"2+2=tail";f.c->doc.cursor=4;};f.release();expect(!f.m->element&&f.c->doc.value==L"2+2=tail");});
 test("existing CtrlShiftM explicitly enables numeric association",[]{NumericFixture f;WindowsPersonalTestAccess::mathMode(f.s,false);BYTE old[256],keys[256];GetKeyboardState(old);std::copy_n(old,256,keys);keys[VK_CONTROL]|=0x80;keys[VK_SHIFT]|=0x80;SetKeyboardState(keys);BOOL eaten=FALSE;f.s->OnKeyDown(f.c,'M',0,&eaten);SetKeyboardState(old);expect(eaten);f.release();expect(f.first()==L"1/2");});
 test("applied value followed by focus callback does not move old caret",[]{NumericFixture f;f.release();f.c->doc.afterWrite=[&]{f.s->OnSetFocus(FALSE);};expect(SUCCEEDED(f.choose())&&f.c->doc.value==L"1/3+1/6=1/2tail"&&f.c->doc.cursor==8);});
 test("applied old value cannot clear newer preview from host reentry",[]{NumericFixture f;f.release();f.c->doc.afterWrite=[&]{f.c->doc.value=L"2+2=tail";f.c->doc.cursor=4;f.release();};expect(SUCCEEDED(f.choose())&&f.c->doc.value==L"2+2=tail"&&f.c->doc.cursor==4&&f.first()==L"4");});
 test("numeric fixtures release all COM ownership",[]{expect(objectCount==0);});
 std::cout<<"RESULT passed="<<passed<<" failed="<<failed<<" total="<<passed+failed<<"; actual production PE/mock TSF; real Windows UI NOT_RUN"<<std::endl;return failed?1:0;
}

#ifndef WANGSHU_NUMERIC_FIXTURES_ONLY
int main(){return runNumericChecks();}
#endif
