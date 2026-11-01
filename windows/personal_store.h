#pragma once
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <string>
#include <functional>
#include <mutex>
#include "../staging/personal-native/personal_data.h"

namespace wangshu {
// Local explicit words/phrases plus opt-in bounded confirmed-selection weights.
// No raw keystroke, surrounding-text, clipboard or audio history.
struct PersonalSnapshot {
    PersonalData data;
    std::string revision; // Exact canonical bytes, not a collision-prone revision hash.
    bool fromBackup=false;
};
struct PersonalImportPreview {PersonalData data;bool repair=false;};
struct LearnedCandidate {std::string key,text;unsigned count=0;bool pinned=false;unsigned long long updated=0;};
struct LearningData {
    static constexpr size_t MaxEntries=256,MaxBytes=128*1024;
    static constexpr unsigned MaxCount=255;
    static constexpr unsigned long long DecayMillis=30ULL*24*60*60*1000;
    bool enabled=false;
    std::vector<LearnedCandidate> entries;
    static LearningData parse(const std::string& bytes);
    std::string serialize()const;
    static std::string candidateKey(const std::string& key);
    static std::string candidateText(const std::string& text);
    static unsigned effective(const LearnedCandidate& value,unsigned long long now);
    bool record(const std::string& key,const std::string& text,unsigned long long now);
    bool pin(const std::string& key,const std::string& text,bool value,unsigned long long now);
    bool forget(const std::string& key,const std::string& text);
    bool pinned(const std::string& key,const std::string& text)const;
    std::vector<Candidate> lookup(const Engine& base,const PersonalData& manual,const std::string& key,size_t page,unsigned long long now)const;
};
struct LearningDiscarded:std::runtime_error {using std::runtime_error::runtime_error;};
struct LearningWriteGate {
    std::function<bool()> current; // Preparing may be cancelled without waiting for disk.
    std::function<bool()> claim;   // Linearization point: already-claimed commit may finish.
};
struct LearningSnapshot {LearningData data;std::string revision;bool fromBackup=false;};

class WindowsPersonalStore {
    std::wstring file_;
    std::mutex cacheGate_;
    std::string stamp_;
    PersonalSnapshot cache_;
    bool haveCache_=false;
    PersonalSnapshot readUncached()const;
    LearningSnapshot readLearningUncached()const;
    void writeLearningAtomic(const LearningSnapshot& old,const LearningData& next,const LearningWriteGate* gate=nullptr);
    void writeAtomic(const PersonalSnapshot& old,const PersonalData& next);
public:
    explicit WindowsPersonalStore(std::wstring path=defaultPath());
    static std::wstring defaultPath();
    const std::wstring& path()const{return file_;}
    PersonalSnapshot read();
    // Acceptance must not trust timestamp/size cache metadata as a content revision.
    PersonalSnapshot readFresh();
    std::wstring learningPath()const{return file_+L".learning-v1.json";}
    LearningSnapshot readLearningFresh()const;
    LearningSnapshot modifyLearning(const std::string& expected,const std::function<void(LearningData&)>& action,const LearningWriteGate* gate=nullptr);
    LearningSnapshot restoreLearningBackup(const std::string& expected);
    static unsigned long long learningNow();
    // A displayed edit may never overwrite a concurrently changed file.
    PersonalSnapshot modify(const std::string& expected,const std::function<void(PersonalData&)>& action);
    PersonalSnapshot merge(const PersonalData& incoming);
    PersonalImportPreview previewImport(const PersonalData& incoming);
    PersonalSnapshot restoreBackup(const std::string& expected);
    // Explicitly confirmed repair only; all unreadable originals remain archived.
    PersonalSnapshot repairFromImport(const PersonalData& incoming);
    static PersonalData normalizeWords(PersonalData data);
    static PersonalData parseImport(const std::string& bytes);
    static std::string readExternal(const std::wstring& path);
    static void exportNew(const std::wstring& path,const PersonalData& data);
};
}
