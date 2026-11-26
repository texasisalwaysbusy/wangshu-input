#pragma once
#include <string>
#include <string_view>
#include <vector>
#include <map>
#include <unordered_map>
#include <cstdint>
#include <array>
#include <memory>
#include <mutex>

namespace wangshu {
struct Candidate { std::string text; std::string annotation; int score=0; };
struct Symbol { std::string alias,text,category,spoken; };
class Engine {
public:
    bool load(const std::string& directory);
    std::vector<Candidate> lookup(const std::string& input,size_t limit=9,size_t page=0) const;
    std::vector<Candidate> predict(const std::string& before,const std::string& after,bool english=false,
                                   size_t limit=4,bool numericPanel=false) const;
    // Presentation query: separate bounded Chinese completion and next-word
    // lanes, using the existing safe prediction path and a single append action.
    std::vector<Candidate> predictForDisplay(const std::string& before,const std::string& after,
                                             bool english=false,bool numericPanel=false) const;
    std::vector<Symbol> symbols(const std::string& category="") const;
    std::string voiceMath(const std::string& recognized) const;
    std::string punctuation(char ascii,bool chinese) const;
    size_t entries() const {return entryCount_;}
private:
    struct CompletionWord { std::u16string text; int score; };
    struct CompletionIndex {
        std::once_flag initialized;
        std::vector<CompletionWord> words;
    };
    static std::vector<CompletionWord> completionWords(const std::map<std::string,std::vector<Candidate>>& dictionary);
    std::vector<Candidate> completeChinese(std::u16string_view line,std::u16string_view after,size_t limit,
                                            size_t minimumPrefix=1) const;
    std::map<std::string,std::vector<Candidate>> words_;
    std::shared_ptr<CompletionIndex> chineseCompletions_;
    std::vector<Symbol> symbols_;
    std::array<std::vector<std::string>,26> pinyinSyllables_;
    size_t entryCount_=0;
};
std::u16string utf16(const std::string& text);
std::string utf8(const std::u16string& text);
}
