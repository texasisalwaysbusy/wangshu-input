#include "../windows/personal_store.h"
#include "../core/emoji_catalog.h"
#include <filesystem>
#include <iostream>

int wmain(int argc,wchar_t** argv) {
    if(argc!=3)return 2;
    try{
        using wangshu::PersonalData;
        using wangshu::WindowsPersonalStore;
        const auto folder=std::filesystem::absolute(argv[1]);
        if(!std::filesystem::create_directory(folder))throw std::runtime_error("Expected a new fixture directory");
        unsigned assertions=0;
        auto check=[&](bool pass,const char* subject){
            if(!pass)throw std::runtime_error(subject);
            std::cout<<"PASS "<<subject<<'\n';
            ++assertions;
        };
        auto refuses=[&](const std::function<void()>& action,const char* subject){
            bool refused=false;
            try{action();}catch(const std::exception&){refused=true;}
            check(refused,subject);
        };
        const std::string face="👩🏽‍💻", kaomoji="( 'ω' )", flag="🇨🇦";
        const auto android=WindowsPersonalStore::parseImport(WindowsPersonalStore::readExternal(argv[2]));
        check(android.favorites==std::vector<std::string>{face,kaomoji},"Android favorites keep exact bytes and order");
        WindowsPersonalStore writer((folder/L"personal.json").wstring());
        auto preview=writer.previewImport(android);
        check(preview.data().favorites.size()==2&&writer.readFresh().data.favorites.empty(),"Windows preview is read-only");
        auto saved=writer.confirmImport(preview);
        WindowsPersonalStore other(writer.path());
        auto next=other.modify(saved.revision,[&](PersonalData& data){data.setFavorite(kaomoji,false);data.setFavorite(flag,true);});
        check(next.data.favorites==std::vector<std::string>{face,flag},"pin and unpin preserve remaining order");
        refuses([&]{writer.modify(saved.revision,[&](PersonalData& data){data.setFavorite(face,false);});},
                "stale Windows manager selection cannot overwrite new favorites");
        refuses([&]{writer.modify(next.revision,[&](PersonalData& data){data.setFavorite("arbitrary text",true);});},
                "arbitrary favorite data is rejected");
        refuses([&]{PersonalData::parse(R"({"kind":"wangshu-personal","version":1,"words":[],"phrases":[],"favorites":[]})");},
                "v1 rejects unknown favorites field");
        refuses([&]{PersonalData::parse(R"({"kind":"wangshu-personal","version":2,"words":[],"phrases":[],"favorites":[42]})");},
                "v2 rejects non-string favorite");
        PersonalData full;
        const auto& entries=wangshu::emoji_catalog::Entries;
        for(size_t i=0;i<PersonalData::MaxFavorites;++i)full.setFavorite(entries[i].text,true);
        refuses([&]{full.setFavorite(entries[PersonalData::MaxFavorites].text,true);},"64 favorite limit is explicit");
        refuses([&]{writer.previewImport(full);},"combined limit is checked in Windows merge preview");
        auto reread=WindowsPersonalStore(writer.path()).readFresh();
        check(reread.revision==next.revision&&reread.data.words.size()==1&&reread.data.phrases.size()==1,
              "failure and reopen preserve the new data and prior words");
        WindowsPersonalStore::exportNew((folder/L"windows-v2.json").wstring(),reread.data);
        auto legacy=reread.data;
        legacy.favorites.clear();
        check(legacy.serialize().find("\"version\":1")!=std::string::npos
              && legacy.serialize().find("\"favorites\"")==std::string::npos,"legacy export has the v1 profile");
        std::cout<<"TOTAL "<<assertions<<" PASS; synthetic files with the native Windows store\n";
        return 0;
    }catch(const std::exception& error){
        std::cerr<<"FAIL "<<error.what()<<'\n';
        return 1;
    }
}
