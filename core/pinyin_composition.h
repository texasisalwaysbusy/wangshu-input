#pragma once
#include "engine.h"
#include <algorithm>
#include <array>
#include <deque>
#include <limits>
#include <map>
#include <set>
#include <string>
#include <unordered_map>
#include <vector>

namespace wangshu::pinyin {
// Fixed independently of the requested page: paging must never change the search.
constexpr size_t kBeamWidth = 128;
// The shipped unigram table tops out at 2,000,000 (curated common words).
// Normalize each lexical item by that maximum on the /100 score scale;
// otherwise adding frequent individual syllables can outscore a whole word.
constexpr int kLexicalNormalizer = 20000;
// A missing letter carries one log-frequency unit on that same scale. This is
// a deterministic spelling heuristic, not contextual or learned prediction.
constexpr size_t kOmittedLetterCost = 1000;
constexpr size_t kMaxWordLetters = 24;
constexpr size_t kMaxAbbreviatedLetters = 32;
constexpr size_t kMaxExpandedStates = 16384;
using Syllables = std::array<std::vector<std::string>,26>;

inline bool syllableShape(const std::string& key) {
    static const std::set<std::string> finals = {
        "a","ai","an","ang","ao","e","ei","en","eng","er","o","ou","ong",
        "i","ia","ian","iang","iao","ie","in","ing","iong","iu",
        "u","ua","ue","uai","uan","uang","ui","un","uo","v","ve"};
    size_t initial=0;
    if(key.size()>=2&&(key.compare(0,2,"zh")==0||key.compare(0,2,"ch")==0||key.compare(0,2,"sh")==0))initial=2;
    else if(!key.empty()&&std::string("bpmfdtnlgkhjqxrzcswy").find(key[0])!=std::string::npos)initial=1;
    return finals.count(key.substr(initial))!=0;
}
inline Syllables syllables(const std::map<std::string,std::vector<Candidate>>& words) {
    Syllables result;
    // Derive readings from this dictionary, not a test phrase or external corpus.
    // Exclude one-character unit names with multi-syllable readings (e.g. baike).
    for(const auto& item:words) {
        const auto& key=item.first;
        if(key.empty()||key.size()>6||!syllableShape(key)||
           !std::all_of(key.begin(),key.end(),[](char c){return c>='a'&&c<='z';}))continue;
        bool singleHan=false;
        for(const auto& word:item.second) {
            auto text=utf16(word.text);
            if(text.size()==1&&((text[0]>=0x3400&&text[0]<=0x9fff)||(text[0]>=0xf900&&text[0]<=0xfaff))) {singleHan=true;break;}
        }
        if(singleHan)result[static_cast<size_t>(key[0]-'a')].push_back(key);
    }
    return result;
}
struct Spelling {std::string text;size_t consumed;size_t omitted;};
inline std::vector<Spelling> spellings(const std::string& key,size_t start,const Syllables& syllables) {
    std::vector<Spelling> result;
    for(const auto& syllable:syllables[static_cast<size_t>(key[start]-'a')]) {
        const size_t remaining=key.size()-start;
        std::set<size_t> lengths;
        if(remaining>=syllable.size()&&key.compare(start,syllable.size(),syllable)==0)lengths.insert(syllable.size());
        // Both s and sh (and c/ch, z/zh) are accepted initials. Vowel-leading
        // syllables are not abbreviated internally; their full spelling works.
        if(std::string("bpmfdtnlgkhjqxrzcswy").find(syllable[0])!=std::string::npos) {
            lengths.insert(1);
            if(syllable.size()>2&&syllable[1]=='h'&&remaining>=2&&key[start+1]=='h')lengths.insert(2);
        }
        // An unfinished final syllable is a normal live-composition prefix.
        if(remaining<syllable.size()&&syllable.compare(0,remaining,key,start,remaining)==0)lengths.insert(remaining);
        for(size_t consumed:lengths)result.push_back({syllable,consumed,syllable.size()-consumed});
    }
    // Complete syllables first so a bounded search does not prefer abbreviations.
    std::sort(result.begin(),result.end(),[](const Spelling& a,const Spelling& b){
        if(a.omitted!=b.omitted)return a.omitted<b.omitted;
        if(a.consumed!=b.consumed)return a.consumed>b.consumed;
        return a.text<b.text;
    });
    return result;
}
struct WordMatch {size_t end;const std::vector<Candidate>* words;size_t letters;size_t omitted;};
inline std::vector<WordMatch> abbreviatedWords(const std::string& key,size_t start,
        const std::map<std::string,std::vector<Candidate>>& dictionary,
        const std::vector<std::vector<Spelling>>& choices,size_t& budget) {
    struct State {std::string prefix;size_t at;size_t omitted;};
    std::deque<State> queue{{"",start,0}};
    std::map<std::pair<std::string,size_t>,size_t> visited;
    std::map<std::pair<std::string,size_t>,WordMatch> found;
    while(!queue.empty()&&budget) {
        State state=std::move(queue.front());queue.pop_front();
        if(state.at==key.size())continue;
        for(const auto& spelling:choices[state.at]) {
            if(!budget)break;
            if(state.prefix.size()+spelling.text.size()>kMaxWordLetters)continue;
            auto prefix=state.prefix+spelling.text;
            auto it=dictionary.lower_bound(prefix);
            if(it==dictionary.end()||it->first.compare(0,prefix.size(),prefix)!=0)continue;
            const size_t at=state.at+spelling.consumed,omitted=state.omitted+spelling.omitted;
            auto identity=std::make_pair(prefix,at);
            auto prior=visited.find(identity);
            if(prior!=visited.end()&&prior->second<=omitted)continue;
            visited[identity]=omitted;--budget;
            if(it->first==prefix)found[identity]={at,&it->second,prefix.size(),omitted};
            if(at<key.size())queue.push_back({std::move(prefix),at,omitted});
        }
    }
    std::vector<WordMatch> result;
    for(const auto& item:found)result.push_back(item.second);
    return result;
}
}
