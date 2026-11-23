#pragma once
#include "local_predictions.h"
#include <cstdint>
#include <iterator>
#include <utility>

namespace wangshu::context_prediction {
struct Word { uint32_t text; uint16_t count; };
struct Transition { uint32_t key, text; uint16_t count; bool partial; };
struct Result { std::u16string suffix; int count; bool partial; };

#include "context_prediction_data.inc"

inline std::u16string_view text(uint32_t reference) {
    const auto offset = reference >> 6;
    return {textChunks[offset / textChunkSize] + offset % textChunkSize, reference & 63};
}

inline bool startsWith(std::u16string_view value, std::u16string_view prefix) {
    return value.substr(0, prefix.size()) == prefix;
}

inline void append(std::vector<Result>& result, std::u16string value, int count, bool partial,
                   std::u16string_view after, bool uppercase) {
    if (uppercase) {
        for (auto& c : value)
            if (c >= u'a' && c <= u'z') c -= 32;
    }
    if (local_prediction::duplicate(value, after)) return;
    if (std::none_of(result.begin(), result.end(), [&](const Result& row) {
            return local_prediction::equalFold(row.suffix, value);
        })) {
        result.push_back({std::move(value), count, partial});
    }
}

template<size_t N>
inline void fromContext(const Transition (&table)[N], std::u16string_view key,
                        std::u16string_view prefix, std::u16string_view after,
                        bool uppercase, size_t limit, std::vector<Result>& result) {
    auto row = std::lower_bound(std::begin(table), std::end(table), key,
        [](const Transition& entry, std::u16string_view needle) { return text(entry.key) < needle; });
    for (; row != std::end(table) && text(row->key) == key && result.size() < limit; ++row) {
        auto word = text(row->text);
        if (word.size() <= prefix.size() || !startsWith(word, prefix)) continue;
        std::u16string suffix(word.substr(prefix.size()));
        if (prefix.empty() && (suffix == u"i" || startsWith(suffix, u"i'"))) suffix[0] = u'I';
        append(result, std::move(suffix), row->count, row->partial || !prefix.empty(), after, uppercase);
    }
}

inline std::vector<Result> chinese(std::u16string_view line, std::u16string_view after, size_t limit) {
    size_t length = 0;
    while (length < line.size() && length < 6 && local_prediction::han(line[line.size() - length - 1]))
        ++length;
    std::vector<Result> result;
    for (; length >= 2 && result.empty(); --length) {
        fromContext(chineseContexts, line.substr(line.size() - length), {}, after, false, limit, result);
    }
    return result;
}

inline bool englishCharacter(char16_t value) {
    return local_prediction::letter(value) || value == u'\'' || value == u'’';
}

inline std::u16string_view previousWord(std::u16string_view& line) {
    if (line.empty() || line.back() != u' ') return {};
    const auto end = line.find_last_not_of(u' ');
    if (end == std::u16string_view::npos) return {};
    size_t start = end + 1;
    while (start && englishCharacter(line[start - 1])) --start;
    const auto word = line.substr(start, end + 1 - start);
    if (word.empty() || word.size() > 24 || !local_prediction::letter(word.front()) ||
        !local_prediction::letter(word.back())) return {};
    line = line.substr(0, start);
    return word;
}

inline std::u16string englishKey(std::u16string_view word) {
    std::u16string key(word);
    for (auto& c : key) c = c == u'’' ? u'\'' : local_prediction::lower(c);
    return key;
}

inline uint16_t englishWordId(std::u16string_view word) {
    if (word.empty()) return 0;
    const auto key = englishKey(word);
    auto item = std::lower_bound(std::begin(englishWords), std::end(englishWords), key,
        [](const Word& entry, std::u16string_view needle) { return text(entry.text) < needle; });
    if (item == std::end(englishWords) || text(item->text) != key) return 0;
    return static_cast<uint16_t>(item - std::begin(englishWords) + 1);
}

inline void fromEnglishContext(uint64_t key, std::u16string_view prefix,
                               std::u16string_view after, bool uppercase,
                               size_t limit, std::vector<Result>& result) {
    const auto end = std::end(englishContextKeys);
    const auto match = std::lower_bound(std::begin(englishContextKeys), end, key);
    if (match == end || *match != key) return;
    const auto group = match - std::begin(englishContextKeys);
    for (auto index = englishContextOffsets[group];
         index < englishContextOffsets[group + 1] && result.size() < limit; ++index) {
        const auto packed = englishContextRows[index];
        const auto& entry = englishWords[(packed & 65535u) - 1];
        const auto word = text(entry.text);
        if (word.size() <= prefix.size() || !startsWith(word, prefix)) continue;
        std::u16string suffix(word.substr(prefix.size()));
        if (prefix.empty() && (suffix == u"i" || startsWith(suffix, u"i'"))) suffix[0] = u'I';
        append(result, std::move(suffix), static_cast<int>(packed >> 16), !prefix.empty(), after, uppercase);
    }
}

inline bool allCaps(std::u16string_view value) {
    size_t uppercase = 0;
    for (auto c : value) {
        if (c >= u'a' && c <= u'z') return false;
        uppercase += c >= u'A' && c <= u'Z';
    }
    return uppercase >= 2;
}

inline void completeEnglish(std::u16string_view prefix, std::u16string_view after,
                            bool uppercase, size_t limit, std::vector<Result>& result) {
    auto row = std::lower_bound(std::begin(englishWords), std::end(englishWords), prefix,
        [](const Word& entry, std::u16string_view needle) { return text(entry.text) < needle; });
    std::vector<Word> matches;
    for (; row != std::end(englishWords) && startsWith(text(row->text), prefix); ++row)
        if (text(row->text).size() > prefix.size()) matches.push_back(*row);
    std::stable_sort(matches.begin(), matches.end(), [](const Word& left, const Word& right) {
        return left.count > right.count;
    });
    for (const auto& match : matches) {
        if (result.size() == limit) break;
        append(result, std::u16string(text(match.text).substr(prefix.size())),
               match.count, true, after, uppercase);
    }
}

namespace long_english {
inline std::vector<Result> rerank(std::u16string_view line, std::vector<Result> baseline, size_t limit);
}

inline std::vector<Result> english(std::u16string_view line, std::u16string_view after, size_t limit) {
    std::u16string_view preceding = line;
    std::u16string_view typed;
    if (line.back() != u' ') {
        size_t start = line.size();
        while (start && englishCharacter(line[start - 1])) --start;
        typed = line.substr(start);
        if (typed.size() < 2 || typed.size() >= 24 || !local_prediction::letter(typed.front()) ||
            !local_prediction::boundary(line, start)) return {};
        preceding = line.substr(0, start);
    }
    const auto previous = previousWord(preceding);
    const auto older = previousWord(preceding);
    const auto oldest = previousWord(preceding);
    const auto prefix = englishKey(typed);
    const bool uppercase = allCaps(typed.empty() ? line : typed);
    std::vector<Result> result;
    const auto last = englishWordId(previous);
    const auto middle = last ? englishWordId(older) : 0;
    if (middle) {
        const uint64_t key = (static_cast<uint64_t>(middle) << 16) | last;
        const auto first = englishWordId(oldest);
        if (first) {
            fromEnglishContext((static_cast<uint64_t>(first) << 32) | key, prefix, after,
                               uppercase, limit, result);
        }
        if (result.size() < limit) fromEnglishContext(key, prefix, after, uppercase, limit, result);
    }
    if (last && result.size() < limit) fromEnglishContext(last, prefix, after, uppercase, limit, result);
    if (!prefix.empty() && result.size() < limit)
        completeEnglish(prefix, after, uppercase, limit, result);
    if (prefix.empty() && after.empty()) return long_english::rerank(line, std::move(result), limit);
    return result;
}
}

#include "long_english_prediction.h"
