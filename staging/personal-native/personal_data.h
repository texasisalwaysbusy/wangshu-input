#pragma once
#include <string>
#include <vector>
#include <stdexcept>
#include "../../core/engine.h"
namespace wangshu {
struct PersonalWord {std::string pinyin,text;};
struct PersonalPhrase {std::string shortcut,text;};
// Staged portable production component: native/Windows integration still pending.
class PersonalData {
public:
    std::vector<PersonalWord> words;
    std::vector<PersonalPhrase> phrases;
    static PersonalData parse(const std::string& bytes);
    std::string serialize()const;
    PersonalData merged(const PersonalData& source)const;
    std::vector<Candidate> lookup(const Engine& base,const std::string& input,size_t page=0)const;
    static std::string key(const std::string& value);
    static size_t validateText(const std::string& value,size_t maximum,bool multiline);
};
}
