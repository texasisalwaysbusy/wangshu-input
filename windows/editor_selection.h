#pragma once

#include "grapheme_backspace.h"

namespace wangshu::editor_selection {
struct Position {
    size_t start = 0, end = 0;
    TfActiveSelEnd active = TF_AE_NONE;

    size_t caret() const { return active == TF_AE_START ? start : end; }
    size_t anchor() const { return active == TF_AE_START ? end : start; }
    bool operator==(const Position& other) const {
        return start == other.start && end == other.end && active == other.active;
    }
};

struct Snapshot {
    std::wstring text;
    Position position;
    bool operator==(const Snapshot& other) const {
        return position == other.position && text == other.text;
    }
};

inline voice_position::Range selection(ITfContext* context, TfEditCookie cookie, TF_SELECTIONSTYLE& style) {
    TF_SELECTION selected{};
    ULONG count = 0;
    const auto result = context->GetSelection(cookie, TF_DEFAULT_SELECTION, 1, &selected, &count);
    voice_position::Range range(selected.range);
    if (FAILED(result) || count != 1 || selected.style.fInterimChar) return {};
    style = selected.style;
    return range;
}

inline bool sameSelection(ITfContext* context, TfEditCookie cookie, ITfRange* expected, Position position) {
    TF_SELECTIONSTYLE style{};
    auto current = selection(context, cookie, style);
    LONG left = 1, right = 1;
    return current && (position.start == position.end || style.ase == position.active) &&
        SUCCEEDED(current->CompareStart(cookie, expected, TF_ANCHOR_START, &left)) && !left &&
        SUCCEEDED(current->CompareEnd(cookie, expected, TF_ANCHOR_END, &right)) && !right;
}

inline std::optional<Snapshot> capture(ITfContext* context, TfEditCookie cookie) {
    TF_SELECTIONSTYLE style{};
    auto selected = selection(context, cookie, style);
    if (!selected) return {};
    ITfRange* raw = nullptr;
    const auto startResult = context->GetStart(cookie, &raw);
    voice_position::Range start(raw), full;
    if (FAILED(startResult) || !start || FAILED(voice_position::clone(start.get(), full))) return {};
    raw = nullptr;
    const auto endResult = context->GetEnd(cookie, &raw);
    voice_position::Range end(raw);
    if (FAILED(endResult) || !end || FAILED(full->ShiftEndToRange(cookie, end.get(), TF_ANCHOR_END))) return {};
    LONG before = 0, after = 0;
    if (FAILED(selected->CompareStart(cookie, start.get(), TF_ANCHOR_START, &before)) || before < 0 ||
        FAILED(selected->CompareEnd(cookie, end.get(), TF_ANCHOR_END, &after)) || after > 0) return {};
    if (FAILED(start->ShiftEndToRange(cookie, selected.get(), TF_ANCHOR_START))) return {};
    Snapshot value;
    std::wstring prefix, highlighted;
    if (FAILED(voice_position::read(cookie, full.get(), value.text)) ||
        FAILED(voice_position::read(cookie, start.get(), prefix)) ||
        FAILED(voice_position::read(cookie, selected.get(), highlighted))) return {};
    const size_t right = prefix.size() + highlighted.size();
    if (right > value.text.size() || value.text.compare(0, prefix.size(), prefix) != 0 ||
        value.text.compare(prefix.size(), highlighted.size(), highlighted) != 0) return {};
    if (!highlighted.empty() && style.ase != TF_AE_START && style.ase != TF_AE_END) return {};
    value.position = {prefix.size(), right, highlighted.empty() ? TF_AE_NONE : style.ase};
    if (!sameSelection(context, cookie, selected.get(), value.position)) return {};
    return value;
}

inline voice_position::Range makeRange(ITfContext* context, TfEditCookie cookie, Position position) {
    ITfRange* raw = nullptr;
    const auto result = context->GetStart(cookie, &raw);
    voice_position::Range range(raw);
    if (FAILED(result) || !range) return {};
    const auto start = static_cast<LONG>(position.start), end = static_cast<LONG>(position.end);
    LONG moved = 0;
    if (end && (FAILED(range->ShiftEnd(cookie, end, &moved, nullptr)) || moved != end)) return {};
    if (start && (FAILED(range->ShiftStart(cookie, start, &moved, nullptr)) || moved != start)) return {};
    return range;
}

inline Position destination(const std::vector<size_t>& boundaries, Position before, WPARAM key, bool extend) {
    size_t next = before.caret();
    if (key == VK_HOME) next = boundaries.front();
    else if (key == VK_END) next = boundaries.back();
    else if (key == VK_LEFT || key == VK_RIGHT) {
        if (!extend && before.start != before.end)
            next = key == VK_LEFT ? before.start : before.end;
        else {
            const auto at = std::lower_bound(boundaries.begin(), boundaries.end(), next);
            if (key == VK_LEFT && at != boundaries.begin()) next = *std::prev(at);
            if (key == VK_RIGHT && at != boundaries.end() && std::next(at) != boundaries.end())
                next = *std::next(at);
        }
    }
    const auto anchor = extend ? before.anchor() : next;
    return {std::min(next, anchor), std::max(next, anchor),
            next == anchor ? TF_AE_NONE : next < anchor ? TF_AE_START : TF_AE_END};
}
}
