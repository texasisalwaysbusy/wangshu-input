#ifndef UNICODE
#define UNICODE
#define _UNICODE
#endif
#include "personal_store.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <functional>
#include <set>
using namespace wangshu;
static int passed=0,failed=0;
static void expect(bool value,const char* why){if(!value)throw std::runtime_error(why);}
static void test(const char* label,const std::function<void()>& run){try{run();++passed;std::cout<<"PASS "<<label<<std::endl;}catch(const std::exception& error){++failed;std::cout<<"FAIL "<<label<<" :: "<<error.what()<<std::endl;}}
static bool rejects(const std::function<void()>& run){try{run();return false;}catch(...){return true;}}
static void ownedWrite(const std::wstring& path,const std::string& bytes){std::ofstream out(std::filesystem::path(path),std::ios::binary);out<<bytes;out.close();expect(!out.fail(),"Owned generated write failed");}
static std::wstring guid(){GUID id{};CoCreateGuid(&id);wchar_t out[40];StringFromGUID2(id,out,40);return out;}
static constexpr unsigned long long Now=1600000000000ULL;
int wmain(int argc,wchar_t** argv){if(argc!=2&&argc!=3)return 2;auto directory=std::filesystem::path(argv[1])/guid();std::filesystem::create_directories(directory);auto path=[&](const wchar_t* label){return (directory/label).wstring();};
    test("default ranking is disabled and read creates no file",[&]{WindowsPersonalStore store(path(L"missing.json"));auto read=store.readLearningFresh();expect(!read.data.enabled&&read.data.entries.empty()&&!std::filesystem::exists(store.learningPath()),"Missing overlay enabled learning or wrote file");});
    test("selected terms NFC normalize without compatibility folding",[]{LearningData data;data.record("NI'HAO","e\xcc\x81",Now);data.record("nihao","\xc3\xa9",Now);data.pin("symbol","𝕏",true,Now);data.pin("symbol","X",true,Now);expect(data.entries.size()==3&&data.entries[0].count==2&&data.entries[0].text=="\xc3\xa9","Learning identity normalization changed symbol or duplicated equivalent term");});
    test("codec persists opt-in and Unicode without surrounding input",[]{LearningData data;data.enabled=true;data.record("nihao","你好 👩‍🔬",Now);auto bytes=data.serialize();auto decoded=LearningData::parse(bytes);expect(decoded.enabled&&decoded.entries.size()==1&&decoded.entries[0].text=="你好 👩‍🔬"&&decoded.entries[0].count==1,"Codec lost confirmed term");});
    test("multiline controls invisible direction and whitespace-only terms rejected",[]{for(auto text:{"a\nb","a\tb","a\rb","\xe2\x80\xae" "evil","\xc2\xa0"})expect(rejects([&]{LearningData data;data.record("test",text,Now);}),"Unsupported term retained");});
    test("malformed UTF8 and too-long terms rejected",[]{for(auto text:std::vector<std::string>{"\xff",std::string(65,'a')})expect(rejects([&]{LearningData data;data.record("test",text,Now);}),"Malformed/oversized word accepted");});
    test("frequency is bounded at 255 and thirty-day decay is deterministic",[]{LearningData data;for(int i=0;i<300;++i)data.record("test","term",Now);expect(data.entries[0].count==255&&LearningData::effective(data.entries[0],Now+LearningData::DecayMillis)==127&&LearningData::effective(data.entries[0],Now+8*LearningData::DecayMillis)==0,"Weight bound/decay wrong");});
    test("backward clock does not amplify old observed count",[]{LearningData data;data.record("test","term",Now);data.record("test","term",Now-1);expect(data.entries[0].count==2&&data.entries[0].updated==Now,"Clock rollback corrupted timestamp");});
    test("pin survives decay and unpin zero-count identity removes only overlay",[]{LearningData data;data.pin("test","term",true,Now);expect(data.pinned("test","term"),"Pin missing");data.pin("test","term",false,Now+8*LearningData::DecayMillis);expect(data.entries.empty(),"Unpin retained inactive identity");});
    test("all-pinned capacity refuses new identity without evicting pins",[]{LearningData data;for(int i=0;i<256;++i)data.pin("test","pin"+std::to_string(i),true,Now);auto before=data.serialize();expect(rejects([&]{data.record("test","extra",Now);})&&data.serialize()==before,"Capacity removed existing pinned data");});
    test("bounded automatic eviction excludes pinned identities",[]{LearningData data;data.pin("test","fixed",true,Now);for(int i=1;i<256;++i)data.record("test","word"+std::to_string(i),Now);data.record("test","extra",Now);expect(data.entries.size()==256&&data.pinned("test","fixed")&&data.entries.back().text=="extra","Bounded insertion removed pinned identity");});
    test("ranked candidates precede base/manual entries with stable dedup",[]{LearningData data;PersonalData manual;Engine engine;manual.words={{"nihao","first"},{"nihao","second"}};data.record("nihao","second",Now);auto values=data.lookup(engine,manual,"nihao",0,Now);expect(values.size()==2&&values[0].text=="second"&&values[1].text=="first","Frequency did not rank or duplicated manual entry");data.pin("nihao","first",true,Now);expect(data.lookup(engine,manual,"nihao",0,Now)[0].text=="first","Pin did not precede frequency");});
    test("pagination preserves complete deduplicated candidate order",[]{LearningData data;PersonalData manual;Engine engine;for(int i=0;i<25;++i)manual.words.push_back({"test","word"+std::to_string(i)});data.pin("test","word24",true,Now);std::vector<std::string> result;for(size_t page=0;page<4;++page)for(auto& item:data.lookup(engine,manual,"test",page,Now))result.push_back(item.text);std::set<std::string> unique(result.begin(),result.end());expect(result.size()==25&&unique.size()==25&&result[0]=="word24","Page boundary lost/duplicated terms");});
    test("forget removes weight and pin without modifying explicit dictionary",[]{LearningData data;PersonalData manual;Engine engine;manual.words={{"test","manual"}};data.record("test","manual",Now);data.pin("test","manual",true,Now);expect(data.forget("test","manual")&&data.entries.empty()&&manual.lookup(engine,"test")[0].text=="manual","Forget removed manual dictionary term");});
    test("unknown fields duplicate identities and invalid counts reject whole JSON",[]{LearningData model;model.record("test","term",Now);auto good=model.serialize();for(auto bad:std::vector<std::string>{"{}",good.substr(0,good.size()-1)+",\"unknown\":1}",std::string(128*1024+1,' '),good.substr(0,good.rfind(']'))+","+good.substr(good.find('[')+1,good.rfind(']')-good.find('[')-1)+good.substr(good.rfind(']')),"{\"kind\":\"wangshu-windows-learning\",\"version\":1,\"enabled\":false,\"entries\":[{\"key\":\"test\",\"text\":\"term\",\"count\":-1,\"pinned\":false,\"updated\":0}]}"})expect(rejects([&]{LearningData::parse(bad);}),"Invalid schema accepted");});
    test("separate ranking file leaves Android-compatible explicit data intact",[&]{WindowsPersonalStore store(path(L"manual.json"));PersonalData manual;manual.words={{"test","manual"}};store.merge(manual);auto before=WindowsPersonalStore::readExternal(store.path());auto state=store.readLearningFresh();store.modifyLearning(state.revision,[](LearningData& data){data.enabled=true;data.record("test","manual",Now);});expect(WindowsPersonalStore::readExternal(store.path())==before&&store.readFresh().data.words[0].text=="manual"&&WindowsPersonalStore(store.path()).readLearningFresh().data.entries[0].count==1,"Learning reset explicit data or failed persistence");});
    test("stale revision cannot overwrite another manager's learning preferences",[&]{WindowsPersonalStore a(path(L"race.json")),b(a.path());auto old=a.readLearningFresh();b.modifyLearning(old.revision,[](LearningData& data){data.enabled=true;});expect(rejects([&]{a.modifyLearning(old.revision,[](LearningData& data){data.pin("test","stale",true,Now);});})&&a.readLearningFresh().data.enabled&&a.readLearningFresh().data.entries.empty(),"Stale update replaced newer preferences");});
    test("backup write refusal leaves canonical frequency unchanged",[&]{WindowsPersonalStore store(path(L"failure.json"));auto state=store.readLearningFresh();state=store.modifyLearning(state.revision,[](LearningData& data){data.enabled=true;});state=store.modifyLearning(state.revision,[](LearningData& data){data.record("test","first",Now);});auto before=WindowsPersonalStore::readExternal(store.learningPath());HANDLE handle=CreateFileW((store.learningPath()+L".bak").c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);expect(handle!=INVALID_HANDLE_VALUE,"Generated backup lock unavailable");bool denied=rejects([&]{store.modifyLearning(state.revision,[](LearningData& data){data.record("test","first",Now+1);});});CloseHandle(handle);expect(denied&&WindowsPersonalStore::readExternal(store.learningPath())==before&&store.readLearningFresh().data.entries[0].count==1,"Refused persistence changed visible count");});
    test("corrupt canonical uses backup but does not automatically repair or learn",[&]{WindowsPersonalStore store(path(L"recover.json"));auto state=store.readLearningFresh();state=store.modifyLearning(state.revision,[](LearningData& data){data.enabled=true;data.record("test","first",Now);});store.modifyLearning(state.revision,[](LearningData& data){data.record("test","second",Now);});ownedWrite(store.learningPath(),"owned damaged ranking");auto backup=store.readLearningFresh();expect(backup.fromBackup&&backup.data.entries.size()==1&&rejects([&]{store.modifyLearning(backup.revision,[](LearningData& data){data.record("test","guess",Now);});})&&WindowsPersonalStore::readExternal(store.learningPath())=="owned damaged ranking","Corrupt ranking silently reset/repaired");store.restoreLearningBackup(backup.revision);expect(!store.readLearningFresh().fromBackup&&store.readLearningFresh().data.entries.size()==1,"Explicit recovery failed");bool archived=false;for(const auto& entry:std::filesystem::directory_iterator(directory))if(entry.path().extension()==L".invalid")archived|=WindowsPersonalStore::readExternal(entry.path().wstring())=="owned damaged ranking";expect(archived,"Recovery lost damaged original");});
    test("unrecoverable ranking is reported without overwriting main or backup",[&]{WindowsPersonalStore store(path(L"broken.json"));ownedWrite(store.learningPath(),"broken main");ownedWrite(store.learningPath()+L".bak","broken backup");expect(rejects([&]{store.readLearningFresh();})&&WindowsPersonalStore::readExternal(store.learningPath())=="broken main"&&WindowsPersonalStore::readExternal(store.learningPath()+L".bak")=="broken backup","Unreadable data was reset");});
    test("portable settings export contains settings only and cannot overwrite",[&]{
        WindowsPersonalStore store(path(L"portable.json"));
        auto current=store.readLearningFresh();
        auto configured=store.modifyLearning(current.revision,[](LearningData& data){
            data.speechFormat=voice_format::Mode::Numbers;data.builtinPredictions=true;data.cursorPredictions=false;
            data.phraseAssociations=true;data.enabled=true;data.pin("test","synthetic private rank",true,Now);
        });
        auto file=path(L"portable-windows-export.tsv");
        store.exportPortableSettingsNew(file);
        const auto bytes=WindowsPersonalStore::readExternal(file);
        const auto settings=PortableSettings::parse(bytes);
        expect(settings.serialize()==bytes&&settings.speechFormat==voice_format::Mode::Numbers&&settings.builtinPredictions&&
               !settings.cursorPredictions&&settings.windowsPhraseAssociations==true,"wrong selected settings");
        expect(bytes.find("private")==std::string::npos&&bytes.find("enabled")==std::string::npos,"exported personal data");
        expect(rejects([&]{store.exportPortableSettingsNew(file);})&&WindowsPersonalStore::readExternal(file)==bytes&&
               store.readLearningFresh().revision==configured.revision,"export overwrote state");
    });
    test("portable codec rejects privacy fields, duplicate values and future version",[]{
        const auto bytes=PortableSettings{}.serialize();
        auto crlf=std::string("\xef\xbb\xbf");
        for(auto ch:bytes)crlf+=ch=='\n'?"\r\n":std::string(1,ch);
        expect(PortableSettings::parse(crlf).serialize()==bytes,"BOM/CRLF normalization");
        for(const auto& invalid:std::vector<std::string>{
            bytes+"clipboard\tenabled\tb\ttrue\n",bytes+"associations\tbuiltinPredictions\tb\tfalse\n",
            bytes.substr(0,bytes.rfind("associations\tcursor")),std::string(1025,' '),
            "WANGSHU-PORTABLE-SETTINGS\t2\n"+bytes.substr(bytes.find('\n')+1)
        })expect(rejects([&]{PortableSettings::parse(invalid);}),"Accepted malformed portable settings");
    });
    test("portable settings save atomically without touching rank, dictionary or consent",[&]{
        WindowsPersonalStore store(path(L"receiver.json"));
        PersonalData personal;personal.words={{"test","synthetic"}};store.merge(personal);
        auto old=store.readLearningFresh();
        auto local=store.modifyLearning(old.revision,[](LearningData& data){
            data.enabled=true;data.phraseAssociations=true;data.pin("test","local",true,Now);
        });
        const auto manual=WindowsPersonalStore::readExternal(store.path());
        PortableSettings incoming{voice_format::Mode::Numbers,true,false,{}};
        expect(!local.data.builtinPredictions,"preview changed current setting");
        store.modifyLearning(local.revision,[&](LearningData& data){incoming.apply(data);});
        auto fresh=WindowsPersonalStore(store.path()).readLearningFresh();
        expect(fresh.data.builtinPredictions&&!fresh.data.cursorPredictions&&fresh.data.speechFormat==voice_format::Mode::Numbers&&
               fresh.data.phraseAssociations&&fresh.data.enabled&&fresh.data.pinned("test","local"),"lost portable value or local data");
        expect(WindowsPersonalStore::readExternal(store.path())==manual,"changed dictionary");
        expect(rejects([&]{store.modifyLearning(local.revision,[&](LearningData& data){incoming.apply(data);});}),"stale preview accepted");
    });
    test("Windows fuzzy rule mask uses v6 only while enabled and is preserved by portable merge",[&]{
        WindowsPersonalStore store(path(L"fuzzy.json"));
        auto initial=store.readLearningFresh();
        expect(!initial.data.fuzzyMask,"fuzzy rules were not off by default");
        auto configured=store.modifyLearning(initial.revision,[](LearningData& data){
            data.fuzzyMask=0x81;data.pin("test","local",true,Now);
        });
        auto reopened=WindowsPersonalStore(store.path()).readLearningFresh();
        expect(reopened.data.fuzzyMask==0x81&&reopened.revision.find("\"version\":6")!=std::string::npos,
               "v6 fuzzy mask not persisted");
        auto merged=store.modifyLearning(configured.revision,[](LearningData& data){PortableSettings{}.apply(data);});
        expect(merged.data.fuzzyMask==0x81&&merged.data.pinned("test","local"),"portable merge changed fuzzy rules or ranking");
        auto invalid=merged.revision;auto at=invalid.find("\"fuzzyMask\":129");
        expect(at!=std::string::npos,"missing mask");
        invalid.replace(at,std::string("\"fuzzyMask\":129").size(),"\"fuzzyMask\":256");
        expect(rejects([&]{LearningData::parse(invalid);}),"unknown rule accepted");
        auto restored=store.modifyLearning(merged.revision,[](LearningData& data){data.fuzzyMask=0;});
        expect(restored.revision.find("\"version\":1")!=std::string::npos&&restored.data.pinned("test","local"),
               "default schema or ranking lost");
    });
    if(argc==3)test("Android file round trip retains Windows-only setting and weights",[&]{
        const auto incoming=PortableSettings::parse(WindowsPersonalStore::readExternal(argv[2]));
        expect(!incoming.windowsPhraseAssociations&&incoming.speechFormat==voice_format::Mode::Math&&
               incoming.builtinPredictions&&!incoming.cursorPredictions,"Android return did not match shared values");
        WindowsPersonalStore store(path(L"exchange.json"));
        auto local=store.readLearningFresh();
        local=store.modifyLearning(local.revision,[](LearningData& data){data.phraseAssociations=true;data.pin("test","local",true,Now);});
        store.modifyLearning(local.revision,[&](LearningData& data){incoming.apply(data);});
        const auto saved=WindowsPersonalStore(store.path()).readLearningFresh();
        expect(saved.data.speechFormat==voice_format::Mode::Math&&saved.data.builtinPredictions&&
               !saved.data.cursorPredictions&&saved.data.phraseAssociations&&saved.data.pinned("test","local"),"roundtrip lost data");
    });
    std::cout<<"RESULT passed="<<passed<<" failed="<<failed<<" total="<<(passed+failed)<<"; native Win32 storage/model with synthetic files, real Windows GUI NOT_RUN"<<std::endl;return failed?1:0;
}
