#include "personal_store.h"
#include <filesystem>
#include <sstream>
#include <set>
#include <algorithm>
#include <limits>
#include "../third_party/nlohmann/json.hpp"

namespace wangshu {
namespace {
struct Handle {
    HANDLE value=INVALID_HANDLE_VALUE;
    explicit Handle(HANDLE h):value(h){}
    ~Handle(){if(value!=INVALID_HANDLE_VALUE&&value!=nullptr)CloseHandle(value);}
    Handle(const Handle&)=delete;Handle& operator=(const Handle&)=delete;
};
struct WriteLock {
    Handle handle;
    bool owned=false;
    explicit WriteLock(const std::wstring& path):handle([&]{
        std::vector<wchar_t> buffer(32768);DWORD n=GetFullPathNameW(path.c_str(),static_cast<DWORD>(buffer.size()),buffer.data(),nullptr);
        if(!n||n>=buffer.size())throw std::runtime_error("Personal path unavailable");
        unsigned long long hash=1469598103934665603ULL;
        for(DWORD i=0;i<n;i++){wchar_t c=buffer[i];if(c>=L'A'&&c<=L'Z')c+=32;if(c==L'/')c=L'\\';hash^=static_cast<unsigned short>(c);hash*=1099511628211ULL;}
        auto name=L"Local\\WangshuImePersonal-"+std::to_wstring(hash);
        return CreateMutexW(nullptr,FALSE,name.c_str());
    }()){
        if(!handle.value)throw std::runtime_error("Personal write lock unavailable");
        DWORD result=WaitForSingleObject(handle.value,5000);
        if(result!=WAIT_OBJECT_0&&result!=WAIT_ABANDONED)throw std::runtime_error("Another personal-data operation is still running");
        owned=true;
    }
    ~WriteLock(){if(owned)ReleaseMutex(handle.value);}
};
std::wstring uniqueSuffix(){GUID id{};if(FAILED(CoCreateGuid(&id)))throw std::runtime_error("Temporary identifier unavailable");wchar_t buffer[40];StringFromGUID2(id,buffer,40);return buffer;}
struct Stamp {bool exists=false;unsigned long long time=0,size=0;};
Stamp metadata(const std::wstring& path){WIN32_FILE_ATTRIBUTE_DATA value{};if(!GetFileAttributesExW(path.c_str(),GetFileExInfoStandard,&value)){DWORD error=GetLastError();if(error==ERROR_FILE_NOT_FOUND||error==ERROR_PATH_NOT_FOUND)return {};throw std::runtime_error("Personal-data file metadata is unavailable");}if(value.dwFileAttributes&FILE_ATTRIBUTE_DIRECTORY)throw std::runtime_error("Expected a personal-data file, not a directory");Stamp out;out.exists=true;out.time=(static_cast<unsigned long long>(value.ftLastWriteTime.dwHighDateTime)<<32)|value.ftLastWriteTime.dwLowDateTime;out.size=(static_cast<unsigned long long>(value.nFileSizeHigh)<<32)|value.nFileSizeLow;return out;}
std::string signature(const std::wstring& path){auto a=metadata(path),b=metadata(path+L".bak");return std::to_string(a.exists)+":"+std::to_string(a.time)+":"+std::to_string(a.size)+":"+std::to_string(b.exists)+":"+std::to_string(b.time)+":"+std::to_string(b.size);}
std::string readBytes(const std::wstring& path){
    Handle handle(CreateFileW(path.c_str(),GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr));
    if(handle.value==INVALID_HANDLE_VALUE)throw std::runtime_error("Cannot read selected personal-data file");
    LARGE_INTEGER size{};if(!GetFileSizeEx(handle.value,&size)||size.QuadPart<0||size.QuadPart>1024*1024)throw std::runtime_error("Personal file exceeds 1MiB or size is unavailable");
    std::string bytes;bytes.reserve(static_cast<size_t>(size.QuadPart));char buffer[16384];DWORD n=0;
    do{if(!ReadFile(handle.value,buffer,sizeof(buffer),&n,nullptr))throw std::runtime_error("Personal-data read did not complete");if(bytes.size()+n>1024*1024)throw std::runtime_error("Personal file grew beyond 1MiB");bytes.append(buffer,n);}while(n);
    return bytes;
}
void writeCreated(const std::wstring& path,const std::string& bytes){
    Handle handle(CreateFileW(path.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr));
    if(handle.value==INVALID_HANDLE_VALUE)throw std::runtime_error("Output already exists or cannot be created; nothing overwritten");
    size_t done=0;while(done<bytes.size()){DWORD n=0,part=static_cast<DWORD>(std::min<size_t>(16384,bytes.size()-done));if(!WriteFile(handle.value,bytes.data()+done,part,&n,nullptr)||n!=part)throw std::runtime_error("Personal-data write did not complete");done+=n;}
    if(!FlushFileBuffers(handle.value))throw std::runtime_error("Personal-data flush did not complete");
}
// The handle identifies only this operation's CREATE_NEW file. Never scan or
// delete by path: a different file can appear at the same path after a race.
struct OwnedTemporary {
    Handle handle;
    std::wstring path;
    bool owned=true;
    explicit OwnedTemporary(std::wstring name):handle(CreateFileW(name.c_str(),GENERIC_WRITE|DELETE,FILE_SHARE_DELETE,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr)),path(std::move(name)){
        if(handle.value==INVALID_HANDLE_VALUE){owned=false;throw std::runtime_error("Temporary file already exists or cannot be created");}
    }
    void write(const std::string& bytes){
        size_t done=0;while(done<bytes.size()){DWORD n=0,part=static_cast<DWORD>(std::min<size_t>(16384,bytes.size()-done));if(!WriteFile(handle.value,bytes.data()+done,part,&n,nullptr)||n!=part)throw std::runtime_error("Personal-data write did not complete");done+=n;}
        if(!FlushFileBuffers(handle.value))throw std::runtime_error("Personal-data flush did not complete");
    }
    void publish(const std::wstring& destination){
        if(!MoveFileExW(path.c_str(),destination.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("Atomic replacement failed; prior data retained");
        owned=false; // Destination now owns the bytes; never delete it on readback failure.
        bool closed=CloseHandle(handle.value)!=FALSE;handle.value=INVALID_HANDLE_VALUE;
        if(!closed)throw std::runtime_error("Replacement completed but temporary handle close failed; refresh before retrying");
    }
    bool discard() noexcept {
        if(!owned)return true;
        FILE_DISPOSITION_INFO info{};info.DeleteFile=TRUE;
        bool marked=SetFileInformationByHandle(handle.value,FileDispositionInfo,&info,sizeof(info))!=FALSE;
        bool closed=CloseHandle(handle.value)!=FALSE;handle.value=INVALID_HANDLE_VALUE;owned=false;
        // Deletion can be delayed by another handle or denied; report uncertainty.
        if(!marked||!closed)return false;
        DWORD attributes=GetFileAttributesW(path.c_str());if(attributes!=INVALID_FILE_ATTRIBUTES)return false;
        DWORD error=GetLastError();return error==ERROR_FILE_NOT_FOUND||error==ERROR_PATH_NOT_FOUND;
    }
    ~OwnedTemporary(){if(owned)discard();}
};
std::wstring normalizedNfc(const std::wstring& input){
    if(input.empty())return input;
    int length=NormalizeString(NormalizationC,input.data(),static_cast<int>(input.size()),nullptr,0);
    if(length<=0)throw std::runtime_error("Unicode normalization is unavailable");
    // The documented initial size is an estimate; retry a bounded larger buffer.
    for(int attempt=0;attempt<4&&length<=65536;attempt++){
        std::wstring out(static_cast<size_t>(length),L'\0');
        int n=NormalizeString(NormalizationC,input.data(),static_cast<int>(input.size()),out.data(),length);
        if(n>0){out.resize(static_cast<size_t>(n));return out;}
        if(GetLastError()!=ERROR_INSUFFICIENT_BUFFER)break;
        length=n<0?-n:length*2+16;
    }
    throw std::runtime_error("Unicode normalization did not complete");
}
}
WindowsPersonalStore::WindowsPersonalStore(std::wstring path):file_(std::move(path)){if(file_.empty())throw std::runtime_error("Personal-data location unavailable");}
std::wstring WindowsPersonalStore::defaultPath(){std::vector<wchar_t> value(32768);DWORD n=GetEnvironmentVariableW(L"LOCALAPPDATA",value.data(),static_cast<DWORD>(value.size()));if(!n||n>=value.size())throw std::runtime_error("LocalAppData is unavailable; built-in input remains available");return (std::filesystem::path(std::wstring(value.data(),n))/L"WangshuIme"/L"personal-v1.json").wstring();}
PersonalData WindowsPersonalStore::normalizeWords(PersonalData data){for(auto& word:data.words){auto utf16=wangshu::utf16(word.text);auto nfc=normalizedNfc(std::wstring(utf16.begin(),utf16.end()));word.text=wangshu::utf8(std::u16string(nfc.begin(),nfc.end()));}return PersonalData::parse(data.serialize());}
PersonalSnapshot WindowsPersonalStore::readUncached()const{
    const bool main=metadata(file_).exists,backup=metadata(file_+L".bak").exists;
    if(main){try{auto data=normalizeWords(PersonalData::parse(readBytes(file_)));return {data,data.serialize(),false};}catch(const std::exception&){if(!backup)throw;}}
    if(backup){auto data=normalizeWords(PersonalData::parse(readBytes(file_+L".bak")));return {data,data.serialize(),true};}
    PersonalData empty;return {empty,empty.serialize(),false};
}
PersonalSnapshot WindowsPersonalStore::read(){std::lock_guard<std::mutex> guard(cacheGate_);auto stamp=signature(file_);if(haveCache_&&stamp==stamp_)return cache_;auto fresh=readUncached();cache_=fresh;stamp_=stamp;haveCache_=true;return fresh;}
PersonalSnapshot WindowsPersonalStore::readFresh(){auto fresh=readUncached();std::lock_guard<std::mutex> guard(cacheGate_);haveCache_=false;return fresh;}
void WindowsPersonalStore::writeAtomic(const PersonalSnapshot& old,const PersonalData& input){
    auto data=normalizeWords(input);auto bytes=data.serialize();auto parent=std::filesystem::path(file_).parent_path();if(!parent.empty())std::filesystem::create_directories(parent);
    auto temporary=file_+L"."+uniqueSuffix()+L".tmp";writeCreated(temporary,bytes);
    // Both canonical and last-good backup survive a rejected/incomplete save.
    // A corrupt canonical is retained separately instead of replacing a good backup.
    if(metadata(file_).exists){auto backup=old.fromBackup?file_+L"."+uniqueSuffix()+L".invalid":file_+L".bak";if(!CopyFileW(file_.c_str(),backup.c_str(),old.fromBackup?TRUE:FALSE))throw std::runtime_error("Cannot retain prior personal data; save cancelled");}
    if(!MoveFileExW(temporary.c_str(),file_.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("Atomic personal-data replacement failed; prior file retained");
    std::lock_guard<std::mutex> guard(cacheGate_);haveCache_=false;
}
PersonalSnapshot WindowsPersonalStore::modify(const std::string& expected,const std::function<void(PersonalData&)>& action){WriteLock guard(file_);auto current=readUncached();if(current.revision!=expected)throw std::runtime_error("Data changed in another window; refresh before editing");auto next=current.data;action(next);writeAtomic(current,next);return read();}
PersonalSnapshot WindowsPersonalStore::merge(const PersonalData& source){auto checked=normalizeWords(source);WriteLock guard(file_);auto current=readUncached();auto next=current.data.merged(checked);writeAtomic(current,next);return read();}
PersonalSnapshot WindowsPersonalStore::restoreBackup(const std::string& expected){WriteLock guard(file_);auto current=readUncached();if(current.revision!=expected||!current.fromBackup)throw std::runtime_error("Recovery state changed; refresh first");writeAtomic(current,current.data);return read();}
PersonalImportPreview WindowsPersonalStore::previewImport(const PersonalData& incoming){
    auto checked=normalizeWords(incoming);
    PersonalSnapshot current;
    try{current=read();}catch(const std::exception&){return {checked,true};}
    // A capacity/validation error must not be misclassified as damaged-file repair.
    return {current.data.merged(checked),false};
}
PersonalSnapshot WindowsPersonalStore::repairFromImport(const PersonalData& incoming){
    auto checked=normalizeWords(incoming);
    WriteLock guard(file_);
    bool readable=false;
    try{readUncached();readable=true;}catch(const std::exception&){}
    if(readable)throw std::runtime_error("Data is readable now; refresh and merge instead of replacing it");
    // Preserve an unreadable backup independently too; a later normal save may
    // rotate .bak, but must not discard the only copy of damaged source bytes.
    auto backup=file_+L".bak";
    if(metadata(backup).exists){auto retained=backup+L"."+uniqueSuffix()+L".invalid";if(!CopyFileW(backup.c_str(),retained.c_str(),TRUE))throw std::runtime_error("Cannot preserve damaged backup; repair cancelled");}
    PersonalSnapshot damaged;damaged.fromBackup=true;
    writeAtomic(damaged,checked);
    return read();
}
PersonalData WindowsPersonalStore::parseImport(const std::string& bytes){
    if(bytes.size()>1024*1024)throw std::runtime_error("Personal import exceeds 1MiB");
    std::string input=bytes;if(input.compare(0,3,"\xef\xbb\xbf")==0)input.erase(0,3);auto first=input.find_first_not_of(" \t\r\n");if(first==std::string::npos)throw std::runtime_error("Import is empty");if(input[first]=='{')return normalizeWords(PersonalData::parse(input));
    PersonalData incoming;std::istringstream lines(input);std::string line;size_t number=0;
    while(std::getline(lines,line)){
        ++number;if(!line.empty()&&line.back()=='\r')line.pop_back();
        try{
            // Validate encoding even in skipped comments; invalid input is never
            // silently repaired or partly imported.
            if(!line.empty()&&!MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,line.data(),static_cast<int>(line.size()),nullptr,0))throw std::runtime_error("Invalid UTF8");
            auto content=line.find_first_not_of(" \t");if(content==std::string::npos||line[content]=='#')continue;if(line=="pinyin\ttext")continue;
            auto tab=line.find('\t');if(tab==std::string::npos||line.find('\t',tab+1)!=std::string::npos)throw std::runtime_error("Expected exactly pinyin and text columns");
            auto key=PersonalData::key(line.substr(0,tab));auto text=line.substr(tab+1);PersonalData::validateText(text,200,false);incoming.words.push_back({key,text});
            if(incoming.words.size()>2000)throw std::runtime_error("TSV exceeds 2000 words");
        }catch(const std::exception& e){throw std::runtime_error("TSV row "+std::to_string(number)+": "+e.what());}
    }
    if(incoming.words.empty())throw std::runtime_error("TSV contains no words");return normalizeWords(incoming);
}
std::string WindowsPersonalStore::readExternal(const std::wstring& path){return readBytes(path);}
void WindowsPersonalStore::exportNew(const std::wstring& path,const PersonalData& data){writeCreated(path,normalizeWords(data).serialize());}
namespace {
using LearningJson=nlohmann::json;
void learningFields(const LearningJson& object,std::initializer_list<const char*> fields){
    if(!object.is_object())throw std::runtime_error("Learning record must be an object");
    for(auto it=object.begin();it!=object.end();++it){bool found=false;for(auto key:fields)if(it.key()==key)found=true;if(!found)throw std::runtime_error("Unknown learning field");}
    for(auto key:fields)if(!object.contains(key))throw std::runtime_error("Missing learning field");
}
unsigned long long learningNumber(const LearningJson& value,unsigned long long maximum){
    if(!value.is_number_integer()||(!value.is_number_unsigned()&&value.get<long long>()<0))throw std::runtime_error("Learning number must be nonnegative integer");
    auto number=value.get<unsigned long long>();if(number>maximum)throw std::runtime_error("Learning number exceeds bound");return number;
}
std::string learningString(const LearningJson& value){if(!value.is_string())throw std::runtime_error("Learning string required");return value.get<std::string>();}
void validLearningTime(unsigned long long now){if(now>static_cast<unsigned long long>(std::numeric_limits<long long>::max()))throw std::runtime_error("Learning timestamp exceeds bound");}
// Terms only: no multiline phrases or invisible directional formatting are learned.
std::string checkedLearningText(const std::string& raw){
    PersonalData::validateText(raw,64,false);if(raw.size()>256)throw std::runtime_error("Learning word exceeds 256 UTF8 bytes");
    auto units=wangshu::utf16(raw);bool visible=false;
    for(size_t i=0;i<units.size();++i){unsigned long cp=units[i];if(cp>=0xd800&&cp<=0xdbff&&i+1<units.size())cp=0x10000+((cp-0xd800)<<10)+(units[++i]-0xdc00);
        if(cp==0x2028||cp==0x2029||cp==0x00ad||cp==0x061c||cp==0x180e||cp==0x200b||cp==0x200e||cp==0x200f||(cp>=0x202a&&cp<=0x202e)||cp==0x2060||(cp>=0x2065&&cp<=0x206f)||cp==0xfeff||(cp>=0xfff9&&cp<=0xfffb))throw std::runtime_error("Learning word contains unsupported invisible formatting");
        if(cp>32&&cp!=0xa0&&cp!=0x1680&&!(cp>=0x2000&&cp<=0x200d)&&cp!=0x202f&&cp!=0x205f&&!(cp>=0x2061&&cp<=0x2064)&&cp!=0x3000&&!(cp>=0xe0020&&cp<=0xe007f))visible=true;
    }
    if(!visible)throw std::runtime_error("Learning word has no visible content");
    auto nfc=normalizedNfc(std::wstring(units.begin(),units.end()));auto value=wangshu::utf8(std::u16string(nfc.begin(),nfc.end()));PersonalData::validateText(value,64,false);if(value.size()>256)throw std::runtime_error("Normalized learning word exceeds byte bound");return value;
}
size_t learningSlot(LearningData& data,const std::string& key,const std::string& text,unsigned long long now){
    for(size_t i=0;i<data.entries.size();++i)if(data.entries[i].key==key&&data.entries[i].text==text)return i;
    if(data.entries.size()>=LearningData::MaxEntries){size_t victim=data.entries.size();for(size_t i=0;i<data.entries.size();++i)if(!data.entries[i].pinned&&(victim==data.entries.size()||LearningData::effective(data.entries[i],now)<LearningData::effective(data.entries[victim],now)))victim=i;
        if(victim==data.entries.size())throw std::runtime_error("All personal ranking slots are pinned; no entry removed");data.entries.erase(data.entries.begin()+static_cast<std::ptrdiff_t>(victim));}
    data.entries.push_back({key,text,0,false,now});return data.entries.size()-1;
}
}
std::string LearningData::candidateKey(const std::string& input){if(input.empty()||input.size()>128)throw std::runtime_error("Invalid learning key");auto key=PersonalData::key(input);if(key.size()>64)throw std::runtime_error("Learning key exceeds bound");return key;}
std::string LearningData::candidateText(const std::string& text){return checkedLearningText(text);}
unsigned LearningData::effective(const LearnedCandidate& value,unsigned long long now){validLearningTime(now);auto periods=now<=value.updated?0:(now-value.updated)/DecayMillis;return periods>=8?0:value.count>>static_cast<unsigned>(periods);}
bool LearningData::record(const std::string& input,const std::string& text,unsigned long long now){validLearningTime(now);auto key=candidateKey(input),word=candidateText(text);auto index=learningSlot(*this,key,word,now);auto& value=entries[index];auto count=std::min(MaxCount,effective(value,now)+1);auto time=std::max(now,value.updated);if(value.count==count&&value.updated==time)return false;value.count=count;value.updated=time;return true;}
bool LearningData::pin(const std::string& input,const std::string& text,bool fixed,unsigned long long now){validLearningTime(now);auto key=candidateKey(input),word=candidateText(text);if(!fixed){for(auto it=entries.begin();it!=entries.end();++it)if(it->key==key&&it->text==word){if(!it->pinned)return false;it->pinned=false;if(effective(*it,now)==0)entries.erase(it);return true;}return false;}auto index=learningSlot(*this,key,word,now);auto& value=entries[index];if(value.pinned)return false;value.pinned=true;return true;}
bool LearningData::forget(const std::string& input,const std::string& text){auto key=candidateKey(input),word=candidateText(text);for(auto it=entries.begin();it!=entries.end();++it)if(it->key==key&&it->text==word){entries.erase(it);return true;}return false;}
bool LearningData::pinned(const std::string& input,const std::string& text)const{auto key=candidateKey(input),word=candidateText(text);for(const auto& value:entries)if(value.key==key&&value.text==word)return value.pinned;return false;}
LearningData LearningData::parse(const std::string& bytes){
    if(bytes.empty()||bytes.size()>MaxBytes)throw std::runtime_error("Learning file size is invalid");LearningJson root;
    try{root=LearningJson::parse(bytes,[](int depth,LearningJson::parse_event_t,LearningJson&){if(depth>8)throw std::runtime_error("Learning JSON depth exceeds schema");return true;});}catch(const LearningJson::exception&){throw std::runtime_error("Malformed learning JSON");}
    learningFields(root,{"kind","version","enabled","entries"});if(learningString(root["kind"])!="wangshu-windows-learning"||learningNumber(root["version"],1)!=1||!root["enabled"].is_boolean()||!root["entries"].is_array()||root["entries"].size()>MaxEntries)throw std::runtime_error("Unsupported learning schema");
    LearningData out;out.enabled=root["enabled"].get<bool>();std::set<std::pair<std::string,std::string>> seen;
    for(const auto& item:root["entries"]){learningFields(item,{"key","text","count","pinned","updated"});auto key=learningString(item["key"]),text=learningString(item["text"]);if(key!=candidateKey(key)||text!=candidateText(text)||!item["pinned"].is_boolean()||!seen.emplace(key,text).second)throw std::runtime_error("Noncanonical or duplicate learning entry");auto count=static_cast<unsigned>(learningNumber(item["count"],MaxCount));bool pin=item["pinned"].get<bool>();if(!count&&!pin)throw std::runtime_error("Learning entry has neither weight nor pin");out.entries.push_back({key,text,count,pin,learningNumber(item["updated"],static_cast<unsigned long long>(std::numeric_limits<long long>::max()))});}
    return out;
}
std::string LearningData::serialize()const{
    LearningJson root={{"kind","wangshu-windows-learning"},{"version",1},{"enabled",enabled},{"entries",LearningJson::array()}};for(const auto& value:entries)root["entries"].push_back({{"key",value.key},{"text",value.text},{"count",value.count},{"pinned",value.pinned},{"updated",value.updated}});
    std::string bytes;try{bytes=root.dump();}catch(const LearningJson::exception&){throw std::runtime_error("Invalid learning Unicode");}parse(bytes);return bytes;
}
std::vector<Candidate> LearningData::lookup(const Engine& base,const PersonalData& manual,const std::string& input,size_t page,unsigned long long now)const{
    if(input.empty()||input[0]=='\\'||page>1000)return manual.lookup(base,input,page);std::string key;try{key=candidateKey(input);}catch(...){return manual.lookup(base,input,page);}std::vector<LearnedCandidate> ranked;
    for(const auto& value:entries)if(value.key==key&&(value.pinned||effective(value,now)))ranked.push_back(value);if(ranked.empty())return manual.lookup(base,input,page);
    std::stable_sort(ranked.begin(),ranked.end(),[now](const LearnedCandidate& a,const LearnedCandidate& b){return a.pinned!=b.pinned?a.pinned>b.pinned:effective(a,now)>effective(b,now);});
    size_t needed=(page+1)*9;std::vector<Candidate> values;std::set<std::string> seen;for(const auto& value:ranked)if(seen.insert(value.text).second)values.push_back({value.text,value.pinned?"固定":"个人词频",static_cast<int>(effective(value,now))});
    for(size_t p=0;p<=page&&values.size()<needed;++p){auto chunk=manual.lookup(base,input,p);for(auto& value:chunk)if(seen.insert(value.text).second)values.push_back(std::move(value));if(chunk.size()<9)break;}
    size_t from=page*9;if(from>=values.size())return {};return {values.begin()+static_cast<std::ptrdiff_t>(from),values.begin()+static_cast<std::ptrdiff_t>(std::min(values.size(),needed))};
}
LearningSnapshot WindowsPersonalStore::readLearningUncached()const{
    auto path=learningPath();bool main=metadata(path).exists,backup=metadata(path+L".bak").exists;
    if(main){try{auto data=LearningData::parse(readBytes(path));return {data,data.serialize(),false};}catch(const std::exception&){if(!backup)throw;}}
    if(backup){auto data=LearningData::parse(readBytes(path+L".bak"));return {data,data.serialize(),true};}LearningData empty;return {empty,empty.serialize(),false};
}
LearningSnapshot WindowsPersonalStore::readLearningFresh()const{return readLearningUncached();}
void WindowsPersonalStore::writeLearningAtomic(const LearningSnapshot& old,const LearningData& data,const LearningWriteGate* gate){
    if(gate&&gate->current&&!gate->current())throw LearningDiscarded("Learning preparation cancelled");
    auto bytes=data.serialize();auto path=learningPath();auto parent=std::filesystem::path(path).parent_path();if(!parent.empty())std::filesystem::create_directories(parent);OwnedTemporary temporary(path+L"."+uniqueSuffix()+L".tmp");
    try{temporary.write(bytes);
    // A successful claim is the publication linearization point. Cancellation
    // after this point does not revoke this already-confirmed selection's save.
    if(gate&&gate->claim&&!gate->claim())throw LearningDiscarded("Learning publication cancelled");
    if(metadata(path).exists){auto backup=old.fromBackup?path+L"."+uniqueSuffix()+L".invalid":path+L".bak";if(!CopyFileW(path.c_str(),backup.c_str(),old.fromBackup?TRUE:FALSE))throw std::runtime_error("Cannot preserve prior learning data; save cancelled");}
    temporary.publish(path);
    }catch(const LearningDiscarded& e){std::string message=e.what();if(!temporary.discard())throw std::runtime_error(message+"; temporary cleanup not confirmed, private temporary may remain");throw;}
    catch(const std::exception& e){std::string message=e.what();if(!temporary.discard())message+="; temporary cleanup not confirmed, private temporary may remain";throw std::runtime_error(message);}
    catch(...){if(!temporary.discard())throw std::runtime_error("Learning save failed; temporary cleanup not confirmed, private temporary may remain");throw;}
    if(readBytes(path)!=bytes)throw std::runtime_error("Learning save outcome not confirmed; refresh without retrying");
}
LearningSnapshot WindowsPersonalStore::modifyLearning(const std::string& expected,const std::function<void(LearningData&)>& action,const LearningWriteGate* gate){WriteLock guard(learningPath());auto current=readLearningUncached();if(current.revision!=expected)throw LearningDiscarded("Personal ranking changed; refresh before editing");if(current.fromBackup)throw LearningDiscarded("Personal ranking uses backup; explicitly restore before editing");auto next=current.data;action(next);if(next.serialize()==current.revision)return current;writeLearningAtomic(current,next,gate);return readLearningUncached();}
LearningSnapshot WindowsPersonalStore::restoreLearningBackup(const std::string& expected){WriteLock guard(learningPath());auto current=readLearningUncached();if(current.revision!=expected||!current.fromBackup)throw std::runtime_error("Learning recovery state changed; refresh first");writeLearningAtomic(current,current.data);return readLearningUncached();}
unsigned long long WindowsPersonalStore::learningNow(){FILETIME time{};GetSystemTimeAsFileTime(&time);unsigned long long ticks=(static_cast<unsigned long long>(time.dwHighDateTime)<<32)|time.dwLowDateTime;constexpr unsigned long long Epoch=116444736000000000ULL;return ticks<Epoch?0:(ticks-Epoch)/10000;}

}
