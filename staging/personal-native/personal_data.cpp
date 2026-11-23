#include "personal_data.h"
#include "../../core/emoji_catalog.h"
#include "../../third_party/nlohmann/json.hpp"
#include <algorithm>
#include <set>
#include <limits>
namespace wangshu {
using Json=nlohmann::json;
static void fields(const Json& object,std::initializer_list<const char*> allowed){if(!object.is_object())throw std::runtime_error("Expected object");for(auto i=object.begin();i!=object.end();++i){bool found=false;for(auto key:allowed)if(i.key()==key)found=true;if(!found)throw std::runtime_error("Unknown personal-data field");}}
static std::string field(const Json& object,const char* key){if(!object.contains(key)||!object.at(key).is_string())throw std::runtime_error("Expected string field");return object.at(key).get<std::string>();}
std::string PersonalData::key(const std::string& input){size_t a=0,b=input.size();while(a<b&&static_cast<unsigned char>(input[a])<=32)++a;while(b>a&&static_cast<unsigned char>(input[b-1])<=32)--b;std::string out;for(size_t i=a;i<b;i++){unsigned char c=input[i];if(c=='\'')continue;if(c>='A'&&c<='Z')c+=32;if(c<'a'||c>'z')throw std::runtime_error("Invalid pinyin key");out+=static_cast<char>(c);}if(out.empty()||out.size()>64)throw std::runtime_error("Invalid pinyin length");return out;}
size_t PersonalData::validateText(const std::string& text,size_t maximum,bool multiline){size_t count=0;bool content=false;for(size_t i=0;i<text.size();){unsigned char lead=text[i++];uint32_t point;int remaining;if(lead<128){point=lead;remaining=0;}else if(lead>=0xc2&&lead<=0xdf){point=lead&31;remaining=1;}else if(lead>=0xe0&&lead<=0xef){point=lead&15;remaining=2;}else if(lead>=0xf0&&lead<=0xf4){point=lead&7;remaining=3;}else throw std::runtime_error("Invalid UTF8");int bytes=remaining;for(int j=0;j<remaining;j++){if(i==text.size())throw std::runtime_error("Incomplete UTF8");unsigned char c=text[i++];if((c&0xc0)!=0x80)throw std::runtime_error("Invalid UTF8 continuation");point=(point<<6)|(c&63);}if((bytes==1&&point<0x80)||(bytes==2&&point<0x800)||(bytes==3&&point<0x10000)||point>0x10ffff||(point>=0xd800&&point<=0xdfff))throw std::runtime_error("Noncanonical UTF8 scalar");if((point<32&&!(multiline&&(point==10||point==9)))||(point>=127&&point<=159))throw std::runtime_error("Unsupported control character");content|=point>32;if(++count>maximum)throw std::runtime_error("Text exceeds limit");}if(!content)throw std::runtime_error("Empty text");return count;}
PersonalData PersonalData::parse(const std::string& bytes, WordNormalizer normalizeWord) {
    if(bytes.size()>1024*1024)throw std::runtime_error("Personal file exceeds 1MiB");
    Json root;
    try {
        root=Json::parse(bytes,[](int depth,Json::parse_event_t,Json&){
            if(depth>16)throw std::runtime_error("Nested JSON exceeds schema limit");
            return true;
        });
    }catch(const Json::exception&){throw std::runtime_error("Malformed personal JSON");}
    if(field(root,"kind")!="wangshu-personal"||!root.contains("version")||
            !root["version"].is_number_integer()||(root["version"]!=1&&root["version"]!=2))
        throw std::runtime_error("Unsupported personal version");
    const bool hasFavorites=root["version"]==2;
    if(hasFavorites)fields(root,{"kind","version","words","phrases","favorites"});
    else fields(root,{"kind","version","words","phrases"});
    if(!root.contains("words")||!root["words"].is_array()||!root.contains("phrases")||!root["phrases"].is_array())
        throw std::runtime_error("Missing arrays");
    if(root["words"].size()>2000||root["phrases"].size()>1000)throw std::runtime_error("Too many personal records");
    PersonalData result;
    std::set<std::pair<std::string,std::string>> seen;
    for(const auto& word:root["words"]){
        fields(word,{"pinyin","text"});
        auto pinyin=key(field(word,"pinyin"));
        auto text=field(word,"text");
        if(normalizeWord)text=normalizeWord(text);
        validateText(text,200,false);
        if(seen.emplace(pinyin,text).second)result.words.push_back({pinyin,text});
    }
    seen.clear();
    for(const auto& phrase:root["phrases"]){
        fields(phrase,{"text","shortcut"});
        auto text=field(phrase,"text"),shortcut=phrase.contains("shortcut")?field(phrase,"shortcut"):"";
        validateText(text,4000,true);
        size_t begin=0,end=shortcut.size();
        while(begin<end&&static_cast<unsigned char>(shortcut[begin])<=32)++begin;
        while(end>begin&&static_cast<unsigned char>(shortcut[end-1])<=32)--end;
        shortcut=shortcut.substr(begin,end-begin);
        if(shortcut.size()>32)throw std::runtime_error("Shortcut exceeds limit");
        for(char& character:shortcut){
            if(character>='A'&&character<='Z')character+=32;
            if(character<'a'||character>'z')throw std::runtime_error("Invalid shortcut");
        }
        if(seen.emplace(shortcut,text).second)result.phrases.push_back({shortcut,text});
    }
    if(hasFavorites){
        if(!root.contains("favorites")||!root["favorites"].is_array()||root["favorites"].size()>MaxFavorites)
            throw std::runtime_error("Invalid favorite list");
        for(const auto& entry:root["favorites"]){
            if(!entry.is_string())throw std::runtime_error("Expected favorite string");
            result.setFavorite(entry.get<std::string>(),true);
        }
    }
    return result;
}
bool PersonalData::isFavorite(const std::string& glyph) const {
    return std::find(favorites.begin(),favorites.end(),glyph)!=favorites.end();
}
void PersonalData::setFavorite(const std::string& glyph,bool favorite) {
    if(!emoji_catalog::find(glyph))throw std::runtime_error("Favorite is not in the built-in emoji catalog");
    auto found=std::find(favorites.begin(),favorites.end(),glyph);
    if(favorite){
        if(found!=favorites.end())return;
        if(favorites.size()>=MaxFavorites)throw std::runtime_error("At most 64 favorite emoji and kaomoji");
        favorites.push_back(glyph);
    }else if(found!=favorites.end())favorites.erase(found);
}
std::string PersonalData::serialize()const{Json object={{"kind","wangshu-personal"},{"version",favorites.empty()?1:2},{"words",Json::array()},{"phrases",Json::array()}};for(const auto& w:words)object["words"].push_back({{"pinyin",w.pinyin},{"text",w.text}});for(const auto& p:phrases)object["phrases"].push_back({{"shortcut",p.shortcut},{"text",p.text}});if(!favorites.empty())object["favorites"]=favorites;std::string raw;try{raw=object.dump();}catch(const Json::exception&){throw std::runtime_error("Invalid personal Unicode");}PersonalData::parse(raw);return raw;}
PersonalData PersonalData::merged(const PersonalData& other)const{PersonalData result;std::set<std::pair<std::string,std::string>> seenWords,seenPhrases;for(const auto* source:{this,&other}){auto checked=parse(source->serialize());for(const auto& word:checked.words)if(seenWords.emplace(word.pinyin,word.text).second)result.words.push_back(word);for(const auto& phrase:checked.phrases)if(seenPhrases.emplace(phrase.shortcut,phrase.text).second)result.phrases.push_back(phrase);for(const auto& glyph:checked.favorites)result.setFavorite(glyph,true);}return parse(result.serialize());}
std::vector<Candidate> PersonalData::lookup(const Engine& base,const std::string& input,size_t page)const{if(input.empty()||input[0]=='\\'||page>1000)return base.lookup(input,9,page);std::string normalized;try{normalized=key(input);}catch(const std::exception&){return base.lookup(input,9,page);}std::vector<Candidate> values;std::set<std::string> unique;for(const auto& word:words)if(word.pinyin==normalized&&unique.insert(word.text).second)values.push_back({word.text,"个人",0});for(const auto& phrase:phrases)if(phrase.shortcut==normalized&&unique.insert(phrase.text).second)values.push_back({phrase.text,"常用语",0});if(values.empty())return base.lookup(input,9,page);size_t needed=(page+1)*9;for(size_t i=0;i<=page&&values.size()<needed;i++){auto chunk=base.lookup(input,9,i);for(auto& value:chunk)if(unique.insert(value.text).second)values.push_back(std::move(value));if(chunk.size()<9)break;}size_t from=page*9;if(from>=values.size())return {};size_t to=std::min(values.size(),needed);return {values.begin()+static_cast<std::ptrdiff_t>(from),values.begin()+static_cast<std::ptrdiff_t>(to)};}
}
