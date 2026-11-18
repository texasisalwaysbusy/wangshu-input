#pragma once

#include "voice_position.h"
#include "../core/grapheme.h"

namespace wangshu::grapheme_backspace {

struct Plan {
    voice_position::Snapshot before;
    size_t begin=0;
    bool insideCluster=false;
};

inline voice_position::Range collapsedCaret(ITfContext* context,TfEditCookie cookie) {
    TF_SELECTION selection{};
    ULONG count=0;
    const auto result=context->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&selection,&count);
    voice_position::Range caret(selection.range);
    LONG relation=1;
    if (FAILED(result)||count!=1||!caret||selection.style.fInterimChar||
        FAILED(caret->CompareStart(cookie,caret.get(),TF_ANCHOR_END,&relation))||relation) return {};
    return caret;
}

inline bool sameCaret(ITfContext* context,TfEditCookie cookie,ITfRange* expected) {
    auto fresh=collapsedCaret(context,cookie);
    LONG start=1,end=1;
    return fresh&&SUCCEEDED(fresh->CompareStart(cookie,expected,TF_ANCHOR_START,&start))&&!start&&
        SUCCEEDED(fresh->CompareEnd(cookie,expected,TF_ANCHOR_END,&end))&&!end;
}

inline bool complexNeighbor(TfEditCookie cookie,ITfRange* caret) {
    voice_position::Range around;
    if (FAILED(voice_position::clone(caret,around))) return false;
    LONG left=0,right=0;
    if (FAILED(around->ShiftStart(cookie,-2,&left,nullptr))||left>=0||left<-2||
        FAILED(around->ShiftEnd(cookie,2,&right,nullptr))||right<0||right>2) return false;
    WCHAR text[5]{};
    ULONG read=0;
    if (FAILED(around->GetText(cookie,0,text,5,&read))||read!=static_cast<ULONG>(right-left)) return false;
    return std::any_of(text,text+read,[](wchar_t unit){
        if (unit<128) return unit==L'\r'||unit==L'\n';
        if (unit>=0xd800&&unit<=0xdfff) return true;
        return grapheme::lookup(grapheme::breakRanges,unit,grapheme::Break::Other)!=grapheme::Break::Other||
            grapheme::lookup(grapheme::indicRanges,unit,grapheme::Indic::None)!=grapheme::Indic::None||
            grapheme::lookup(grapheme::pictographRanges,unit,false);
    });
}

// The caller must first check the input scope. Only complex neighbors need a
// complete, bounded snapshot so combining and flag boundaries are unambiguous.
inline std::optional<Plan> prepare(ITfContext* context,TfEditCookie cookie,ITfRange* caret) {
    Plan plan;
    if (FAILED(voice_position::capture(context,cookie,plan.before,true))||
        !sameCaret(context,cookie,caret)||!plan.before.caret) return {};
    const auto& source=plan.before.text;
    static_assert(sizeof(wchar_t)==sizeof(char16_t),"TSF text must be UTF-16");
    auto offsets=grapheme::boundaries(std::u16string(source.begin(),source.end()));
    if (!offsets) return {};
    auto position=std::lower_bound(offsets->begin(),offsets->end(),plan.before.caret);
    if (position==offsets->end()) return {};
    if (*position!=plan.before.caret) {
        plan.insideCluster=true;
        return plan;
    }
    plan.begin=*std::prev(position);
    if (plan.before.caret-plan.begin<2) return {};
    return plan;
}

inline voice_position::Range deletionRange(TfEditCookie cookie,ITfRange* caret,const Plan& plan) {
    voice_position::Range range;
    if (FAILED(voice_position::clone(caret,range))) return {};
    const auto length=plan.before.caret-plan.begin;
    const auto shift=-static_cast<LONG>(length);
    LONG moved=0;
    if (FAILED(range->ShiftStart(cookie,shift,&moved,nullptr))||moved!=shift) return {};
    std::wstring contents;
    if (FAILED(voice_position::read(cookie,range.get(),contents))||
        plan.before.text.compare(plan.begin,length,contents)!=0) return {};
    LONG end=1;
    if (FAILED(range->CompareEnd(cookie,caret,TF_ANCHOR_END,&end))||end) return {};
    return range;
}

}
