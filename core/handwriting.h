#pragma once
#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

namespace wangshu {
// Phase-two extension contract only. No recognizer/model is bundled, no UI entry
// advertises availability, and strokes are not collected or persisted by v1.
struct StrokePoint {
    float x=0, y=0; // Coordinates normalized to the explicitly opened canvas.
    uint32_t elapsed_ms=0;
};
struct HandwritingRequest {
    uint64_t editor_generation=0;
    std::vector<std::vector<StrokePoint>> strokes;
    size_t candidate_limit=9;
};
struct HandwritingCandidate {
    std::string text_utf8;
    float score=0; // Provider-specific rank, not a calibrated probability.
};
struct HandwritingResult {
    uint64_t editor_generation=0;
    std::vector<HandwritingCandidate> candidates;
    bool cancelled=false;
};
class HandwritingBackend {
public:
    virtual ~HandwritingBackend()=default;
    virtual bool available() const noexcept=0;
    // Future callers run outside the UI thread, validate stroke/size limits and
    // discard results whose editor generation has changed or was cancelled.
    virtual HandwritingResult recognize(const HandwritingRequest& request,
                                       const std::atomic<bool>& cancelled)=0;
};
}
