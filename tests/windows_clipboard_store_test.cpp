// Runs the actual Win32 file transactions with synthetic text and an injected
// protector. It does not use the Windows user profile or system clipboard.
#include "../windows/clipboard_store.h"
#include "../windows/local_store_io.h"
#include <filesystem>
#include <iostream>

namespace {
using wangshu::ClipboardStore;
using wangshu::local_store_io::readBytes;
using wangshu::local_store_io::writeCreated;
namespace fs=std::filesystem;
constexpr std::uint64_t Day=24ULL*60*60*1000;
constexpr std::uint64_t Now=100*Day;
constexpr char Prefix[]="OWNED-TEST-CODEC:";
bool refuseSeal=false;
unsigned checks=0;

std::string seal(const std::string& text) {
    if (refuseSeal) throw std::runtime_error("synthetic protect failure");
    return Prefix+std::string(text.rbegin(),text.rend());
}

std::string open(const std::string& bytes) {
    if (bytes.rfind(Prefix,0)!=0) throw std::runtime_error("synthetic corrupt payload");
    return {bytes.rbegin(),bytes.rend()-static_cast<std::ptrdiff_t>(sizeof(Prefix)-1)};
}

constexpr ClipboardStore::Protection TestCodec{seal,open};

void check(bool result,const char* label) {
    if (!result) throw std::runtime_error(label);
    ++checks;
    std::cout<<"PASS "<<label<<'\n';
}

template<class Work> bool rejected(Work work) {
    try{work();}catch(const std::runtime_error&){return true;}
    return false;
}

bool record(const ClipboardStore& store,const std::string& value,std::uint64_t now=Now) {
    return store.record([&]{return std::optional<std::string>(value);},now);
}

void tests(const fs::path& folder) {
    const auto path=(folder/L"history.dat").wstring();
    ClipboardStore first(path,TestCodec),second(path,TestCodec);
    auto state=first.read(Now);
    unsigned sourceReads=0;
    check(!state.recording&&state.retentionDays==1&&state.revision.empty()&&!fs::exists(path),
          "missing history defaults off with 24-hour retention and no file");
    check(!first.record([&]{++sourceReads;return std::optional<std::string>("forbidden");},Now)&&
          !sourceReads&&!fs::exists(path),"disabled recording never calls the clipboard source");
    check(rejected([&]{first.configure(state.revision,true,2,Now);})&&!fs::exists(path),
          "unknown retention does not create a file");

    first.configure(state.revision,true,7,Now);
    const auto enabled=second.read(Now);
    check(enabled.recording&&enabled.retentionDays==7&&!enabled.revision.empty(),
          "explicit opt-in and retention survive reopening in a second store");
    check(!first.record([]{return std::optional<std::string>{};},Now)&&first.read(Now).revision==enabled.revision,
          "filtered or unavailable clipboard source leaves history unchanged");
    check(!record(first,std::string("bad\0text",8))&&!record(first,std::string("\xED\xA0\x80",3))&&
          !record(first,std::string(8001,'x'))&&first.read(Now).items.empty(),
          "embedded NUL, ill-formed UTF-8 and oversized text are rejected");

    const std::string exact=u8" A\r\ne\u0301 \U0001f469\U0001f3fd\u200d\U0001f4bb ";
    check(record(first,exact,Now),"multiline Unicode clipboard text saved");
    state=second.read(Now);
    check(state.items.size()==1&&state.items[0].text==exact&&state.items[0].copiedAt==Now&&
          readBytes(path)!=exact,"exact Unicode, whitespace and CRLF round-trip through the injected codec");
    const auto id=state.items[0].id;
    first.pin(state.revision,id,true,Now);
    check(record(second,exact,Now+3),"copying a pinned item refreshes its timestamp");
    state=first.read(Now+3);
    check(state.items.size()==1&&state.items[0].id==id&&state.items[0].pinned&&state.items[0].copiedAt==Now+3,
          "deduplication keeps the stable item identifier and pin");
    check(record(first,"older",Now+4)&&record(first,"newer",Now+5),"distinct records saved");
    state=second.read(Now+5);
    check(state.items.size()==3&&state.items[0].id==id&&state.items[1].text=="newer",
          "pinned entries lead, with newest unpinned entries next");
    check(rejected([&]{first.remove(enabled.revision,id,Now+5);})&&first.read(Now+5).items.size()==3,
          "stale editor revision cannot delete a displayed item");
    check(rejected([&]{first.remove(state.revision,"absent",Now+5);})&&first.read(Now+5).items.size()==3,
          "missing stable identifier never deletes a different item");

    const auto oldBytes=readBytes(path);
    refuseSeal=true;
    const bool failed=rejected([&]{first.remove(state.revision,state.items[1].id,Now+5);});
    refuseSeal=false;
    check(failed&&readBytes(path)==oldBytes,"protection failure leaves canonical bytes untouched");
    check(first.read(Now-1).items.size()==3,"clock rollback does not expire entries with future timestamps");
    first.remove(state.revision,state.items[1].id,Now+5);
    state=second.read(Now+5);
    check(state.items.size()==2&&state.items[1].text=="older","single delete removes exactly the selected stable item");
    first.configure(state.revision,false,1,Now+5);
    state=second.read(Now+5);
    check(!state.recording&&state.items.size()==2&&!record(second,"disabled",Now+5),
          "disable preserves existing records and prevents new records across stores");

    const auto backup=(folder/L"snapshot.wsc").wstring();
    first.exportBackup(backup,state.revision,Now+5);
    const auto backupBytes=readBytes(backup);
    check(rejected([&]{first.exportBackup(backup,state.revision,Now+5);})&&readBytes(backup)==backupBytes,
          "backup creates a new file and preserves a preexisting destination");
    auto merge=second.previewBackup(backup,Now+5);
    check(merge.added==0&&merge.conflicts==2&&!merge.repairing&&first.read(Now+5).revision==state.revision,
          "backup preview reads without publishing and identifies duplicates");
    first.clear(state.revision,false,Now+5);
    state=second.read(Now+5);
    check(state.items.size()==1&&state.items[0].pinned,"clear unpinned preserves the pinned item");
    merge=second.previewBackup(backup,Now+5);
    check(merge.added==1&&merge.conflicts==1,"merge preview separates additions and duplicate entries");
    first.configure(state.revision,false,7,Now+5);
    check(rejected([&]{second.restoreBackup(merge);})&&first.read(Now+5).items.size()==1,
          "restore refuses a preview when another instance changed settings");
    second.restoreBackup(second.previewBackup(backup,Now+5));
    state=first.read(Now+5);
    check(state.items.size()==2&&!state.recording&&state.retentionDays==7,
          "explicit backup merge preserves settings, including the disabled recording switch");
    first.configure(state.revision,false,1,Now+Day+5);
    state=second.read(Now+Day+5);
    check(state.items.size()==1&&state.items[0].id==id,"shortened retention expires only unpinned old entries");
    auto aged=second.previewBackup(backup,Now+Day+5);
    check(aged.expired==1&&aged.conflicts==1&&aged.added==0,"import cannot resurrect expired unpinned entries");
    first.clear(state.revision,true,Now+Day+5);
    check(second.read(Now+Day+5).items.empty(),"explicit clear-all removes pinned and unpinned entries");

    const auto broken=(folder/L"broken.dat").wstring();
    writeCreated(broken,"");
    ClipboardStore damaged(broken,TestCodec);
    check(rejected([&]{damaged.read(Now);})&&rejected([&]{record(damaged,"unreadable");})&&readBytes(broken).empty(),
          "unreadable canonical file blocks recording and remains intact");
    auto repair=damaged.previewBackup(backup,Now+5);
    check(repair.repairing&&repair.added==2&&readBytes(broken).empty(),"corruption repair preview preserves original bytes");
    damaged.restoreBackup(repair);
    const auto restored=damaged.read(Now+5);
    unsigned archives=0;
    for(const auto& file:fs::directory_iterator(folder)){
        const auto name=file.path().filename().wstring();
        if(name.rfind(L"broken.dat.",0)==0&&file.path().extension()==L".invalid"&&readBytes(file.path().wstring()).empty())
            ++archives;
    }
    check(restored.items.size()==2&&!restored.recording&&archives==1,
          "confirmed repair retains the corrupt source and starts with recording off");
    check(rejected([&]{damaged.previewBackup(broken,Now+5);}),
          "live store cannot be interpreted as a user-selected backup");
    check(rejected([&]{first.restoreBackup(repair);}),"restore preview is bound to its original store");

    state=first.read(Now+Day+5);
    first.configure(state.revision,true,1,Now+Day+5);
    const bool late=first.record([&]{
        const auto current=second.read(Now+Day+5);
        second.configure(current.revision,false,1,Now+Day+5);
        return std::optional<std::string>("late source");
    },Now+Day+5);
    check(!late&&!first.read(Now+Day+5).recording&&first.read(Now+Day+5).items.empty(),
          "reentrant opt-out during source capture prevents a stale write");

    const auto concurrent=(folder/L"multiprocess.dat").wstring();
    ClipboardStore concurrentStore(concurrent,TestCodec);
    concurrentStore.configure("",true,7,Now);
}
}

int main(int argc,char** argv) {
    try {
        if(argc!=2&&argc!=3)return 2;
        const auto root=fs::absolute(fs::path(argv[1]));
        if(root.root_name()!=L"E:")return 2;
        if(argc==2){
            tests(root);
            std::cout<<"PASS "<<checks<<" clipboard file-transaction assertions\n";
            return 0;
        }
        const ClipboardStore store((root/L"multiprocess.dat").wstring(),TestCodec);
        const std::string operation=argv[2];
        if(operation=="writer-a"||operation=="writer-b")return record(store,operation)?0:1;
        if(operation=="verify-processes"){
            const auto items=store.read(Now).items;
            if(items.size()!=2||items[0].id==items[1].id)return 1;
            const auto seen=[&](const std::string& value){
                return std::any_of(items.begin(),items.end(),[&](const auto& item){return item.text==value;});
            };
            check(seen("writer-a")&&seen("writer-b"),"parallel processes preserve both independently recorded items");
            return 0;
        }
        return 2;
    } catch(const std::exception& error) {
        std::cerr<<"FAIL "<<error.what()<<'\n';
        return 1;
    }
}
