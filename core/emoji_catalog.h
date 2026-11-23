#pragma once

#include <string_view>

namespace wangshu::emoji_catalog {

struct Entry {
    const char* category;
    const char* text;
    const char* description;
    const char* pinyin;
};

inline constexpr Entry Entries[] = {
#include "../windows/emoji_catalog.inc"
};

inline const Entry* find(std::string_view text) {
    for (const auto& entry : Entries)
        if (text == entry.text) return &entry;
    return nullptr;
}

}
