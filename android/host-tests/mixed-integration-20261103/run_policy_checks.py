from pathlib import Path
import subprocess,json,hashlib,sys
r=Path(__file__).resolve().parents[1];src=(Path(sys.argv[1]).resolve() if len(sys.argv)>1 else r/'source')/'android/app/src/main/java/org/wangshu/ime/WangshuIme.java';s=src.read_text();out=Path(sys.argv[2]).resolve();out.mkdir(exist_ok=False)
def take(signature,offset=0):
 start=s.index(signature,offset);opening=s.index('{',start);end=s.index('}',opening)+1;return s[start:end]
start=s.index('private void createPhraseAssociation()')
a=take('private boolean phraseEditorAllowed()');b=take('public boolean allowed()',start).replace('public boolean allowed()','boolean allowed()');c=take('public boolean localEnabled()',start).replace('public boolean localEnabled()','boolean localEnabled()')
java='''public class PredictionPolicyChecks {
 static final class InputType {static final int TYPE_MASK_CLASS=15,TYPE_CLASS_TEXT=1,TYPE_MASK_VARIATION=4080,TYPE_TEXT_VARIATION_URI=16,TYPE_TEXT_VARIATION_EMAIL_ADDRESS=32,TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS=208;}
 static final class EditorInfo {int inputType=1;}EditorInfo info=new EditorInfo();EditorInfo getCurrentInputEditorInfo(){return info;}
 static final class LearningStore {static boolean paused;static boolean paused(){return paused;}}static final class DataFiles {static boolean ready=true;}
 static final class BackspaceGesture {enum Phase {IDLE,BUSY}Phase phase(){return Phase.IDLE;}}static final class VoiceHoldController {enum Phase {IDLE,BUSY}static class State {Phase phase=Phase.IDLE;}State state(){return new State();}}
 boolean privateField,math,editing,numbers,phrasesOpen,saved,builtin,voice,shown=true;StringBuilder buffer=new StringBuilder();BackspaceGesture backspaceGesture;VoiceHoldController voiceHold;
 boolean privateInput(){return privateField;}boolean phraseAssociationsEnabled(){return saved;}boolean builtinPredictionsEnabled(){return builtin;}boolean emojiPanelActive(){return false;}boolean hasVoiceWork(){return voice;}boolean isInputViewShown(){return shown;}
'''+a+'\n'+b+'\n'+c+'''
 static int n;void expect(boolean value,String label){n++;if(allowed()!=value)throw new AssertionError(label);System.out.println("PASS "+label);}
 public static void main(String[]args){PredictionPolicyChecks f=new PredictionPolicyChecks();f.expect(false,"all prediction settings off");f.builtin=true;f.expect(true,"builtin separate opt in");f.numbers=true;f.expect(true,"ordinary text numeric layout allowed");f.numbers=false;LearningStore.paused=true;f.expect(false,"temporary no learning suppresses builtin before context read");if(f.localEnabled())throw new AssertionError("local enabled during pause");LearningStore.paused=false;DataFiles.ready=false;f.expect(false,"loading native provider suppresses context read");DataFiles.ready=true;f.saved=true;f.builtin=false;f.expect(true,"saved phrases keep independent existing switch");f.saved=false;f.builtin=true;for(int type:new int[]{2,3,18,17,33,209}){f.info.inputType=type;f.expect(false,"restricted input type "+type);}f.info.inputType=1;f.privateField=true;f.expect(false,"private and no personalized field suppressed");f.privateField=false;f.buffer.append("ni");f.expect(false,"composing suppresses predictions");f.buffer.setLength(0);f.shown=false;f.expect(false,"hidden keyboard no context read");f.shown=true;f.voice=true;f.expect(false,"voice work suppresses predictions");f.voice=false;f.editing=true;f.expect(false,"editing gesture suppresses predictions");f.editing=false;f.math=true;f.expect(false,"math mode independent");f.math=false;f.info=null;f.expect(false,"missing editor info suppressed");System.out.println("TOTAL "+n+" PASS; extracted production eligibility with synthetic Android constants");}
}
'''
p=out/'PredictionPolicyChecks.java';p.write_text(java);jdk=r.parent/'_local/bin'
for name,cmd in [('compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',out,p]),('run',['java','-cp',out,'PredictionPolicyChecks'])]:
 v=subprocess.run([str(x) for x in cmd],capture_output=True,text=True);(out/(name+'.txt')).write_text(v.stdout+v.stderr);print(v.stdout+v.stderr,end='');assert not v.returncode
(out/'source-receipt.json').write_text(json.dumps({'source_sha256':hashlib.sha256(s.encode()).hexdigest(),'fragments':{str(n):hashlib.sha256(x.encode()).hexdigest() for n,x in enumerate([a,b,c])},'scope':'Actual production eligibility methods. Not Android API resolution, IPC, or UI.'},indent=2))
