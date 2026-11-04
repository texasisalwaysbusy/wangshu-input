#include "engine.h"
#include "pinyin_composition.h"
#include "mixed_composition.h"
#include "local_predictions.h"
#include <fstream>
#include <sstream>
#include <algorithm>
#include <set>
#include <cctype>
#include <limits>
#include <filesystem>
#include <stdexcept>
#include <queue>

namespace wangshu {
static std::vector<std::string> fields(const std::string& line){std::vector<std::string> f;size_t start=0;while(start<line.size()){size_t end=line.find('\t',start);if(end==std::string::npos){f.push_back(line.substr(start));break;}f.push_back(line.substr(start,end-start));start=end+1;}return f;}
static bool better(const Candidate& a,const Candidate& b){if(a.score!=b.score)return a.score>b.score;return a.text<b.text;}
static int clampScore(int64_t score){return static_cast<int>(std::clamp(score,static_cast<int64_t>(std::numeric_limits<int>::min()),static_cast<int64_t>(std::numeric_limits<int>::max())));}
static void trim(std::vector<Candidate>& out,size_t n){if(out.size()<2){if(out.size()>n)out.resize(n);return;}std::sort(out.begin(),out.end(),better);std::set<std::string> seen;out.erase(std::remove_if(out.begin(),out.end(),[&](const Candidate& c){return !seen.insert(c.text).second;}),out.end());if(out.size()>n)out.resize(n);}
// Merge the best Cartesian products without materializing beam^2 candidates.
// Both inputs are sorted by trim(); each fixed-prefix row stays sorted.
static void composeBest(const std::vector<Candidate>& prefixes,const std::vector<Candidate>& suffixes,
                        std::vector<Candidate>& destination,const std::string& annotation){
    struct Pair {size_t prefix,suffix;Candidate value;};
    auto compare=[](const Pair& a,const Pair& b){return better(b.value,a.value);};
    std::priority_queue<Pair,std::vector<Pair>,decltype(compare)> queue(compare);
    auto push=[&](size_t a,size_t b){
        while(b<suffixes.size()&&prefixes[a].text.size()+suffixes[b].text.size()>512)++b;
        if(b<suffixes.size())queue.push({a,b,{prefixes[a].text+suffixes[b].text,annotation,
            clampScore(static_cast<int64_t>(prefixes[a].score)+suffixes[b].score)}});
    };
    if(suffixes.empty())return;
    for(size_t a=0;a<prefixes.size();++a)push(a,0);
    std::set<std::string> seen;
    while(!queue.empty()&&seen.size()<pinyin::kBeamWidth){
        auto pair=queue.top();queue.pop();
        if(seen.insert(pair.value.text).second)destination.push_back(std::move(pair.value));
        if(pair.suffix+1<suffixes.size())push(pair.prefix,pair.suffix+1);
    }
    trim(destination,pinyin::kBeamWidth);
}
bool Engine::load(const std::string& directory){
    auto path=std::filesystem::u8path(directory);
    std::ifstream dictionary(path/"pinyin.tsv"),symbols(path/"symbols.tsv");if(!dictionary||!symbols)return false;
    std::map<std::string,std::vector<Candidate>> loaded;std::vector<Symbol> signs;size_t count=0;std::string line;
    auto previous=loaded.end();
    while(std::getline(dictionary,line)){
        if(!line.empty()&&line.back()=='\r')line.pop_back(); // Accept Windows CRLF without treating CR as part of a score.
        if(line.empty()||line[0]=='#')continue;
        auto f=fields(line);if(f.size()<3||f[0].size()>80||f[1].size()>300)continue;
        const std::string& value=f[2];size_t digit=(value.size()>0&&(value[0]=='+'||value[0]=='-'))?1:0;
        if(digit==value.size()||!std::all_of(value.begin()+static_cast<std::ptrdiff_t>(digit),value.end(),[](char c){return c>='0'&&c<='9';}))continue;
        int score=0;try{size_t parsed=0;score=std::stoi(value,&parsed);if(parsed!=value.size())continue;}catch(const std::invalid_argument&){continue;}catch(const std::out_of_range&){continue;}
        // Generated files are sorted. Hint insertion avoids a tree search for every word;
        // an edited/unsorted file still follows std::map's correct fallback path.
        if(previous==loaded.end()||previous->first!=f[0])previous=loaded.emplace_hint(loaded.end(),f[0],std::vector<Candidate>{});
        previous->second.push_back({f[1],f[0],score});count++;
    }
    while(std::getline(symbols,line)){if(!line.empty()&&line.back()=='\r')line.pop_back();if(line.empty()||line[0]=='#')continue;auto f=fields(line);if(f.size()>=3)signs.push_back({f[0],f[1],f[2],f.size()>3?f[3]:""});}
    if(count<10||signs.empty())return false;
    for(auto& item:loaded)trim(item.second,2000);
    auto syllables=pinyin::syllables(loaded);
    words_=std::move(loaded);symbols_=std::move(signs);pinyinSyllables_=std::move(syllables);entryCount_=count;return true;
}
std::vector<Candidate> Engine::lookup(const std::string& raw,size_t limit,size_t page) const{
    if(raw.empty()||raw.size()>128||limit==0||limit>50||page>100)return {};
    if(raw.find('`')!=std::string::npos){
        std::vector<mixed::Segment> segments;if(!mixed::parse(raw,segments))return {};
        std::vector<Candidate> beam{{"","中英分段",0}};
        for(const auto& segment:segments){
            std::vector<Candidate> part=segment.literal?std::vector<Candidate>{{segment.text,"原文英段",0}}:lookup(segment.text,3,0);
            if(part.empty())return {};
            std::vector<Candidate> next;for(const auto& prefix:beam)for(const auto& suffix:part){
                if(prefix.text.size()+suffix.text.size()>512)continue;
                next.push_back({prefix.text+suffix.text,"中英分段 · 原文保留",clampScore(static_cast<int64_t>(prefix.score)+suffix.score)});
            }
            trim(next,36);beam=std::move(next);if(beam.empty())return {};
        }
        size_t begin=page*limit;if(begin>=beam.size())return {};size_t end=std::min(beam.size(),begin+limit);
        return {beam.begin()+static_cast<std::ptrdiff_t>(begin),beam.begin()+static_cast<std::ptrdiff_t>(end)};
    }
    std::vector<Candidate> out;size_t needed=(page+1)*limit;
    if(raw[0]=='\\'){
        std::string alias=raw.substr(1);
        for(const auto& s:symbols_)if(s.alias==alias)out.push_back({s.text,"\\"+s.alias,2000000});else if(s.alias.compare(0,alias.size(),alias)==0)out.push_back({s.text,"\\"+s.alias,1000000-static_cast<int>(s.alias.size())});
    }else{
        std::string key;for(unsigned char c:raw){if(c=='\'')continue;if(c<'A'||(c>'Z'&&c<'a')||c>'z')return {};key+=static_cast<char>(std::tolower(c));}
        // A separator without letters is not a query and must not match every prefix.
        if(key.empty())return {};
        auto exact=words_.find(key);
        if(exact!=words_.end())out=exact->second;
        else {
            // Offer prefix completions before enough letters exist for a whole syllable.
            size_t scanned=0;
            for(auto it=words_.lower_bound(key);it!=words_.end()&&it->first.compare(0,key.size(),key)==0&&scanned<5000;++it,++scanned)
                for(size_t i=0;i<std::min<size_t>(3,it->second.size());i++){auto c=it->second[i];c.score=clampScore(static_cast<int64_t>(c.score)-static_cast<int64_t>(it->first.size()-key.size())*40);out.push_back(c);}
            // Beam segmentation handles full phrases without requiring a pre-existing exact entry.
            if(key.size()>=2){
                std::vector<std::vector<Candidate>> dp(key.size()+1);dp[0].push_back({"","",0});
                for(size_t i=0;i<key.size();i++)if(!dp[i].empty())for(size_t len=1;len<=24&&i+len<=key.size();len++){
                    auto it=words_.find(key.substr(i,len));if(it==words_.end())continue;
                    for(const auto& prefix:dp[i])for(size_t k=0;k<std::min<size_t>(3,it->second.size());k++){
                        const auto& word=it->second[k];
                        if(prefix.text.size()+word.text.size()>512)continue;
                        int score=clampScore(static_cast<int64_t>(prefix.score)+word.score/100-pinyin::kLexicalNormalizer+static_cast<int64_t>(len*len*20));
                        dp[i+len].push_back({prefix.text+word.text,key,score});
                    }
                    trim(dp[i+len],pinyin::kBeamWidth);
                }
                // Only fall back to initial/full-spelling composition if the full
                // spelling has neither prefix completions nor a complete segmentation.
                // Preserve existing prefix ordering and avoid extra search while the
                // user is still typing a normal word. Exact lookups stay on their fast path.
                if(dp.back().empty()&&out.empty()&&key.size()<=pinyin::kMaxAbbreviatedLetters) {
                    std::vector<std::vector<pinyin::Spelling>> choices(key.size());
                    for(size_t i=0;i<key.size();++i)choices[i]=pinyin::spellings(key,i,pinyinSyllables_);
                    std::vector<std::vector<Candidate>> abbreviated(key.size()+1);
                    abbreviated[0].push_back({"","",0});
                    size_t budget=pinyin::kMaxExpandedStates;
                    for(size_t i=0;i<key.size()&&budget;++i)if(!abbreviated[i].empty()) {
                        auto matches=pinyin::abbreviatedWords(key,i,words_,choices,budget);
                        std::vector<std::vector<Candidate>> parts(key.size()+1);
                        for(const auto& match:matches) {
                            for(size_t k=0;k<std::min<size_t>(3,match.words->size());++k) {
                                const auto& word=(*match.words)[k];
                                int score=clampScore(static_cast<int64_t>(word.score)/100-pinyin::kLexicalNormalizer+
                                    static_cast<int64_t>(match.letters*match.letters*20)-static_cast<int64_t>(match.omitted*pinyin::kOmittedLetterCost));
                                parts[match.end].push_back({word.text,"",score});
                            }
                        }
                        for(size_t end=i+1;end<=key.size();++end)if(!parts[end].empty()) {
                            trim(parts[end],pinyin::kBeamWidth);
                            composeBest(abbreviated[i],parts[end],abbreviated[end],key+" · 简拼");
                        }
                    }
                    dp.back()=std::move(abbreviated.back());
                }
                for(auto c:dp.back()){c.score=clampScore(static_cast<int64_t>(c.score)+1500000);out.push_back(c);}
            }
        }
    }
    trim(out,needed);size_t begin=page*limit;if(begin>=out.size())return {};return {out.begin()+static_cast<std::ptrdiff_t>(begin),out.end()};
}
std::vector<Candidate> Engine::predict(const std::string& before,const std::string& after,bool english,size_t limit) const{
    if(before.size()>131072||after.size()>131072)return {};
    auto left=utf16(before),right=utf16(after);
    // Reject malformed input instead of predicting from replacement characters.
    if(utf8(left)!=before||utf8(right)!=after)return {};
    std::vector<Candidate> out;for(const auto& value:local_prediction::suggest(left,right,english,limit))
        out.push_back({utf8(value.suffix),"本地例句 #"+std::to_string(value.row)+(value.sentence?" · 整句":" · 下一词"),static_cast<int>(value.matched)});
    return out;
}
std::vector<Symbol> Engine::symbols(const std::string& category) const{std::vector<Symbol> out;for(const auto& s:symbols_)if(category.empty()||s.category==category)out.push_back(s);return out;}
std::string Engine::punctuation(char c,bool chinese)const{if(!chinese)return std::string(1,c);const std::map<char,std::string> names={{',',"comma"},{'.',"period"},{'?',"question"},{'!',"exclamation"},{';',"semicolon"},{':',"colon"}};auto it=names.find(c);if(it!=names.end())for(const auto& s:symbols_)if(s.alias==it->second)return s.text;return std::string(1,c);}
std::string Engine::voiceMath(const std::string& input) const{
    if(input.size()>100000)return input;
    std::vector<std::pair<std::string,std::string>> substitutions;
    for(const auto& s:symbols_)if(!s.spoken.empty()){std::istringstream in(s.spoken);std::string phrase;while(std::getline(in,phrase,'|'))if(!phrase.empty())substitutions.push_back({phrase,s.text});}
    std::sort(substitutions.begin(),substitutions.end(),[](const auto& a,const auto& b){return a.first.size()>b.first.size();});std::string out=input;
    for(const auto& item:substitutions){size_t at=0;while((at=out.find(item.first,at))!=std::string::npos){out.replace(at,item.first.size(),item.second);at+=item.second.size();}}
    return out;
}
std::u16string utf16(const std::string& text){
    std::u16string out;for(size_t i=0;i<text.size();){unsigned char b=text[i++];uint32_t c=0;int n=0;if(b<128)c=b;else if((b&0xe0)==0xc0){c=b&31;n=1;}else if((b&0xf0)==0xe0){c=b&15;n=2;}else if((b&0xf8)==0xf0){c=b&7;n=3;}else{out+=u'\ufffd';continue;}bool valid=true;for(int j=0;j<n;j++){if(i>=text.size()||(static_cast<unsigned char>(text[i])&0xc0)!=0x80){valid=false;break;}c=(c<<6)|(static_cast<unsigned char>(text[i++])&63);}if(!valid||c>0x10ffff||(c>=0xd800&&c<=0xdfff)||(n==1&&c<128)||(n==2&&c<2048)||(n==3&&c<65536)){out+=u'\ufffd';continue;}if(c<65536)out+=static_cast<char16_t>(c);else{c-=65536;out+=static_cast<char16_t>(0xd800+(c>>10));out+=static_cast<char16_t>(0xdc00+(c&1023));}}
    return out;
}
std::string utf8(const std::u16string& text){std::string out;for(size_t i=0;i<text.size();i++){uint32_t c=text[i];if(c>=0xd800&&c<=0xdbff&&i+1<text.size()&&text[i+1]>=0xdc00&&text[i+1]<=0xdfff){c=0x10000+((c-0xd800)<<10)+(text[++i]-0xdc00);}else if(c>=0xd800&&c<=0xdfff)c=0xfffd;if(c<128)out+=static_cast<char>(c);else if(c<2048){out+=static_cast<char>(0xc0|(c>>6));out+=static_cast<char>(0x80|(c&63));}else if(c<65536){out+=static_cast<char>(0xe0|(c>>12));out+=static_cast<char>(0x80|((c>>6)&63));out+=static_cast<char>(0x80|(c&63));}else{out+=static_cast<char>(0xf0|(c>>18));out+=static_cast<char>(0x80|((c>>12)&63));out+=static_cast<char>(0x80|((c>>6)&63));out+=static_cast<char>(0x80|(c&63));}}return out;}
}
