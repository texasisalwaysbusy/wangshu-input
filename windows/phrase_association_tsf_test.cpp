// Production TSF + synthetic text store and private per-test personal data.
// Prepared here; Windows build/runtime acceptance NOT_RUN in the Linux executor.
#define main unused_baseline_tsf_main
#include "../tests/tsf_edit_test.cpp"
#undef main
#include <functional>
struct WindowsPersonalTestAccess {
 static void store(TextService* s,const std::wstring& path){s->personalStore=std::make_unique<wangshu::WindowsPersonalStore>(path);}
 static void pause(TextService* s){s->noLearn=true;s->invalidatePending();}
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
 void release(){BOOL eaten=TRUE;expect(SUCCEEDED(s->OnTestKeyUp(c,VK_END,0,&eaten))&&!eaten);}
 ITfCandidateListUIElementBehavior* ui(){ITfCandidateListUIElementBehavior* p=nullptr;expect(m->element&&SUCCEEDED(m->element->QueryInterface(IID_ITfCandidateListUIElementBehavior,reinterpret_cast<void**>(&p))));return p;}
 std::wstring first(){auto p=ui();BSTR b=nullptr;expect(SUCCEEDED(p->GetString(0,&b)));std::wstring value(b,SysStringLen(b));SysFreeString(b);p->Release();return value;}
 HRESULT choose(){auto p=ui();auto h=p->Finalize();p->Release();return h;}
};
int main(){
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
 test("Space and Enter do not select association",[]{Fixture f;f.enabled();f.release();BOOL eaten=TRUE;f.s->OnTestKeyDown(f.c,VK_SPACE,0,&eaten);expect(!eaten&&f.c->doc.value==L"he");f.release();f.s->OnTestKeyDown(f.c,VK_RETURN,0,&eaten);expect(!eaten&&f.c->doc.value==L"he");});
 test("fixture releases COM objects",[]{expect(objectCount==0);});
 std::cout<<"RESULT "<<passed<<" passed, "<<failed<<" failed; production Windows PE/mock TSF, no real device"<<std::endl;return failed?1:0;
}
