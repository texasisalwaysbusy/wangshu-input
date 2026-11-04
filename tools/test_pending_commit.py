"""Exercise exact production commit methods with a synthetic editor, never Android UI.

The wrapper supplies only Android/service boundaries; method bodies are copied
unchanged from the selected source. This is a host contract check, not a device test.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess

parser=argparse.ArgumentParser()
parser.add_argument('--source',type=Path)
parser.add_argument('--output',required=True,type=Path)
args=parser.parse_args()
project=Path(__file__).resolve().parents[1]
repo=project.parents[1]
source=args.source or project/'android/app/src/main/java/org/wangshu/ime/WangshuIme.java'
text=source.read_text()
out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)

def method(name):
    # These methods have no literal brace characters in strings/comments.
    definition=re.search(r'^    private \S+ '+re.escape(name)+r'\(',text,re.MULTILINE)
    if definition is None:raise ValueError('Missing method '+name)
    start=definition.start()
    opening=text.index('{',start);depth=1;end=opening+1
    while depth:
        if text[end]=='{':depth+=1
        elif text[end]=='}':depth-=1
        end+=1
    return text[start:end]

methods=method('finishRaw')
if 'private boolean commitPending(' in text:methods+='\n'+method('commitPending')
for name in ['choose','chooseBound','punctuation','key']:
    methods+='\n'+method(name)
wrapper='''package org.wangshu.ime;
public final class PendingCommitIntegration {
    interface InputConnection { boolean commitText(String text,int position); void finishComposingText(); }
    static final class Undo {void invalidate(){}}
    final Undo beforeCaret=new Undo();final StringBuilder buffer=new StringBuilder("ni");
    int inputSession,page,commits,finishes;boolean accept,pendingCommitInFlight;Runnable during=()->{};
    boolean english,password,numbers,math,shownFuzzy;boolean shownDictionaryReady=true;long shownGeneration,shownRevision,shownLearningRevision;
    int shownSession,shownPage,shownModes;String shownInput="ni";String[] current={"你","native"};
    java.util.Set<String> shownRules=java.util.Collections.emptySet();
    static class DataFiles {static boolean ready=true;}
    static class NativeBridge {static String punctuation(char c,boolean chinese){return String.valueOf(c);}}
    static class FuzzySettingsActivity {static java.util.Set<String> enabledPairs(Object ignored){return java.util.Collections.emptySet();}}
    static class SplitSelection {static class Snapshot {}}
    static class LearningStore {static boolean mayLearn(Object context,boolean restricted){return false;}static LearningStore get(Object context)throws java.io.IOException{return new LearningStore();}void enqueueAutomatic(String raw,String value,long time,Object completion)throws java.io.IOException{}}
    boolean learningWriteWarned;
    int candidateModes(){return 0;}long personalRevision(){return 0;}long learningRevision(){return 0;}
    boolean privateInput(){return false;}SplitSelection.Snapshot snapshot(){return null;}Object learningCompletion(int session,boolean manual){return null;}
    boolean verifiedCandidateInsertion(SplitSelection.Snapshot before,SplitSelection.Snapshot after,String raw,String word){return false;}
    boolean candidatesReady(){return true;}void updateComposition(){}
    InputConnection editorConnection=new InputConnection(){public boolean commitText(String value,int ignored){commits++;during.run();return accept;}public void finishComposingText(){finishes++;}};
    InputConnection getCurrentInputConnection(){return editorConnection;}
    boolean hasVoiceWork(){return false;}void cancelVoice(){}void refresh(){}void toast(String value){}
    boolean commit(String value){return editorConnection!=null&&editorConnection.commitText(value,1);}
    METHOD_BODIES
    static int checks;
    static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    Object invoke()throws Exception{return getClass().getDeclaredMethod("finishRaw").invoke(this);}
    public static void main(String[] ignored)throws Exception{
        PendingCommitIntegration denied=new PendingCommitIntegration();Object refusal=denied.invoke();
        check(denied.buffer.toString().equals("ni")&&denied.commits==1&&denied.finishes==0,"actual finishRaw explicit rejection preserves pending, without finishing or retrying");
        check(Boolean.FALSE.equals(refusal),"actual finishRaw reports failure to dependent actions");
        PendingCommitIntegration yes=new PendingCommitIntegration();yes.accept=true;
        check(Boolean.TRUE.equals(yes.invoke())&&yes.buffer.length()==0&&yes.commits==1,"actual finishRaw accepted commit clears only once");
        PendingCommitIntegration switched=new PendingCommitIntegration();switched.accept=true;switched.during=()->{switched.inputSession++;switched.editorConnection=null;switched.buffer.setLength(0);switched.buffer.append("new");};
        check(Boolean.FALSE.equals(switched.invoke())&&switched.buffer.toString().equals("new"),"actual finishRaw preserves replacement editor pending state");
        PendingCommitIntegration missing=new PendingCommitIntegration();missing.editorConnection=null;
        check(Boolean.FALSE.equals(missing.invoke())&&missing.buffer.toString().equals("ni")&&missing.commits==0,"actual finishRaw no editor keeps pending composition");
        PendingCommitIntegration candidate=new PendingCommitIntegration();candidate.choose(0);
        check(candidate.buffer.toString().equals("ni")&&candidate.commits==1,"actual chooseBound rejected candidate preserves composition");
        PendingCommitIntegration punctuation=new PendingCommitIntegration();punctuation.punctuation(',');
        check(punctuation.buffer.toString().equals("ni")&&punctuation.commits==1,"actual punctuation stops after rejected candidate without second commit");
        PendingCommitIntegration key=new PendingCommitIntegration();key.key("-");
        check(key.buffer.toString().equals("ni")&&key.commits==1,"actual nonletter key stops after rejected candidate");
        PendingCommitIntegration command=new PendingCommitIntegration();command.key("\\\\");
        check(command.buffer.toString().equals("ni")&&command.commits==1,"actual command entry does not append after rejected raw flush");
        PendingCommitIntegration direct=new PendingCommitIntegration();direct.english=true;direct.key("x");
        check(direct.buffer.toString().equals("ni")&&direct.commits==1,"actual direct mode does not insert after rejected raw flush");
        PendingCommitIntegration success=new PendingCommitIntegration();success.accept=true;success.punctuation(',');
        check(success.buffer.length()==0&&success.commits==2,"actual accepted candidate then punctuation makes exactly two commits");
        PendingCommitIntegration stale=new PendingCommitIntegration();stale.shownGeneration=1;stale.chooseBound(0,0);
        check(stale.buffer.toString().equals("ni")&&stale.commits==0,"actual stale visible candidate rejects before touching editor");
        System.out.println("PASS "+checks+" exact-production-method assertions with fake editor; Android runtime NOT_RUN");
    }
}
'''.replace('METHOD_BODIES',methods)
java=out/'PendingCommitIntegration.java';java.write_text(wrapper)
java_home=repo/'.tools/jdk21/bin'
component=project/'android/app/src/main/java/org/wangshu/ime/PendingTextCommit.java'
compile_run=subprocess.run([str(java_home/'javac'),'-d',str(out),str(component),str(java)],capture_output=True,text=True)
(out/'javac.txt').write_text(compile_run.stdout+compile_run.stderr)
run=subprocess.run([str(java_home/'java'),'-cp',str(out),'org.wangshu.ime.PendingCommitIntegration'],capture_output=True,text=True) if compile_run.returncode==0 else None
if run:(out/'results.txt').write_text(run.stdout+run.stderr)
receipt={'source':str(source),'sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'exact_methods':['finishRaw','choose','chooseBound','punctuation','key']+(['commitPending'] if 'private boolean commitPending(' in text else []),'compile_exit':compile_run.returncode,'test_exit':run.returncode if run else None,'scope':'extracted production bodies with fake editor/JNI/store boundaries; no Android runtime assertion'}
(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt));print(run.stdout+run.stderr if run else compile_run.stderr)
raise SystemExit(run.returncode if run else compile_run.returncode)
