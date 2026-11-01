#pragma once
#include <string>
#include <vector>
#include <map>
#include <unordered_map>
#include <cstdint>

namespace wangshu {
struct Candidate { std::string text; std::string annotation; int score=0; };
struct Symbol { std::string alias,text,category,spoken; };
class Engine {
public:
    bool load(const std::string& directory);
    std::vector<Candidate> lookup(const std::string& input,size_t limit=9,size_t page=0) const;
    std::vector<Symbol> symbols(const std::string& category="") const;
    std::string voiceMath(const std::string& recognized) const;
    std::string punctuation(char ascii,bool chinese) const;
    size_t entries() const {return entryCount_;}
private:
    std::map<std::string,std::vector<Candidate>> words_;
    std::vector<Symbol> symbols_;
    size_t entryCount_=0;
};
std::u16string utf16(const std::string& text);
std::string utf8(const std::u16string& text);
}
