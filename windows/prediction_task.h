#pragma once
#include <windows.h>
#include <atomic>
#include <memory>
#include <utility>
#include "../core/engine.h"

namespace wangshu {

// Worker payload contains only the immutable engine and bounded text values.
// TSF objects and all candidate validation stay on the apartment thread.
class PredictionTask {
    struct Payload {
        std::shared_ptr<PredictionTask> task;
        std::shared_ptr<const Engine> engine;
        std::string before, after;
        bool english, numericPanel;
        HMODULE module;
    };

    static VOID CALLBACK calculate(PTP_CALLBACK_INSTANCE instance, PVOID opaque) noexcept {
        std::unique_ptr<Payload> input(static_cast<Payload*>(opaque));
        if (input->module) FreeLibraryWhenCallbackReturns(instance, input->module);
        auto result = input->task;
        try {
            result->candidates_ = input->engine->predictForDisplay(input->before, input->after, input->english, input->numericPanel);
        } catch (...) {
            // A failed optional query leaves ordinary input available.
        }
        input.reset();
        result->ready_.store(true, std::memory_order_release);
    }

    std::atomic<bool> ready_{false};
    std::vector<Candidate> candidates_;

public:
    static std::shared_ptr<PredictionTask> start(std::shared_ptr<const Engine> engine,
            std::string before, std::string after, bool english, bool numericPanel = false) {
        if (!engine || before.empty() || before.size() > 1024 || after.size() > 1024) return {};
        auto task = std::make_shared<PredictionTask>();
        auto input = std::make_unique<Payload>(Payload{
            task, std::move(engine), std::move(before), std::move(after), english, numericPanel, nullptr});
        HMODULE module = nullptr;
        if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
                               reinterpret_cast<LPCWSTR>(&calculate), &module)) return {};
        if (module == GetModuleHandleW(nullptr)) FreeLibrary(module);
        else input->module = module;
        auto* payload = input.release();
        if (TrySubmitThreadpoolCallback(&calculate, payload, nullptr)) return task;
        if (payload->module) FreeLibrary(payload->module);
        delete payload;
        return {};
    }

    bool ready() const { return ready_.load(std::memory_order_acquire); }
    std::vector<Candidate> take() { return ready() ? std::move(candidates_) : std::vector<Candidate>{}; }
};

}
