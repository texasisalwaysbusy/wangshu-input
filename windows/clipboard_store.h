#pragma once

#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <vector>

namespace wangshu {

struct ClipboardItem {
    std::string id, text;
    std::uint64_t copiedAt=0;
    bool pinned=false;
};

struct ClipboardHistory {
    bool recording=false;
    unsigned retentionDays=1;
    std::vector<ClipboardItem> items;
    std::string revision;
};

struct ClipboardRestorePreview {
    size_t added=0, conflicts=0, expired=0;
    bool repairing=false;

private:
    friend class ClipboardStore;
    std::wstring store;
    std::optional<std::string> expected;
    ClipboardHistory result;
};

class ClipboardStore {
public:
    using ReadText=std::function<std::optional<std::string>()>;
    struct Protection {
        std::string (*seal)(const std::string&);
        std::string (*open)(const std::string&);
    };

    ClipboardStore();
    ClipboardStore(std::wstring path,Protection protection);

    ClipboardHistory read(std::uint64_t now) const;
    void configure(const std::string& revision,bool recording,unsigned days,std::uint64_t now) const;
    bool record(const ReadText& readText,std::uint64_t now) const;
    void pin(const std::string& revision,const std::string& id,bool pinned,std::uint64_t now) const;
    void remove(const std::string& revision,const std::string& id,std::uint64_t now) const;
    void clear(const std::string& revision,bool includePinned,std::uint64_t now) const;

    void exportBackup(const std::wstring& destination,const std::string& revision,std::uint64_t now) const;
    ClipboardRestorePreview previewBackup(const std::wstring& source,std::uint64_t now) const;
    void restoreBackup(const ClipboardRestorePreview& preview) const;

private:
    std::wstring path;
    Protection protection;
    std::optional<std::string> storedBytes() const;
    ClipboardHistory decode(const std::optional<std::string>& bytes) const;
    void save(ClipboardHistory& data) const;
    void change(const std::string& revision,std::uint64_t now,
                const std::function<void(ClipboardHistory&)>& action) const;
};

}
