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
static void waitForPreview(Fixture& fixture,const std::filesystem::path& folder){
 expect(TextServiceTestAccess::beginVoicePreviewFixture(fixture.s,fixture.c,folder));
 const auto deadline=GetTickCount64()+5000;
 while(TextServiceTestAccess::voicePreview(fixture.s)!=L"你好"&&GetTickCount64()<deadline){
  TextServiceTestAccess::voiceTick(fixture.s);
  Sleep(10);
 }
 expect(TextServiceTestAccess::voicePreview(fixture.s)==L"你好");
}
static void livePreviewSuite(const std::filesystem::path& folder){
 test("live preview belongs to the speech session; modifier down and repeats do not finalize",[&]{
  Fixture f;waitForPreview(f,folder);
  expect(!f.m->element&&!TextServiceTestAccess::hasVoice(f.s)&&f.c->doc.value==L"beforeafter");
  ScriptedKeyboardState chord(true,true);BOOL eat=TRUE;
  expect(SUCCEEDED(f.s->OnTestKeyDown(f.c,VK_CONTROL,0,&eat))&&!eat);
  expect(SUCCEEDED(f.s->OnTestKeyDown(f.c,VK_SHIFT,0,&eat))&&!eat);
  expect(SUCCEEDED(f.s->OnTestKeyDown(f.c,'V',0x40000000,&eat))&&eat);
  expect(SUCCEEDED(f.s->OnKeyDown(f.c,'V',0x40000000,&eat))&&eat);
  expect(TextServiceTestAccess::voicePreview(f.s)==L"你好");
  expect(SUCCEEDED(f.s->OnTestKeyDown(f.c,'V',0,&eat))&&eat);
  expect(SUCCEEDED(f.s->OnKeyDown(f.c,'V',0,&eat))&&eat);
  expect(TextServiceTestAccess::voiceProcessState(f.s)==VoiceProcess::Finishing);
  expect(TextServiceTestAccess::voicePreview(f.s).empty());
  const auto deadline=GetTickCount64()+5000;
  while(!TextServiceTestAccess::hasVoice(f.s)&&GetTickCount64()<deadline){
   TextServiceTestAccess::voiceTick(f.s);Sleep(10);
  }
  expect(TextServiceTestAccess::hasVoice(f.s)&&f.c->doc.value==L"beforeafter");
  expect(f.enter()&&f.c->doc.value==L"beforefinishedafter");
  f.unchanged();
 });
 test("Escape consumes cancellation and clears uncommitted preview",[&]{
  Fixture f;waitForPreview(f,folder);BOOL eat=FALSE;
  expect(SUCCEEDED(scriptedTestKeyDown(f.s,f.c,VK_ESCAPE,&eat))&&eat);
  expect(SUCCEEDED(scriptedKeyDown(f.s,f.c,VK_ESCAPE,&eat))&&eat);
  TextServiceTestAccess::voiceTick(f.s);
  expect(TextServiceTestAccess::voiceProcessState(f.s)==VoiceProcess::Idle&&
         TextServiceTestAccess::voicePreview(f.s).empty()&&!TextServiceTestAccess::recovery(f.s)&&
         f.c->doc.value==L"beforeafter");
 });
 test("new top context erases partial and has no speech candidate or recovery",[&]{
  Fixture f;waitForPreview(f,folder);
  auto other=new Context;drop(f.m->document);f.m->document=new DocumentManager(other);
  f.s->OnPushContext(other);
  TextServiceTestAccess::voiceTick(f.s);
  expect(TextServiceTestAccess::voiceProcessState(f.s)==VoiceProcess::Idle&&
         TextServiceTestAccess::voicePreview(f.s).empty()&&!TextServiceTestAccess::recovery(f.s)&&
         f.c->doc.value==L"beforeafter"&&other->doc.value==L"beforeafter");
  other->Release();
 });
 test("private scope transition discards partial and stops the synthetic child",[&]{
  Fixture f;waitForPreview(f,folder);f.c->scope=IS_PASSWORD;
  TextServiceTestAccess::voiceTick(f.s);
  expect(TextServiceTestAccess::voiceProcessState(f.s)==VoiceProcess::Idle&&
         TextServiceTestAccess::voicePreview(f.s).empty()&&!TextServiceTestAccess::hasVoice(f.s)&&
         !TextServiceTestAccess::recovery(f.s)&&f.c->doc.value==L"beforeafter");
 });
}
static bool claimUndo(Fixture& fixture,LPARAM message=0){
 ScriptedKeyboardState chord(true,true);BOOL eat=FALSE;
 expect(SUCCEEDED(fixture.s->OnTestKeyDown(fixture.c,'Z',message,&eat)));
 return eat!=FALSE;
}
static bool dispatchUndo(Fixture& fixture,ITfContext* target,LPARAM message=0){
 ScriptedKeyboardState chord(true,true);BOOL eat=FALSE;
 expect(SUCCEEDED(fixture.s->OnKeyDown(target,'Z',message,&eat)));
 return eat!=FALSE;
}
static void voiceUndoSuite(){
 test("voice undo removes only the latest confirmed UTF-16 insertion",[]{
  Fixture f;f.c->doc.value=L"语音原有语音";f.c->doc.cursor=4;
  expect(TextServiceTestAccess::stageVoice(f.s,f.c,u8"语音🎈")&&f.enter());
  expect(f.c->doc.value==L"语音原有语音🎈语音"&&TextServiceTestAccess::canUndoSpeech(f.s));
  expect(claimUndo(f)&&dispatchUndo(f,f.c)&&f.c->doc.value==L"语音原有语音"&&f.c->doc.cursor==4);
  expect(claimUndo(f,0x40000000)&&dispatchUndo(f,f.c,0x40000000));
  expect(!claimUndo(f)&&f.c->doc.textWrites==2);
 });
 test("mouse or programmatic selection and document changes refuse voice undo",[]{
  for(int scenario:{0,1,2}){
   Fixture f;f.stage();expect(f.enter()&&TextServiceTestAccess::canUndoSpeech(f.s));
   if(scenario==0)f.c->doc.cursor=1;
   if(scenario==1)f.c->doc.selectionEnd=2;
   if(scenario==2)f.c->doc.value[0]=L'X';
   const auto before=f.c->doc.value;
   expect(claimUndo(f)&&dispatchUndo(f,f.c)&&f.c->doc.value==before&&f.c->doc.textWrites==1);
  }
 });
 test("voice undo claimed in first field cannot dispatch in second",[]{
  Fixture f;f.stage();expect(f.enter()&&claimUndo(f));
  auto other=new Context;drop(f.m->document);f.m->document=new DocumentManager(other);
  f.s->OnSetFocus(FALSE);
  expect(dispatchUndo(f,other)&&other->doc.textWrites==0&&f.c->doc.textWrites==1);
  other->Release();
 });
 test("duplicate preflight and late dispatch cannot undo a newer voice write",[]{
  Fixture f;f.stage();expect(f.enter()&&claimUndo(f,4));
  f.s->OnSetFocus(FALSE);
  expect(TextServiceTestAccess::stageVoice(f.s,f.c,"new")&&f.enter());
  const auto newer=f.c->doc.value;
  expect(TextServiceTestAccess::canUndoSpeech(f.s)&&claimUndo(f,4)&&dispatchUndo(f,f.c,4));
  expect(f.c->doc.value==newer&&f.c->doc.textWrites==2&&TextServiceTestAccess::canUndoSpeech(f.s));
  expect(claimUndo(f,5)&&dispatchUndo(f,f.c,5)&&f.c->doc.value==L"beforespeechafter"&&f.c->doc.textWrites==3);
 });
 test("password transition refuses undo before reading any document text",[]{
  Fixture f;f.stage();expect(f.enter());
  const auto reads=f.c->fullReads;
  f.c->scope=IS_PASSWORD;
  expect(claimUndo(f)&&dispatchUndo(f,f.c)&&f.c->doc.textWrites==1&&f.c->fullReads==reads);
 });
 test("ambiguous undo write consumes the command without retry",[]{
  Fixture f;f.stage();expect(f.enter());f.c->doc.writeThenFail=true;
  expect(claimUndo(f)&&dispatchUndo(f,f.c)&&f.c->doc.value==L"beforeafter"&&f.c->doc.textWrites==2);
  expect(!claimUndo(f)&&!TextServiceTestAccess::canUndoSpeech(f.s));
 });
 test("host refusing caret placement suppresses possible voice undo",[]{
  Fixture f;f.stage();f.c->doc.denySelection=true;
  expect(f.enter()&&f.c->doc.value==L"beforespeechafter"&&!TextServiceTestAccess::canUndoSpeech(f.s));
 });
 test("only modifier keys preserve the shortcut until another text action",[]{
  Fixture f;f.stage();expect(f.enter());BOOL eat=TRUE;
  expect(SUCCEEDED(scriptedTestKeyDown(f.s,f.c,VK_SHIFT,&eat))&&!eat);
  expect(TextServiceTestAccess::canUndoSpeech(f.s));
  expect(SUCCEEDED(scriptedTestKeyDown(f.s,f.c,'N',&eat))&&eat);
  expect(!claimUndo(f)&&f.c->doc.value==L"beforespeechafter");
 });
}
static void formatSuite(const std::filesystem::path& folder){
 using Mode=wangshu::voice_format::Mode;
 test("bounded Windows number formatting matches six Android reference pairs",[]{
  const std::wstring ambiguous=L"一点点，下午三点五分，一百二个，一万二个，二〇二三年二月二十九日，编号为零九号，型号A三米，001个";
  const std::wstring prose=L"他说等一等。已经看过了吗？";
  const std::pair<std::wstring,std::wstring> examples[]={
   {L"二〇二四年二月二十九日，称了两千零六克。",L"2024年2月29日，称了2006克。"},
   {L"序列号为〇〇四五，备件二十个。",L"序列号为0045，备件20个。"},
   {L"仓库里有两百零七个零件，缺口为零点五毫米。",L"仓库里有207个零件，缺口为0.5毫米。"},
   {L"它值一亿零五万元，另付十二点五元。",L"它值100050000元，另付12.5元。"},
   {ambiguous,ambiguous},{prose,prose}};
  for(const auto& entry:examples)expect(wangshu::voice_format::numbers(entry.first)==entry.second);
 });
 test("format setting survives reopen, remains independent from math panel and binds per recording",[&]{
  const auto path=(folder/L"voice-format-personal.json").wstring();
  wangshu::WindowsPersonalStore store(path);
  auto base=store.readLearningFresh();
  expect(base.data.speechFormat==Mode::Raw);
  auto numbers=store.modifyLearning(base.revision,[](wangshu::LearningData& data){data.speechFormat=Mode::Numbers;});
  wangshu::WindowsPersonalStore reopened(path);
  expect(reopened.readLearningFresh().data.speechFormat==Mode::Numbers);
  Fixture numeric;expect(TextServiceTestAccess::beginVoice(numeric.s,numeric.c));
  TextServiceTestAccess::mathPanel(numeric.s,true);
  TextServiceTestAccess::loadSpeechFormat(numeric.s,path);
  auto math=reopened.modifyLearning(numbers.revision,[](wangshu::LearningData& data){data.speechFormat=Mode::Math;});
  TextServiceTestAccess::recognizeSpeech(numeric.s,u8"两千克，根号。");
  expect(numeric.enter()&&numeric.c->doc.value==L"before2000克，根号。after");
  Fixture maths;expect(TextServiceTestAccess::beginVoice(maths.s,maths.c));
  TextServiceTestAccess::loadSpeechFormat(maths.s,path);
  const std::string spoken=u8"根号";
  TextServiceTestAccess::recognizeSpeech(maths.s,spoken);
  expect(maths.enter()&&maths.c->doc.value==L"before√after");
  auto raw=reopened.modifyLearning(math.revision,[](wangshu::LearningData& data){data.speechFormat=Mode::Raw;});
  expect(raw.revision==base.revision);
  Fixture original;expect(TextServiceTestAccess::beginVoice(original.s,original.c));
  TextServiceTestAccess::mathPanel(original.s,true);
  TextServiceTestAccess::loadSpeechFormat(original.s,path);
  TextServiceTestAccess::recognizeSpeech(original.s,spoken);
  expect(original.enter()&&original.c->doc.value==L"before根号after");
 });
}
int main(int argc,char** argv){
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
 if(argc==2){const std::filesystem::path folder(argv[1]);livePreviewSuite(folder);voiceUndoSuite();formatSuite(folder);}
 test("fixture COM references release",[]{expect(objectCount==0);});
 std::cout<<"RESULT passed="<<passed<<" failed="<<failed<<" total="<<passed+failed<<"; production PE/mock TSF, generated speech; real Windows/microphone NOT_RUN"<<std::endl;return failed?1:0;
}
