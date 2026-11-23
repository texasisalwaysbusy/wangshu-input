#pragma once

#include "../core/engine.h"
#include <string_view>

namespace wangshu::symbol_panel {

inline constexpr std::string_view Prefix{"\\\\", 2};
inline constexpr std::string_view SearchPrefix{"\\\\find\\", 7};
inline constexpr size_t SearchLimit = 40;
inline constexpr size_t PageSize = 9;

enum class Source { Dictionary, Emoji, English, Search };
struct Category {
    const char* key;
    const char* label;
    Source source;
};
inline constexpr Category Categories[] = {
    {"greek", "希腊", Source::Dictionary},
    {"operators", "运算", Source::Dictionary},
    {"relations", "关系", Source::Dictionary},
    {"sets", "集合", Source::Dictionary},
    {"logic", "逻辑", Source::Dictionary},
    {"arrows", "箭头", Source::Dictionary},
    {"scripts", "上下标", Source::Dictionary},
    {"fractions", "分数", Source::Dictionary},
    {"punct", "标点", Source::Dictionary},
    {"english", "英文标点", Source::English},
    {"faces", "笑脸", Source::Emoji},
    {"gestures", "手势", Source::Emoji},
    {"people", "人物", Source::Emoji},
    {"nature", "自然", Source::Emoji},
    {"food", "饮食", Source::Emoji},
    {"activities", "活动", Source::Emoji},
    {"emoji", "符号", Source::Emoji},
    {"flags", "旗帜", Source::Emoji},
    {"kaomoji", "颜文字", Source::Emoji},
    {"find", "表情和颜文字检索", Source::Search},
};

struct EmojiEntry {
    const char* category;
    const char* text;
    const char* description;
    const char* pinyin;
};
inline constexpr EmojiEntry Emoji[] = {
#include "emoji_catalog.inc"
};
inline constexpr const char* EnglishPunctuation[] = {
    ",", ".", "?", "!", ":", ";", "'", "\"", "-", "_", "(", ")", "[", "]", "{", "}", "@", "#",
};

inline bool active(std::string_view input) {
    return input.substr(0, Prefix.size()) == Prefix;
}

inline bool searching(std::string_view input) {
    return input.substr(0, SearchPrefix.size()) == SearchPrefix;
}

inline std::vector<Candidate> lookup(const Engine& dictionary, std::string_view input, size_t page) {
    if (!active(input) || page > 100) return {};
    std::vector<Candidate> result;
    size_t skip = page * PageSize;
    auto append = [&](const char* text, std::string annotation) {
        if (skip) --skip;
        else if (result.size() < PageSize) result.push_back({text, std::move(annotation)});
    };
    if (searching(input)) {
        input.remove_prefix(SearchPrefix.size());
        if (input.empty() || input.size() > SearchLimit) return {};
        std::string query(input);
        for (auto& letter : query) {
            if (letter >= 'A' && letter <= 'Z') letter += 'a' - 'A';
            if (letter < 'a' || letter > 'z') return {};
        }
        // Exact names first, then pinyin prefixes, then matches inside a name.
        for (int rank = 0; rank < 3 && result.size() < PageSize; ++rank)
            for (const auto& entry : Emoji) {
                std::string_view pinyin(entry.pinyin);
                const auto position = pinyin.find(query);
                if (position == std::string_view::npos) continue;
                const int match = pinyin == query ? 0 : position == 0 ? 1 : 2;
                if (match == rank) append(entry.text, entry.description);
            }
        return result;
    }
    input.remove_prefix(Prefix.size());
    if (input.empty()) {
        for (const auto& group : Categories) {
            const auto command = group.source == Source::Search
                ? std::string(SearchPrefix) : std::string(Prefix) + std::string(group.key);
            append(group.label, command);
        }
        return result;
    }
    const auto separator = input.find('\\');
    const auto key = input.substr(0, separator);
    const auto filter = separator == std::string_view::npos ? std::string_view{} : input.substr(separator + 1);
    for (const auto& group : Categories) {
        if (key != group.key) continue;
        switch (group.source) {
        case Source::Dictionary:
            for (const auto& symbol : dictionary.symbols(group.label)) {
                if (std::string_view(symbol.alias).substr(0, filter.size()) == filter)
                    append(symbol.text.c_str(), "\\" + symbol.alias);
            }
            break;
        case Source::Emoji:
            if (filter.empty()) {
                for (const auto& entry : Emoji)
                    if (std::string_view(entry.category) == group.label) append(entry.text, entry.description);
            }
            break;
        case Source::English:
            if (filter.empty())
                for (const auto* mark : EnglishPunctuation) append(mark, group.label);
            break;
        case Source::Search:
            break;
        }
        break;
    }
    return result;
}

}
