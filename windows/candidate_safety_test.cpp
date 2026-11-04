// Actual production TSF code in a PE executable with the existing COM/text-store fixture.
#define main unused_existing_tsf_main
#include "../tests/tsf_edit_test.cpp"
#undef main
#include <functional>
#include <fstream>
struct WindowsPersonalTestAccess {
    static void file(TextService* s,const std::wstring& path){s->personalStore=std::make_unique<wangshu::WindowsPersonalStore>(path);}
    static void corruptVisibleRow(TextService* s){s->candidates.at(0).text="not-displayed";}
    static void refresh(TextService* s){s->update();}
};
static int safetyPassed=0,safetyFailed=0;
static void safetyCheck(bool yes,const char* text){if(!yes)throw std::runtime_error(text);}
static void safetyCase(const char* text,const std::function<void()>& body){try{body();safetyPassed++;std::cout<<"PASS "<<text<<std::endl;}catch(const std::exception& e){safetyFailed++;std::cout<<"FAIL "<<text<<" :: "<<e.what()<<std::endl;}}
struct SafetyFixture {
    TextService* service=new TextService;Manager* manager=new Manager;Context* context=new Context;ITfCandidateListUIElementBehavior* element=nullptr;
    std::unique_ptr<wangshu::WindowsPersonalStore> store;
    SafetyFixture(){
        GUID guid{};CoCreateGuid(&guid);wchar_t id[40];StringFromGUID2(guid,id,40);
        auto dir=std::filesystem::path(modulePath()).parent_path()/L"candidate-safety-fixtures"/id;std::filesystem::create_directories(dir);store=std::make_unique<wangshu::WindowsPersonalStore>((dir/L"personal.json").wstring());
        wangshu::PersonalData initial;initial.words={{"nihao","displayed-A"}};store->merge(initial);
        manager->supportsUI=true;manager->document=new DocumentManager(context);context->documentManager=manager->document;
        safetyCheck(SUCCEEDED(service->ActivateEx(manager,83,TF_TMAE_UIELEMENTENABLEDONLY)),"production activation");WindowsPersonalTestAccess::file(service,store->path());
        BOOL eat=FALSE;for(WPARAM key:{'N','I','H','A','O'})service->OnKeyDown(context,key,0,&eat);
        safetyCheck(manager->element&&SUCCEEDED(manager->element->QueryInterface(IID_ITfCandidateListUIElementBehavior,reinterpret_cast<void**>(&element))),"production UIElement");element->SetSelection(0);
    }
    void changePersonal(){auto now=store->read();store->modify(now.revision,[](wangshu::PersonalData& data){data.words[0].text="updated-B";});}
    ~SafetyFixture(){if(element)element->Release();service->Deactivate();service->Release();context->Release();manager->Release();}
};
int main(){
    safetyCase("current displayed candidate commits once to owned composition",[]{SafetyFixture f;auto h=f.element->Finalize();safetyCheck(SUCCEEDED(h)&&f.context->doc.value==L"beforedisplayed-Aafter","current candidate did not commit");safetyCheck(FAILED(f.element->Finalize()),"obsolete interface remained actionable");});
    safetyCase("personal file revision changed after display refuses old candidate",[]{SafetyFixture f;auto before=f.context->doc.value;f.changePersonal();safetyCheck(FAILED(f.element->Finalize())&&f.context->doc.value==before,"stale personal candidate inserted");});
    safetyCase("personal file revision changed after async queue refuses late edit",[]{SafetyFixture f;f.context->deferred=true;auto before=f.context->doc.value;safetyCheck(SUCCEEDED(f.element->Finalize()),"candidate was not queued");f.changePersonal();auto result=f.context->flush();safetyCheck(result.size()==1&&FAILED(result[0])&&f.context->doc.value==before,"queued candidate ignored later personal revision");});
    safetyCase("candidate callback cannot substitute a row different from displayed snapshot",[]{SafetyFixture f;auto before=f.context->doc.value;WindowsPersonalTestAccess::corruptVisibleRow(f.service);safetyCheck(FAILED(f.element->Finalize())&&f.context->doc.value==before,"callback used live undisplayed row");});
    safetyCase("same-context host edit inside composition is not overwritten by old candidate",[]{SafetyFixture f;f.context->doc.value.replace(6,5,L"fresh");auto before=f.context->doc.value;safetyCheck(FAILED(f.element->Finalize())&&f.context->doc.value==before,"candidate overwrote changed host composition");});
    safetyCase("same-size and same-timestamp personal replacement cannot bypass acceptance revision",[]{
        SafetyFixture f;auto current=f.store->read();f.store->modify(current.revision,[](wangshu::PersonalData&){});WindowsPersonalTestAccess::refresh(f.service);
        WIN32_FILE_ATTRIBUTE_DATA main{},backup{};auto path=f.store->path();safetyCheck(GetFileAttributesExW(path.c_str(),GetFileExInfoStandard,&main)&&GetFileAttributesExW((path+L".bak").c_str(),GetFileExInfoStandard,&backup),"fixture metadata unavailable");
        current=f.store->read();f.store->modify(current.revision,[](wangshu::PersonalData& data){data.words[0].text="replaced-BB";});
        for(auto item:std::vector<std::pair<std::wstring,FILETIME>>{{path,main.ftLastWriteTime},{path+L".bak",backup.ftLastWriteTime}}){HANDLE file=CreateFileW(item.first.c_str(),FILE_WRITE_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,0,nullptr);safetyCheck(file!=INVALID_HANDLE_VALUE,"generated metadata fixture open failed");BOOL ok=SetFileTime(file,nullptr,nullptr,&item.second);CloseHandle(file);safetyCheck(ok,"generated metadata fixture timestamp restoration failed");}
        WIN32_FILE_ATTRIBUTE_DATA after{};GetFileAttributesExW(path.c_str(),GetFileExInfoStandard,&after);safetyCheck(after.nFileSizeLow==main.nFileSizeLow&&CompareFileTime(&after.ftLastWriteTime,&main.ftLastWriteTime)==0,"fixture did not preserve cache metadata");auto before=f.context->doc.value;safetyCheck(FAILED(f.element->Finalize())&&f.context->doc.value==before,"metadata cache was trusted instead of exact fresh revision");
    });
    safetyCase("unreadable personal file after display refuses without resetting source",[]{SafetyFixture f;{std::ofstream bytes(std::filesystem::path(f.store->path()),std::ios::binary);bytes<<"broken generated fixture";}auto before=f.context->doc.value;safetyCheck(FAILED(f.element->Finalize())&&f.context->doc.value==before,"unreadable overlay accepted old candidate");safetyCheck(wangshu::WindowsPersonalStore::readExternal(f.store->path())=="broken generated fixture","failed acceptance reset personal source");});
    safetyCase("new typing invalidates queued candidate before editing",[]{SafetyFixture f;f.context->deferred=true;safetyCheck(SUCCEEDED(f.element->Finalize()),"queue failed");BOOL eat=FALSE;f.service->OnKeyDown(f.context,'N',0,&eat);auto before=f.context->doc.value;auto result=f.context->flush();safetyCheck(result.size()==1&&FAILED(result[0])&&f.context->doc.value==before,"late candidate edited new composition");});
    safetyCase("focus loss invalidates retained candidate interface",[]{SafetyFixture f;auto before=f.context->doc.value;f.service->OnSetFocus(FALSE);safetyCheck(FAILED(f.element->Finalize())&&f.context->doc.value==before,"old focus candidate edited host");});
    safetyCase("push-context transition invalidates already queued candidate",[]{SafetyFixture f;f.context->deferred=true;safetyCheck(SUCCEEDED(f.element->Finalize()),"queue failed");auto before=f.context->doc.value;f.service->OnPushContext(f.context);auto result=f.context->flush();safetyCheck(result.size()==1&&FAILED(result[0])&&f.context->doc.value==before,"old queued selection survived context push");});
    safetyCase("password transition refuses displayed candidate",[]{SafetyFixture f;auto before=f.context->doc.value;f.context->scope=IS_PASSWORD;safetyCheck(FAILED(f.element->Finalize())&&f.context->doc.value==before,"password candidate was committed");});
    safetyCase("changed focused context cannot receive old host candidate",[]{SafetyFixture f;auto other=new Context;auto old=f.manager->document;f.manager->document=new DocumentManager(other);old->Release();auto before=f.context->doc.value;safetyCheck(FAILED(f.element->Finalize())&&f.context->doc.value==before&&other->doc.value==L"beforeafter","cross-context candidate inserted");other->Release();});
    safetyCase("secure console activation keeps built-in keyboard usable without personal UI",[]{auto service=new TextService;auto manager=new Manager;auto context=new Context;manager->supportsUI=true;manager->document=new DocumentManager(context);context->documentManager=manager->document;safetyCheck(SUCCEEDED(service->ActivateEx(manager,84,TF_TMAE_SECUREMODE|TF_TMAE_CONSOLE)),"restricted activation failed");BOOL eat=FALSE;for(WPARAM key:{'N','I','H','A','O'})service->OnKeyDown(context,key,0,&eat);service->OnKeyDown(context,VK_SPACE,0,&eat);bool good=eat&&context->doc.value==L"before你好after"&&!manager->element;service->Deactivate();service->Release();context->Release();manager->Release();safetyCheck(good,"restricted UI suppression disabled ordinary built-in input");});
    safetyCase("all test scopes release service and candidate COM ownership",[]{safetyCheck(objectCount==0,"COM objects retained");});
    std::cout<<"RESULT passed="<<safetyPassed<<" failed="<<safetyFailed<<" total="<<(safetyPassed+safetyFailed)<<"; actual PE production TSF with mock text store and generated personal files; real Windows GUI NOT_RUN"<<std::endl;
    return safetyFailed?1:0;
}
