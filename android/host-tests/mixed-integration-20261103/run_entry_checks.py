from pathlib import Path
import subprocess,hashlib,json,os,sys
r=Path(__file__).resolve().parents[1];src=(Path(sys.argv[1]).resolve() if len(sys.argv)>1 else r/'source')/'android/app/src/main/java/org/wangshu/ime';s=(src/'WangshuIme.java').read_text();out=Path(sys.argv[2]).resolve();out.mkdir(exist_ok=False)
def block(signature):
 start=s.index(signature);i=s.index('{',start);depth=0;state='code';j=i
 while j<len(s):
  c=s[j];pair=s[j:j+2]
  if state in ['str','char']:
   if c=='\\':j+=2;continue
   if c==('"' if state=='str' else "'"):state='code'
  elif state=='line':
   if c=='\n':state='code'
  elif state=='comment':
   if pair=='*/':state='code';j+=2;continue
  elif pair=='//':state='line';j+=2;continue
  elif pair=='/*':state='comment';j+=2;continue
  elif c=='"':state='str'
  elif c=="'":state='char'
  elif c=='{':depth+=1
  elif c=='}':
   depth-=1
   if not depth:return s[start:j+1]
  j+=1
 raise ValueError(signature)
parts={name:block(sig) for name,sig in [('key','private void key(String key)'),('space','private void space()'),('punctuation','private void punctuation(char c)'),('finishRaw','private boolean finishRaw()'),('TextWrite','private static final class TextWrite'),('textWriteFields','private boolean textWriteFields('),('textWriteCurrent','private boolean textWriteCurrent(')]}
code='''package org.wangshu.ime;
public class MixedImeEntryChecks {
 interface InputConnection {boolean commitText(String value,int caret);}
 InputConnection preeditConnection;int preeditSession;boolean localCommitInFlight;boolean inputEntryInFlight,pendingCommitInFlight,candidateChoiceInFlight,password,english,numbers,math,shownDictionaryReady=true;StringBuilder buffer=new StringBuilder();int page,inputSession,shownSession,shownModes,updates,chooses,directs,refreshes;long shownGeneration;String shownInput="";TextWrite inputEntry;String committed="";Runnable onConnection=()->{};InputConnection owner=(v,c)->{committed+=v;return true;};
 InputConnection getCurrentInputConnection(){onConnection.run();return owner;}int candidateModes(){return 0;}boolean prepareTextWrite(TextWrite s,boolean undo){return textWriteCurrent(s,s.raw);}boolean finishEntry(TextWrite s){return s.raw.isEmpty();}void commitEntry(TextWrite s,String value){directs++;owner.commitText(value,1);}boolean candidatesReady(){return true;}void updateEntryComposition(TextWrite s){if(!textWriteCurrent(s,s.raw))throw new AssertionError("stale update");updates++;}boolean chooseEntry(TextWrite s){chooses++;buffer.setLength(0);return true;}void textWriteWarning(PendingTextCommit.Result result){throw new AssertionError(result);}void toast(String value){}void refreshAfterCandidate(boolean b){refreshes++;}void refresh(){refreshes++;}boolean commit(String value){return owner.commitText(value,1);}boolean choose(int index){chooses++;buffer.setLength(0);return true;}
 static final class DataFiles {static boolean ready=true;}static final class NativeBridge {static String punctuation(char c,boolean chinese){return chinese&&c==','?"，":String.valueOf(c);}}
 PendingTextCommit.Result writeBound(TextWrite s,String value,boolean clear){if(!textWriteCurrent(s,s.raw))return PendingTextCommit.Result.STALE;owner.commitText(value,1);if(clear)buffer.setLength(0);return PendingTextCommit.Result.ACCEPTED;}
'''+"\n".join(parts.values()).replace('TextWrite(WangshuIme host)','TextWrite(MixedImeEntryChecks host)')+'''
 static int checks;static void check(boolean b,String label){if(!b)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 public static void main(String[]args){
 MixedImeEntryChecks h=new MixedImeEntryChecks();h.key("n");h.key("i");h.key("h");h.key("a");h.key("o");h.key("`");h.key("O");h.key("p");h.key("e");h.key("n");h.key("A");h.key("I");check(h.buffer.toString().equals("nihao`OpenAI")&&h.chooses==0&&h.directs==0,"one preedit holds Chinese and exact-cased literal");h.punctuation(',');h.key("\\\\");h.key("1");h.space();check(h.buffer.toString().equals("nihao`OpenAI,\\\\1 ")&&h.chooses==0&&h.directs==0,"punctuation slash digits and space stay in literal");h.key("`");h.key("z");h.key("h");h.key("o");h.key("n");h.key("g");h.key("w");h.key("e");h.key("n");check(h.buffer.toString().endsWith("`zhongwen"),"closed literal returns to same Chinese preedit");h.space();check(h.chooses==1&&h.buffer.length()==0,"space confirms combined candidate only after closing literal");
 h=new MixedImeEntryChecks();h.key("`");h.key("Q");h.key("X");check(h.finishRaw()&&h.committed.equals("QX")&&h.buffer.length()==0,"raw mode-switch flush strips preedit-only delimiter");
 h=new MixedImeEntryChecks();h.key("`");check(h.buffer.toString().equals("`")&&h.updates==1,"empty literal boundary refreshes local preedit without composing IPC");h.key("A");check(h.buffer.toString().equals("`A")&&h.updates==2,"first literal character refreshes local composition");h.key("`");check(h.buffer.toString().equals("`A`")&&h.updates==3,"closing literal refreshes local preedit without host text");
 h=new MixedImeEntryChecks();h.password=true;h.key("`");check(h.buffer.length()==0&&h.committed.equals("`"),"password always stays direct and never creates mixed composition");
 h=new MixedImeEntryChecks();h.english=true;h.key("`");check(h.buffer.length()==0&&h.committed.equals("`"),"explicit English mode retains raw backtick behavior");
 h=new MixedImeEntryChecks();h.buffer.append("\\\\alpha");h.key("`");check(!MixedComposition.present(h.buffer.toString()),"symbol command cannot start literal subgrammar");
 h=new MixedImeEntryChecks();h.buffer.append("`"+"x".repeat(127));h.key("y");check(h.buffer.length()==128&&h.updates==0,"composition bound blocks extra literal byte");
 h=new MixedImeEntryChecks();final MixedImeEntryChecks f=h;h.onConnection=()->{f.inputSession++;};h.key("`");check(h.buffer.length()==0&&h.updates==0,"reentrant editor/session change prevents appending segment metadata");
 System.out.println("TOTAL "+checks+" PASS; extracted production entry methods, fake editor only");}
}
'''
p=out/'MixedImeEntryChecks.java';p.write_text(code);jdk=r.parent/'_local/bin'
for name,cmd in [('compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8','-d',out,p,src/'MixedComposition.java',src/'PendingTextCommit.java']),('run',['java','-cp',out,'org.wangshu.ime.MixedImeEntryChecks'])]:
 v=subprocess.run([str(x) for x in cmd],capture_output=True,text=True);(out/(name+'.txt')).write_text(v.stdout+v.stderr);print(v.stdout+v.stderr,end='');assert v.returncode==0
(out/'source-receipt.json').write_text(json.dumps({'source_sha256':hashlib.sha256(s.encode()).hexdigest(),'method_sha256':{k:hashlib.sha256(v.encode()).hexdigest() for k,v in parts.items()},'scope':'Actual method bodies with synthetic boundaries; nested constructor host type renamed to wrapper. No Android API resolution or runtime.'},indent=2))
