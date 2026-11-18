// Production TSF with a synthetic text store, independent fixture settings and no registration.
#define main unused_basic_tsf
#include "../tests/tsf_edit_test.cpp"
#undef main

struct WindowsPersonalTestAccess {
    static void store(TextService* service,const std::wstring& path){
        service->personalStore=std::make_unique<wangshu::WindowsPersonalStore>(path);
    }
    static std::vector<wangshu::Candidate> choices(TextService* service){return service->candidates;}
    static bool fuzzy(TextService* service){return service->displayed.fuzzy;}
    static size_t page(TextService* service){return service->page;}
    static void setMath(TextService* service){service->math=true;}
    static void setEnglish(TextService* service){service->chinese=false;}
};

namespace {
void expect(bool pass){if(!pass)throw std::runtime_error("fuzzy or keyboard routing assertion");}

struct Fixture {
    TextService* service=new TextService;
    Manager* manager=new Manager;
    Context* context=new Context;
    std::unique_ptr<wangshu::WindowsPersonalStore> store;

    explicit Fixture(const std::filesystem::path& folder){
        moduleHandle=GetModuleHandleW(nullptr);
        GUID id{};
        expect(SUCCEEDED(CoCreateGuid(&id)));
        wchar_t suffix[40]{};StringFromGUID2(id,suffix,40);
        store=std::make_unique<wangshu::WindowsPersonalStore>((folder/suffix/L"personal.json").wstring());
        wangshu::PersonalData terms;
        terms.words={{"zhang",u8"合成模糊记词"},{"zang",u8"合成精确原词"}};
        store->merge(terms);
        manager->supportsUI=true;
        manager->document=new DocumentManager(context);
        context->documentManager=manager->document;
        expect(SUCCEEDED(service->ActivateEx(manager,916,TF_TMAE_UIELEMENTENABLEDONLY)));
        WindowsPersonalTestAccess::store(service,store->path());
    }

    ~Fixture(){context->flush();service->Deactivate();service->Release();context->Release();manager->Release();}

    void fuzzy(unsigned mask){
        auto prior=store->readLearningFresh();
        store->modifyLearning(prior.revision,[&](wangshu::LearningData& data){data.enabled=true;data.fuzzyMask=mask;});
    }
    bool key(WPARAM code,bool shift=false){
        BOOL consumed=FALSE;
        expect(SUCCEEDED(scriptedKeyDown(service,context,code,&consumed,shift)));
        return consumed!=FALSE;
    }
    void type(const char* text){
        for(auto letter=static_cast<unsigned char>(*text);letter;letter=static_cast<unsigned char>(*++text))
            expect(key(letter));
    }
    std::vector<std::string> candidates() const{
        std::vector<std::string> values;
        for(auto& row:WindowsPersonalTestAccess::choices(service))values.push_back(row.text);
        return values;
    }
    void toFuzzyPage(){
        for(int i=0;i<16&&!WindowsPersonalTestAccess::fuzzy(service);++i){
            auto index=WindowsPersonalTestAccess::page(service);
            key(VK_NEXT);
            expect(WindowsPersonalTestAccess::page(service)==index+1);
        }
        expect(WindowsPersonalTestAccess::fuzzy(service));
    }
};
}

int wmain(int argc,wchar_t** argv){
    if(argc!=2)return 2;
    const std::filesystem::path root=argv[1];
    try{
        check(wangshu::fuzzy_pinyin::variants("zang",0).empty(),"default mask gives no fuzzy expansion");
        check(wangshu::fuzzy_pinyin::variants("ZANG",1)==std::vector<std::string>{"zhang"},"single onset, ASCII case and direction");
        check(wangshu::fuzzy_pinyin::variants("ni'hao",0x8)==std::vector<std::string>{"li'hao"}&&
              wangshu::fuzzy_pinyin::variants("ni''hao",0x8).empty(),"apostrophe boundary and invalid separator");
        {
            Fixture off(root),on(root);on.fuzzy(1);
            off.type("ZANG");on.type("ZANG");
            check(on.candidates()==off.candidates()&&on.candidates().front()==u8"合成精确原词","enabled expansion preserves exact first page and order");
            auto exact=on.candidates();
            while(!WindowsPersonalTestAccess::fuzzy(on.service)){
                const auto index=WindowsPersonalTestAccess::page(on.service);
                on.key(VK_NEXT);
                expect(WindowsPersonalTestAccess::page(on.service)==index+1&&index<16);
                if(!WindowsPersonalTestAccess::fuzzy(on.service)){
                    off.key(VK_NEXT);
                    expect(on.candidates()==off.candidates());
                    auto page=on.candidates();exact.insert(exact.end(),page.begin(),page.end());
                }
            }
            auto fuzzy=on.candidates();
            check(fuzzy.front()==u8"合成模糊记词"&&std::none_of(fuzzy.begin(),fuzzy.end(),[&](const auto& candidate){
                return std::find(exact.begin(),exact.end(),candidate)!=exact.end();
            }),"fuzzy pages deduplicate exact and prioritize explicit personal words");
            check(on.key('1')&&on.context->doc.value==L"before合成模糊记词after"&&on.store->readLearningFresh().data.entries.empty(),
                  "production selection inserts fuzzy text without learning under the original key");
        }
        {
            Fixture fixture(root);fixture.fuzzy(1);fixture.type("ZANG");fixture.toFuzzyPage();
            auto* candidate=fixture.manager->element;
            ITfCandidateListUIElementBehavior* ui=nullptr;
            expect(candidate&&SUCCEEDED(candidate->QueryInterface(IID_ITfCandidateListUIElementBehavior,reinterpret_cast<void**>(&ui))));
            fixture.fuzzy(0);
            const auto before=fixture.context->doc.value;
            const auto rejected=ui->Finalize();
            ui->Release();
            check(FAILED(rejected)&&fixture.context->doc.value==before,"a stale fuzzy candidate cannot commit after a rule change");
        }
        {
            Fixture fixture(root);fixture.fuzzy(1);WindowsPersonalTestAccess::setMath(fixture.service);
            fixture.type("ZANG");
            const auto max=WindowsPersonalTestAccess::page(fixture.service);
            for(int i=0;i<4;++i)fixture.key(VK_NEXT);
            check(!WindowsPersonalTestAccess::fuzzy(fixture.service)&&WindowsPersonalTestAccess::page(fixture.service)>=max,
                  "math mode leaves fuzzy processing disabled while exact pages work");
        }
        {
            Fixture literal(root);literal.fuzzy(1);
            if(literal.key(VK_OEM_3)){
                literal.key('A',true);literal.key(VK_OEM_3);
                check(literal.key(VK_RETURN)&&literal.context->doc.value==L"beforeAafter","explicit mixed literal preserves native shifted case");
            }else throw std::runtime_error("mock keyboard layout did not provide a backtick key");
            Fixture english(root);english.fuzzy(1);WindowsPersonalTestAccess::setEnglish(english.service);
            check(!english.key('A')&&!english.key('A',true)&&english.context->doc.value==L"beforeafter",
                  "native English input and Shift are left to the real editor");
        }
        check(objectCount==0,"all production COM fixture references released");
        std::cout<<"TOTAL "<<checks<<" PASS; native mock TSF; real keyboard and app NOT_RUN\n";
        return 0;
    }catch(const std::exception& error){std::cerr<<"FAIL "<<error.what()<<'\n';return 1;}
}
