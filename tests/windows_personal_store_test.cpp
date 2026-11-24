#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define NOMINMAX
#include "../windows/personal_store.h"
#include "../windows/local_store_io.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <vector>
using namespace wangshu;
static int count=0;
static void check(bool value,const char* label){if(!value)throw std::runtime_error(label);++count;std::cout<<"PASS "<<label<<std::endl;}
static std::wstring guid(){GUID id{};if(FAILED(CoCreateGuid(&id)))throw std::runtime_error("Test GUID unavailable");wchar_t out[40];StringFromGUID2(id,out,40);return out;}
static void writeOwned(const std::wstring& path,const std::string& bytes){HANDLE file=CreateFileW(path.c_str(),GENERIC_WRITE,0,nullptr,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);if(file==INVALID_HANDLE_VALUE)throw std::runtime_error("Owned test write unavailable");DWORD done;bool ok=WriteFile(file,bytes.data(),static_cast<DWORD>(bytes.size()),&done,nullptr)&&done==bytes.size()&&FlushFileBuffers(file);CloseHandle(file);if(!ok)throw std::runtime_error("Owned test fixture write failed");}
static std::string utf8(const std::wstring& value){return wangshu::utf8(std::u16string(value.begin(),value.end()));}
static PersonalData one(const std::string& value){PersonalData data;data.words.push_back({"sync",value});return data;}
static int checkRecoveryFaults(const std::filesystem::path& folder) {
    using wangshu::local_store_io::Handle;
    const auto rejected = [](const auto& action) {
        try { action(); } catch (const std::runtime_error&) { return true; }
        return false;
    };
    const auto read = WindowsPersonalStore::readExternal;
    const auto noTemporary = [](const auto& directory) {
        for (const auto& entry : std::filesystem::directory_iterator(directory))
            if (entry.path().extension() == L".tmp") return false;
        return true;
    };
    unsigned failures = 0;
    const auto report = [&](bool passed, const char* text) {
        std::cout << (passed ? "PASS " : "FAIL ") << text << std::endl;
        failures += !passed;
    };
    for (const bool learning : {false, true}) {
        auto directory = folder / (learning ? L"ranking" : L"words");
        std::filesystem::create_directories(directory);
        WindowsPersonalStore store((directory / L"personal.json").wstring());
        const auto main = learning ? store.learningPath() : store.path();
        const auto backup = main + L".bak";
        if (learning) {
            auto initial = store.readLearningFresh();
            auto first = store.modifyLearning(initial.revision, [](LearningData& data) { data.enabled = true; });
            store.modifyLearning(first.revision, [](LearningData& data) { data.numericPredictions = true; });
        } else {
            store.merge(one("first"));
            store.merge(one("second"));
        }
        const auto before = read(main);
        const auto previous = read(backup);
        if (before == previous) throw std::runtime_error("Fixture needs distinct generations");
        const auto save = [&] {
            if (learning) {
                store.modifyLearning(store.readLearningFresh().revision, [](LearningData& data) {
                    data.cursorPredictions = false;
                });
            } else {
                store.merge(one("third"));
            }
        };
        bool stopped = false;
        {
            Handle lock(CreateFileW(main.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                                    nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
            if (lock.value == INVALID_HANDLE_VALUE) throw std::runtime_error("Fixture lock unavailable");
            stopped = rejected(save);
        }
        const bool keptBackup = read(backup) == previous;
        report(stopped && read(main) == before,
               learning ? "ranking rejected locked main; canonical unchanged" :
                          "words rejected locked main; canonical unchanged");
        report(keptBackup && noTemporary(directory),
               learning ? "ranking preserved previous backup and discarded staged files" :
                          "words preserved previous backup and discarded staged files");
        if (keptBackup) {
            save();
            report(read(backup) == before && read(main) != before && noTemporary(directory),
                   learning ? "ranking retry rotates exact prior canonical and publishes new settings" :
                              "words retry rotates exact prior canonical and publishes new terms");
        }
    }
    auto path = (folder / L"fallback.json").wstring();
    WindowsPersonalStore fallback(path);
    auto first = fallback.merge(one("first"));
    fallback.merge(one("second"));
    const std::string broken = "corrupt canonical, retained verbatim";
    writeOwned(path, broken);
    const auto prior = read(path + L".bak");
    auto recoveryPreview = fallback.previewImport(one("external"));
    report(recoveryPreview.usesLocalBackup() && !recoveryPreview.repair() && read(path) == broken,
           "import preview discloses recovery from a valid local backup without publishing");
    const bool editStopped = rejected([&] {
        fallback.modify(first.revision, [](PersonalData& data) { data.words.push_back({"sync", "unconfirmed"}); });
    });
    report(editStopped && fallback.readFresh().fromBackup && read(path) == broken
                      && read(path + L".bak") == prior,
           "stale edit with matching backup revision cannot implicitly repair canonical");
    if (editStopped) {
        report(rejected([&] { fallback.merge(one("unconfirmed")); }) &&
                   read(path) == broken && read(path + L".bak") == prior,
               "ordinary merge requires explicit recovery while using backup");
        const auto restored = fallback.restoreBackup(fallback.readFresh().revision);
        report(!restored.fromBackup && restored.data.words.size() == 1 &&
                   restored.data.words.front().text == "first" && read(path + L".bak") == prior,
               "explicit restore brings back prior terms and preserves the known-good backup");
    }
    auto lateDir = folder / L"late-backup";
    std::filesystem::create_directories(lateDir);
    WindowsPersonalStore settings((lateDir / L"personal.json").wstring());
    auto initial = settings.readLearningFresh();
    auto oneSave = settings.modifyLearning(initial.revision, [](LearningData& data) { data.enabled = true; });
    auto previousSettings = settings.modifyLearning(oneSave.revision, [](LearningData& data) { data.numericPredictions = true; });
    auto lateBackup = settings.learningPath() + L".bak";
    const auto oldBytes = read(lateBackup);
    const auto currentBytes = read(settings.learningPath());
    LearningWriteGate cancelPublication;
    cancelPublication.claim = [] { return false; };
    const auto changeSettings = [](LearningData& data) { data.cursorPredictions = false; };
    report(rejected([&] {
               settings.modifyLearning(previousSettings.revision, changeSettings, &cancelPublication);
           }) && read(settings.learningPath()) == currentBytes && read(lateBackup) == oldBytes
              && noTemporary(lateDir),
           "cancelled learning publication preserves both generations and discards staging");
    LearningWriteGate blockPromotion;
    blockPromotion.claim = [&] {
        if (!SetFileAttributesW(lateBackup.c_str(), FILE_ATTRIBUTE_READONLY))
            throw std::runtime_error("Fault injection could not mark the owned backup read-only");
        return true;
    };
    std::string message;
    try {
        settings.modifyLearning(previousSettings.revision, changeSettings, &blockPromotion);
    } catch (const std::runtime_error& failure) { message = failure.what(); }
    if (!SetFileAttributesW(lateBackup.c_str(), FILE_ATTRIBUTE_NORMAL))
        throw std::runtime_error("Could not restore attributes on the owned fixture");
    auto reopened = WindowsPersonalStore(settings.path()).readLearningFresh();
    report(message.find("backup rotation was not confirmed") != std::string::npos
               && !reopened.fromBackup && !reopened.data.cursorPredictions
               && read(lateBackup) == oldBytes && noTemporary(lateDir),
           "late backup rotation failure preserves older backup and reports committed settings");
    std::cout << "RECOVERY FAULTS " << failures << " FAIL" << std::endl;
    return failures ? 1 : 0;
}

int wmain(int argc,wchar_t** argv){try{
    if(argc==3&&std::wstring(argv[1])==L"--recovery-faults"){
        auto output=std::filesystem::path(argv[2])/guid();
        std::filesystem::create_directories(output);
        return checkRecoveryFaults(output);
    }
    if(argc==4&&std::wstring(argv[1])==L"--append"){WindowsPersonalStore child(argv[2]);for(int i=0;i<10;i++)child.merge(one(utf8(argv[3])+std::to_string(i)));return 0;}
    if(argc<2)throw std::runtime_error("Owned test evidence directory required");
    auto folder=std::filesystem::path(argv[1])/guid();std::filesystem::create_directories(folder);auto path=(folder/L"personal-v1.json").wstring();WindowsPersonalStore store(path);
    check(store.read().data.words.empty()&&!std::filesystem::exists(path),"missing personal file yields empty overlay without creating user data");
    auto before=store.read();auto saved=store.modify(before.revision,[](PersonalData& data){data.words.push_back({"WANG'SHU","望舒"});data.phrases.push_back({"THANKS","谢谢\nThank you 👩‍🔬"});});check(saved.data.words.size()==1&&saved.data.words[0].pinyin=="wangshu","native atomic save validates and normalizes full pinyin");
    check(WindowsPersonalStore(path).read().data.phrases[0].text=="谢谢\nThank you 👩‍🔬","fresh Win32 file reader preserves multiline non-BMP phrase");
    auto nfc=PersonalData{};nfc.words={{"accent","e\xcc\x81"},{"accent","\xc3\xa9"},{"symbol","𝕏"},{"symbol","X"}};nfc.phrases={{"raw","e\xcc\x81"}};auto normalized=WindowsPersonalStore::normalizeWords(nfc);check(normalized.words.size()==3&&normalized.words[0].text=="\xc3\xa9","word NFC deduplicates canonically equivalent forms");check(normalized.words[1].text=="𝕏"&&normalized.words[2].text=="X","NFC retains mathematical compatibility distinction instead of NFKC folding");check(normalized.phrases[0].text=="e\xcc\x81","phrase bytes are not Unicode-normalized");
    auto fromTsv=WindowsPersonalStore::parseImport("\xef\xbb\xbfpinyin\ttext\r\n\r\n# synthetic comment\r\nWANG'SHU\t望舒\r\n");check(fromTsv.words.size()==1&&fromTsv.words[0].pinyin=="wangshu","UTF8 BOM/header/blank/comment/CRLF TSV accepted as two columns");
    bool invalid=false;try{WindowsPersonalStore::parseImport("nihao\t你好\t100\n");}catch(const std::exception& e){invalid=std::string(e.what()).find("row 1")!=std::string::npos;}check(invalid,"third TSV column refused with row number rather than ignored");
    invalid=false;try{WindowsPersonalStore::parseImport("pinyin\ttext\nnihao\t你好\nbad1\t错误\n");}catch(const std::exception& e){invalid=std::string(e.what()).find("row 3")!=std::string::npos;}check(invalid,"invalid pinyin reports the original physical TSV row including header");
    invalid=false;try{WindowsPersonalStore::parseImport("nihao\t你好\nbad\t\xff\n");}catch(const std::exception& e){invalid=std::string(e.what()).find("row 2")!=std::string::npos;}check(invalid,"malformed UTF8 word reports its TSV row without replacing bytes");
    invalid=false;try{WindowsPersonalStore::parseImport("nihao\t你好\nlong\t"+std::string(201,'x')+"\n");}catch(const std::exception& e){invalid=std::string(e.what()).find("row 2")!=std::string::npos;}check(invalid,"overlong TSV word reports its own row rather than a generic merge failure");
    invalid=false;try{WindowsPersonalStore::parseImport("nihao\t你好\n# invalid comment \xff\n");}catch(const std::exception& e){invalid=std::string(e.what()).find("row 2")!=std::string::npos;}check(invalid,"strict UTF8 validation includes skipped TSV comment lines");
    auto stable=WindowsPersonalStore::readExternal(path);invalid=false;try{store.merge(WindowsPersonalStore::parseImport("nihao\t你好\nbad-row\n"));}catch(...){invalid=true;}check(invalid&&WindowsPersonalStore::readExternal(path)==stable,"invalid later import row leaves entire canonical store unchanged");
    store.merge(fromTsv);check(store.read().data.words.size()==1,"same-key same-text additive import is idempotent");
    WindowsPersonalStore other(path);auto stale=store.read();other.merge(one("concurrent"));invalid=false;try{store.modify(stale.revision,[](PersonalData& data){data.words.clear();});}catch(...){invalid=true;}check(invalid&&store.read().data.words.size()==2,"stale editor revision cannot overwrite another process update");
    auto backup=path+L".bak";HANDLE locked=CreateFileW(backup.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);check(locked!=INVALID_HANDLE_VALUE,"test owns an exclusive prior-backup handle without permission changes");stable=WindowsPersonalStore::readExternal(path);invalid=false;try{auto snapshot=store.read();store.modify(snapshot.revision,[](PersonalData& data){data.words.push_back({"failure","must not publish"});});}catch(...){invalid=true;}CloseHandle(locked);check(invalid&&WindowsPersonalStore::readExternal(path)==stable&&store.read().data.words.size()==2,"backup-sharing failure preserves canonical and in-memory state");
    auto exportPath=(folder/L"backup-中国.json").wstring();WindowsPersonalStore::exportNew(exportPath,store.read().data);check(WindowsPersonalStore::parseImport(WindowsPersonalStore::readExternal(exportPath)).phrases[0].text=="谢谢\nThank you 👩‍🔬","explicit new-file export is Android-compatible JSON with Unicode path");stable=WindowsPersonalStore::readExternal(exportPath);invalid=false;try{WindowsPersonalStore::exportNew(exportPath,PersonalData{});}catch(...){invalid=true;}check(invalid&&WindowsPersonalStore::readExternal(exportPath)==stable,"export refuses an existing output without overwriting it");
    auto recoverPath=(folder/L"recover.json").wstring();WindowsPersonalStore recovery(recoverPath);recovery.merge(one("first"));recovery.merge(one("second"));writeOwned(recoverPath,"incomplete corrupt canonical");auto recovered=recovery.read();check(recovered.fromBackup&&recovered.data.words.size()==1&&recovered.data.words[0].text=="first","corrupt canonical loads previous valid backup without resetting either file");check(WindowsPersonalStore::readExternal(recoverPath)=="incomplete corrupt canonical","read-only fallback does not silently rewrite corrupt canonical");recovery.restoreBackup(recovered.revision);check(!recovery.read().fromBackup&&recovery.read().data.words.size()==1,"explicit backup restore commits a valid canonical");bool archived=false;for(const auto& entry:std::filesystem::directory_iterator(folder))if(entry.path().extension()==L".invalid")archived|=WindowsPersonalStore::readExternal(entry.path().wstring())=="incomplete corrupt canonical";check(archived,"recovery preserves damaged canonical in an independently named archive");
    auto brokenPath=(folder/L"broken.json").wstring();writeOwned(brokenPath,"invalid owned test JSON");writeOwned(brokenPath+L".bak","invalid owned backup JSON");WindowsPersonalStore broken(brokenPath);invalid=false;try{broken.read();}catch(...){invalid=true;}check(invalid&&WindowsPersonalStore::readExternal(brokenPath)=="invalid owned test JSON","unrecoverable invalid file is reported and never replaced with empty data");
    auto overPath=(folder/L"capacity.json").wstring();WindowsPersonalStore capacity(overPath);PersonalData capacityData;for(int i=0;i<2000;i++)capacityData.words.push_back({"capacity","word"+std::to_string(i)});capacity.merge(capacityData);auto capacityBefore=WindowsPersonalStore::readExternal(overPath);invalid=false;try{capacity.previewImport(one("extra"));}catch(...){invalid=true;}check(invalid&&WindowsPersonalStore::readExternal(overPath)==capacityBefore,"over-capacity preview refuses rather than offering destructive repair of valid data");auto duplicate=capacity.previewImport(capacityData);check(!duplicate.repair()&&duplicate.data().words.size()==2000,"capacity-sized duplicate import previews idempotent additive merge");
    auto importPath=(folder/L"import-confirm.json").wstring();
    WindowsPersonalStore importStore(importPath),otherWindow(importPath);
    importStore.merge(one("original"));
    auto importBefore=WindowsPersonalStore::readExternal(importPath);
    auto incoming=one("displayed");
    auto importPlan=importStore.previewImport(incoming);
    check(!importPlan.repair()&&importPlan.data().words.size()==2&&WindowsPersonalStore::readExternal(importPath)==importBefore,
          "merge preview computes the exact result without writing the store");
    otherWindow.merge(one("changed in other window"));
    auto expectedAfterChange=WindowsPersonalStore::readExternal(importPath);
    invalid=false;try{importStore.confirmImport(importPlan);}catch(...){invalid=true;}
    check(invalid&&WindowsPersonalStore::readExternal(importPath)==expectedAfterChange,"changed data refuses stale merge instead of recomputing");
    auto freshPlan=importStore.previewImport(incoming);
    incoming.words.front().text="not displayed";
    check(importStore.confirmImport(freshPlan).data.serialize()==freshPlan.data().serialize()&&
          WindowsPersonalStore(importPath).read().data.words.back().text=="displayed",
          "confirmation writes only the frozen result and persists across reopen");
    auto replacePath=(folder/L"became-readable.json").wstring();
    writeOwned(replacePath,"bad before repair");
    WindowsPersonalStore replaceStore(replacePath);
    auto oldRepair=replaceStore.previewImport(one("must not replace valid data"));
    writeOwned(replacePath,one("now readable").serialize());
    invalid=false;try{replaceStore.confirmImport(oldRepair);}catch(...){invalid=true;}
    check(oldRepair.repair()&&invalid&&replaceStore.readFresh().data.words.front().text=="now readable",
          "repair refuses to replace a concurrently readable store");
    auto repairPlan=broken.previewImport(one("explicit repair"));
    check(repairPlan.repair(),"unreadable store offers explicitly labeled backup repair preview");
    writeOwned(brokenPath+L".bak","changed owned backup JSON");
    invalid=false;try{broken.confirmImport(repairPlan);}catch(...){invalid=true;}
    check(invalid&&WindowsPersonalStore::readExternal(brokenPath)=="invalid owned test JSON"&&
          WindowsPersonalStore::readExternal(brokenPath+L".bak")=="changed owned backup JSON",
          "changed damaged backup invalidates repair preview without replacing either file");
    broken.confirmImport(broken.previewImport(one("explicit repair")));
    check(broken.read().data.words.size()==1&&broken.read().data.words[0].text=="explicit repair","explicit confirmed import repairs an otherwise unreadable store");
    bool brokenArchived=false;for(const auto& entry:std::filesystem::directory_iterator(folder))if(entry.path().extension()==L".invalid")brokenArchived|=WindowsPersonalStore::readExternal(entry.path().wstring())=="invalid owned test JSON";check(brokenArchived,"explicit external-backup repair retains every unreadable canonical file");
    bool invalidBackupArchived=false;for(const auto& entry:std::filesystem::directory_iterator(folder))if(entry.path().extension()==L".invalid")invalidBackupArchived|=WindowsPersonalStore::readExternal(entry.path().wstring())=="changed owned backup JSON";check(invalidBackupArchived,"explicit repair archives unreadable backup independently before later backup rotation");
    auto oversizedPath=(folder/L"oversized-damaged.json").wstring();
    std::string oversized(1024*1024+1,'x');
    writeOwned(oversizedPath,oversized);
    WindowsPersonalStore oversizedStore(oversizedPath);
    auto oversizedPlan=oversizedStore.previewImport(one("oversized recovery"));
    oversized.back()='y';
    writeOwned(oversizedPath,oversized);
    invalid=false;try{oversizedStore.confirmImport(oversizedPlan);}catch(...){invalid=true;}
    check(oversizedPlan.repair()&&invalid&&std::filesystem::file_size(oversizedPath)==oversized.size(),
          "oversized damaged file stays repairable but a same-size change invalidates the preview");
    oversizedStore.confirmImport(oversizedStore.previewImport(one("oversized recovery")));
    bool oversizedArchived=false;
    for(const auto& entry:std::filesystem::directory_iterator(folder))
        if(entry.path().extension()==L".invalid"&&std::filesystem::file_size(entry.path())==oversized.size()){
            std::ifstream evidence(entry.path(),std::ios::binary);
            evidence.seekg(-1,std::ios::end);
            oversizedArchived=evidence.get()=='y';
        }
    check(oversizedArchived&&oversizedStore.readFresh().data.words.front().text=="oversized recovery",
          "explicit repair preserves the oversized original and publishes the validated candidate");
    std::vector<wchar_t> executable(32768);DWORD length=GetModuleFileNameW(nullptr,executable.data(),static_cast<DWORD>(executable.size()));if(!length||length>=executable.size())throw std::runtime_error("Test executable path unavailable");auto concurrentPath=(folder/L"two-process.json").wstring();PROCESS_INFORMATION processes[2]{};for(int i=0;i<2;i++){auto command=L"\""+std::wstring(executable.data(),length)+L"\" --append \""+concurrentPath+L"\" "+(i?L"childb":L"childa");STARTUPINFOW startup{};startup.cb=sizeof(startup);std::vector<wchar_t> mutableCommand(command.begin(),command.end());mutableCommand.push_back(0);if(!CreateProcessW(executable.data(),mutableCommand.data(),nullptr,nullptr,FALSE,0,nullptr,nullptr,&startup,&processes[i]))throw std::runtime_error("Owned child test unavailable");}
    bool children=true;for(auto& process:processes){DWORD result=WaitForSingleObject(process.hProcess,30000),code=1;if(result==WAIT_OBJECT_0)GetExitCodeProcess(process.hProcess,&code);else TerminateProcess(process.hProcess,99);children&=result==WAIT_OBJECT_0&&code==0;CloseHandle(process.hThread);CloseHandle(process.hProcess);}check(children,"both owned PE writer processes finish normally within bounded wait");check(WindowsPersonalStore(concurrentPath).read().data.words.size()==20,"cross-process named lock prevents lost additive updates");
    if(argc>=3){auto android=WindowsPersonalStore::parseImport(WindowsPersonalStore::readExternal(argv[2]));check(android.words.size()>=2&&android.phrases.size()==1&&android.phrases[0].text=="First line\n数学 α + 👩‍🔬","actual Android AtomicFile synthetic fixture is consumed intact by Windows wrapper");}
    std::cout<<"TOTAL "<<count<<" PASS; Win32 files/normalization/processes with synthetic data, GUI NOT_RUN"<<std::endl;return 0;
}catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<std::endl;return 1;}}
