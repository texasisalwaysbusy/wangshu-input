#pragma once
#include <string>
#include <string_view>
#include <vector>
// Backtick is an explicit preedit-only English literal boundary. No escapes,
// evaluation, inferred language changes or interpretation of literal contents.
namespace wangshu::mixed {
struct Segment {bool literal;std::string text;};
inline bool active(std::string_view text){bool value=false;for(char c:text)if(c=='`')value=!value;return value;}
inline std::string raw(std::string_view text){std::string result;for(char c:text)if(c!='`')result+=c;return result;}
inline bool parse(std::string_view text,std::vector<Segment>& out){
 out.clear();if(text.empty()||text.size()>128||text.find('`')==std::string_view::npos||text.front()=='\\')return false;
 bool literal=false;std::string part;
 for(unsigned char c:text){if(c=='`'){if(!part.empty()){out.push_back({literal,std::move(part)});part.clear();}literal=!literal;}
  else{if(c<32||c>126||(!literal&&!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||c=='\'')))return false;part+=static_cast<char>(c);}if(out.size()>8)return false;}
 if(!part.empty())out.push_back({literal,std::move(part)});
 return !out.empty()&&out.size()<=8;
}
}
