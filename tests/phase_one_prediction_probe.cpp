#include "../core/engine.h"
#include "../third_party/nlohmann/json.hpp"

#include <algorithm>
#include <chrono>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using Json = nlohmann::json;
using Clock = std::chrono::steady_clock;

// Warm measurements of the actual shared-engine candidate query. No editor or model installation.
int main(int argc, char** argv) {
    if (argc != 4 && argc != 5) {
        std::cerr << "usage: phase_one_prediction_probe <prediction.jsonl> <build-id> <platform> [dictionary-dir]\n";
        return 2;
    }
    try {
        std::ifstream source(argv[1], std::ios::binary);
        if (!source) throw std::runtime_error("Cannot open frozen samples");
        wangshu::Engine engine;
        const auto loadStart = Clock::now();
        if (argc == 5 && !engine.load(argv[4])) throw std::runtime_error("Cannot load requested dictionary");
        const auto loadMs = std::chrono::duration<double, std::milli>(Clock::now() - loadStart).count();
        Json observations = Json::array();
        std::string line;
        constexpr int repeats = 7;
        while (std::getline(source, line)) {
            if (line.empty()) continue;
            const auto sample = Json::parse(line);
            const auto before = sample.at("before").get<std::string>();
            const auto after = sample.at("after").get<std::string>();
            const bool english = sample.at("language") == "en";
            const auto firstStart = Clock::now();
            const auto expected = engine.predict(before, after, english, 5);
            const auto firstMs = std::chrono::duration<double, std::milli>(Clock::now() - firstStart).count();
            std::vector<double> latency;
            for (int repeat = 0; repeat < repeats; ++repeat) {
                const auto start = Clock::now();
                const auto actual = engine.predict(before, after, english, 5);
                latency.push_back(std::chrono::duration<double, std::milli>(Clock::now() - start).count());
                if (actual.size() != expected.size() ||
                    !std::equal(actual.begin(), actual.end(), expected.begin(),
                                [](const auto& left, const auto& right) { return left.text == right.text; })) {
                    throw std::runtime_error("Prediction output changed during repeated measurement");
                }
            }
            Json candidates = Json::array();
            for (const auto& candidate : expected) candidates.push_back(candidate.text);
            observations.push_back({{"id", sample.at("id")}, {"candidates", candidates},
                                    {"latency_ms", *std::max_element(latency.begin(), latency.end())},
                                    {"first_query_ms", firstMs},
                                    {"warm_query_ms", latency}});
        }
        if (!source.eof() || observations.empty()) throw std::runtime_error("Sample read failed");
        const Json run = {{"build", argv[2]}, {"platform", argv[3]}, {"layer", "shared-core"},
                          {"configuration", "Engine::predict, max 5, warm serial queries, maximum of 7 per case"},
                          {"dictionary_entries", engine.entries()},
                          {"dictionary_load_ms", argc == 5 ? Json(loadMs) : Json(nullptr)}};
        std::cout << Json{{"run", run}, {"observations", observations}}.dump(2) << '\n';
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
