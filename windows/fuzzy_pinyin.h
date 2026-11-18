#pragma once

#include <deque>
#include <string>
#include <string_view>
#include <unordered_set>
#include <vector>

namespace wangshu::fuzzy_pinyin {

struct Rule {const char* left;const char* right;const wchar_t* label;bool initial;};
inline constexpr Rule Rules[]={
    {"z","zh",L"z / zh",true},{"c","ch",L"c / ch",true},{"s","sh",L"s / sh",true},
    {"n","l",L"n / l",true},{"f","h",L"f / h",true},
    {"an","ang",L"an / ang",false},{"en","eng",L"en / eng",false},{"in","ing",L"in / ing",false},
};
inline constexpr unsigned AllRules=(1u<<(sizeof(Rules)/sizeof(Rules[0])))-1;
inline constexpr size_t MaxLength=64,MaxSyllables=16,MaxAttempts=1024,MaxKeys=8;

inline const std::unordered_set<std::string>& syllables(){
    // Conservative table used by the Android FuzzyPinyin classifier.
    static const auto words=[]{
        std::unordered_set<std::string> result;
        const auto add=[&](std::string_view onset,std::string_view ends){
            while(!ends.empty()){
                const auto separator=ends.find(' ');
                result.insert(std::string(onset)+std::string(ends.substr(0,separator)));
                if(separator==std::string_view::npos)break;
                ends.remove_prefix(separator+1);
            }
        };
        add("","a ai an ang ao e ei en eng er o ou");
        add("b","a ai an ang ao ei en eng i ian iao ie in ing o u");
        add("p","a ai an ang ao ei en eng i ian iao ie in ing o ou u");
        add("m","a ai an ang ao e ei en eng i ian iao ie in ing iu o ou u");
        add("f","a an ang ei en eng o ou u");
        add("d","a ai an ang ao e ei en eng i ia ian iao ie ing iu ong ou u uan ui un uo");
        add("t","a ai an ang ao e eng i ian iao ie ing ong ou u uan ui un uo");
        add("n","a ai an ang ao e ei en eng i ian iang iao ie in ing iu ong ou u uan uo v ve");
        add("l","a ai an ang ao e ei eng i ia ian iang iao ie in ing iu ong ou u uan un uo v ve");
        add("g","a ai an ang ao e ei en eng ong ou u ua uai uan uang ui un uo");
        add("k","a ai an ang ao e en eng ong ou u ua uai uan uang ui un uo");
        add("h","a ai an ang ao e ei en eng ong ou u ua uai uan uang ui un uo");
        for(const auto* onset:{"j","q","x"})add(onset,"i ia ian iang iao ie in ing iong iu u uan ue un");
        add("zh","a ai an ang ao e ei en eng i ong ou u ua uai uan uang ui un uo");
        add("ch","a ai an ang ao e en eng i ong ou u ua uai uan uang ui un uo");
        add("sh","a ai an ang ao e ei en eng i ou u ua uai uan uang ui un uo");
        add("r","an ang ao e en eng i ong ou u uan ui un uo");
        add("z","a ai an ang ao e ei en eng i ong ou u uan ui un uo");
        add("c","a ai an ang ao e en eng i ong ou u uan ui un uo");
        add("s","a ai an ang ao e en eng i ong ou u uan ui un uo");
        add("y","a an ang ao e i in ing ong ou u uan ue un");
        add("w","a ai an ang ei en eng o u");
        return result;
    }();
    return words;
}

struct Unit {std::string text;bool separator=false;};

inline std::vector<Unit> segment(std::string_view key){
    std::vector<Unit> units;
    bool separator=false;
    while(!key.empty()){
        const auto split=key.find('\'');
        const auto part=key.substr(0,split);
        if(part.empty())return {};
        // Minimize the syllable count, preferring the longer first syllable on ties.
        struct Step {size_t length=0,count=MaxLength+1;};
        std::vector<Step> best(part.size()+1);
        best.back().count=0;
        for(size_t i=part.size();i>0;){
            --i;
            for(size_t length=1;length<=6&&i+length<=part.size();++length){
                if(!syllables().count(std::string(part.substr(i,length))))continue;
                const auto count=best[i+length].count+1;
                if(count<best[i].count||(count==best[i].count&&length>best[i].length))
                    best[i]={length,count};
            }
        }
        if(best[0].count>MaxSyllables-units.size())return {};
        for(size_t i=0;i<part.size();i+=best[i].length){
            units.push_back({std::string(part.substr(i,best[i].length)),separator});
            separator=false;
        }
        if(split==std::string_view::npos)break;
        key.remove_prefix(split+1);
        if(key.empty())return {};
        separator=true;
    }
    return units;
}

inline std::string substitute(std::string_view syllable,const Rule& rule){
    const auto onsetLength=syllable.size()>1&&syllable[1]=='h'&&
        std::string_view("zcs").find(syllable[0])!=std::string_view::npos?2:
        std::string_view("bpmfdtnlgkhjqxrzcsyw").find(syllable[0])!=std::string_view::npos?1:0;
    const auto onset=syllable.substr(0,onsetLength),ending=syllable.substr(onsetLength);
    const std::string_view left=rule.left,right=rule.right;
    auto endsWith=[&](std::string_view suffix){
        return ending.size()>=suffix.size()&&ending.substr(ending.size()-suffix.size())==suffix;
    };
    std::string result;
    if(rule.initial){
        if(onset==left)result=std::string(right)+std::string(ending);
        else if(onset==right)result=std::string(left)+std::string(ending);
    }else if(endsWith(right))result=std::string(syllable.substr(0,syllable.size()-right.size()))+std::string(left);
    else if(endsWith(left))result=std::string(syllable.substr(0,syllable.size()-left.size()))+std::string(right);
    return syllables().count(result)?result:std::string{};
}

// Variant keys only. The original input stays on the existing exact route.
inline std::vector<std::string> variants(const std::string& raw,unsigned mask){
    if(!mask||raw.empty()||raw.size()>MaxLength)return {};
    auto key=raw;
    for(auto& ch:key){
        if(ch>='A'&&ch<='Z')ch+=('a'-'A');
        if(ch!='\''&&(ch<'a'||ch>'z'))return {};
    }
    auto initial=segment(key);
    if(initial.empty())return {};
    struct Node {std::vector<Unit> units;unsigned depth;};
    std::deque<Node> queue;
    queue.push_back({std::move(initial),0});
    std::unordered_set<std::string> seen{key};
    std::vector<std::string> result;
    size_t attempts=0;
    while(!queue.empty()){
        auto node=std::move(queue.front());queue.pop_front();
        for(size_t unit=0;unit<node.units.size();++unit){
            for(size_t i=0;i<sizeof(Rules)/sizeof(Rules[0]);++i){
                if(!(mask&(1u<<i)))continue;
                if(attempts++>=MaxAttempts)return result;
                auto changed=substitute(node.units[unit].text,Rules[i]);
                if(changed.empty())continue;
                auto next=node.units;
                next[unit].text=std::move(changed);
                std::string value;
                for(const auto& part:next){
                    if(part.separator)value+='\'';
                    value+=part.text;
                }
                if(value.size()>MaxLength||!seen.insert(value).second)continue;
                result.push_back(std::move(value));
                if(result.size()+1==MaxKeys)return result;
                if(node.depth==0)queue.push_back({std::move(next),1});
            }
        }
    }
    return result;
}

}
