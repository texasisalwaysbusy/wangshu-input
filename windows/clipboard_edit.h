#pragma once

#include "grapheme_backspace.h"

namespace wangshu::clipboard_edit {

inline constexpr char Command[]="\\\\clip";
inline constexpr size_t HistoryPage=4,SplitPage=9;

inline bool asciiWord(wchar_t ch) {
    return (ch>=L'a'&&ch<=L'z')||(ch>=L'A'&&ch<=L'Z')||(ch>=L'0'&&ch<=L'9')||ch==L'_';
}

inline std::vector<std::wstring> split(std::wstring_view text) {
    const auto offsets=grapheme::boundaries(std::u16string(text.begin(),text.end()));
    if (!offsets) return {};
    std::vector<std::wstring> tokens;
    bool previousWord=false;
    for (size_t index=1;index<offsets->size();++index) {
        const auto piece=text.substr((*offsets)[index-1],(*offsets)[index]-(*offsets)[index-1]);
        const bool word=piece.size()==1&&asciiWord(piece.front());
        if (word&&previousWord) tokens.back().append(piece.data(),piece.size());
        else tokens.emplace_back(piece);
        previousWord=word;
    }
    return tokens;
}

struct Edit {
    size_t begin=0, end=0;
    std::wstring replacement;
    voice_position::Snapshot after;
};

inline std::optional<Edit> insert(const voice_position::Snapshot& before,std::wstring text) {
    if (text.empty()||!voice_position::textValid(text)||
        before.text.size()+text.size()>voice_position::MaxUnits) return {};
    Edit result{before.caret,before.caret,std::move(text),before};
    result.after.text.insert(before.caret,result.replacement);
    result.after.caret+=result.replacement.size();
    return result;
}

struct SplitSelection {
    std::vector<std::wstring> tokens;
    std::vector<size_t> order;
    size_t origin=0;

    bool selected(size_t token) const {
        return std::find(order.begin(),order.end(),token)!=order.end();
    }

    // The caller applies this to a copy, publishing the new selection only
    // after the host confirms both the document and caret.
    std::optional<Edit> toggle(size_t token,const voice_position::Snapshot& before) {
        if (token>=tokens.size()) return {};
        const auto found=std::find(order.begin(),order.end(),token);
        if (found==order.end()) {
            auto result=insert(before,tokens[token]);
            if (result) order.push_back(token);
            return result;
        }
        size_t start=origin;
        for (auto current=order.begin();current!=found;++current) start+=tokens[*current].size();
        const size_t length=tokens[token].size(),end=start+length;
        if (end>before.caret||before.text.compare(start,length,tokens[token])!=0) return {};
        Edit result{start,end,L"",before};
        result.after.text.erase(start,length);
        result.after.caret-=length;
        order.erase(found);
        return result;
    }
};

inline voice_position::Range editRange(ITfRange* caret,TfEditCookie cookie,
                                       const voice_position::Snapshot& before,const Edit& edit) {
    voice_position::Range range;
    if (edit.begin>edit.end||edit.end>before.caret||FAILED(voice_position::clone(caret,range))) return {};
    const auto left=-static_cast<LONG>(before.caret-edit.begin),right=-static_cast<LONG>(before.caret-edit.end);
    LONG moved=0;
    if ((left&&(FAILED(range->ShiftStart(cookie,left,&moved,nullptr))||moved!=left))||
        (right&&(FAILED(range->ShiftEnd(cookie,right,&moved,nullptr))||moved!=right))) return {};
    std::wstring text;
    if (FAILED(voice_position::read(cookie,range.get(),text))||
        before.text.compare(edit.begin,edit.end-edit.begin,text)!=0) return {};
    return range;
}

inline voice_position::Range expectedCaret(ITfContext* context,TfEditCookie cookie,
                                            const voice_position::Snapshot& expected) {
    ITfRange* raw=nullptr;
    auto result=context->GetStart(cookie,&raw);
    voice_position::Range caret(raw);
    if (FAILED(result)||!caret) return {};
    raw=nullptr;
    result=context->GetEnd(cookie,&raw);
    voice_position::Range end(raw),full;
    std::wstring text;
    if (FAILED(result)||!end||FAILED(voice_position::clone(caret.get(),full))||
        FAILED(full->ShiftEndToRange(cookie,end.get(),TF_ANCHOR_END))||
        FAILED(voice_position::read(cookie,full.get(),text))||text!=expected.text) return {};
    LONG moved=0;
    if (expected.caret&&(FAILED(caret->ShiftEnd(cookie,static_cast<LONG>(expected.caret),&moved,nullptr))||
                         moved!=static_cast<LONG>(expected.caret))) return {};
    std::wstring prefix;
    if (FAILED(voice_position::read(cookie,caret.get(),prefix))||
        expected.text.compare(0,expected.caret,prefix)!=0||
        FAILED(caret->Collapse(cookie,TF_ANCHOR_END))) return {};
    return caret;
}

inline bool boundary(const voice_position::Snapshot& position) {
    const auto points=grapheme::boundaries(std::u16string(position.text.begin(),position.text.end()));
    return points&&std::binary_search(points->begin(),points->end(),position.caret);
}

}
