#include "../core/context_predictions.h"

#include <fstream>
#include <iostream>
#include <sstream>

int main(int argc, char** argv) {
    using namespace wangshu::context_prediction::long_english;
    if (argc != 2) return 2;
    std::ifstream fitted(argv[1]);
    if (!fitted) return 2;

    const std::array<std::string, 10> families{{"n1", "n2", "n3", "n4", "n5", "n6", "g11", "g12", "g21", "g22"}};
    Key previous{};
    size_t groups = 0;
    size_t rows = 0;
    std::string line;
    while (std::getline(fitted, line)) {
        std::istringstream columns(line);
        std::string family;
        columns >> family;
        const auto category = std::find(families.begin(), families.end(), family);
        if (category == families.end()) return 2;
        Key key{};
        key[0] = static_cast<Id>(category - families.begin() + 1);
        unsigned value = 0;
        size_t width = 1;
        while (columns >> value && value) {
            if (width == key.size() || value > longEnglishWordCount) return 2;
            key[width++] = static_cast<Id>(value);
        }
        if (!columns || !(previous < key)) return 2;
        auto found = find(key);
        unsigned count = 0;
        while (columns >> value >> count) {
            if (found.empty() || wordId(*found.first) != value || frequency(*found.first) != count) {
                std::cerr << "Unexpected candidate at group " << groups + 1 << '\n';
                return 1;
            }
            ++found.first;
            ++rows;
        }
        if (!columns.eof() || !found.empty()) return 1;
        // No group can contain an out-of-vocabulary ID. With a valid family
        // and prefix, this also exercises misses inside the block index.
        auto missing = key;
        missing[width - 1] = static_cast<Id>(longEnglishWordCount + 1);
        if (!find(missing).empty()) return 1;
        previous = key;
        ++groups;
    }
    if (groups != 198891 || rows != 677829 || !find(Key{}).empty() ||
            !find(Key{{11, 1}}).empty()) return 1;
    std::cout << groups << " matching keys and " << rows << " matching rows\n";
}
