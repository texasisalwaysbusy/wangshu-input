package org.wangshu.ime;
import android.os.Handler;
import android.view.inputmethod.InputConnection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic reentrant host; never reads a real editor or personal data. */
public final class PhrasePrivacyRaceTest {
 static final class Host implements PersonalPhraseAssociation.Host {
  volatile boolean allowed=true;volatile int session=1;final InputConnection reused=new InputConnection(){};final AtomicInteger reads=new AtomicInteger(),privatePhraseReads=new AtomicInteger();volatile PersonalPhraseAssociation.Display shown;
  public boolean allowed(){return allowed;}public int session(){return session;}public InputConnection connection(){return reused;}public PersonalPhraseSuggestions.Language language(){return PersonalPhraseSuggestions.Language.ENGLISH;}
  public SplitSelection.Snapshot read(InputConnection owner){int count=reads.incrementAndGet();if(count==1){session++;allowed=false;}return new SplitSelection.Snapshot("thank ",6,6);}
  public PersonalPhraseAssociation.PhraseData phrases(){if(!allowed)privatePhraseReads.incrementAndGet();return new PersonalPhraseAssociation.PhraseData(1,List.of("thank you"));}
  public long revision(){return 1;}public void show(PersonalPhraseAssociation.Display d){shown=d;}public boolean insert(String value){throw new AssertionError("unexpected insertion");}public void unavailable(){}
 }
 public static void main(String[] args)throws Exception{
  Host host=new Host();PersonalPhraseAssociation provider=new PersonalPhraseAssociation(host);provider.request();long deadline=System.currentTimeMillis()+2000;
  while(System.currentTimeMillis()<deadline&&host.privatePhraseReads.get()==0){Handler.pump();Thread.sleep(5);}Handler.pump();provider.close();
  System.out.println("Synthetic switched-private reads="+host.reads.get()+" phraseQueriesAfterSwitch="+host.privatePhraseReads.get()+" candidateDisplayed="+(host.shown!=null));
  if(host.privatePhraseReads.get()!=0||host.reads.get()>1)throw new AssertionError("RED: worker continues context-dependent work after a private/session transition");
 }
}
