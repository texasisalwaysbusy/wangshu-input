// Test-only DLL fixture: exports are NOT part of the production input-method DLL.
#include "learning_queue.h"
#include <memory>
static std::unique_ptr<wangshu::WindowsPersonalStore> fixtureStore;
static std::unique_ptr<wangshu::ConfirmedLearningQueue> fixtureQueue;
extern "C" __declspec(dllexport) BOOL WINAPI PrepareFixture(LPCWSTR path){try{fixtureStore=std::make_unique<wangshu::WindowsPersonalStore>(path);auto state=fixtureStore->readLearningFresh();fixtureStore->modifyLearning(state.revision,[](wangshu::LearningData& data){data.enabled=true;});fixtureQueue=std::make_unique<wangshu::ConfirmedLearningQueue>();return TRUE;}catch(...){return FALSE;}}
extern "C" __declspec(dllexport) BOOL WINAPI SubmitFixture(){try{return fixtureQueue&&fixtureQueue->enqueue({fixtureStore->path(),"module","module-term",fixtureStore->readLearningFresh().revision});}catch(...){return FALSE;}}
extern "C" __declspec(dllexport) DWORD WINAPI OutstandingFixture(){if(!fixtureQueue)return 0;auto state=fixtureQueue->stats();return state.pending+state.callbacks;}
