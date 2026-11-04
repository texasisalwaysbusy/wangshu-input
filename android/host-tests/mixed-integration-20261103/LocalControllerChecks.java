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
  boolean allowed=true,saved=true,local=true,refuse,lying,mutationOnRead,swapOnInsert,clearOnRead,disableOnLocal;int session=1,readCalls,inserts,deletes,unavailable,personalReads;long revision=1;String text="Thank you ";int caret=text.length();
  InputConnection owner=new InputConnection(){};PersonalPhraseAssociation.Display visible;PersonalPhraseAssociation controller=new PersonalPhraseAssociation(this);
  public boolean allowed(){return allowed;}public int session(){return session;}public InputConnection connection(){return owner;}public PersonalPhraseSuggestions.Language language(){return PersonalPhraseSuggestions.Language.ENGLISH;}
  public SplitSelection.Snapshot read(InputConnection c){readCalls++;if(clearOnRead){clearOnRead=false;controller.clear();}if(mutationOnRead){mutationOnRead=false;text+="x";caret++;}return new SplitSelection.Snapshot(text,caret,caret);}
  public PersonalPhraseAssociation.PhraseData phrases(){personalReads++;return new PersonalPhraseAssociation.PhraseData(revision,List.of("Thank you for your help"));}
  public long revision(){return revision;}public boolean savedEnabled(){return saved;}public boolean localEnabled(){return local;}
  public List<PersonalPhraseAssociation.Choice> localSuggestions(String before,String after,PersonalPhraseSuggestions.Language language){if(disableOnLocal){disableOnLocal=false;local=false;}return List.of(new PersonalPhraseAssociation.Choice("for", "本地例句 #1 · 下一词",true),new PersonalPhraseAssociation.Choice("for the update.","本地例句 #2 · 整句",true));}
  public void show(PersonalPhraseAssociation.Display value){visible=value;}public boolean insert(String value){inserts++;if(refuse)return false;if(!lying){text=text.substring(0,caret)+value+text.substring(caret);caret+=value.length();}if(swapOnInsert){owner=new InputConnection(){};session++;}return true;}
  public boolean retract(InputConnection expected,int id,int units){deletes++;if(refuse)return false;if(!lying){text=text.substring(0,caret-units)+text.substring(caret);caret-=units;}return true;}
  public void unavailable(){unavailable++;}public void close(){controller.close();}
  void request()throws Exception{controller.request();Handler.advance(120);await(()->visible!=null);}
 }
 public static void main(String[]args)throws Exception{
  try(F f=new F()){f.request();check(f.visible.choices.size()==2,"saved candidate priority and duplicate local suffix removed");check(!f.visible.choices.get(0).builtin&&f.visible.choices.get(1).builtin,"provenance retained per choice");String original=f.text;var d=f.visible;f.controller.accept(d,d.choices.get(1));check(f.text.equals(original+"for the update.")&&f.controller.hasUndo(),"full sentence inserted once with exact undo token");f.controller.accept(d);check(f.inserts==1,"old choice cannot insert twice");f.controller.undo();check(f.text.equals(original)&&f.deletes==1&&!f.controller.hasUndo(),"undo deletes only this suffix");f.controller.undo();check(f.deletes==1,"undo single use");}
  try(F f=new F()){f.saved=false;f.request();check(f.personalReads==0,"builtin only never reads saved phrase store");check(f.visible.revision==0&&f.visible.choices.get(0).builtin,"builtin independent of personal revision");f.revision=99;f.controller.accept(f.visible);check(f.inserts==1,"builtin unaffected by unrelated personal revision");}
  try(F f=new F()){f.request();f.revision++;f.controller.accept(f.visible);check(f.inserts==0,"saved revision race refused");}
  try(F f=new F()){f.request();var d=f.visible;f.local=false;f.controller.accept(d,d.choices.get(1));check(f.inserts==0,"local setting disabled before selection refuses stale choice");}
  try(F f=new F()){f.saved=false;f.disableOnLocal=true;f.controller.request();Handler.advance(120);Thread.sleep(30);Handler.pump();check(f.visible==null,"toggle during worker prevents result publication");}
  try(F f=new F()){f.request();f.caret=0;f.controller.accept(f.visible);check(f.inserts==0,"caret movement before acceptance refused");}
  try(F f=new F()){f.request();f.owner=new InputConnection(){};f.session++;f.controller.accept(f.visible);check(f.inserts==0,"editor replacement before acceptance refused");}
  try(F f=new F()){f.request();f.allowed=false;f.controller.accept(f.visible);check(f.inserts==0,"privacy before acceptance refused");}
  try(F f=new F()){f.request();f.refuse=true;f.controller.accept(f.visible);check(f.inserts==1&&!f.controller.hasUndo(),"refused insert never yields undo or replay");}
  try(F f=new F()){f.request();f.lying=true;f.controller.accept(f.visible);check(f.inserts==1&&!f.controller.hasUndo(),"lying host acknowledgment cannot yield undo");}
  try(F f=new F()){f.request();f.swapOnInsert=true;f.controller.accept(f.visible);check(!f.controller.hasUndo(),"editor swap during insertion cannot yield undo");}
  try(F f=new F()){f.request();f.controller.accept(f.visible);f.text+="changed";f.caret=f.text.length();f.controller.undo();check(f.deletes==0&&!f.controller.hasUndo(),"later text edit makes undo inert");}
  try(F f=new F()){f.request();f.controller.accept(f.visible);f.session++;f.controller.undo();check(f.deletes==0,"later session makes undo inert");}
  try(F f=new F()){f.request();f.controller.accept(f.visible);f.allowed=false;f.controller.undo();check(f.deletes==0,"privacy makes undo inert");}
  try(F f=new F()){f.request();f.controller.accept(f.visible);f.refuse=true;f.controller.undo();f.controller.undo();check(f.deletes==1&&!f.controller.hasUndo(),"uncertain undo never retries");}
  try(F f=new F()){f.request();f.controller.accept(f.visible);f.controller.clear();check(!f.controller.hasUndo(),"lifecycle clear drops undo token");}
  try(F f=new F()){f.text="Thank you , after";f.caret=10;String before=f.text;f.request();f.controller.accept(f.visible);check(f.text.endsWith(", after"),"middle insertion preserves trailing text");f.controller.undo();check(f.text.equals(before),"middle undo restores exact full text");}
  check(MixedComposition.active("nihao`OpenAI")&&!MixedComposition.active("nihao`OpenAI`"),"Java literal boundary parity");check(MixedComposition.raw("nihao``zhongwen").equals("nihaozhongwen"),"Java raw strips metadata only");check(!MixedComposition.literalKey("中文")&&!MixedComposition.literalKey("\n")&&MixedComposition.literalKey("\\path @ x"),"Java literal grammar rejects nonASCII and controls");
  System.out.println("TOTAL "+checks+" PASS; actual controller synthetic Android only");
 }
}
