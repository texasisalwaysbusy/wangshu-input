// Generated documents, actual production COM candidate entry points, existing Wine.
#define WANGSHU_NUMERIC_FIXTURES_ONLY
#include "numeric_tsf_test.cpp"

struct ReleaseCandidate {void operator()(ITfCandidateListUIElementBehavior* p)const{if(p)p->Release();}};
static std::unique_ptr<ITfCandidateListUIElementBehavior,ReleaseCandidate> keep(ITfCandidateListUIElementBehavior* p){return std::unique_ptr<ITfCandidateListUIElementBehavior,ReleaseCandidate>(p);}
static void symbolic(NumericFixture& f,const std::wstring& text=L"a=j,b=1,c=h\na²+b²−c=tail"){
 f.c->doc.value=text;f.c->doc.cursor=static_cast<LONG>(text.find(L"tail"));
}
int main(){
 if(unused_baseline_main())return 2;
 runNumericChecks();
 const int initialPassed=passed,initialFailed=failed;
 test("substitution preview is read only and visibly distinguished",[]{NumericFixture f;symbolic(f);auto original=f.c->doc.value;f.release();expect(f.first()==L"j²+1²−h"&&f.c->doc.value==original);auto label=WindowsPersonalTestAccess::annotation(f.s);expect(label.find("代入")!=std::string::npos&&label.find("精确结果")==std::string::npos);});
 test("explicit TSF Finalize appends substitution preserving definitions formula and suffix",[]{NumericFixture f;symbolic(f);auto original=f.c->doc.value;auto caret=f.c->doc.cursor;f.release();expect(SUCCEEDED(f.choose())&&f.c->doc.value==original.substr(0,caret)+L"j²+1²−h"+original.substr(caret)&&f.c->doc.cursor==caret+7);});
 test("space separated explicit definitions support substitution",[]{NumericFixture f;symbolic(f,L"a=j b=1 c=h\na²+b²−c=tail");f.release();expect(f.first()==L"j²+1²−h");});
 test("numeric context resolves to exact rational with value label",[]{NumericFixture f;symbolic(f,L"a=1/3;b=1/6\na+b=tail");f.release();auto label=WindowsPersonalTestAccess::annotation(f.s);expect(f.first()==L"1/2"&&label.find("精确结果")!=std::string::npos&&label.find("代入")==std::string::npos);});
 test("substitution preserves required parentheses",[]{NumericFixture f;symbolic(f,L"a=j+1\na²=tail");f.release();expect(f.first()==L"(j+1)²");});
 test("unknown names remain symbolic after defined name is substituted",[]{NumericFixture f;symbolic(f,L"a=j\na+z=tail");f.release();expect(f.first()==L"j+z");});
 test("definition mutation refuses old substitution even at unchanged caret",[]{NumericFixture f;symbolic(f);f.release();auto p=keep(f.ui());f.c->doc.value[2]=L'k';auto before=f.c->doc.value;expect(FAILED(p->Finalize())&&f.c->doc.value==before);p.reset();});
 test("queued selection rechecks changed definition at actual write cookie",[]{NumericFixture f;symbolic(f);f.release();f.c->deferred=true;expect(SUCCEEDED(f.choose()));f.c->doc.value[2]=L'k';auto before=f.c->doc.value;auto results=f.c->flush();expect(results.size()==1&&FAILED(results[0])&&f.c->doc.value==before);});
 test("definition changed during capture is not published",[]{NumericFixture f;symbolic(f);f.c->afterRead=[&]{f.c->doc.value[2]=L'k';};f.release();expect(!f.m->element&&f.c->doc.value[2]==L'k');});
 test("focus loss detaches old symbolic UI",[]{NumericFixture f;symbolic(f);f.release();auto p=keep(f.ui());auto before=f.c->doc.value;f.s->OnSetFocus(FALSE);expect(FAILED(p->Finalize())&&f.c->doc.value==before);p.reset();});
 test("forward selection after preview cannot be overwritten",[]{NumericFixture f;symbolic(f);f.release();f.c->doc.cursor=1;f.c->doc.selectionEnd=5;auto before=f.c->doc.value;expect(FAILED(f.choose())&&f.c->doc.value==before&&f.c->doc.cursor==1&&f.c->doc.selectionEnd==5);});
 test("reverse selection after preview cannot be overwritten",[]{NumericFixture f;symbolic(f);f.release();f.c->doc.cursor=5;f.c->doc.selectionEnd=1;auto before=f.c->doc.value;expect(FAILED(f.choose())&&f.c->doc.value==before&&f.c->doc.cursor==5&&f.c->doc.selectionEnd==1);});
 test("existing selected text refuses symbolic preview",[]{NumericFixture f;symbolic(f);f.c->doc.selectionEnd=1;f.release();expect(!f.m->element);});
 test("PRIVATE field does not read or expose contextual math",[]{NumericFixture f;symbolic(f);f.c->scope=IS_PRIVATE;bool read=false;f.c->doc.onRead=[&]{read=true;};f.release();expect(!f.m->element&&!read);});
 test("PIN field does not expose contextual math",[]{NumericFixture f;symbolic(f);f.c->scope=IS_NUMERIC_PIN;f.release();expect(!f.m->element);});
 test("privacy change after preview rejects contextual choice",[]{NumericFixture f;symbolic(f);f.release();f.c->scope=IS_PRIVATE;auto before=f.c->doc.value;expect(FAILED(f.choose())&&f.c->doc.value==before);});
 test("unreadable host refuses contextual capture",[]{NumericFixture f;symbolic(f);f.c->doc.noFull=true;f.release();expect(!f.m->element);});
 test("unreadable host at selection refuses contextual insertion",[]{NumericFixture f;symbolic(f);f.release();f.c->doc.noRead=true;auto before=f.c->doc.value;expect(FAILED(f.choose())&&f.c->doc.value==before);});
 for(auto text:{L"a=j;a=k\na+1=tail",L"a=b;b=a\na+1=tail",L"a=1/0\na+1=tail",L"a²+b=tail"})test("conflict cycle invalid arithmetic or undefined expression has no host candidate",[text]{NumericFixture f;symbolic(f,text);f.release();expect(!f.m->element);});
 test("oversized context is refused without truncating definitions",[]{NumericFixture f;symbolic(f,L"a=j;"+std::wstring(1024,L' ')+L"\na+1=tail");f.release();expect(!f.m->element);});
 test("new symbolic query detaches old numeric proof",[]{NumericFixture f;f.release();auto old=keep(f.ui());symbolic(f);f.release();auto before=f.c->doc.value;expect(FAILED(old->Finalize())&&f.c->doc.value==before&&f.first()==L"j²+1²−h");old.reset();});
 test("new numeric query detaches old symbolic proof and resets kind",[]{NumericFixture f;symbolic(f);f.release();auto old=keep(f.ui());f.c->doc.value=L"2+2=tail";f.c->doc.cursor=4;f.release();auto before=f.c->doc.value;expect(FAILED(old->Finalize())&&f.c->doc.value==before&&f.first()==L"4"&&WindowsPersonalTestAccess::annotation(f.s).find("代入")==std::string::npos);old.reset();});
 test("Enter does not automatically choose substitution",[]{NumericFixture f;symbolic(f);auto before=f.c->doc.value;f.release();BOOL eaten=TRUE;f.s->OnTestKeyDown(f.c,VK_RETURN,0,&eaten);expect(!eaten&&!f.m->element&&f.c->doc.value==before);});
 test("Space does not automatically choose substitution",[]{NumericFixture f;symbolic(f);auto before=f.c->doc.value;f.release();BOOL eaten=TRUE;f.s->OnTestKeyDown(f.c,VK_SPACE,0,&eaten);expect(!eaten&&!f.m->element&&f.c->doc.value==before);});
 test("symbolic Abort preserves host selected text",[]{NumericFixture f;symbolic(f);f.release();auto p=keep(f.ui());f.c->doc.cursor=1;f.c->doc.selectionEnd=4;auto before=f.c->doc.value;expect(SUCCEEDED(p->Abort())&&f.c->doc.value==before&&f.c->doc.cursor==1&&f.c->doc.selectionEnd==4);p.reset();});
 test("two queued contextual choices insert once",[]{NumericFixture f;symbolic(f);f.release();auto p=keep(f.ui());f.c->deferred=true;auto a=p->Finalize(),b=p->Finalize();p.reset();auto results=f.c->flush();expect(SUCCEEDED(a)&&SUCCEEDED(b)&&results.size()==2&&FAILED(results[0])&&SUCCEEDED(results[1])&&f.c->doc.value==L"a=j,b=1,c=h\na²+b²−c=j²+1²−htail");});
 test("contextual failed write does not retry or remove original",[]{NumericFixture f;symbolic(f);f.release();f.c->doc.denyText=true;auto before=f.c->doc.value;expect(FAILED(f.choose())&&f.c->doc.value==before&&!f.m->element);});
 test("contextual uncertain applied write is never replayed",[]{NumericFixture f;symbolic(f);f.release();auto p=keep(f.ui());f.c->doc.writeThenFail=true;auto first=p->Finalize();auto after=f.c->doc.value;f.c->doc.writeThenFail=false;auto second=p->Finalize();p.reset();expect(FAILED(first)&&FAILED(second)&&f.c->doc.value==after&&after==L"a=j,b=1,c=h\na²+b²−c=j²+1²−htail");});
 test("write focus reentry does not move old caret",[]{NumericFixture f;symbolic(f);f.release();auto caret=f.c->doc.cursor;f.c->doc.afterWrite=[&]{f.s->OnSetFocus(FALSE);};expect(SUCCEEDED(f.choose())&&f.c->doc.cursor==caret&&f.c->doc.value==L"a=j,b=1,c=h\na²+b²−c=j²+1²−htail");});
 test("new contextual preview survives old write callback",[]{NumericFixture f;symbolic(f);f.release();f.c->doc.afterWrite=[&]{symbolic(f,L"a=k\na²=tail");f.release();};expect(SUCCEEDED(f.choose())&&f.first()==L"k²"&&f.c->doc.value==L"a=k\na²=tail");});
 test("active pinyin composition is not replaced by math query",[]{NumericFixture f;symbolic(f);BOOL eaten=FALSE;f.s->OnKeyDown(f.c,'N',0,&eaten);auto before=f.c->doc.value;f.release();expect(TextServiceTestAccess::pendingText(f.s)=="n"&&f.c->doc.value==before&&WindowsPersonalTestAccess::annotation(f.s).find("代入")==std::string::npos);});
 test("voice review has priority and keeps existing explicit confirmation",[]{NumericFixture f;symbolic(f);expect(TextServiceTestAccess::stageVoice(f.s,f.c,"speech"));auto before=f.c->doc.value;auto caret=f.c->doc.cursor;f.release();expect(f.first()==L"speech"&&f.c->doc.value==before);BOOL eaten=FALSE;f.s->OnKeyDown(f.c,VK_RETURN,0,&eaten);expect(eaten&&f.c->doc.value==before.substr(0,caret)+L"speech"+before.substr(caret));});
 test("voice stale position remains protected with context provider enabled",[]{NumericFixture f;symbolic(f);expect(TextServiceTestAccess::stageVoice(f.s,f.c,"speech"));f.c->doc.cursor=1;auto before=f.c->doc.value;BOOL eaten=FALSE;f.s->OnKeyDown(f.c,VK_RETURN,0,&eaten);expect(eaten&&f.c->doc.value==before&&TextServiceTestAccess::recovery(f.s));});
 test("all contextual fixtures release actual COM references",[]{expect(objectCount==0);});
 std::cout<<"CONTEXT RESULT passed="<<passed-initialPassed<<" failed="<<failed-initialFailed<<" total="<<passed+failed-initialPassed-initialFailed<<"; all combined numeric/context="<<passed<<"/"<<passed+failed<<"; original baseline counted separately; real Windows GUI NOT_RUN"<<std::endl;
 return failed?1:0;
}
