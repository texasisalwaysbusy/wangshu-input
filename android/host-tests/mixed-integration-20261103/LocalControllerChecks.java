package org.wangshu.ime;
import android.os.Handler;
import android.view.inputmethod.InputConnection;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
public class LocalControllerChecks {
 static int checks;static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()&&System.nanoTime()<end){Handler.pump();Thread.sleep(1);}check(condition.getAsBoolean(),"async completed");Handler.pump();}
 static final class F implements PersonalPhraseAssociation.Host,AutoCloseable {
  boolean allowed=true,saved=true,local=true,cursorEnabled=true,refuse,lying,mutationOnRead,swapOnInsert,clearOnRead,disableOnLocal,modeChangedOnLocal;volatile boolean numbers;int session=1,readCalls,inserts,deletes,unavailable,personalReads,providerCalls;long revision=1;String text="Thank you ";int caret=text.length();
  InputConnection owner=new InputConnection(){};PersonalPhraseAssociation.Display visible;PersonalPhraseAssociation controller=new PersonalPhraseAssociation(this);
  public boolean allowed(){return allowed;}public int session(){return session;}public InputConnection connection(){return owner;}public PersonalPhraseSuggestions.Language language(){return PersonalPhraseSuggestions.Language.ENGLISH;}
  public SplitSelection.Snapshot read(InputConnection c){readCalls++;if(clearOnRead){clearOnRead=false;controller.clear();}if(mutationOnRead){mutationOnRead=false;text+="x";caret++;}return new SplitSelection.Snapshot(text,caret,caret);}
  public PersonalPhraseAssociation.PhraseData phrases(){personalReads++;return new PersonalPhraseAssociation.PhraseData(revision,List.of("Thank you for your help"));}
  public long revision(){return revision;}public boolean savedEnabled(){return saved;}public boolean localEnabled(){return local;}
  public boolean numericPanel(){return numbers;}
  public boolean cursorEnabled(){return cursorEnabled;}
  public List<PersonalPhraseAssociation.Choice> localSuggestions(String before,String after,PersonalPhraseSuggestions.Language language,boolean numericPanel){providerCalls++;if(disableOnLocal){disableOnLocal=false;local=false;}if(modeChangedOnLocal)numbers=false;if(numericPanel)return List.of(new PersonalPhraseAssociation.Choice("年","数字续写",true));return List.of(new PersonalPhraseAssociation.Choice("for", "本地例句 #1 · 下一词",true),new PersonalPhraseAssociation.Choice("for the update.","本地例句 #2 · 整句",true));}
  public void show(PersonalPhraseAssociation.Display value){visible=value;}
  public boolean insert(InputConnection expected,int id,SplitSelection.Snapshot snapshot,
                        java.util.function.BooleanSupplier current,String value){
   if(!current.getAsBoolean()||expected!=owner||id!=session)return false;
   inserts++;if(refuse)return false;if(!lying){text=text.substring(0,caret)+value+text.substring(caret);caret+=value.length();}if(swapOnInsert){owner=new InputConnection(){};session++;}return true;
  }
  public boolean retract(InputConnection expected,int id,SplitSelection.Snapshot snapshot,
                         java.util.function.BooleanSupplier current,int units){
   if(!current.getAsBoolean()||expected!=owner||id!=session)return false;
   deletes++;if(refuse)return false;if(!lying){text=text.substring(0,caret-units)+text.substring(caret);caret-=units;}return true;
  }
  public void unavailable(){unavailable++;}public void close(){controller.close();}
  void undo(){Runnable current=controller.undoAction();if(current!=null)current.run();}
  void request()throws Exception{controller.request();Handler.advance(120);await(()->visible!=null);}
 }
 static void settleRequest()throws Exception{
  Handler.advance(120);
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
  while(Handler.pending()<2&&System.nanoTime()<deadline)Thread.sleep(1);
  check(Handler.pending()==2,"worker posted alongside its timeout");Handler.pump();
 }
 public static void main(String[]args)throws Exception{
  try(F f=new F()){f.request();check(f.visible.choices.size()==2,"saved candidate priority and duplicate local suffix removed");check(!f.visible.choices.get(0).builtin&&f.visible.choices.get(1).builtin,"provenance retained per choice");String original=f.text;var d=f.visible;f.controller.accept(d,d.choices.get(1));check(f.text.equals(original+"for the update.")&&f.controller.hasUndo(),"full sentence inserted once with exact undo token");f.controller.accept(d);check(f.inserts==1,"old choice cannot insert twice");f.undo();check(f.text.equals(original)&&f.deletes==1&&!f.controller.hasUndo(),"undo deletes only this suffix");f.undo();check(f.deletes==1,"undo single use");}
  try(F f=new F()){f.saved=false;f.request();check(f.personalReads==0,"builtin only never reads saved phrase store");check(f.visible.revision==0&&f.visible.choices.get(0).builtin,"builtin independent of personal revision");f.revision=99;f.controller.accept(f.visible);check(f.inserts==1,"builtin unaffected by unrelated personal revision");}
  try(F f=new F()){f.request();f.revision++;f.controller.accept(f.visible);check(f.inserts==0,"saved revision race refused");}
  try(F f=new F()){f.request();var d=f.visible;f.local=false;f.controller.accept(d,d.choices.get(1));check(f.inserts==0,"local setting disabled before selection refuses stale choice");}
  try(F f=new F()){f.saved=false;f.disableOnLocal=true;f.controller.request();Handler.advance(120);Thread.sleep(30);Handler.pump();check(f.visible==null,"toggle during worker prevents result publication");}
  try(F f=new F()){f.request();f.caret=0;f.controller.accept(f.visible);check(f.inserts==0,"caret movement before acceptance refused");}
  try(F f=new F()){
   f.request();var old=f.visible;f.caret=5;f.controller.selectionChanged(5,5,false,true);
   check(f.visible==null,"caret notification immediately hides the old choice");
   f.controller.accept(old);check(f.inserts==0,"old position cannot insert after notification");
   settleRequest();check(f.visible!=null&&f.visible.snapshot.start==5&&f.visible.fromMovement,"enabled movement binds new results to the notified caret");
   f.cursorEnabled=false;f.controller.accept(f.visible);check(f.inserts==0,"disabling movement rejects an already displayed movement choice");
  }
  try(F f=new F()){
   f.cursorEnabled=false;f.request();int calls=f.providerCalls;f.caret=5;
   f.controller.selectionChanged(5,5,false,true);settleRequest();
   check(f.visible==null&&f.providerCalls==calls,"disabled movement never invokes the provider for unchanged text");
   f.controller.request();settleRequest();
   check(f.visible==null&&f.providerCalls==calls,"passive refresh does not reissue a disabled movement candidate");
   f.text="Thank you again ";f.caret=f.text.length();f.controller.selectionChanged(f.caret,f.caret,false,true);settleRequest();
   check(f.visible!=null&&!f.visible.fromMovement&&f.providerCalls==calls+1,"observed text edit still refreshes with movement disabled");
  }
  try(F f=new F()){
   f.cursorEnabled=false;f.text="First ";f.caret=f.text.length();f.controller.textEdited();
   f.controller.selectionChanged(f.caret,f.caret,false,true);settleRequest();
   check(f.visible!=null&&!f.visible.fromMovement,"first acknowledged edit still refreshes before a baseline exists");
  }
  try(F f=new F()){
   f.request();int reads=f.readCalls,calls=f.providerCalls;
   f.controller.selectionChanged(3,5,false,true);Handler.advance(120);
   check(f.visible==null&&f.readCalls==reads&&f.providerCalls==calls,"noncollapsed selection hides candidates before an editor read");
   f.controller.selectionChanged(f.caret,f.caret,true,false);f.controller.request();Handler.advance(120);
   check(f.visible==null&&f.readCalls==reads&&f.providerCalls==calls,"active composition hides candidates before an editor read");
   f.controller.selectionChanged(3,3,false,true);settleRequest();
   check(f.visible==null&&f.providerCalls==calls,"mismatching reported and extracted carets are refused");
   f.controller.selectionChanged(f.caret,f.caret,true,false);
   f.controller.selectionChanged(f.caret,f.caret,false,false);settleRequest();
   check(f.visible!=null,"confirmed end of composition resumes at the same caret");
  }
  try(F f=new F()){f.request();f.owner=new InputConnection(){};f.session++;f.controller.accept(f.visible);check(f.inserts==0,"editor replacement before acceptance refused");}
  try(F f=new F()){f.saved=false;f.numbers=true;f.text="日期：2026，备注";f.caret=7;f.request();check(f.visible.numericPanel&&f.visible.suffix.equals("年"),"numeric panel captured by asynchronous provider");f.numbers=false;f.controller.accept(f.visible);check(f.inserts==0,"leaving the numeric panel rejects its stale choice");}
  try(F f=new F()){
   f.saved=false;f.numbers=true;f.modeChangedOnLocal=true;f.controller.request();Handler.advance(120);
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
   while(Handler.pending()<2&&System.nanoTime()<deadline)Thread.sleep(1);
   check(Handler.pending()==2,"worker posted numeric result alongside its timeout");Handler.pump();
   check(!f.numbers&&f.visible==null,"panel change during numeric worker rejects publication");
  }
  try(F f=new F()){f.request();f.allowed=false;f.controller.accept(f.visible);check(f.inserts==0,"privacy before acceptance refused");}
  try(F f=new F()){f.request();f.refuse=true;f.controller.accept(f.visible);check(f.inserts==1&&!f.controller.hasUndo(),"refused insert never yields undo or replay");}
  try(F f=new F()){f.request();f.lying=true;f.controller.accept(f.visible);check(f.inserts==1&&!f.controller.hasUndo(),"lying host acknowledgment cannot yield undo");}
  try(F f=new F()){f.request();f.swapOnInsert=true;f.controller.accept(f.visible);check(!f.controller.hasUndo(),"editor swap during insertion cannot yield undo");}
  try(F f=new F()){f.request();f.controller.accept(f.visible);f.text+="changed";f.caret=f.text.length();f.undo();check(f.deletes==0&&!f.controller.hasUndo(),"later text edit makes undo inert");}
  try(F f=new F()){f.request();f.controller.accept(f.visible);f.session++;f.undo();check(f.deletes==0,"later session makes undo inert");}
  try(F f=new F()){f.request();f.controller.accept(f.visible);f.allowed=false;f.undo();check(f.deletes==0,"privacy makes undo inert");}
  try(F f=new F()){f.request();f.controller.accept(f.visible);f.refuse=true;f.undo();f.undo();check(f.deletes==1&&!f.controller.hasUndo(),"uncertain undo never retries");}
  try(F f=new F()){f.request();f.controller.accept(f.visible);f.controller.clear();check(!f.controller.hasUndo(),"lifecycle clear drops undo token");}
  try(F f=new F()){
   f.request();f.controller.accept(f.visible);
   Runnable old=f.controller.undoAction();check(old!=null,"first continuation exposes an undo action");
   f.controller.clear();f.owner=new InputConnection(){};f.session++;
   f.text="Thank you ";f.caret=f.text.length();f.request();f.controller.accept(f.visible);
   Runnable current=f.controller.undoAction();String second=f.text;
   old.run();check(f.deletes==0&&f.text.equals(second)&&f.controller.hasUndo(),"old rendered undo cannot delete a newer continuation");
   current.run();current.run();
   check(f.deletes==1&&f.text.equals("Thank you ")&&!f.controller.hasUndo(),"the newer rendered undo works exactly once");
  }
  try(F f=new F()){f.text="Thank you , after";f.caret=10;String before=f.text;f.request();f.controller.accept(f.visible);check(f.text.endsWith(", after"),"middle insertion preserves trailing text");f.undo();check(f.text.equals(before),"middle undo restores exact full text");}
  check(MixedComposition.active("nihao`OpenAI")&&!MixedComposition.active("nihao`OpenAI`"),"Java literal boundary parity");check(MixedComposition.raw("nihao``zhongwen").equals("nihaozhongwen"),"Java raw strips metadata only");check(!MixedComposition.literalKey("中文")&&!MixedComposition.literalKey("\n")&&MixedComposition.literalKey("\\path @ x"),"Java literal grammar rejects nonASCII and controls");
  System.out.println("TOTAL "+checks+" PASS; actual controller synthetic Android only");
 }
}
