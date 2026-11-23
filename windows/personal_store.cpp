#include "personal_store.h"
#include "local_store_io.h"
#include <filesystem>
#include <sstream>
#include <set>
#include <algorithm>
#include <limits>
#include <string_view>
#include <wincrypt.h>
#include "../third_party/nlohmann/json.hpp"

namespace wangshu {
namespace {
using namespace local_store_io;
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

std::string normalizedWord(const std::string& word) {
    PersonalData::validateText(word,1024*1024,false);
    auto units=wangshu::utf16(word);
    auto nfc=normalizedNfc(std::wstring(units.begin(),units.end()));
    return wangshu::utf8(std::u16string(nfc.begin(),nfc.end()));
}

std::string_view trimAscii(std::string_view value) {
    while(!value.empty()&&static_cast<unsigned char>(value.front())<=32)value.remove_prefix(1);
    while(!value.empty()&&static_cast<unsigned char>(value.back())<=32)value.remove_suffix(1);
    return value;
}

bool equalAsciiFold(std::string_view value,std::string_view lowercase) {
    if(value.size()!=lowercase.size())return false;
    return std::equal(value.begin(),value.end(),lowercase.begin(),[](char actual,char expected){
        return (actual>='A'&&actual<='Z'?actual+('a'-'A'):actual)==expected;
    });
}
std::string hashImportFile(const std::wstring& path){
    // Oversized damaged files remain eligible for repair. Hash in chunks
    // instead of storing unbounded contents in a preview.
    Handle source(CreateFileW(path.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,
                             FILE_FLAG_SEQUENTIAL_SCAN,nullptr));
    if(source.value==INVALID_HANDLE_VALUE)throw std::runtime_error("无法核对故障文件");
    struct Digest {
        HCRYPTPROV provider=0;
        HCRYPTHASH hash=0;
        ~Digest(){if(hash)CryptDestroyHash(hash);if(provider)CryptReleaseContext(provider,0);}
    } digest;
    if(!CryptAcquireContextW(&digest.provider,nullptr,nullptr,PROV_RSA_AES,CRYPT_VERIFYCONTEXT)||
            !CryptCreateHash(digest.provider,CALG_SHA_256,0,0,&digest.hash))
        throw std::runtime_error("无法核对故障文件指纹");
    BYTE buffer[16384];
    DWORD read=0;
    do{
        if(!ReadFile(source.value,buffer,sizeof(buffer),&read,nullptr)||
                (read&&!CryptHashData(digest.hash,buffer,read,0)))
            throw std::runtime_error("无法读取完整的故障文件");
    }while(read);
    BYTE result[32];
    DWORD length=sizeof(result);
    if(!CryptGetHashParam(digest.hash,HP_HASHVAL,result,&length,0)||length!=sizeof(result))
        throw std::runtime_error("无法确认故障文件指纹");
    return std::string(reinterpret_cast<const char*>(result),length);
}

std::optional<PersonalData> decodeImportBase(const std::string& bytes){
    try{return PersonalData::parse(bytes,normalizedWord);}
    catch(const std::bad_alloc&){throw;}
    catch(const std::exception&){return std::nullopt;}
}
}
WindowsPersonalStore::WindowsPersonalStore(std::wstring path):file_(std::move(path)){if(file_.empty())throw std::runtime_error("Personal-data location unavailable");}
std::wstring WindowsPersonalStore::defaultPath(){std::vector<wchar_t> value(32768);DWORD n=GetEnvironmentVariableW(L"LOCALAPPDATA",value.data(),static_cast<DWORD>(value.size()));if(!n||n>=value.size())throw std::runtime_error("LocalAppData is unavailable; built-in input remains available");return (std::filesystem::path(std::wstring(value.data(),n))/L"WangshuIme"/L"personal-v1.json").wstring();}
PersonalData WindowsPersonalStore::normalizeWords(PersonalData data){
    for(auto& word:data.words)word.text=normalizedWord(word.text);
    return PersonalData::parse(data.serialize());
}
PersonalSnapshot WindowsPersonalStore::readUncached()const{
    const bool main=metadata(file_).exists,backup=metadata(file_+L".bak").exists;
    if(main){try{auto data=PersonalData::parse(readBytes(file_),normalizedWord);return {data,data.serialize(),false};}catch(const std::exception&){if(!backup)throw;}}
    if(backup){auto data=PersonalData::parse(readBytes(file_+L".bak"),normalizedWord);return {data,data.serialize(),true};}
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
std::optional<PersonalImportPreview::FileState> WindowsPersonalStore::inspectImportFile(const std::wstring& path){
    auto state=metadata(path);
    if(!state.exists)return std::nullopt;
    if(state.size>1024*1024)return PersonalImportPreview::FileState{hashImportFile(path),true};
    return PersonalImportPreview::FileState{readBytes(path),false};
}
PersonalImportPreview WindowsPersonalStore::previewImport(const PersonalData& incoming){
    auto checked=normalizeWords(incoming);
    WriteLock guard(file_);
    PersonalImportPreview preview;
    preview.path_=file_;
    preview.main_=inspectImportFile(file_);
    preview.backup_=inspectImportFile(file_+L".bak");
    auto decode=[](const auto& file){
        return file&&!file->hashed?decodeImportBase(file->content):std::nullopt;
    };
    auto current=decode(preview.main_);
    if(!current){
        current=decode(preview.backup_);
        preview.fromBackup_=current.has_value();
    }
    preview.repair_=!current&&(preview.main_||preview.backup_);
    if(preview.repair_)preview.data_=std::move(checked);
    else if(current)preview.data_=current->merged(checked);
    else preview.data_=std::move(checked);
    return preview;
}
PersonalSnapshot WindowsPersonalStore::confirmImport(const PersonalImportPreview& preview){
    if(preview.path_!=file_)throw std::runtime_error("导入预览已失效，请重新选择文件并预览");
    WriteLock guard(file_);
    auto backup=file_+L".bak";
    if(preview.main_!=inspectImportFile(file_)||preview.backup_!=inspectImportFile(backup))
        throw std::runtime_error("预览后本机个人数据已更新，请重新选择文件并预览");
    if(preview.repair_&&preview.backup_){
        auto retained=backup+L"."+uniqueSuffix()+L".invalid";
        if(!CopyFileW(backup.c_str(),retained.c_str(),TRUE))
            throw std::runtime_error("无法保留损坏的备份，修复未写入");
    }
    PersonalSnapshot prior;
    prior.fromBackup=preview.fromBackup_||preview.repair_;
    writeAtomic(prior,preview.data_);
    auto confirmed=readUncached();
    if(confirmed.fromBackup||confirmed.revision!=preview.data_.serialize())
        throw std::runtime_error("导入结果未确认，请重新打开词库核对");
    return confirmed;
}
PersonalData WindowsPersonalStore::parseImport(const std::string& bytes){
    if(bytes.size()>1024*1024)throw std::runtime_error("Personal import exceeds 1MiB");
    std::string input=bytes;
    if(input.compare(0,3,"\xef\xbb\xbf")==0)input.erase(0,3);
    auto content=trimAscii(input);
    if(content.empty())throw std::runtime_error("Import is empty");
    if(content.front()=='{'||content.front()=='['){
        auto parsed=PersonalData::parse(input,normalizedWord);
        parsed.serialize(); // Also enforce the file limit on normalized records.
        return parsed;
    }
    PersonalData incoming;
    std::set<std::pair<std::string,std::string>> seen;
    size_t number=0,offset=0;
    bool firstRecord=true;
    while(offset<input.size()){
        auto end=input.find_first_of("\r\n",offset);
        if(end==std::string::npos)end=input.size();
        const auto line=std::string_view(input).substr(offset,end-offset);
        offset=end;
        if(offset<input.size()&&input[offset++]=='\r'&&offset<input.size()&&input[offset]=='\n')++offset;
        ++number;
        try{
            // Validate encoding even in skipped comments; invalid input is never
            // silently repaired or partly imported.
            if(!line.empty()&&!MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,line.data(),static_cast<int>(line.size()),nullptr,0))throw std::runtime_error("Invalid UTF8");
            auto trimmed=trimAscii(line);
            if(trimmed.empty()||trimmed.front()=='#')continue;
            auto tab=line.find('\t');
            if(tab==std::string_view::npos||line.find('\t',tab+1)!=std::string_view::npos)
                throw std::runtime_error("Expected exactly pinyin and text columns");
            auto left=line.substr(0,tab),right=line.substr(tab+1);
            auto leftHeader=trimAscii(left),rightHeader=trimAscii(right);
            bool header=(equalAsciiFold(leftHeader,"pinyin")&&equalAsciiFold(rightHeader,"text"))||
                (leftHeader==u8"拼音"&&rightHeader==u8"词条");
            if(header&&!firstRecord)throw std::runtime_error("Header must precede all data rows");
            firstRecord=false;
            if(header)continue;
            auto key=PersonalData::key(std::string(left));
            auto text=normalizedWord(std::string(right));
            PersonalData::validateText(text,200,false);
            if(seen.emplace(key,text).second){
                if(incoming.words.size()>=2000)throw std::runtime_error("TSV exceeds 2000 unique words");
                incoming.words.push_back({key,text});
            }
        }catch(const std::exception& e){throw std::runtime_error("TSV row "+std::to_string(number)+": "+e.what());}
    }
    if(incoming.words.empty())throw std::runtime_error("TSV contains no words");
    return PersonalData::parse(incoming.serialize());
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
    auto version=root.contains("version")?learningNumber(root["version"],6):0;
    if(version==6)learningFields(root,{"kind","version","enabled","entries","phraseAssociations","builtinPredictions","cursorPredictions","speechFormat","fuzzyMask"});
    else if(version==5)learningFields(root,{"kind","version","enabled","entries","phraseAssociations","builtinPredictions","cursorPredictions","speechFormat"});
    else if(version==4)learningFields(root,{"kind","version","enabled","entries","phraseAssociations","builtinPredictions","cursorPredictions"});
    else if(version==3)learningFields(root,{"kind","version","enabled","entries","phraseAssociations","builtinPredictions"});
    else if(version==2)learningFields(root,{"kind","version","enabled","entries","phraseAssociations"});
    else learningFields(root,{"kind","version","enabled","entries"});
    if(!version||learningString(root["kind"])!="wangshu-windows-learning"||!root["enabled"].is_boolean()||!root["entries"].is_array()||root["entries"].size()>MaxEntries)throw std::runtime_error("Unsupported learning schema");
    LearningData out;
    out.enabled=root["enabled"].get<bool>();
    if(version>=2){
        if(!root["phraseAssociations"].is_boolean())throw std::runtime_error("Invalid phrase association setting");
        out.phraseAssociations=root["phraseAssociations"].get<bool>();
    }
    if(version>=3){
        if(!root["builtinPredictions"].is_boolean())throw std::runtime_error("Invalid built-in prediction setting");
        out.builtinPredictions=root["builtinPredictions"].get<bool>();
    }
    if(version>=4){
        if(!root["cursorPredictions"].is_boolean())throw std::runtime_error("Invalid cursor prediction setting");
        out.cursorPredictions=root["cursorPredictions"].get<bool>();
    }
    if(version>=5)out.speechFormat=static_cast<voice_format::Mode>(learningNumber(root["speechFormat"],2));
    if(version==6)out.fuzzyMask=static_cast<unsigned>(learningNumber(root["fuzzyMask"],fuzzy_pinyin::AllRules));
    std::set<std::pair<std::string,std::string>> seen;
    for(const auto& item:root["entries"]){learningFields(item,{"key","text","count","pinned","updated"});auto key=learningString(item["key"]),text=learningString(item["text"]);if(key!=candidateKey(key)||text!=candidateText(text)||!item["pinned"].is_boolean()||!seen.emplace(key,text).second)throw std::runtime_error("Noncanonical or duplicate learning entry");auto count=static_cast<unsigned>(learningNumber(item["count"],MaxCount));bool pin=item["pinned"].get<bool>();if(!count&&!pin)throw std::runtime_error("Learning entry has neither weight nor pin");out.entries.push_back({key,text,count,pin,learningNumber(item["updated"],static_cast<unsigned long long>(std::numeric_limits<long long>::max()))});}
    return out;
}
std::string LearningData::serialize()const{
    LearningJson root={{"kind","wangshu-windows-learning"},{"version",1},{"enabled",enabled},{"entries",LearningJson::array()}};for(const auto& value:entries)root["entries"].push_back({{"key",value.key},{"text",value.text},{"count",value.count},{"pinned",value.pinned},{"updated",value.updated}});
    if(phraseAssociations){root["version"]=2;root["phraseAssociations"]=true;}
    if(builtinPredictions){
        root["version"]=3;
        root["phraseAssociations"]=phraseAssociations;
        root["builtinPredictions"]=true;
    }
    if(!cursorPredictions){
        root["version"]=4;
        root["phraseAssociations"]=phraseAssociations;
        root["builtinPredictions"]=builtinPredictions;
        root["cursorPredictions"]=false;
    }
    if(speechFormat!=voice_format::Mode::Raw||fuzzyMask){
        root["version"]=5;
        root["phraseAssociations"]=phraseAssociations;
        root["builtinPredictions"]=builtinPredictions;
        root["cursorPredictions"]=cursorPredictions;
        root["speechFormat"]=static_cast<unsigned>(speechFormat);
    }
    if(fuzzyMask){
        root["version"]=6;
        root["fuzzyMask"]=fuzzyMask;
    }
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

namespace {
constexpr char PortableHeader[]="WANGSHU-PORTABLE-SETTINGS\t1";
constexpr char VoiceSetting[]="keyboard\tvoiceOutputMode\ti\t";
constexpr char BuiltinSetting[]="associations\tbuiltinPredictions\tb\t";
constexpr char CursorSetting[]="associations\tcursorPredictions\tb\t";
constexpr char PhraseSetting[]="windows\tphraseAssociations\tb\t";

bool portableBoolean(std::string_view value){
    if(value=="true")return true;
    if(value=="false")return false;
    throw std::runtime_error("Invalid portable setting boolean");
}
}

PortableSettings PortableSettings::parse(const std::string& bytes){
    if(bytes.empty()||bytes.size()>1024)throw std::runtime_error("Invalid portable settings file size");
    std::string_view text(bytes);
    if(text.substr(0,3)=="\xef\xbb\xbf")text.remove_prefix(3);
    // Only ASCII identifiers and scalar values are accepted after an optional BOM.
    auto nextLine=[&]{
        auto pos=text.find('\n');
        auto line=text.substr(0,pos);
        if(pos!=std::string_view::npos&&!line.empty()&&line.back()=='\r')line.remove_suffix(1);
        text.remove_prefix(pos==std::string_view::npos?text.size():pos+1);
        return line;
    };
    if(nextLine()!=PortableHeader)throw std::runtime_error("Unsupported portable settings version");
    std::optional<voice_format::Mode> speech;
    std::optional<bool> builtin,cursor,phrase;
    while(!text.empty()){
        const auto line=nextLine();
        const auto value=[&](std::string_view id){return line.substr(id.size());};
        const auto setting=[&](std::string_view id){return line.substr(0,id.size())==id;};
        if(setting(VoiceSetting)&&!speech){
            const auto digit=value(VoiceSetting);
            if(digit.size()!=1||digit.front()<'0'||digit.front()>'2')throw std::runtime_error("Invalid voice format");
            speech=static_cast<voice_format::Mode>(digit.front()-'0');
        }else if(setting(BuiltinSetting)&&!builtin)builtin=portableBoolean(value(BuiltinSetting));
        else if(setting(CursorSetting)&&!cursor)cursor=portableBoolean(value(CursorSetting));
        else if(setting(PhraseSetting)&&!phrase)phrase=portableBoolean(value(PhraseSetting));
        else throw std::runtime_error("Unknown or duplicate portable setting");
    }
    if(!speech||!builtin||!cursor)throw std::runtime_error("Incomplete portable settings backup");
    return {*speech,*builtin,*cursor,phrase};
}

PortableSettings PortableSettings::from(const LearningData& data){
    return {data.speechFormat,data.builtinPredictions,data.cursorPredictions,data.phraseAssociations};
}

std::string PortableSettings::serialize()const{
    const auto boolean=[](bool value){return value?"true":"false";};
    std::string result=std::string(PortableHeader)+"\n"+VoiceSetting+std::to_string(static_cast<unsigned>(speechFormat))+"\n"+
        BuiltinSetting+boolean(builtinPredictions)+"\n"+CursorSetting+boolean(cursorPredictions)+"\n";
    if(windowsPhraseAssociations)result+=std::string(PhraseSetting)+boolean(*windowsPhraseAssociations)+"\n";
    parse(result);
    return result;
}

void PortableSettings::apply(LearningData& data)const{
    data.speechFormat=speechFormat;
    data.builtinPredictions=builtinPredictions;
    data.cursorPredictions=cursorPredictions;
    if(windowsPhraseAssociations)data.phraseAssociations=*windowsPhraseAssociations;
}

void WindowsPersonalStore::exportPortableSettingsNew(const std::wstring& path)const{
    const auto current=readLearningFresh();
    if(current.fromBackup)throw std::runtime_error("Restore the verified local backup before exporting settings");
    const auto bytes=PortableSettings::from(current.data).serialize();
    OwnedTemporary temporary(path+L"."+uniqueSuffix()+L".tmp");
    temporary.write(bytes);
    temporary.publish(path,false);
    if(readBytes(path)!=bytes)throw std::runtime_error("Settings export could not be read back");
}
unsigned long long WindowsPersonalStore::learningNow(){FILETIME time{};GetSystemTimeAsFileTime(&time);unsigned long long ticks=(static_cast<unsigned long long>(time.dwHighDateTime)<<32)|time.dwLowDateTime;constexpr unsigned long long Epoch=116444736000000000ULL;return ticks<Epoch?0:(ticks-Epoch)/10000;}

}
