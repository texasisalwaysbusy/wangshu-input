from pathlib import Path
import subprocess,sys,json,hashlib
r=Path(__file__).resolve().parents[1];source=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else r/'source';out=Path(sys.argv[2]).resolve();out.mkdir(exist_ok=False,parents=True)
# Reuse just the extractor definition, without importing a runner with side effects.
helper=(Path(__file__).resolve().parent/'run_render_checks.py').read_text();ns={};exec(helper[helper.index('def take('):helper.index('fragments=')],ns);take=ns['take']
s=(source/'windows/text_service.cpp').read_text();parts={n:take(s,sig) for n,sig in {'eats':'    bool eats(', 'key':'    HRESULT keyDownImpl(', 'candidate':'    bool sameCandidateComposition('}.items()}
code=r'''
#include <string>
#include <vector>
#include <memory>
#include <cstring>
#include <iostream>
#include "mixed_composition.h"
using HRESULT=int;using BOOL=int;using WPARAM=unsigned;using LPARAM=long;using TfEditCookie=int;
constexpr HRESULT S_OK=0,E_POINTER=-1,E_FAIL=-2;constexpr BOOL TRUE=1,FALSE=0;
#define FAILED(x) ((x)<0)
#define SUCCEEDED(x) ((x)>=0)
constexpr unsigned VK_RETURN=13,VK_SPACE=32,VK_BACK=8,VK_ESCAPE=27,VK_NEXT=34,VK_PRIOR=33,VK_MENU=18;
int GetKeyState(unsigned){return 0;}
std::wstring wide(const std::string& s){return std::wstring(s.begin(),s.end());}
struct ITfRange{void Release(){}};
struct ITfContext{};
struct Composition{ITfRange range;HRESULT GetRange(ITfRange** p){*p=&range;return S_OK;}};
struct CandidateProof{std::string input;};
struct Candidate{std::string text;};
struct Dictionary{std::vector<Candidate> lookup(const std::string& s){return s=="\\alpha"?std::vector<Candidate>{{"alpha"}}:std::vector<Candidate>{};}std::string punctuation(char c,bool){return std::string(1,c);}};
struct UI{size_t selection(){return 0;}};
struct Host {
 bool loaded=true,chinese=true,math=false,listening=false,reject=false;std::string buffer,calculation,voiceReview,voiceRecovery;std::wstring status,document;size_t page=0;std::vector<Candidate> candidates;UI* uiCandidates=nullptr;int selects=0,requests=0;Composition owned;Composition* composition=&owned;ITfContext context;ITfContext* composingContext=&context;
 static bool ctrl(){return false;}static bool shift(){return false;}static char character(WPARAM k){return k>=1000?char(k-1000):k==VK_SPACE?' ':0;}bool disabled(ITfContext*){return false;}Dictionary& dictionary(){static Dictionary d;return d;}void clearCalculation(){calculation.clear();}void invalidatePending(){}void update(){}void startVoice(ITfContext*){}void stopVoice(bool=false){}std::vector<Candidate> personalLookup(const std::string&,size_t){return {};}
 HRESULT request(ITfContext*,const std::wstring& text,bool){requests++;if(reject)return E_FAIL;document=text;return S_OK;}bool commit(ITfContext*,const std::string& value){document=wide(value);buffer.clear();return true;}bool selectCandidate(ITfContext*,size_t,bool=false,const std::string& suffix=""){selects++;document=L"chosen"+wide(suffix);buffer.clear();return true;}
 bool sameCompositionRange(ITfContext*,TfEditCookie,ITfRange*,const std::wstring& expected){return expected==document;}
'''+ '\n'.join(parts.values())+r'''
 void type(char c){BOOL eaten=FALSE;keyDownImpl(&context,1000+static_cast<unsigned char>(c),0,&eaten);}
};
int failures=0,checks=0;void check(bool value,const char* name){checks++;if(!value)failures++;std::cout<<(value?"PASS ":"FAIL ")<<name<<"\n";}
int main(){
 Host h;for(char c:std::string("nihao`OpenAI`zhongwen"))h.type(c);check(h.buffer=="nihao`OpenAI`zhongwen","mixed grammar stays in internal buffer");check(h.document==L"nihaoOpenAIzhongwen","editor composition contains no preedit-only metadata");check(h.sameCandidateComposition(&h.context,1,CandidateProof{h.buffer}),"candidate proof accepts corresponding rendered composition");h.document=L"nihaoOpenAIzhongwenX";check(!h.sameCandidateComposition(&h.context,1,CandidateProof{h.buffer}),"candidate proof rejects host document edit");
 Host literal;for(char c:std::string("nihao`OpenAI, 123\\"))literal.type(c);check(literal.buffer=="nihao`OpenAI, 123\\"&&literal.selects==0,"literal punctuation spaces digits and slash stay in one composition");BOOL eaten=FALSE;literal.keyDownImpl(&literal.context,VK_RETURN,0,&eaten);check(literal.document==L"nihaoOpenAI, 123\\"&&literal.buffer.empty(),"Enter commits marker-free literal raw text");
 Host command;command.buffer="\\alpha";check(!command.eats(&command.context,1000+'`'),"symbol command does not claim mixed delimiter key");command.type('`');check(command.buffer=="\\alpha","symbol command cannot acquire mixed grammar metadata");
 Host empty;empty.type('`');check(empty.buffer=="`"&&empty.requests==0,"opening empty literal is metadata-only without an empty editor edit");empty.type('A');check(empty.document==L"A"&&empty.requests==1,"first literal character starts editor composition");empty.type('`');check(empty.requests==1,"closing literal is metadata-only");
 Host back;back.document=L"SELECTED";back.type('`');BOOL backEat=FALSE;back.keyDownImpl(&back.context,VK_BACK,0,&backEat);check(back.buffer.empty()&&back.document==L"SELECTED"&&back.requests==0,"marker-only Backspace preserves selected host text");
 Host last;last.type('`');last.type('A');int priorRequests=last.requests;last.keyDownImpl(&last.context,VK_BACK,0,&backEat);check(last.buffer=="`"&&last.document.empty()&&last.requests==priorRequests+1,"last actual literal character deletion still updates host composition");
 Host denied;denied.buffer="nihao`";denied.document=L"nihao";denied.reject=true;denied.type('A');check(denied.buffer=="nihao`"&&denied.document==L"nihao"&&denied.requests==1,"refused edit rolls back appended literal without retry");
 std::cout<<"TOTAL "<<checks<<" checks; failures="<<failures<<"; extracted production key/eats/proof methods, synthetic TSF boundaries only\n";return failures?1:0;
}
'''
p=out/'windows_mixed_checks.cpp';p.write_text(code)
for label,cmd in [('compile',['g++','-std=c++17','-Wall','-Wextra','-Werror','-I',str(source/'core'),str(p),'-o',str(out/'checks')]),('run',[str(out/'checks')])]:
 x=subprocess.run(cmd,capture_output=True,text=True);(out/(label+'.txt')).write_text(x.stdout+x.stderr);print(x.stdout+x.stderr,end='')
 if label=='compile' and x.returncode:raise SystemExit(x.returncode)
(out/'receipt.json').write_text(json.dumps({'source_sha256':hashlib.sha256(s.encode()).hexdigest(),'fragments':{n:hashlib.sha256(v.encode()).hexdigest() for n,v in parts.items()},'test_exit':x.returncode,'scope':'Actual production key/eats/proof methods extracted. Windows ABI/PE/runtime NOT_RUN.'},indent=2))
raise SystemExit(x.returncode)
