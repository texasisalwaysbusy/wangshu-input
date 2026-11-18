#include "clipboard_store.h"
#include "local_store_io.h"
#include "personal_store.h"
#include "../third_party/nlohmann/json.hpp"

#include <wincrypt.h>
#include <filesystem>
#include <set>

namespace wangshu {
namespace {

using Json=nlohmann::json;
using namespace local_store_io;
constexpr size_t MaxItems=100;
constexpr size_t MaxScalars=8000;
constexpr size_t MaxPlainBytes=1024*1024-4096;
constexpr size_t MaxProtectedBytes=1024*1024;
constexpr std::uint64_t Day=24ULL*60*60*1000;
constexpr DWORD ClipboardLockTimeout=250;

bool validDays(unsigned days) {
    return days==1||days==7||days==30;
}

bool validId(const std::string& id) {
    if (id.size()!=38||id.front()!='{'||id.back()!='}') return false;
    for (size_t index=1;index<37;++index) {
        const char c=id[index];
        if (index==9||index==14||index==19||index==24) {
            if (c!='-') return false;
        } else if (!((c>='0'&&c<='9')||(c>='A'&&c<='F'))) return false;
    }
    return true;
}

std::string newId() {
    const auto value=uniqueSuffix();
    std::string result(value.begin(),value.end());
    for (auto& unit:result) if (unit>='a'&&unit<='f') unit=static_cast<char>(unit-'a'+'A');
    return result;
}

bool validText(const std::string& text) {
    if (text.empty()||text.size()>4*MaxScalars||text.find('\0')!=std::string::npos) return false;
    if (!MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,text.data(),static_cast<int>(text.size()),nullptr,0))
        return false;
    return static_cast<size_t>(std::count_if(text.begin(),text.end(),[](unsigned char unit) {
        return (unit&0xc0)!=0x80;
    }))<=MaxScalars;
}

void order(ClipboardHistory& data) {
    std::stable_sort(data.items.begin(),data.items.end(),[](const ClipboardItem& a,const ClipboardItem& b) {
        if (a.pinned!=b.pinned) return a.pinned;
        return a.copiedAt>b.copiedAt;
    });
}

size_t expire(ClipboardHistory& data,std::uint64_t now) {
    const auto before=data.items.size();
    data.items.erase(std::remove_if(data.items.begin(),data.items.end(),[&](const ClipboardItem& item) {
        return !item.pinned&&now>=item.copiedAt&&now-item.copiedAt>=data.retentionDays*Day;
    }),data.items.end());
    return before-data.items.size();
}

Json toJson(const ClipboardHistory& data,bool backup) {
    Json result={{"kind",backup?"wangshu-clipboard-backup":"wangshu-clipboard"},
                 {"version",1},{"items",Json::array()}};
    if (!backup) {
        result["recording"]=data.recording;
        result["retentionDays"]=data.retentionDays;
        result["revision"]=data.revision;
    }
    for (const auto& item:data.items)
        result["items"].push_back({{"id",item.id},{"text",item.text},{"at",item.copiedAt},{"pinned",item.pinned}});
    return result;
}

ClipboardHistory parse(const std::string& bytes,bool backup) {
    if (bytes.size()>MaxPlainBytes) throw std::runtime_error("Clipboard data exceeds its limit");
    try {
        const auto json=Json::parse(bytes,[](int depth,Json::parse_event_t,Json&) {
            if (depth>5) throw std::runtime_error("Clipboard data is not a supported file");
            return true;
        });
        if (!json.is_object()||json.size()!=(backup?3:6)||
            json.at("kind")!=(backup?"wangshu-clipboard-backup":"wangshu-clipboard")||
            !json.at("version").is_number_integer()||json.at("version")!=1||
            !json.at("items").is_array()||json.at("items").size()>MaxItems)
            throw std::runtime_error("Clipboard data is not a supported file");
        ClipboardHistory data;
        if (!backup) {
            if (!json.at("recording").is_boolean()||!json.at("retentionDays").is_number_unsigned()||
                json.at("retentionDays").get<std::uint64_t>()>30)
                throw std::runtime_error("Clipboard settings are invalid");
            data.recording=json.at("recording").get<bool>();
            data.retentionDays=json.at("retentionDays").get<unsigned>();
            data.revision=json.at("revision").get<std::string>();
            if (!validDays(data.retentionDays)||!validId(data.revision))
                throw std::runtime_error("Clipboard settings are invalid");
        }
        std::set<std::string> ids,texts;
        for (const auto& row:json.at("items")) {
            if (!row.is_object()||row.size()!=4||!row.at("at").is_number_unsigned()||
                !row.at("pinned").is_boolean())
                throw std::runtime_error("Clipboard entry is invalid");
            ClipboardItem item{row.at("id").get<std::string>(),row.at("text").get<std::string>(),
                               row.at("at").get<std::uint64_t>(),row.at("pinned").get<bool>()};
            if (!validId(item.id)||!validText(item.text)||!ids.insert(item.id).second||
                !texts.insert(item.text).second)
                throw std::runtime_error("Clipboard entry is invalid");
            data.items.push_back(std::move(item));
        }
        order(data);
        return data;
    } catch (const Json::exception&) {
        throw std::runtime_error("Clipboard data is not a supported file");
    }
}

std::string protectedBytes(const std::string& bytes,bool protect) {
    DATA_BLOB input{static_cast<DWORD>(bytes.size()),reinterpret_cast<BYTE*>(const_cast<char*>(bytes.data()))};
    struct Output {
        DATA_BLOB blob{};
        ~Output() {
            if (blob.pbData) {
                SecureZeroMemory(blob.pbData,blob.cbData);
                LocalFree(blob.pbData);
            }
        }
    } output;
    const BOOL ok=protect
        ? CryptProtectData(&input,L"Wangshu Clipboard",nullptr,nullptr,nullptr,CRYPTPROTECT_UI_FORBIDDEN,&output.blob)
        : CryptUnprotectData(&input,nullptr,nullptr,nullptr,nullptr,CRYPTPROTECT_UI_FORBIDDEN,&output.blob);
    if (!ok||!output.blob.pbData||output.blob.cbData>MaxProtectedBytes)
        throw std::runtime_error("Windows could not protect or open this clipboard file");
    return {reinterpret_cast<const char*>(output.blob.pbData),output.blob.cbData};
}

void writeProtected(const std::wstring& path,const std::string& bytes,bool replace) {
    if (bytes.empty()||bytes.size()>MaxProtectedBytes)
        throw std::runtime_error("Protected clipboard data exceeds its limit");
    OwnedTemporary temporary(path+L"."+uniqueSuffix()+L".tmp");
    temporary.write(bytes);
    temporary.publish(path,replace);
    if (readBytes(path)!=bytes) throw std::runtime_error("Clipboard write is unconfirmed; refresh before retrying");
}

ClipboardItem& findItem(ClipboardHistory& data,const std::string& id) {
    auto item=std::find_if(data.items.begin(),data.items.end(),[&](const auto& value){return value.id==id;});
    if (item==data.items.end()) throw std::runtime_error("Clipboard entry changed; refresh before editing");
    return *item;
}

void requireRevision(const ClipboardHistory& data,const std::string& revision) {
    if (data.revision!=revision) throw std::runtime_error("Clipboard history changed; refresh before editing");
}

}

ClipboardStore::ClipboardStore()
    : ClipboardStore((std::filesystem::path(WindowsPersonalStore::defaultPath()).parent_path()/L"clipboard-v1.dat").wstring(),
                     {[](const std::string& bytes){return protectedBytes(bytes,true);},
                      [](const std::string& bytes){return protectedBytes(bytes,false);}}) {}

ClipboardStore::ClipboardStore(std::wstring file,Protection codec)
    : path(std::move(file)),protection(codec) {
    if (path.empty()||!protection.seal||!protection.open)
        throw std::runtime_error("Clipboard storage is unavailable");
}

std::optional<std::string> ClipboardStore::storedBytes() const {
    if (!metadata(path).exists) return std::nullopt;
    return readBytes(path);
}

ClipboardHistory ClipboardStore::decode(const std::optional<std::string>& bytes) const {
    return bytes?parse(protection.open(*bytes),false):ClipboardHistory{};
}

void ClipboardStore::save(ClipboardHistory& data) const {
    order(data);
    data.revision=newId();
    const auto bytes=toJson(data,false).dump();
    if (data.items.size()>MaxItems||bytes.size()>MaxPlainBytes)
        throw std::runtime_error("Clipboard history is full");
    const auto sealed=protection.seal(bytes);
    const auto parent=std::filesystem::path(path).parent_path();
    if (!parent.empty()) std::filesystem::create_directories(parent);
    writeProtected(path,sealed,true);
}

ClipboardHistory ClipboardStore::read(std::uint64_t now) const {
    WriteLock lock(path,ClipboardLockTimeout);
    auto data=decode(storedBytes());
    if (expire(data,now)) save(data);
    return data;
}

void ClipboardStore::change(const std::string& revision,std::uint64_t now,
                            const std::function<void(ClipboardHistory&)>& action) const {
    WriteLock lock(path,ClipboardLockTimeout);
    auto data=decode(storedBytes());
    requireRevision(data,revision);
    const auto before=toJson(data,false);
    expire(data,now);
    action(data);
    expire(data,now);
    if (toJson(data,false)!=before) save(data);
}

void ClipboardStore::configure(const std::string& revision,bool recording,unsigned days,std::uint64_t now) const {
    if (!validDays(days)) throw std::runtime_error("Unsupported clipboard retention");
    change(revision,now,[&](ClipboardHistory& data){data.recording=recording;data.retentionDays=days;});
}

bool ClipboardStore::record(const ReadText& readText,std::uint64_t now) const {
    WriteLock lock(path,ClipboardLockTimeout);
    const auto before=storedBytes();
    auto data=decode(before);
    if (!data.recording||!readText) return false;
    const auto text=readText();
    if (!text||!validText(*text)) return false;
    if (storedBytes()!=before) return false;
    const auto expired=expire(data,now);
    const auto existing=std::find_if(data.items.begin(),data.items.end(),[&](const auto& item){return item.text==*text;});
    if (existing!=data.items.end()) {
        if (expired||now>existing->copiedAt) {
            existing->copiedAt=std::max(now,existing->copiedAt);
            save(data);
        }
        return true;
    }
    order(data);
    data.items.push_back({newId(),*text,now,false});
    const auto recordedId=data.items.back().id;
    while (data.items.size()>MaxItems||toJson(data,false).dump().size()>MaxPlainBytes) {
        const auto oldest=std::find_if(data.items.rbegin(),data.items.rend(),[&](const auto& item) {
            return !item.pinned&&item.id!=recordedId;
        });
        if (oldest==data.items.rend()) return false;
        data.items.erase(std::next(oldest).base());
    }
    save(data);
    return true;
}

void ClipboardStore::pin(const std::string& revision,const std::string& id,bool pinned,std::uint64_t now) const {
    change(revision,now,[&](ClipboardHistory& data){findItem(data,id).pinned=pinned;});
}

void ClipboardStore::remove(const std::string& revision,const std::string& id,std::uint64_t now) const {
    change(revision,now,[&](ClipboardHistory& data) {
        const auto index=&findItem(data,id)-data.items.data();
        data.items.erase(data.items.begin()+index);
    });
}

void ClipboardStore::clear(const std::string& revision,bool includePinned,std::uint64_t now) const {
    change(revision,now,[&](ClipboardHistory& data) {
        if (includePinned) data.items.clear();
        else data.items.erase(std::remove_if(data.items.begin(),data.items.end(),
            [](const ClipboardItem& item){return !item.pinned;}),data.items.end());
    });
}

void ClipboardStore::exportBackup(const std::wstring& destination,const std::string& revision,std::uint64_t now) const {
    WriteLock lock(path,ClipboardLockTimeout);
    auto data=decode(storedBytes());
    requireRevision(data,revision);
    expire(data,now);
    if (data.items.empty()) throw std::runtime_error("Clipboard history is empty");
    writeProtected(destination,protection.seal(toJson(data,true).dump()),false);
}

ClipboardRestorePreview ClipboardStore::previewBackup(const std::wstring& source,std::uint64_t now) const {
    const auto incoming=parse(protection.open(readBytes(source)),true);
    WriteLock lock(path,ClipboardLockTimeout);
    ClipboardRestorePreview preview;
    preview.store=path;
    preview.expected=storedBytes();
    try {
        preview.result=decode(preview.expected);
    } catch (const std::runtime_error&) {
        preview.repairing=true;
    }
    expire(preview.result,now);
    std::set<std::string> ids,texts;
    for (const auto& item:preview.result.items) {
        ids.insert(item.id);
        texts.insert(item.text);
    }
    for (auto item:incoming.items) {
        if (!item.pinned&&now>=item.copiedAt&&now-item.copiedAt>=preview.result.retentionDays*Day) {
            ++preview.expired;
            continue;
        }
        if (!texts.insert(item.text).second) {
            ++preview.conflicts;
            continue;
        }
        if (!ids.insert(item.id).second) {
            do {item.id=newId();} while (!ids.insert(item.id).second);
        }
        preview.result.items.push_back(std::move(item));
        ++preview.added;
    }
    if (preview.result.items.size()>MaxItems||toJson(preview.result,false).dump().size()+38>MaxPlainBytes)
        throw std::runtime_error("Backup would exceed clipboard capacity; current history is retained");
    return preview;
}

void ClipboardStore::restoreBackup(const ClipboardRestorePreview& preview) const {
    WriteLock lock(path,ClipboardLockTimeout);
    if (preview.store!=path||storedBytes()!=preview.expected)
        throw std::runtime_error("Clipboard history changed; preview again");
    if (!preview.repairing&&!preview.added) return;
    if (preview.repairing) {
        if (!preview.expected) throw std::runtime_error("Clipboard recovery source is missing");
        const auto retained=path+L"."+uniqueSuffix()+L".invalid";
        writeCreated(retained,*preview.expected);
        if (readBytes(retained)!=*preview.expected) throw std::runtime_error("Clipboard recovery source could not be retained");
    }
    auto next=preview.result;
    save(next);
}

}
