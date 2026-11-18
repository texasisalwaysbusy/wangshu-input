// Replays public/synthetic key sequences without opening a personal store or an editor.
#include "../windows/text_service.cpp"

#include <chrono>
#include <fstream>
#include <iostream>
#include <sstream>

struct WindowsPersonalTestAccess {
    static void setDictionary(TextService& service, const std::shared_ptr<wangshu::Engine>& engine) {
        service.sharedEngine = engine;
    }
    static std::vector<wangshu::Candidate> page(TextService& service, const std::string& key, size_t page,
            const wangshu::PersonalData& manual, const wangshu::LearningData& learned, bool& fuzzy) {
        CandidateProof proof;
        auto candidates = service.pinyinPage(key, page, manual, learned, &proof);
        fuzzy = proof.fuzzy;
        return candidates;
    }
};

namespace {
using Clock = std::chrono::steady_clock;
constexpr size_t PageSize = 9;
constexpr size_t MaxCandidates = 64;

struct Case {
    std::string id, category, keys, correctKey, expected;
    unsigned mask = 0;
};

Case readCase(const std::string& line) {
    std::istringstream input(line);
    Case item;
    std::string mask;
    if (!std::getline(input, item.id, '\t') || !std::getline(input, item.category, '\t') ||
        !std::getline(input, item.keys, '\t') || !std::getline(input, item.correctKey, '\t') ||
        !std::getline(input, item.expected, '\t') || !std::getline(input, mask) ||
        item.id.empty() || item.keys.empty() || item.expected.empty())
        throw std::runtime_error("invalid replay row");
    size_t consumed = 0;
    item.mask = std::stoul(mask, &consumed);
    if (consumed != mask.size() || item.mask > wangshu::fuzzy_pinyin::AllRules ||
        item.keys.find_first_not_of("abcdefghijklmnopqrstuvwxyz'<") != std::string::npos)
        throw std::runtime_error("invalid replay key or rule");
    return item;
}

void dumpCandidates(const std::vector<wangshu::Candidate>& candidates, size_t maximum) {
    for (size_t index = 0; index < std::min(maximum, candidates.size()); ++index)
        std::cout << '\t' << candidates[index].text;
    std::cout << '\n';
}

template<class Lookup>
void replay(const Case& item, const char* profile, Lookup lookup) {
    std::string key;
    for (size_t index = 0; index < item.keys.size(); ++index) {
        const auto letter = item.keys[index];
        if (letter == '<') {
            if (key.empty()) throw std::runtime_error("backspace without input");
            key.pop_back();
        } else key += letter;
        bool fuzzy = false;
        const auto start = Clock::now();
        auto candidates = lookup(key, 0, fuzzy);
        const auto nanos = std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - start).count();
        std::cout << "STEP\t" << item.id << '\t' << profile << '\t' << index << '\t' << key << '\t'
                  << nanos << '\t' << fuzzy;
        dumpCandidates(candidates, PageSize);
        if (index + 1 != item.keys.size()) continue;
        size_t emitted = 0;
        for (size_t page = 0; emitted < MaxCandidates; ++page) {
            if (page) candidates = lookup(key, page, fuzzy);
            if (candidates.empty()) break;
            std::cout << "PAGE\t" << item.id << '\t' << profile << '\t' << page << '\t' << fuzzy;
            dumpCandidates(candidates, MaxCandidates - emitted);
            emitted += std::min(MaxCandidates - emitted, candidates.size());
        }
    }
}
}

int main(int argc, char** argv) {
    try {
        if (argc != 3) throw std::runtime_error("expected dictionary directory and UTF-8 case file");
        const auto start = Clock::now();
        const auto engine = std::make_shared<wangshu::Engine>();
        if (!engine->load(argv[1])) throw std::runtime_error("dictionary load failed");
        const auto load = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - start).count();
        std::cout << "DICTIONARY\t" << engine->entries() << '\t' << load << '\n';
        TextService service;
        WindowsPersonalTestAccess::setDictionary(service, engine);
        const wangshu::PersonalData empty;
        wangshu::PersonalData synthetic;
        synthetic.words = {{"zhongwen", u8"合成中文词"}};
        wangshu::LearningData learned;
        const auto now = wangshu::WindowsPersonalStore::learningNow();
        learned.enabled = true;
        learned.pin("nihao", u8"你好", true, now);
        for (int i = 0; i < 12; ++i) learned.record("nihao", u8"拟好", now);
        std::ifstream file(argv[2], std::ios::binary);
        if (!file) throw std::runtime_error("fixture unavailable");
        std::string line;
        if (!std::getline(file, line)) throw std::runtime_error("empty fixture");
        while (std::getline(file, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.empty()) continue;
            const auto item = readCase(line);
            replay(item, "core", [&](const auto& key, size_t page, bool& fuzzy) {
                fuzzy = false;
                return engine->lookup(key, PageSize, page);
            });
            wangshu::LearningData off, on;
            on.fuzzyMask = item.mask;
            replay(item, "windows_off", [&](const auto& key, size_t page, bool& fuzzy) {
                return WindowsPersonalTestAccess::page(service, key, page, empty, off, fuzzy);
            });
            replay(item, "windows_learned", [&](const auto& key, size_t page, bool& fuzzy) {
                return WindowsPersonalTestAccess::page(service, key, page, synthetic, learned, fuzzy);
            });
            if (item.mask) replay(item, "windows_fuzzy", [&](const auto& key, size_t page, bool& fuzzy) {
                return WindowsPersonalTestAccess::page(service, key, page, empty, on, fuzzy);
            });
        }
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
