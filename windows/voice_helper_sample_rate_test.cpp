// Exercise the production helper's waveform boundary with an in-process decoder double.
#define wmain voice_helper_entry
#include "voice_helper.cpp"
#undef wmain
#include <chrono>
#include <sstream>

namespace {
struct Observed {
    int rate = 0;
    int calls = 0;
    bool finished = false;
    bool correct = true;
} observed;

void accept(const SherpaOnnxOnlineStream*, int32_t rate, const float* samples, int32_t size) {
    ++observed.calls;
    if (rate != observed.rate || observed.finished) observed.correct = false;
    if (observed.calls == 2) {
        if (size != rate / 2) observed.correct = false;
        for (int32_t i = 0; i < size; ++i)
            if (samples[i] != 0.0f) observed.correct = false;
    }
}
int32_t ready(const SherpaOnnxOnlineRecognizer*, const SherpaOnnxOnlineStream*) { return 0; }
void finish(const SherpaOnnxOnlineStream*) { observed.finished = true; }
const SherpaOnnxOnlineRecognizerResult* result(
        const SherpaOnnxOnlineRecognizer*, const SherpaOnnxOnlineStream*) {
    static SherpaOnnxOnlineRecognizerResult value{};
    value.text = "synthetic";
    if (!observed.finished) observed.correct = false;
    return &value;
}
void destroy(const SherpaOnnxOnlineRecognizerResult*) {}

class PausedOutput : public std::stringbuf {
    std::mutex mutex;
    std::condition_variable changed;
    bool entered = false;
    bool released = false;
protected:
    std::streamsize xsputn(const char* data, std::streamsize size) override {
        std::unique_lock<std::mutex> lock(mutex);
        entered = true;
        changed.notify_one();
        changed.wait(lock, [this] { return released; });
        lock.unlock();
        return std::stringbuf::xsputn(data, size);
    }
public:
    bool waitForWrite() {
        std::unique_lock<std::mutex> lock(mutex);
        return changed.wait_for(lock, std::chrono::seconds(3), [this] { return entered; });
    }
    void resume() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            released = true;
        }
        changed.notify_one();
    }
};

SherpaOnnxOnlineRecognizerResult previewValue{};
const SherpaOnnxOnlineRecognizerResult* previewResult(const SherpaOnnxOnlineRecognizer*,
                                                       const SherpaOnnxOnlineStream*) {
    return &previewValue;
}

bool checkSlowPreviewOutput() {
    Asr decoder;
    decoder.pSherpaOnnxGetOnlineStreamResult = previewResult;
    decoder.pSherpaOnnxDestroyOnlineRecognizerResult = destroy;
    PausedOutput target;
    std::ostream output(&target);
    bool waiting = false;
    {
        PreviewReporter reporter(output);
        previewValue.text = "first";
        reporter.afterAudio(decoder, 4000, 16000);
        waiting = target.waitForWrite();
        previewValue.text = "obsolete";
        reporter.afterAudio(decoder, 4000, 16000);
        previewValue.text = "latest";
        reporter.afterAudio(decoder, 4000, 16000);
        target.resume();
    }
    return waiting && target.str() == "PREVIEW first\nPREVIEW latest\n";
}
}

int main() {
    for (int rate : {8000, 16000}) {
        observed = {};
        observed.rate = rate;
        Asr decoder;
        decoder.pSherpaOnnxOnlineStreamAcceptWaveform = accept;
        decoder.pSherpaOnnxIsOnlineStreamReady = ready;
        decoder.pSherpaOnnxOnlineStreamInputFinished = finish;
        decoder.pSherpaOnnxGetOnlineStreamResult = result;
        decoder.pSherpaOnnxDestroyOnlineRecognizerResult = destroy;
        float sample = 0.125f;
        decoder.accept(&sample, 1, rate);
        if (decoder.result(rate) != "synthetic" || observed.calls != 2 || !observed.correct)
            return 1;
        std::cout << "PASS " << rate << " Hz input and half-second tail keep one sample rate\n";
    }
    if (!checkSlowPreviewOutput()) return 1;
    std::cout << "PASS blocked preview consumer does not block producer and only latest pending update is retained\n";
    return 0;
}
