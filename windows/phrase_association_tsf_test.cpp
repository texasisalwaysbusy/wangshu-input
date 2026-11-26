// Production TSF + synthetic text store and private per-test personal data.
// Prepared here; Windows build/runtime acceptance NOT_RUN in the Linux executor.
#define main unused_baseline_tsf_main
#include "../tests/tsf_edit_test.cpp"
#undef main
#include <functional>
struct WindowsPersonalTestAccess {
 static void store(TextService* s,const std::wstring& path){s->personalStore=std::make_unique<wangshu::WindowsPersonalStore>(path);}
 static void pause(TextService* s){s->noLearn=true;s->invalidatePending();}
 static void english(TextService* s){s->chinese=false;}
 static void math(TextService* s,bool enabled=true){s->math=enabled;}
 static bool pending(TextService* s){return static_cast<bool>(s->predictionRequest);}
 static void tick(TextService* s){
     if(!pending(s))return;
     s->predictionRequest->due=0;
     SendMessageW(s->predictionWindow,WM_TIMER,PredictionTimer,0);
 }
 static void expire(TextService* s){s->predictionRequest->expires=0;tick(s);}
 static bool calculating(TextService* s){return s->predictionWork!=nullptr;}
 static HWND dispatchWindow(TextService* s){return s->predictionWindow;}
 static const std::vector<wangshu::Candidate>& displayedPredictions(TextService* s){return s->builtinCandidates;}
};
static int passed=0,failed=0;
static void expect(bool ok){if(!ok)throw std::runtime_error("phrase association contract");}
static void test(const char* name,const std::function<void()>& run){try{run();++passed;std::cout<<"PASS "<<name<<std::endl;}catch(const std::exception& e){++failed;std::cout<<"FAIL "<<name<<" :: "<<e.what()<<std::endl;}}
struct Fixture {
 TextService* s=new TextService;Manager* m=new Manager;Context* c=new Context;
 std::unique_ptr<wangshu::WindowsPersonalStore> store;
 Fixture(){moduleHandle=GetModuleHandleW(nullptr);GUID id{};CoCreateGuid(&id);wchar_t name[40];StringFromGUID2(id,name,40);auto folder=std::filesystem::path(modulePath()).parent_path()/L"phrase-association-fixtures"/name;store=std::make_unique<wangshu::WindowsPersonalStore>((folder/L"personal.json").wstring());wangshu::PersonalData data;data.phrases={{"",u8"你好世界"},{"","hello wonderful world"}};store->merge(data);m->supportsUI=true;m->document=new DocumentManager(c);c->documentManager=m->document;expect(SUCCEEDED(s->ActivateEx(m,409,TF_TMAE_UIELEMENTENABLEDONLY)));WindowsPersonalTestAccess::store(s,store->path());text(L"he");}
 ~Fixture(){c->flush();s->Deactivate();s->Release();c->Release();m->Release();}
 void text(const std::wstring& value){c->doc.value=value;c->doc.cursor=static_cast<LONG>(value.size());c->doc.selectionEnd=-1;}
 void enabled(bool value=true){auto old=store->readLearningFresh();store->modifyLearning(old.revision,[&](wangshu::LearningData& x){x.phraseAssociations=value;});}
 void builtin(bool value=true){
     auto old=store->readLearningFresh();
     store->modifyLearning(old.revision,[&](wangshu::LearningData& x){x.builtinPredictions=value;});
 }
 void numeric(bool value=true){
     auto old=store->readLearningFresh();
     store->modifyLearning(old.revision,[&](wangshu::LearningData& x){x.numericPredictions=value;});
 }
 void cursorPredictions(bool value){
     auto old=store->readLearningFresh();
     store->modifyLearning(old.revision,[&](wangshu::LearningData& x){x.cursorPredictions=value;});
 }
 void release(WPARAM key=VK_END){ScriptedKeyboardState state;BOOL eaten=TRUE;expect(SUCCEEDED(s->OnTestKeyUp(c,key,0,&eaten))&&!eaten);}
 void waitPrediction(){
     auto expires=GetTickCount64()+1200;
     while(WindowsPersonalTestAccess::pending(s)&&GetTickCount64()<expires){
         WindowsPersonalTestAccess::tick(s);
         if(WindowsPersonalTestAccess::pending(s))Sleep(2);
     }
     expect(!WindowsPersonalTestAccess::pending(s));
 }
 ITfCandidateListUIElementBehavior* ui(){ITfCandidateListUIElementBehavior* p=nullptr;expect(m->element&&SUCCEEDED(m->element->QueryInterface(IID_ITfCandidateListUIElementBehavior,reinterpret_cast<void**>(&p))));return p;}
 std::wstring at(UINT index){auto p=ui();BSTR b=nullptr;auto result=p->GetString(index,&b);p->Release();expect(SUCCEEDED(result));std::wstring value(b,SysStringLen(b));SysFreeString(b);return value;}
 std::wstring first(){return at(0);}
 HRESULT choose(){auto p=ui();auto h=p->Finalize();p->Release();return h;}
};
static int builtinSuite(){
 test("built-in flag persists independently and can return to legacy format",[]{
     Fixture f;f.enabled();auto old=f.store->readLearningFresh();
     expect(old.data.phraseAssociations&&!old.data.builtinPredictions);
     f.store->modifyLearning(old.revision,[](wangshu::LearningData& data){data.pin("nihao","你好",true,100);});
     f.builtin();
     wangshu::WindowsPersonalStore reopened(f.store->path());
     auto saved=reopened.readLearningFresh();
     expect(saved.data.phraseAssociations&&saved.data.builtinPredictions&&!saved.data.numericPredictions&&saved.data.pinned("nihao","你好"));
     expect(saved.revision.find("\"version\":7")!=std::string::npos);
     f.builtin(false);
     auto restored=reopened.readLearningFresh();
     expect(restored.data.phraseAssociations&&!restored.data.builtinPredictions&&restored.data.pinned("nihao","你好"));
     expect(restored.revision.find("\"version\":2")!=std::string::npos);
 });
 test("default off does not request editor text",[]{
     Fixture f;bool read=false;f.c->doc.onRead=[&]{read=true;};
     f.release();f.waitPrediction();expect(!read&&!f.m->element);
 });
 test("disabled caret preference persists and legacy defaults preserve navigation behavior",[]{
     Fixture f;f.enabled();f.builtin();f.numeric();auto previous=f.store->readLearningFresh();
     expect(previous.data.cursorPredictions&&previous.revision.find("\"version\":3")!=std::string::npos);
     f.cursorPredictions(false);
     wangshu::WindowsPersonalStore reopened(f.store->path());auto changed=reopened.readLearningFresh();
     expect(!changed.data.cursorPredictions&&changed.data.builtinPredictions&&changed.data.phraseAssociations&&
            changed.revision.find("\"version\":4")!=std::string::npos);
     f.cursorPredictions(true);expect(reopened.readLearningFresh().revision==previous.revision);
 });
 test("disabled movement avoids provider reads but ordinary input still refreshes",[]{
     Fixture f;f.builtin();f.cursorPredictions(false);WindowsPersonalTestAccess::english(f.s);f.text(L"THE WEATH");
     bool read=false;f.c->doc.onRead=[&]{read=true;};
     for(auto key:{VK_LEFT,VK_RIGHT,VK_UP,VK_DOWN,VK_HOME,VK_END})f.release(key);
     expect(!read&&!f.m->element&&!WindowsPersonalTestAccess::pending(f.s));
     f.release('H');f.waitPrediction();expect(read&&f.first()==L"ER");
 });
 test("turned-off cursor setting rejects a queued movement choice",[]{
     Fixture f;f.builtin();WindowsPersonalTestAccess::english(f.s);f.text(L"THE WEATH");
     f.release(VK_DOWN);f.waitPrediction();expect(f.first()==L"ER");
     f.c->deferred=true;expect(SUCCEEDED(f.choose()));f.cursorPredictions(false);
     auto outcome=f.c->flush();expect(outcome.size()==1&&FAILED(outcome[0])&&f.c->doc.value==L"THE WEATH");
 });
 test("disabling movement during a saved-phrase snapshot prevents publication",[]{
     Fixture f;f.enabled();bool disabled=false;
     f.c->doc.onRead=[&]{if(!disabled){disabled=true;f.cursorPredictions(false);}};
     f.release(VK_RIGHT);expect(disabled&&!f.m->element);
     f.release('E');expect(f.first()==L"llo");
 });
 test("independent exact arithmetic remains available with movement prediction off",[]{
     Fixture f;f.builtin();f.cursorPredictions(false);WindowsPersonalTestAccess::math(f.s);f.text(L"2+2=");
     f.release(VK_LEFT);expect(f.first()==L"4"&&!WindowsPersonalTestAccess::pending(f.s));
 });
 test("English completion is computed after key-up and selected row is appended",[]{
     Fixture f;f.builtin();WindowsPersonalTestAccess::english(f.s);f.text(L"THE WEATH");
     bool read=false;f.c->doc.onRead=[&]{read=true;};f.release();
     expect(!read&&!f.m->element&&WindowsPersonalTestAccess::pending(f.s));
     f.waitPrediction();expect(read&&f.first()==L"ER");
     const auto second=f.at(1);
     expect(!second.empty()&&second!=L"ER");
     auto p=f.ui();expect(SUCCEEDED(p->SetSelection(1)));auto committed=p->Finalize();p->Release();
     expect(SUCCEEDED(committed)&&f.c->doc.value==L"THE WEATH"+second);
 });
 test("Chinese next-word choice preserves separated right-side text",[]{
     Fixture f;f.builtin();f.text(L"你想不想，之后再说");f.c->doc.cursor=4;f.release();
     f.waitPrediction();expect(f.at(2)==L"一起");
     auto p=f.ui();expect(SUCCEEDED(p->SetSelection(2)));auto committed=p->Finalize();p->Release();
     expect(SUCCEEDED(committed));
     expect(f.c->doc.value==L"你想不想一起，之后再说");
 });
 test("Chinese completion and next-word lanes both arrive through the native worker",[]{
     Fixture f;f.builtin();f.text(L"一个小，之后");f.c->doc.cursor=3;f.release();
     f.waitPrediction();
     const auto& values=WindowsPersonalTestAccess::displayedPredictions(f.s);
     expect(values.size()>=2&&values.size()<=8&&values[0].annotation=="语料补全"&&
            values[1].annotation=="语料下一词");
     const auto alternate=f.at(1);
     expect(alternate==wide(values[1].text)&&f.c->doc.value==L"一个小，之后");
     auto p=f.ui();expect(SUCCEEDED(p->SetSelection(1)));
     auto result=p->Finalize();p->Release();
     expect(SUCCEEDED(result)&&f.c->doc.value==L"一个小"+alternate+L"，之后");
 });
 test("queued alternate-lane selection cannot insert after the caret changes",[]{
     Fixture f;f.builtin();f.text(L"一个小，之后");f.c->doc.cursor=3;f.release();f.waitPrediction();
     auto p=f.ui();expect(SUCCEEDED(p->SetSelection(1)));f.c->deferred=true;
     expect(SUCCEEDED(p->Finalize()));p->Release();
     f.c->doc.cursor=1;auto result=f.c->flush();
     expect(result.size()==1&&FAILED(result[0])&&f.c->doc.value==L"一个小，之后");
 });
 test("English completion after a sentence period preserves the following sentence",[]{
     Fixture f;f.builtin();WindowsPersonalTestAccess::english(f.s);
     const std::wstring prefix=L"The note ended. THE WEATH";
     f.text(prefix+L". Thanks.");f.c->doc.cursor=static_cast<LONG>(prefix.size());
     f.release();f.waitPrediction();expect(f.first()==L"ER");
     expect(SUCCEEDED(f.choose())&&f.c->doc.value==prefix+L"ER. Thanks.");
 });
 test("native timer dispatch publishes without taking focus",[]{
     Fixture f;f.builtin();WindowsPersonalTestAccess::english(f.s);f.text(L"THE WEATH");
     auto focus=GetFocus();f.release();
     auto window=WindowsPersonalTestAccess::dispatchWindow(f.s);
     expect(window&&FindWindowExW(HWND_MESSAGE,nullptr,PredictionWindowClass,nullptr)==window&&!IsWindowVisible(window));
     auto expires=GetTickCount64()+1000;
     while(WindowsPersonalTestAccess::pending(f.s)&&GetTickCount64()<expires){
         MSG message{};
         if(PeekMessageW(&message,window,WM_TIMER,WM_TIMER,PM_REMOVE))DispatchMessageW(&message);
         else Sleep(2);
     }
     expect(!WindowsPersonalTestAccess::pending(f.s)&&f.first()==L"ER");
     expect(GetFocus()==focus&&f.c->doc.value==L"THE WEATH");
 });
 test("personal phrase takes precedence when both flags are on",[]{
     Fixture f;f.enabled();f.builtin();f.release();expect(f.first()==L"llo"&&!WindowsPersonalTestAccess::pending(f.s));
 });
 test("exact arithmetic takes precedence over built-in predictions",[]{
     Fixture f;WindowsPersonalTestAccess::math(f.s);f.text(L"2+2=");
     f.release();expect(f.first()==L"4"&&!WindowsPersonalTestAccess::pending(f.s));
 });
 test("numeric mode appends a date unit without modifying digits or right-side text",[]{
     Fixture f;f.numeric();WindowsPersonalTestAccess::math(f.s);f.text(L"日期：2026，备注");
     f.c->doc.cursor=7;f.release();f.waitPrediction();
     expect(f.first()==L"年"&&f.c->doc.value==L"日期：2026，备注");
     expect(SUCCEEDED(f.choose())&&f.c->doc.value==L"日期：2026年，备注");
 });
 test("ordinary and numeric predictions have independent opt-in and scope",[]{
     Fixture f;f.builtin();f.text(L"Time: 09");f.release();f.waitPrediction();expect(!f.m->element);
     WindowsPersonalTestAccess::math(f.s);f.release();f.waitPrediction();expect(!f.m->element);
     f.numeric();f.builtin(false);f.release();f.waitPrediction();expect(f.first()==L":");
     WindowsPersonalTestAccess::math(f.s,false);WindowsPersonalTestAccess::english(f.s);
     f.text(L"THE WEATH");f.release();f.waitPrediction();expect(!f.m->element);
     f.builtin();f.release();f.waitPrediction();expect(f.first()==L"ER");
 });
 test("restricted number field has no date suggestion",[]{
     Fixture f;f.numeric();WindowsPersonalTestAccess::math(f.s);f.c->scope=IS_DIGITS;
     f.text(L"日期：2026");f.release();f.waitPrediction();expect(!f.m->element);
 });
 test("mode change after numeric selection rejects a queued suffix",[]{
     Fixture f;f.numeric();WindowsPersonalTestAccess::math(f.s);f.text(L"日期：2026");
     f.release();f.waitPrediction();expect(f.first()==L"年");
     f.c->deferred=true;expect(SUCCEEDED(f.choose()));WindowsPersonalTestAccess::math(f.s,false);
     auto outcome=f.c->flush();expect(outcome.size()==1&&FAILED(outcome[0])&&f.c->doc.value==L"日期：2026");
 });
 test("disabling numeric predictions rejects a queued date suffix",[]{
     Fixture f;f.builtin();f.numeric();WindowsPersonalTestAccess::math(f.s);f.text(L"日期：2026");
     f.release();f.waitPrediction();expect(f.first()==L"年");
     f.c->deferred=true;expect(SUCCEEDED(f.choose()));f.numeric(false);
     auto outcome=f.c->flush();expect(outcome.size()==1&&FAILED(outcome[0])&&f.c->doc.value==L"日期：2026");
     f.c->deferred=false;f.text(L"1/3+1/6=");f.release();expect(f.first()==L"1/2");
 });
 test("restricted scopes refuse before requesting editor text",[]{
     for(auto scope:{IS_PASSWORD,IS_PRIVATE,IS_URL,IS_EMAIL_SMTPEMAILADDRESS,IS_DIGITS}){
         Fixture f;f.builtin();f.c->scope=scope;bool read=false;f.c->doc.onRead=[&]{read=true;};
         f.release();f.waitPrediction();expect(!read&&!f.m->element);
     }
 });
 test("moving the caret while the worker is active discards publication",[]{
     Fixture f;f.builtin();WindowsPersonalTestAccess::english(f.s);f.text(L"THE WEATH");
     f.release();WindowsPersonalTestAccess::tick(f.s);expect(WindowsPersonalTestAccess::calculating(f.s));
     f.c->doc.cursor=4;f.waitPrediction();expect(!f.m->element&&f.c->doc.value==L"THE WEATH");
 });
 test("switching focus cancels the running suggestion",[]{
     Fixture f;f.builtin();WindowsPersonalTestAccess::english(f.s);f.text(L"THE WEATH");
     f.release();WindowsPersonalTestAccess::tick(f.s);expect(WindowsPersonalTestAccess::calculating(f.s));
     f.s->OnSetFocus(FALSE);f.waitPrediction();expect(!f.m->element&&f.c->doc.value==L"THE WEATH");
 });
 test("focus reentry during snapshot capture cannot start a worker",[]{
     Fixture f;f.builtin();WindowsPersonalTestAccess::english(f.s);f.text(L"THE WEATH");
     f.release();f.c->afterRead=[&]{f.s->OnSetFocus(FALSE);};
     f.waitPrediction();expect(!f.m->element&&!WindowsPersonalTestAccess::calculating(f.s));
 });
 test("same caret with changed right-side text rejects queued insertion",[]{
     Fixture f;f.builtin();WindowsPersonalTestAccess::english(f.s);f.text(L"THE WEATH, later");
     f.c->doc.cursor=9;f.release();f.waitPrediction();expect(f.first()==L"ER");
     f.c->deferred=true;expect(SUCCEEDED(f.choose()));f.c->doc.value=L"THE WEATH, again";
     auto outcome=f.c->flush();expect(outcome.size()==1&&FAILED(outcome[0])&&f.c->doc.value==L"THE WEATH, again");
 });
 test("switching preference off rejects an already queued insertion",[]{
     Fixture f;f.builtin();WindowsPersonalTestAccess::english(f.s);f.text(L"THE WEATH");f.release();f.waitPrediction();
     f.c->deferred=true;expect(SUCCEEDED(f.choose()));f.builtin(false);
     auto outcome=f.c->flush();expect(outcome.size()==1&&FAILED(outcome[0])&&f.c->doc.value==L"THE WEATH");
 });
 test("expiration invalidates a running request and never inserts",[]{
     Fixture f;f.builtin();WindowsPersonalTestAccess::english(f.s);f.text(L"THE WEATH");
     f.release();WindowsPersonalTestAccess::tick(f.s);expect(WindowsPersonalTestAccess::calculating(f.s));
     WindowsPersonalTestAccess::expire(f.s);f.waitPrediction();
     expect(!f.m->element&&f.c->doc.value==L"THE WEATH");
 });
 test("fixtures release COM references and timer windows",[]{expect(objectCount==0&&predictionWindowCount==0);});
 std::cout<<"RESULT "<<passed<<" passed, "<<failed<<" failed; Windows native prediction, mock TSF only"<<std::endl;
 return failed?1:0;
}

int main(int argc,char** argv){
 if(argc==2&&std::string(argv[1])=="--builtin")return builtinSuite();
 if(argc!=1)return 2;
 test("default off creates no candidate",[]{Fixture f;f.release();expect(!f.m->element&&f.c->doc.value==L"he");});
 test("explicit setting enables English suffix",[]{Fixture f;f.enabled();f.release();expect(f.first()==L"llo"&&f.c->doc.value==L"he");});
 test("explicit TSF choice appends",[]{Fixture f;f.enabled();f.release();expect(SUCCEEDED(f.choose())&&f.c->doc.value==L"hello");});
 test("Chinese saved phrase remainder",[]{Fixture f;f.enabled();f.text(L"你好");f.release();expect(f.first()==L"世界"&&SUCCEEDED(f.choose())&&f.c->doc.value==L"你好世界");});
 test("English uppercase suffix",[]{Fixture f;f.enabled();f.text(L"HE");f.release();expect(f.first()==L"LLO");});
 test("English next word after space",[]{Fixture f;f.enabled();f.text(L"hello ");f.release();expect(f.first()==L"wonderful");});
 test("one letter does not trigger",[]{Fixture f;f.enabled();f.text(L"h");f.release();expect(!f.m->element);});
 test("middle caret does not trigger",[]{Fixture f;f.enabled();f.text(L"hello");f.c->doc.cursor=2;f.release();expect(!f.m->element);});
 test("password no preview",[]{Fixture f;f.enabled();f.c->scope=IS_PASSWORD;f.c->doc.noRead=true;f.release();expect(!f.m->element);});
 test("temporary no-learn no preview",[]{Fixture f;f.enabled();WindowsPersonalTestAccess::pause(f.s);f.release();expect(!f.m->element);});
 test("setting change rejects old selection",[]{Fixture f;f.enabled();f.release();f.enabled(false);expect(FAILED(f.choose())&&f.c->doc.value==L"he");});
 test("phrase change rejects old selection",[]{Fixture f;f.enabled();f.release();auto old=f.store->readFresh();f.store->modify(old.revision,[](wangshu::PersonalData& d){d.phrases[1].text="help";});expect(FAILED(f.choose())&&f.c->doc.value==L"he");});
 test("async caret change rejects",[]{Fixture f;f.enabled();f.release();f.c->deferred=true;expect(SUCCEEDED(f.choose()));f.c->doc.cursor=1;auto result=f.c->flush();expect(result.size()==1&&FAILED(result[0])&&f.c->doc.value==L"he");});
 test("focus loss rejects queued choice",[]{Fixture f;f.enabled();f.release();f.c->deferred=true;expect(SUCCEEDED(f.choose()));f.s->OnSetFocus(FALSE);auto result=f.c->flush();expect(result.size()==1&&FAILED(result[0])&&f.c->doc.value==L"he");});
 test("Space and Enter do not select association",[]{Fixture f;f.enabled();f.release();BOOL eaten=TRUE;scriptedTestKeyDown(f.s,f.c,VK_SPACE,&eaten);expect(!eaten&&f.c->doc.value==L"he");f.release();scriptedTestKeyDown(f.s,f.c,VK_RETURN,&eaten);expect(!eaten&&f.c->doc.value==L"he");});
 test("fixture releases COM objects",[]{expect(objectCount==0);});
 std::cout<<"RESULT "<<passed<<" passed, "<<failed<<" failed; production Windows PE/mock TSF, no real device"<<std::endl;return failed?1:0;
}
