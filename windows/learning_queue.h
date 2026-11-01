#pragma once
#include "personal_store.h"
#include <atomic>
#include <deque>
#include <memory>
#include <limits>

namespace wangshu {
// Contains a confirmed selected term, never an editor/context/COM reference or
// raw surrounding text. Revisions are bounded canonical ranking-file bytes.
enum class LearningJobAction { Record, Pin, Unpin, Forget };
struct ConfirmedLearningJob {std::wstring path;std::string key,word,expected;LearningJobAction action=LearningJobAction::Record;};
struct LearningQueueStats {unsigned pending=0,callbacks=0;unsigned long long saved=0,cancelled=0,conflicts=0,overflow=0,failed=0;};
// Shared by real queue and deterministic commit/cancel interleaving tests.
class LearningCommitFence {
    std::atomic<unsigned long long> control_{0};std::atomic<bool> closed_{false};
public:
    bool closed()const{return closed_.load(std::memory_order_acquire);}
    unsigned long long token()const{return control_.load(std::memory_order_acquire)&~1ULL;}
    bool current(unsigned long long token)const{return !closed()&&control_.load(std::memory_order_acquire)==token;}
    bool claim(unsigned long long token){if(closed())return false;auto expected=token;return control_.compare_exchange_strong(expected,token|1ULL,std::memory_order_acq_rel);}
    bool cancel(){auto old=control_.load();for(;;){if(old>std::numeric_limits<unsigned long long>::max()-2){closed_.store(true);break;}if(control_.compare_exchange_weak(old,old+2,std::memory_order_acq_rel))break;}return (old&1ULL)!=0;}
    bool close(){closed_.store(true,std::memory_order_release);return cancel();}
    void releaseClaim(){control_.fetch_and(~1ULL,std::memory_order_release);}
};
class ConfirmedLearningQueue {
    struct Item {ConfirmedLearningJob job;unsigned long long token=0;};
    struct State {
        std::mutex mutex;std::deque<Item> queue;bool running=false;
        // Even values = privacy/session generation; low bit = commit claimed.
        // cancel increments generation preserving the low bit. claim uses CAS.
        LearningCommitFence fence;
        std::atomic<unsigned> pending{0},callbacks{0};
        std::atomic<unsigned long long> saved{0},cancelled{0},conflicts{0},overflow{0},failed{0};
        void (*retain)()=nullptr;void (*release)()=nullptr;
    };
    struct Payload {std::shared_ptr<State> state;HMODULE module=nullptr;};
    std::shared_ptr<State> state_;
    static bool current(const std::shared_ptr<State>& state,unsigned long long token){return state->fence.current(token);}
    static bool claim(const std::shared_ptr<State>& state,unsigned long long token){
        return state->fence.claim(token);
    }
    static void run(const std::shared_ptr<State>& state){
        for(;;){Item item;
            {std::lock_guard<std::mutex> lock(state->mutex);if(state->queue.empty()){state->running=false;break;}item=std::move(state->queue.front());state->queue.pop_front();}
            bool claimed=false;
            try{
                if(!current(state,item.token)){state->cancelled.fetch_add(1);state->pending.fetch_sub(1);continue;}
                WindowsPersonalStore store(item.job.path);
                LearningWriteGate gate;
                gate.current=[&]{return current(state,item.token);};
                gate.claim=[&]{claimed=claim(state,item.token);return claimed;};
                store.modifyLearning(item.job.expected,[&](LearningData& next){
                    if(!current(state,item.token))throw LearningDiscarded("Learning session changed");
                    switch(item.job.action){
                    case LearningJobAction::Record:
                        if(!next.enabled)throw LearningDiscarded("Learning disabled");
                        next.record(item.job.key,item.job.word,WindowsPersonalStore::learningNow());break;
                    case LearningJobAction::Pin:next.pin(item.job.key,item.job.word,true,WindowsPersonalStore::learningNow());break;
                    case LearningJobAction::Unpin:next.pin(item.job.key,item.job.word,false,WindowsPersonalStore::learningNow());break;
                    case LearningJobAction::Forget:next.forget(item.job.key,item.job.word);break;
                    default:throw LearningDiscarded("Unknown ranking action");
                    }
                },&gate);
                state->saved.fetch_add(1);
            }catch(const LearningDiscarded&){if(!current(state,item.token)&&!claimed)state->cancelled.fetch_add(1);else state->conflicts.fetch_add(1);}
            catch(...){if(!claimed&&!current(state,item.token))state->cancelled.fetch_add(1);else state->failed.fetch_add(1);}
            if(claimed)state->fence.releaseClaim();
            state->pending.fetch_sub(1,std::memory_order_release);
        }
    }
    static VOID CALLBACK callback(PTP_CALLBACK_INSTANCE instance,PVOID opaque){
        std::unique_ptr<Payload> payload(static_cast<Payload*>(opaque));auto state=payload->state;
        // The DLL's loader reference remains valid until this callback returns,
        // independently of the service's COM lifetime and DllCanUnloadNow count.
        if(payload->module)FreeLibraryWhenCallbackReturns(instance,payload->module);
        CallbackMayRunLong(instance);
        try{run(state);}catch(...){state->failed.fetch_add(1);std::lock_guard<std::mutex> lock(state->mutex);auto abandoned=static_cast<unsigned>(state->queue.size());state->queue.clear();state->pending.fetch_sub(abandoned);state->running=false;}
        if(state->release)state->release();state->callbacks.fetch_sub(1,std::memory_order_release);
    }
public:
    static constexpr unsigned Capacity=4;
    ConfirmedLearningQueue(void (*retain)()=nullptr,void (*release)()=nullptr):state_(std::make_shared<State>()){state_->retain=retain;state_->release=release;}
    ~ConfirmedLearningQueue(){close();}
    ConfirmedLearningQueue(const ConfirmedLearningQueue&)=delete;ConfirmedLearningQueue& operator=(const ConfirmedLearningQueue&)=delete;
    bool enqueue(ConfirmedLearningJob job){
        if(job.path.empty()||job.path.size()>32768||job.expected.size()>LearningData::MaxBytes||job.key.size()>128||job.word.size()>256)return false;
        LearningData::candidateKey(job.key);LearningData::candidateText(job.word);
        auto state=state_;bool schedule=false;
        {std::lock_guard<std::mutex> lock(state->mutex);if(state->fence.closed())return false;if(state->pending.load()>=Capacity){state->overflow.fetch_add(1);return false;}auto token=state->fence.token();state->queue.push_back({std::move(job),token});state->pending.fetch_add(1);if(!state->running){state->running=true;schedule=true;}}
        if(!schedule)return true;
        auto* payload=new(std::nothrow)Payload{state,nullptr};bool ready=payload!=nullptr;
        if(ready){HMODULE module=nullptr;ready=GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,reinterpret_cast<LPCWSTR>(&callback),&module)!=FALSE;
            if(ready){if(module==GetModuleHandleW(nullptr))FreeLibrary(module);else payload->module=module;}}
        if(ready){state->callbacks.fetch_add(1);if(state->retain)state->retain();if(TrySubmitThreadpoolCallback(&callback,payload,nullptr))return true;if(state->release)state->release();state->callbacks.fetch_sub(1);}
        if(payload){if(payload->module)FreeLibrary(payload->module);delete payload;}
        {std::lock_guard<std::mutex> lock(state->mutex);auto abandoned=static_cast<unsigned>(state->queue.size());state->queue.clear();state->pending.fetch_sub(abandoned);state->failed.fetch_add(abandoned);state->running=false;}
        return false;
    }
    // Nonblocking with respect to I/O. A true result means a save claimed its
    // commit BEFORE cancellation linearized and may still finish afterwards.
    bool cancel(){return cancelQueued(false);}
    void close(){cancelQueued(true);}
private:
    bool cancelQueued(bool closing){
        auto state=state_;bool claimed=closing?state->fence.close():state->fence.cancel();
        {std::lock_guard<std::mutex> lock(state->mutex);auto generation=state->fence.token();unsigned abandoned=0;for(auto it=state->queue.begin();it!=state->queue.end();){if(state->fence.closed()||it->token!=generation){it=state->queue.erase(it);++abandoned;}else ++it;}state->pending.fetch_sub(abandoned);state->cancelled.fetch_add(abandoned);}
        return claimed;
    }
public:
    LearningQueueStats stats()const{auto s=state_;return {s->pending.load(std::memory_order_acquire),s->callbacks.load(std::memory_order_acquire),s->saved.load(),s->cancelled.load(),s->conflicts.load(),s->overflow.load(),s->failed.load()};}
};
}
