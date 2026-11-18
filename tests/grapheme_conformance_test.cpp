#include "../core/grapheme.h"

#include <fstream>
#include <iostream>
#include <sstream>
#include <string>

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream input(argv[1]);
    if (!input) return 2;
    size_t tested=0, lineNumber=0;
    std::string line;
    while (std::getline(input,line)) {
        ++lineNumber;
        const auto comment=line.find('#');
        std::istringstream row(line.substr(0,comment));
        std::u16string text;
        std::vector<size_t> expected;
        std::string item;
        while (row >> item) {
            if (item==u8"÷") expected.push_back(text.size());
            else if (item!=u8"×") {
                auto cp=std::stoul(item,nullptr,16);
                if (cp<=0xffff) text.push_back(static_cast<char16_t>(cp));
                else {
                    cp-=0x10000;
                    text.push_back(static_cast<char16_t>(0xd800+(cp>>10)));
                    text.push_back(static_cast<char16_t>(0xdc00+(cp&1023)));
                }
            }
        }
        if (expected.empty()) continue;
        const auto actual=wangshu::grapheme::boundaries(text);
        if (!actual || *actual!=expected) {
            std::cerr << "FAIL boundary disagreement at UCD test line " << lineNumber << '\n';
            return 1;
        }
        ++tested;
    }
    if (!input.eof() || !tested || wangshu::grapheme::boundaries(std::u16string(1,0xd800)) ||
        wangshu::grapheme::boundaries(std::u16string(1,0xdc00))) return 1;
    std::cout << "PASS " << tested << " Unicode 17 grapheme vectors and isolated surrogate rejection\n";
    return 0;
}
