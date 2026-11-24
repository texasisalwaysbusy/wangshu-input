#pragma once

// Included by context_predictions.h after the ordinary helpers.
#include <array>

namespace wangshu::context_prediction::long_english {
using Id = uint16_t;
using Key = std::array<Id, 7>;
struct Block {
    Key first;
    uint32_t keyOffset;
    uint32_t rowOffset;
};

#include "long_english_data.inc"

static_assert(sizeof(Block) == 24, "unexpected English table block layout");
static_assert(std::size(englishWords) == longEnglishWordCount, "English word IDs changed");
static_assert(std::size(longEnglishWords) == std::size(longEnglishCounts), "missing English frequencies");
static_assert(std::size(longEnglishWideIndices) == std::size(longEnglishWideCounts), "missing wide English frequencies");
static_assert(sizeof(longEnglishBlocks) + sizeof(longEnglishKeys) + sizeof(longEnglishWords) +
              sizeof(longEnglishCounts) + sizeof(longEnglishWideIndices) + sizeof(longEnglishWideCounts) <=
              longEnglishByteLimit, "the offline English next-word table must remain within its byte budget");

struct RowIterator {
    uint32_t index = 0;

    Id id() const { return longEnglishWords[index]; }
    uint32_t operator*() const {
        const auto encoded = longEnglishCounts[index];
        unsigned count = encoded + 2u;
        if (encoded == 255) {
            const auto first = std::begin(longEnglishWideIndices);
            const auto found = std::lower_bound(first, std::end(longEnglishWideIndices), index);
            count = longEnglishWideCounts[found - first];
        }
        return (count << 16) | id();
    }
    RowIterator& operator++() {
        ++index;
        return *this;
    }
    bool operator!=(const RowIterator& other) const { return index != other.index; }
};

struct Rows {
    RowIterator first{};
    RowIterator end{};
    bool empty() const { return !(first != end); }
};

// Each block starts with a complete key. Subsequent keys encode a shared prefix
// length and a row count in one byte, then store only the changed word IDs.
inline Rows find(const Key& key) {
    const auto end = std::end(longEnglishBlocks);
    const auto next = std::upper_bound(std::begin(longEnglishBlocks), end, key,
                                       [](const Key& query, const Block& block) { return query < block.first; });
    if (next == std::begin(longEnglishBlocks)) return {};
    const auto& block = next[-1];
    const auto stop = next == end ? std::end(longEnglishKeys) : longEnglishKeys + next->keyOffset;
    const auto* encoded = longEnglishKeys + block.keyOffset;
    uint32_t rowIndex = block.rowOffset;
    auto current = block.first;
    constexpr size_t keySizes[] = {0, 2, 3, 4, 5, 6, 7, 3, 3, 4, 4};
    for (size_t group = 0; group < longEnglishBlockGroups && encoded != stop; ++group) {
        const auto header = *encoded++;
        size_t shared = header >> 4;
        const uint32_t count = (header & 15) + 1;
        if (!shared) {
            current = {};
            current[0] = *encoded++;
            shared = 1;
        }
        for (size_t index = shared; index < keySizes[current[0]]; ++index) {
            const auto high = *encoded++;
            current[index] = high < 128 ? high : ((high & 127) << 8) | *encoded++;
        }
        if (current == key) return {{rowIndex}, {rowIndex + count}};
        if (key < current) return {};
        rowIndex += count;
    }
    return {};
}

inline bool ordinaryWords(std::u16string_view line) {
    if (line.empty() || !local_prediction::letter(line.front()) || line.back() != u' ') return false;
    bool apostrophe = false;
    for (size_t i = 0; i < line.size(); ++i) {
        const auto value = line[i];
        if (local_prediction::letter(value)) continue;
        if (value == u' ') {
            apostrophe = false;
            continue;
        }
        if (value != u'\'' || apostrophe || i == 0 || i + 1 >= line.size() ||
                !local_prediction::letter(line[i - 1]) || !local_prediction::letter(line[i + 1])) return false;
        apostrophe = true;
    }
    return true;
}

struct Evidence {
    Id word;
    double normal = 0;
    double skipped = 0;
    bool ordered = false;
    int count = 0;
};

inline Id wordId(uint32_t row) { return static_cast<Id>(row); }
inline unsigned frequency(uint32_t row) { return row >> 16; }

inline Evidence& evidenceFor(std::vector<Evidence>& scores, Id word) {
    auto found = std::find_if(scores.begin(), scores.end(), [word](const Evidence& item) { return item.word == word; });
    if (found != scores.end()) return *found;
    scores.push_back({word});
    return scores.back();
}

inline unsigned mass(Rows rows) {
    unsigned total = 0;
    for (auto row = rows.first; row != rows.end; ++row) total += frequency(*row);
    return total;
}

inline Result candidate(Id id, int count, bool uppercase) {
    auto suffix = std::u16string(text(englishWords[id - 1].text));
    if (suffix == u"i" || startsWith(suffix, u"i'")) suffix.front() = u'I';
    if (uppercase) {
        for (auto& value : suffix)
            if (value >= u'a' && value <= u'z') value -= 32;
    }
    return {std::move(suffix), count, false};
}

inline std::vector<Result> rerank(std::u16string_view line, std::vector<Result> baseline, size_t limit) {
    if (!ordinaryWords(line)) return baseline;
    const bool uppercase = allCaps(line);
    std::array<Id, 6> history{};
    size_t available = 0;
    while (available < history.size()) {
        const auto word = previousWord(line);
        if (word.empty()) break;
        history[available++] = englishWordId(word);
    }
    // Keep the established short-history ranking when the last three words
    // cannot be resolved. An unknown word is not evidence for reranking.
    if (available < 3 || !history[0] || !history[1] || !history[2]) return baseline;
    std::vector<Rows> contexts;
    for (size_t width = 1; width <= available; ++width) {
        if (!history[width - 1]) break;
        Key key{};
        key[0] = static_cast<Id>(width);
        for (size_t i = 0; i < width; ++i) key[1 + i] = history[width - 1 - i];
        auto rows = find(key);
        if (!rows.empty()) contexts.push_back(rows);
    }
    if (contexts.empty()) return baseline;
    std::vector<Rows> shifted;
    for (size_t width : {1, 2}) {
        for (size_t gap : {1, 2}) {
            const auto needed = width + gap + 1;
            if (needed > available || !history[needed - 1] || !history[0] || (width == 2 && !history[1])) continue;
            Key key{};
            key[0] = static_cast<Id>(6 + (width - 1) * 2 + gap);
            key[1] = history[needed - 1];
            for (size_t i = 0; i < width; ++i) key[2 + i] = history[width - 1 - i];
            auto rows = find(key);
            if (!rows.empty()) shifted.push_back(rows);
        }
    }
    if (contexts.size() == 1 && shifted.empty()) {
        std::vector<Result> result;
        const auto members = contexts.front();
        for (auto row = members.first; row != members.end && result.size() < limit; ++row) {
            const auto entry = *row;
            result.push_back(candidate(wordId(entry), frequency(entry), uppercase));
        }
        return result.size() >= baseline.size() ? std::move(result) : std::move(baseline);
    }

    std::vector<Evidence> scores;
    scores.reserve(104);
    for (const Rows rows : contexts) {
        const double denominator = mass(rows) + (scores.empty() ? 0 : 8);
        for (auto& item : scores) item.normal = item.normal * 8 / denominator;
        for (auto row = rows.first; row != rows.end; ++row) {
            const auto entry = *row;
            auto& item = evidenceFor(scores, wordId(entry));
            item.normal += frequency(entry) / denominator;
            item.count = static_cast<int>(frequency(entry));
        }
    }
    for (const Rows rows : shifted) {
        const double denominator = 4.0 * (mass(rows) + 8);
        for (auto row = rows.first; row != rows.end; ++row) {
            const auto entry = *row;
            auto& item = evidenceFor(scores, wordId(entry));
            item.skipped = std::max(item.skipped, frequency(entry) / denominator);
            item.count = std::max(item.count, static_cast<int>(frequency(entry)));
        }
    }
    std::vector<Evidence> order;
    order.reserve(scores.size());
    auto keep = [&](Id id) {
        auto at = std::find_if(scores.begin(), scores.end(), [id](const Evidence& item) { return item.word == id; });
        if (at == scores.end() || at->ordered) return;
        order.push_back(*at);
        at->ordered = true;
    };
    for (const auto& item : baseline) keep(englishWordId(item.suffix));
    for (auto at = contexts.rbegin(); at != contexts.rend(); ++at)
        for (auto row = at->first; row != at->end; ++row) keep(row.id());
    for (const Rows rows : shifted)
        for (auto row = rows.first; row != rows.end; ++row) keep(row.id());
    std::stable_sort(order.begin(), order.end(), [](const Evidence& a, const Evidence& b) {
        return a.normal + a.skipped > b.normal + b.skipped;
    });
    std::vector<Result> result;
    for (const auto& item : order) {
        if (result.size() == limit) break;
        result.push_back(candidate(item.word, item.count, uppercase));
    }
    return result.size() >= baseline.size() ? std::move(result) : std::move(baseline);
}
}
