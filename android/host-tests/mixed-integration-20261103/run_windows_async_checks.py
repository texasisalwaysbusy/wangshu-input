from pathlib import Path
import subprocess,sys,json,hashlib
r=Path(__file__).resolve().parents[1];source=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else r/'source';out=Path(sys.argv[2]).resolve();out.mkdir(exist_ok=False,parents=True)
helper=(Path(__file__).resolve().parent/'run_render_checks.py').read_text();ns={};exec(helper[helper.index('def take('):helper.index('fragments=')],ns);take=ns['take'];s=(source/'windows/text_service.cpp').read_text()
parts={n:take(s,sig) for n,sig in {'session':'HRESULT EditSession::DoEditSession(', 'candidate':'    bool sameCandidateComposition(', 'complete':'    void completeAsync(', 'accepts':'    bool acceptsAsync(', 'commit':'    bool commit('}.items()}
code=r'''
#include <string>
#include <memory>
#include <iostream>
#include <stdexcept>
#include "mixed_composition.h"
using HRESULT=int;using TfEditCookie=int;constexpr HRESULT S_OK=0,E_ABORT=-1,E_FAIL=-2,E_ACCESSDENIED=-3,E_OUTOFMEMORY=-4,TF_S_ASYNC=1;
#define SUCCEEDED(x) ((x)>=0)
#define FAILED(x) ((x)<0)
std::wstring wide(const std::string& s){return std::wstring(s.begin(),s.end());}
struct ITfRange{void Release(){}};struct ITfContext{};struct Composition{ITfRange range;HRESULT GetRange(ITfRange** p){*p=&range;return S_OK;}};
struct CandidateProof{std::string input;bool ranking=false,autoLearn=false;};
struct VoiceGuard{VoiceGuard(ITfContext*){}HRESULT DoEditSession(int){return S_OK;}};
struct TextService {
 unsigned long long revision=11,pendingRevision=11,voiceGeneration=1;bool learningWriteFailed=false;int requests=0;bool manager=true,loaded=true,privateInput=false,allowed=true,focus=true;std::string buffer="nihao`OpenAI`",voiceReview,voiceRecovery;std::wstring document=L"nihaoOpenAI",status;size_t page=2;int writes=0,updates=0;HRESULT writeResult=S_OK;Composition owned;Composition* composition=&owned;ITfContext context;ITfContext* composingContext=&context;
 HRESULT request(ITfContext*,const std::wstring& value,bool,bool,bool,const CandidateProof*){requests++;writes++;document=value;return S_OK;}
 bool sameCompositionRange(ITfContext*,int,ITfRange*,const std::wstring& expected){return document==expected;}
 bool validCandidate(ITfContext* c,const CandidateProof& p){return focus&&c==&context&&buffer==p.input;}
 bool personalizationAllowed(ITfContext*,int){return allowed;}void updateInputPrivacy(bool b){privateInput=b;}void stopVoice(){voiceReview.clear();}void retainVoice(){}HRESULT checkVoicePosition(ITfContext*,int,unsigned long long){return S_OK;}void recordConfirmedSelection(ITfContext*,const CandidateProof&,unsigned long long){}void finishLearningStatus(){}void update(){updates++;}
 HRESULT edit(ITfContext*,int,const std::wstring& text,bool,bool*,unsigned long long*){if(!allowed)return E_ACCESSDENIED;if(FAILED(writeResult))return writeResult;writes++;document=text;return S_OK;}
'''+ '\n'.join(parts[n] for n in ['candidate','complete','accepts','commit'])+r'''
};
struct EditSession {
 TextService* service;ITfContext* context;bool executed=false,notify=true,speech=false,guarded=true,finish=true;unsigned long long expectedRevision=11,voiceGeneration=1;CandidateProof proof;std::wstring text=L"chosen OpenAI";
 explicit EditSession(TextService& s):service(&s),context(&s.context),proof{s.buffer}{}
 HRESULT DoEditSession(TfEditCookie cookie);
};
'''+parts['session']+r'''
int failures=0,checks=0;void check(bool b,const char* s){checks++;failures+=!b;std::cout<<(b?"PASS ":"FAIL ")<<s<<"\n";}
int main(){
 {TextService h;EditSession e(h);auto result=e.DoEditSession(1);check(result==S_OK&&h.document==L"chosen OpenAI"&&h.writes==1&&h.buffer.empty()&&h.pendingRevision==0,"deferred mixed candidate validates rendered range and commits once");check(e.DoEditSession(1)==E_ABORT&&h.writes==1,"replayed edit session cannot insert twice");}
 {TextService h;EditSession e(h);h.document+=L"changed";auto previous=h.document;check(e.DoEditSession(1)==E_ABORT&&h.document==previous&&h.writes==0&&h.buffer==e.proof.input,"host text change before async execution preserved");}
 {TextService h;EditSession e(h);h.focus=false;check(e.DoEditSession(1)==E_ABORT&&h.writes==0&&h.buffer==e.proof.input,"focus change before async execution prevents write");}
 {TextService h;EditSession e(h);h.revision++;h.pendingRevision=0;h.buffer="new`text";check(e.DoEditSession(1)==E_ABORT&&h.writes==0&&h.buffer=="new`text","newer pending input survives old async completion");}
 {TextService h;EditSession e(h);h.pendingRevision=0;check(e.DoEditSession(1)==E_ABORT&&h.writes==0,"cancelled pending token refuses old callback");}
 {TextService h;EditSession e(h);h.allowed=false;check(e.DoEditSession(1)==E_ACCESSDENIED&&h.writes==0&&h.buffer==e.proof.input,"private scope rejection leaves pending input and never writes");}
 {TextService h;EditSession e(h);h.writeResult=E_FAIL;check(e.DoEditSession(1)==E_FAIL&&h.writes==0&&h.buffer==e.proof.input&&h.pendingRevision==0,"refused async edit retains pending input without retry");}
 {TextService h;h.composition=nullptr;h.buffer="`";h.document=L"SELECTED";h.pendingRevision=0;check(h.commit(&h.context,"",false)&&h.buffer.empty()&&h.document==L"SELECTED"&&h.requests==0,"marker-only raw finalize/Enter clears grammar without deleting selected document text");}
 {TextService h;h.composition=nullptr;h.buffer="``";h.document=L"SELECTED";h.pendingRevision=0;check(h.commit(&h.context,"",true)&&h.buffer.empty()&&h.document==L"SELECTED"&&h.requests==0&&h.pendingRevision==0,"marker-only asynchronous cancel never queues an empty destructive edit");}
 {TextService h;check(h.commit(&h.context,"")&&h.document.empty()&&h.requests==1&&h.buffer.empty(),"Escape with a real mixed composition still cancels its rendered text");}
 {TextService h;h.revision=12;h.pendingRevision=12;h.buffer="new`text";h.completeAsync(11,S_OK);check(h.buffer=="new`text"&&h.pendingRevision==12,"late completion cannot clear newer transaction");}
 std::cout<<"TOTAL "<<checks<<" checks; failures="<<failures<<"; actual DoEditSession/completion/proof bodies, synthetic TSF only\n";return failures?1:0;
}
'''
p=out/'windows_async_checks.cpp';p.write_text(code)
for label,cmd in [('compile',['g++','-std=c++17','-Wall','-Wextra','-Wno-misleading-indentation','-Werror','-I',str(source/'core'),str(p),'-o',str(out/'checks')]),('run',[str(out/'checks')])]:
 x=subprocess.run(cmd,capture_output=True,text=True);(out/(label+'.txt')).write_text(x.stdout+x.stderr);print(x.stdout+x.stderr,end='')
 if label=='compile' and x.returncode:raise SystemExit(x.returncode)
(out/'receipt.json').write_text(json.dumps({'source_sha256':hashlib.sha256(s.encode()).hexdigest(),'fragments':{n:hashlib.sha256(v.encode()).hexdigest() for n,v in parts.items()},'test_exit':x.returncode,'scope':'Production DoEditSession/completion/candidate proof extracted. Windows ABI/PE/runtime NOT_RUN.'},indent=2))
raise SystemExit(x.returncode)
