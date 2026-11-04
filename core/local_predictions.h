#pragma once
#include <algorithm>
#include <string>
#include <string_view>
#include <vector>

// Finite, original editorial corpus. No history, learning, model, or network I/O.
// Matching is a bounded suffix/n-gram rule, not a semantic prediction claim.
namespace wangshu::local_prediction {
struct Example {int language;std::vector<std::u16string> units;};
struct Result {std::u16string suffix;size_t row=0,matched=0;bool sentence=false;};
inline const std::vector<Example>& examples(){static const std::vector<Example> values={
#include "local_prediction_data.inc"
};return values;}
inline bool letter(char16_t c){return(c>=u'a'&&c<=u'z')||(c>=u'A'&&c<=u'Z');}
inline bool han(char16_t c){return (c>=0x3400&&c<=0x4dbf)||(c>=0x4e00&&c<=0x9fff);}
inline char16_t lower(char16_t c){return c>=u'A'&&c<=u'Z'?c+32:c;}
inline bool equalFold(std::u16string_view a,std::u16string_view b){if(a.size()!=b.size())return false;for(size_t i=0;i<a.size();++i)if(lower(a[i])!=lower(b[i]))return false;return true;}
inline bool separator(char16_t c){return c==u' '||c==u'\t'||c==u'\n'||c==u'\r'||std::u16string_view(u",!?;，。！？；、…").find(c)!=std::u16string_view::npos;}
inline bool valid(std::u16string_view text){for(size_t i=0;i<text.size();++i){auto c=text[i];if(c>=0xd800&&c<=0xdbff){if(++i>=text.size()||text[i]<0xdc00||text[i]>0xdfff)return false;}else if(c>=0xdc00&&c<=0xdfff)return false;}return true;}
inline bool boundary(std::u16string_view text,size_t at){return at==0||separator(text[at-1])||han(text[at-1]);}
inline bool safeLine(std::u16string_view text){for(char16_t c:text)if(!letter(c)&&!han(c)&&!separator(c)&&c!=u'\''&&c!=u'’')return false;return true;}
inline bool duplicate(std::u16string_view suffix,std::u16string_view after){size_t start=0;while(start<after.size()&&separator(after[start]))++start;if(start==after.size())return false;size_t n=std::min(suffix.size(),after.size()-start);return n&&equalFold(suffix.substr(0,n),after.substr(start,n));}
inline std::vector<Result> suggest(std::u16string_view before,std::u16string_view after,bool english,size_t limit=4){
 if(before.empty()||before.size()>32768||after.size()>32768||!limit||limit>8||!valid(before)||!valid(after))return {};
 auto lineStart=before.find_last_of(u"\r\n");auto line=before.substr(lineStart==std::u16string_view::npos?0:lineStart+1);
 // Never truncate an unsafe code/URL prefix into apparently safe prose.
 if(line.size()>256||!safeLine(line)||(!after.empty()&&!separator(after.front())))return {};
 std::vector<Result> out;size_t row=0,bestMatched=0;
 for(const auto& example:examples()){
  ++row;if(example.language!=2&&example.language!=(english?1:0))continue;
  std::u16string sentence;std::vector<size_t> offsets{0};for(const auto& unit:example.units){sentence+=unit;offsets.push_back(sentence.size());}
  for(size_t token=0;token<example.units.size();++token){
   // Use no more than three preceding units plus a partial current unit.
   for(size_t startToken=token>3?token-3:0;startToken<=token;++startToken){
    size_t start=offsets[startToken];
    for(size_t end=offsets[token]+1;end<=offsets[token+1]&&end<sentence.size();++end){
     size_t count=end-start;if(count>line.size()||count<2)continue;
     auto typed=line.substr(line.size()-count);if(!boundary(line,line.size()-count)||!equalFold(typed,std::u16string_view(sentence).substr(start,count)))continue;
     // A completed English word without a typed separating space is not a
     // next-word trigger. Punctuation-only continuation is also suppressed.
     char16_t next=sentence[end];if(next==u' '||separator(next)||(!letter(next)&&!han(next)))continue;
     bool partial=letter(sentence[end-1])&&letter(next);
     size_t partialStart=end;while(partialStart>start&&letter(sentence[partialStart-1]))--partialStart;
     if(partial&&end-partialStart<2)continue;
     // Require at least two Han characters for Chinese context; one ambiguous
     // character is not enough to manufacture a continuation.
     size_t hans=0;for(auto c:typed)hans+=han(c);if(!english&&example.language==0&&hans<2)continue;
     bestMatched=std::max(bestMatched,count);
     std::u16string full=sentence.substr(end),word=sentence.substr(end,offsets[token+1]-end);
     if(word.empty()&&token+1<example.units.size())word=example.units[token+1];
     // At a unit boundary the next unit, not an empty prior-unit tail, is the word.
     if(end==offsets[token+1]&&token+1<example.units.size())word=example.units[token+1];
     while(!word.empty()&&word.back()==u' ')word.pop_back();
     bool upper=true;size_t letters=0;auto caseTyped=typed;while(!caseTyped.empty()&&caseTyped.back()==u' ')caseTyped.remove_suffix(1);
     size_t at=caseTyped.size();while(at&&letter(caseTyped[at-1]))--at;for(auto c:caseTyped.substr(at)){if(c>=u'a'&&c<=u'z')upper=false;letters+=letter(c);}
     if(upper&&letters>=2){for(auto& c:word)if(c>=u'a'&&c<=u'z')c-=32;for(auto& c:full)if(c>=u'a'&&c<=u'z')c-=32;}
     if(!word.empty()&&word.size()<=64&&!duplicate(word,after))out.push_back({word,row,count,false});
     if(full.size()<=128&&!full.empty()&&full!=word&&!duplicate(full,after))out.push_back({full,row,count,true});
    }
   }
  }
 }
 std::stable_sort(out.begin(),out.end(),[](const Result& a,const Result& b){if(a.matched!=b.matched)return a.matched>b.matched;if(a.row!=b.row)return a.row<b.row;return a.sentence<b.sentence;});
 std::vector<Result> unique;for(auto& value:out){if(value.matched<bestMatched)continue;if(std::none_of(unique.begin(),unique.end(),[&](const Result& old){return equalFold(old.suffix,value.suffix);}))unique.push_back(std::move(value));if(unique.size()==limit)break;}return unique;
}
}
