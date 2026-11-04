#pragma once
#include <string>
#include <string_view>
#include <vector>
#include <optional>
// Bounded, deterministic continuation of explicitly saved phrases only.
// No model, history, automatic learning, text replacement or external service.
namespace wangshu::phrase_association {
inline bool asciiLetter(wchar_t c){return(c>=L'a'&&c<=L'z')||(c>=L'A'&&c<=L'Z');}
inline wchar_t lower(wchar_t c){return c>=L'A'&&c<=L'Z'?c+32:c;}
inline bool han(wchar_t c){return c>=0x3400&&c<=0x9fff;}
inline bool safe(std::wstring_view s){if(s.empty()||s.size()>512)return false;for(wchar_t c:s)if(!asciiLetter(c)&&!han(c)&&c!=L' ')return false;return true;}
inline bool equalFold(std::wstring_view a,std::wstring_view b){if(a.size()!=b.size())return false;for(size_t i=0;i<a.size();++i)if(lower(a[i])!=lower(b[i]))return false;return true;}
inline std::optional<std::wstring> suggest(std::wstring_view text,size_t caret,const std::vector<std::wstring>& phrases){
 if(text.empty()||text.size()>1024||caret!=text.size()||phrases.size()>2000)return {};
 // Use the current line, never infer across line boundaries or parse code/URLs.
 auto split=text.find_last_of(L"\r\n");auto line=text.substr(split==std::wstring_view::npos?0:split+1);
 if(!safe(line))return {};
 bool chinese=han(line.back());bool next=line.back()==L' ';
 if(!chinese&&!next&&!asciiLetter(line.back()))return {};
 size_t wordStart=line.size();while(wordStart&&asciiLetter(line[wordStart-1]))--wordStart;
 if(!chinese&&!next&&line.size()-wordStart<2)return {};
 // Chinese requires at least one Han prefix; English matches the entire current
 // phrase (not arbitrary suffixes) so unrelated preceding words never autocomplete.
 for(const auto& phrase:phrases){
  if(!safe(phrase)||phrase.size()<=line.size()||!equalFold(std::wstring_view(phrase).substr(0,line.size()),line))continue;
  std::wstring rest=phrase.substr(line.size());
  if(chinese){if(rest.size()>128)continue;return rest;}
  if(next){
   if(line.size()<2||!asciiLetter(line[line.size()-2])||!asciiLetter(rest[0]))continue;
   auto end=rest.find(L' ');if(end!=std::wstring::npos)rest.resize(end);
  }else{
   auto end=rest.find(L' ');if(end!=std::wstring::npos)rest.resize(end);
  }
  if(rest.empty()||rest.size()>128)continue;
  // Uppercase word prefixes keep the appended word uppercase. Title case is
  // naturally preserved because only the untyped suffix is appended.
  bool letters=false,allUpper=true;auto prefix=next?line.substr(0,line.size()-1):line.substr(wordStart);
  for(wchar_t c:prefix)if(asciiLetter(c)){letters=true;if(c>=L'a'&&c<=L'z')allUpper=false;}
  if(letters&&allUpper){for(auto& c:rest)if(c>=L'a'&&c<=L'z')c-=32;}
  else if(!next){bool lowerTail=true;for(size_t i=1;i<prefix.size();++i)if(prefix[i]<L'a'||prefix[i]>L'z')lowerTail=false;if(lowerTail)for(auto& c:rest)c=lower(c);}
  return rest;
 }
 return {};
}
}
