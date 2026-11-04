// Generated speech only; actual production TSF with a complete, mutable text-store fixture.
#define main unused_tsf_baseline
#include "../tests/tsf_edit_test.cpp"
#undef main
#include <functional>
static int passed=0,failed=0;
static void test(const char* name,const std::function<void()>& fn){try{fn();++passed;std::cout<<"PASS "<<name<<std::endl;}catch(const std::exception& e){++failed;std::cout<<"FAIL "<<name<<" :: "<<e.what()<<std::endl;}}
static void expect(bool ok){if(!ok)throw std::runtime_error("guard/host preservation assertion");}
struct Fixture {
 TextService* s=new TextService;Manager* m=new Manager;Context* c=new Context;
 Fixture(){moduleHandle=GetModuleHandleW(nullptr);m->supportsUI=true;m->document=new DocumentManager(c);c->documentManager=m->document;expect(SUCCEEDED(s->Activate(m,203)));}
 ~Fixture(){c->flush();s->Deactivate();s->Release();c->Release();m->Release();}
 void stage(){TextServiceTestAccess::stageVoice(s,c,"speech");}
 bool enter(){BOOL eat=FALSE;s->OnKeyDown(c,VK_RETURN,0,&eat);return eat;}
 void unchanged(){auto text=c->doc.value;auto cursor=c->doc.cursor;auto end=c->doc.selectionEnd;enter();expect(c->doc.value==text&&c->doc.cursor==cursor&&c->doc.selectionEnd==end);}
};
int main(){
 test("unchanged preview commits once",[]{Fixture f;f.stage();expect(f.enter()&&f.c->doc.value==L"beforespeechafter");});
 test("caret moved before original position refuses old speech",[]{Fixture f;f.stage();f.c->doc.cursor=1;f.unchanged();});
 test("caret moved after original position refuses old speech",[]{Fixture f;f.stage();f.c->doc.cursor=10;f.unchanged();});
 test("new forward selection cannot be overwritten by speech",[]{Fixture f;f.stage();f.c->doc.cursor=1;f.c->doc.selectionEnd=4;f.unchanged();});
 test("new reverse selection cannot be overwritten by speech",[]{Fixture f;f.stage();f.c->doc.cursor=9;f.c->doc.selectionEnd=7;f.unchanged();});
 test("same length prefix edit rejects stale speech",[]{Fixture f;f.stage();f.c->doc.value[0]=L'X';f.unchanged();});
 test("suffix edit rejects stale speech",[]{Fixture f;f.stage();f.c->doc.value+=L"!";f.unchanged();});
 test("unavailable complete host state refuses speech",[]{Fixture f;f.stage();f.c->doc.noFull=true;f.unchanged();});
 test("unreadable host text refuses speech",[]{Fixture f;f.stage();f.c->doc.noRead=true;f.unchanged();});
 test("non advancing range refuses speech",[]{Fixture f;f.stage();f.c->doc.ignoreMove=true;f.unchanged();});
 test("async callback checks caret at actual execution",[]{Fixture f;f.stage();f.c->deferred=true;expect(TextServiceTestAccess::click(f.s,f.c,"speech"));f.c->doc.cursor=2;auto before=f.c->doc.value;auto result=f.c->flush();expect(result.size()==1&&FAILED(result[0])&&f.c->doc.value==before);});
 test("async callback checks text at actual execution",[]{Fixture f;f.stage();f.c->deferred=true;expect(TextServiceTestAccess::click(f.s,f.c,"speech"));f.c->doc.value[0]=L'Z';auto before=f.c->doc.value;auto result=f.c->flush();expect(result.size()==1&&FAILED(result[0])&&f.c->doc.value==before);});
 test("write refusal does not discard valid review",[]{Fixture f;f.stage();f.c->doc.denyText=true;f.unchanged();expect(TextServiceTestAccess::hasVoice(f.s));});
 test("beginning-of-listening snapshot survives until recognition result",[]{Fixture f;expect(TextServiceTestAccess::beginVoice(f.s,f.c));f.c->doc.cursor=2;auto before=f.c->doc.value;TextServiceTestAccess::resultVoice(f.s,"heard");expect(!TextServiceTestAccess::hasVoice(f.s)&&TextServiceTestAccess::recovery(f.s)&&f.c->doc.value==before);});
 test("start refuses unsupported complete context",[]{Fixture f;f.c->doc.noFull=true;expect(!TextServiceTestAccess::beginVoice(f.s,f.c));});
 test("start refuses noncollapsed selection",[]{Fixture f;f.c->doc.selectionEnd=8;expect(!TextServiceTestAccess::beginVoice(f.s,f.c));});
 test("start refuses oversized context without truncating",[]{Fixture f;f.c->doc.value.assign(32769,L'x');expect(!TextServiceTestAccess::beginVoice(f.s,f.c));});
 test("exact 32768-unit context remains supported",[]{Fixture f;f.c->doc.value.assign(32768,L'x');expect(TextServiceTestAccess::stageVoice(f.s,f.c,"ok")&&f.enter()&&f.c->doc.value.size()==32770);});
 test("complete empty context supports speech",[]{Fixture f;f.c->doc.value.clear();f.c->doc.cursor=0;expect(TextServiceTestAccess::stageVoice(f.s,f.c,"ok")&&f.enter()&&f.c->doc.value==L"ok");});
 test("non BMP and combining context is preserved",[]{Fixture f;f.c->doc.value=L"𝕏ésuffix";f.c->doc.cursor=4;expect(TextServiceTestAccess::stageVoice(f.s,f.c,"ok")&&f.enter()&&f.c->doc.value==L"𝕏éoksuffix");});
 test("caret splitting surrogate pair refuses capture",[]{Fixture f;f.c->doc.value=L"𝕏suffix";f.c->doc.cursor=1;expect(!TextServiceTestAccess::beginVoice(f.s,f.c));});
 test("embedded rich object refuses plain text position assumption",[]{Fixture f;f.c->doc.value=L"ab\ufffccd";f.c->doc.cursor=2;expect(!TextServiceTestAccess::beginVoice(f.s,f.c));});
 test("mismatch retains result but repeated Enter cannot submit it",[]{Fixture f;f.stage();f.c->doc.cursor=2;f.unchanged();expect(TextServiceTestAccess::recovery(f.s)&&!TextServiceTestAccess::hasVoice(f.s));f.unchanged();});
 test("explicit rebind does not insert; separate confirm inserts at new position",[]{Fixture f;f.stage();f.c->doc.cursor=2;f.unchanged();auto before=f.c->doc.value;expect(TextServiceTestAccess::rebind(f.s,f.c)&&f.c->doc.value==before);expect(f.enter()&&f.c->doc.value==L"bespeechforeafter");});
 test("unreadable rebind preserves result and refuses insertion",[]{Fixture f;f.stage();f.c->doc.cursor=2;f.unchanged();f.c->doc.noFull=true;expect(!TextServiceTestAccess::rebind(f.s,f.c)&&TextServiceTestAccess::recovery(f.s));f.unchanged();});
 test("private rebind clears result without reading host text",[]{Fixture f;f.stage();f.c->doc.cursor=2;f.unchanged();f.c->scope=IS_PRIVATE;f.c->doc.noRead=true;expect(!TextServiceTestAccess::rebind(f.s,f.c)&&!TextServiceTestAccess::recovery(f.s));f.unchanged();});
 test("focus loss unbinds and retains only recognized result",[]{Fixture f;f.stage();f.s->OnSetFocus(FALSE);expect(!TextServiceTestAccess::hasVoice(f.s)&&TextServiceTestAccess::recovery(f.s));f.unchanged();expect(TextServiceTestAccess::rebind(f.s,f.c)&&f.enter());});
 test("old queued result cannot consume new voice session",[]{Fixture f;f.stage();f.c->deferred=true;expect(TextServiceTestAccess::click(f.s,f.c,"speech"));expect(TextServiceTestAccess::stageVoice(f.s,f.c,"new"));auto before=f.c->doc.value;auto results=f.c->flush();expect(results.size()==1&&FAILED(results[0])&&f.c->doc.value==before&&TextServiceTestAccess::hasVoice(f.s));f.c->deferred=false;expect(f.enter()&&f.c->doc.value==L"beforenewafter");});
 test("Escape explicitly discards recognized and recoverable text",[]{Fixture f;f.stage();BOOL eat=FALSE;f.s->OnKeyDown(f.c,VK_ESCAPE,0,&eat);expect(eat&&!TextServiceTestAccess::hasVoice(f.s)&&!TextServiceTestAccess::recovery(f.s));});
 test("rejected confirmation stays consumed instead of forwarding Enter",[]{Fixture f;f.stage();f.c->doc.cursor=2;expect(f.enter());});
 test("write refusal confirmation does not forward Space or Enter",[]{Fixture f;f.stage();f.c->doc.denyText=true;expect(f.enter()&&TextServiceTestAccess::hasVoice(f.s));});
 test("write applied but error returned is never blindly duplicated",[]{Fixture f;f.stage();f.c->doc.writeThenFail=true;f.enter();auto after=f.c->doc.value;f.c->doc.writeThenFail=false;f.enter();expect(f.c->doc.value==after&&after==L"beforespeechafter"&&TextServiceTestAccess::recovery(f.s));});
 test("focus callback during capture invalidates new binding",[]{Fixture f;bool fired=false;f.c->afterRead=[&]{fired=true;f.s->OnSetFocus(FALSE);};expect(!TextServiceTestAccess::beginVoice(f.s,f.c)&&fired);});
 test("focus reentry during completion read retains result without dereference",[]{Fixture f;expect(TextServiceTestAccess::beginVoice(f.s,f.c));bool fired=false;f.c->doc.onRead=[&]{fired=true;f.s->OnSetFocus(FALSE);};TextServiceTestAccess::resultVoice(f.s,"heard");expect(fired&&!TextServiceTestAccess::hasVoice(f.s)&&TextServiceTestAccess::recovery(f.s)&&f.c->doc.value==L"beforeafter");});
 test("focus reentry during write-cookie snapshot refuses old speech",[]{Fixture f;f.stage();bool fired=false;f.c->doc.onRead=[&]{fired=true;f.s->OnSetFocus(FALSE);};f.unchanged();expect(fired&&!TextServiceTestAccess::hasVoice(f.s)&&TextServiceTestAccess::recovery(f.s));});
 test("unreported context focus change rejects previous target",[]{Fixture f;f.stage();auto other=new Context;drop(f.m->document);f.m->document=new DocumentManager(other);other->Release();f.unchanged();expect(TextServiceTestAccess::recovery(f.s));});
 test("private scope at recognition completion clears generated result",[]{Fixture f;expect(TextServiceTestAccess::beginVoice(f.s,f.c));f.c->scope=IS_PRIVATE;f.c->doc.noRead=true;TextServiceTestAccess::resultVoice(f.s,"heard");expect(!TextServiceTestAccess::hasVoice(f.s)&&!TextServiceTestAccess::recovery(f.s));});
 test("scope becoming PRIVATE during capture clears active review",[]{Fixture f;f.stage();bool fired=false;f.c->doc.onRead=[&]{fired=true;f.c->scope=IS_PRIVATE;};f.unchanged();expect(fired&&!TextServiceTestAccess::hasVoice(f.s)&&!TextServiceTestAccess::recovery(f.s));});
 test("PRIVATE change during initial capture refuses binding",[]{Fixture f;bool fired=false;f.c->doc.onRead=[&]{fired=true;f.c->scope=IS_PRIVATE;};expect(!TextServiceTestAccess::beginVoice(f.s,f.c)&&fired);});
 test("PRIVATE change during completion capture discards recognized result",[]{Fixture f;expect(TextServiceTestAccess::beginVoice(f.s,f.c));bool fired=false;f.c->doc.onRead=[&]{fired=true;f.c->scope=IS_PRIVATE;};TextServiceTestAccess::resultVoice(f.s,"heard");expect(fired&&!TextServiceTestAccess::hasVoice(f.s)&&!TextServiceTestAccess::recovery(f.s));});
 test("unreadable selection is unknown and retains recoverable result",[]{Fixture f;f.stage();f.c->doc.noSelection=true;f.unchanged();expect(!TextServiceTestAccess::hasVoice(f.s)&&TextServiceTestAccess::recovery(f.s));});
 test("fixture COM references release",[]{expect(objectCount==0);});
 std::cout<<"RESULT passed="<<passed<<" failed="<<failed<<" total="<<passed+failed<<"; production PE/mock TSF, generated speech; real Windows/microphone NOT_RUN"<<std::endl;return failed?1:0;
}
