#pragma once

#include <algorithm>
#include <cstdint>
#include <iterator>
#include <optional>
#include <string_view>
#include <vector>

namespace wangshu::grapheme {

enum class Break : uint8_t {
    Other, CR, LF, Control, Extend, ZWJ, Regional_Indicator, Prepend, SpacingMark, L, V, T, LV, LVT
};
enum class Indic : uint8_t { None, Consonant, Extend, Linker };
template<class Value> struct PropertyRange { uint32_t first, last; Value value; };

#include "grapheme_data.inc"

template<class Value, size_t N>
inline Value lookup(const PropertyRange<Value> (&ranges)[N], uint32_t cp, Value fallback) {
    auto match = std::lower_bound(std::begin(ranges), std::end(ranges), cp,
        [](const auto& range, uint32_t value) { return range.last < value; });
    return match != std::end(ranges) && match->first <= cp ? match->value : fallback;
}

inline bool control(Break value) {
    return value == Break::Control || value == Break::CR || value == Break::LF;
}

// Streaming lookbehind for GB9c, GB11, and regional indicator parity.
struct BoundaryState {
    enum class Conjunct { None, Consonant, Linked };
    Break previous = Break::Other;
    Conjunct conjunct = Conjunct::None;
    bool pictographRun = false, pictographJoiner = false, oddFlagRun = false;

    bool separates(Break next, Indic indic, bool pictograph) const {
        if (previous == Break::CR && next == Break::LF) return false;
        if (control(previous) || control(next)) return true;
        if (previous == Break::L &&
            (next == Break::L || next == Break::V || next == Break::LV || next == Break::LVT)) return false;
        if ((previous == Break::LV || previous == Break::V) &&
            (next == Break::V || next == Break::T)) return false;
        if ((previous == Break::LVT || previous == Break::T) && next == Break::T) return false;
        if (next == Break::Extend || next == Break::ZWJ || next == Break::SpacingMark ||
            previous == Break::Prepend) return false;
        if (conjunct == Conjunct::Linked && indic == Indic::Consonant) return false;
        if (pictographJoiner && pictograph) return false;
        return next != Break::Regional_Indicator || !oddFlagRun;
    }

    void add(Break value, Indic indic, bool pictograph) {
        if (indic == Indic::Consonant) conjunct = Conjunct::Consonant;
        else if (indic == Indic::Linker && conjunct != Conjunct::None) conjunct = Conjunct::Linked;
        else if (indic != Indic::Extend) conjunct = Conjunct::None;

        pictographJoiner = value == Break::ZWJ && pictographRun;
        pictographRun = pictograph || (value == Break::Extend && pictographRun);
        oddFlagRun = value == Break::Regional_Indicator && !oddFlagRun;
        previous = value;
    }
};

// Returns offsets in UTF-16 code units, including the two ends. Reject ill-formed UTF-16.
inline std::optional<std::vector<size_t>> boundaries(std::u16string_view text) {
    BoundaryState state;
    std::vector<size_t> result{0};
    for (size_t offset = 0; offset < text.size();) {
        const auto start = offset;
        uint32_t cp = text[offset++];
        if (cp >= 0xd800 && cp <= 0xdbff) {
            if (offset == text.size() || text[offset] < 0xdc00 || text[offset] > 0xdfff) return {};
            cp = 0x10000 + ((cp - 0xd800) << 10) + (text[offset++] - 0xdc00);
        } else if (cp >= 0xdc00 && cp <= 0xdfff) {
            return {};
        }
        const auto value = lookup(breakRanges, cp, Break::Other);
        const auto indic = lookup(indicRanges, cp, Indic::None);
        const bool pictograph = lookup(pictographRanges, cp, false);
        if (start && state.separates(value, indic, pictograph)) result.push_back(start);
        state.add(value, indic, pictograph);
    }
    if (!text.empty()) result.push_back(text.size());
    return result;
}

}
