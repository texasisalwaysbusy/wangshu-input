package org.wangshu.ime;
import android.app.Instrumentation;
import android.content.*;
import android.os.PersistableBundle;
import org.json.*;
import java.io.*;
import java.util.*;

final class ClipboardStoreChecks {
    private static int count;private static StringBuilder log;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);count++;log.append("PASS ").append(label).append('\n');}
    private static boolean findText(ClipboardStore store,long now,String value)throws Exception {JSONArray rows=store.list(now,7);for(int i=0;i<rows.length();i++)if(value.equals(rows.getJSONObject(i).getString("text")))return true;return false;}
    static String run(Instrumentation test)throws Exception {
        count=0;log=new StringBuilder();File path=new File(test.getTargetContext().getFilesDir(),"clipboard-checks-"+UUID.randomUUID()+".json");ClipboardStore store=new ClipboardStore(path);long now=100*ClipboardStore.DAY;
        ClipData plain=ClipData.newPlainText("synthetic clipboard check","数学 α\nEnglish 👩‍🔬");check(ClipboardStore.allowedText(plain,false,false)==null,"recording disabled refuses clipboard text");check(ClipboardStore.allowedText(plain,true,true)==null,"password or private editor refuses history");check(ClipboardStore.allowedText(plain,true,false).equals("数学 α\nEnglish 👩‍🔬"),"enabled normal editor accepts exact multiline Unicode text");
        ClipData sensitive=ClipData.newPlainText("synthetic sensitive flag","TEST-SECRET");PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);sensitive.getDescription().setExtras(extras);check(ClipboardStore.allowedText(sensitive,true,false)==null,"official sensitive-content flag never enters history");
        ClipData uri=new ClipData(new ClipDescription("synthetic URI",new String[]{"text/plain"}),new ClipData.Item(android.net.Uri.parse("content://example.invalid/not-opened")));check(ClipboardStore.allowedText(uri,true,false)==null,"URI reference refused without resolving content provider");
        ClipData multi=ClipData.newPlainText("synthetic multi","first");multi.addItem(new ClipData.Item("second"));check(ClipboardStore.allowedText(multi,true,false)==null,"ambiguous multiple-item clips refused");
        check(store.add("same",now,7),"first explicit record persists");String id=store.list(now,7).getJSONObject(0).getString("id");store.pin(id,true);store.add("same",now+1,7);check(store.list(now+1,7).length()==1&&store.list(now+1,7).getJSONObject(0).getBoolean("pinned"),"repeat copy deduplicates while retaining stable identity and pin");
        store.add("recent",now,7);store.add("expired",now-8*ClipboardStore.DAY,7);JSONArray visible=store.list(now,7);check(visible.length()==2&&visible.getJSONObject(0).getString("id").equals(id),"expiry removes only old unpinned rows and pins stay first");
        check(new ClipboardStore(path).list(now,7).length()==2,"expired rows physically removed from persisted store");
        store.edit(id,"same","edited");check(store.list(now,7).getJSONObject(0).getString("text").equals("edited"),"editing targets stable ID and preserves unrelated entry");boolean stale=false;try{store.edit(id,"same","wrong");}catch(IOException expected){stale=true;}check(stale&&store.list(now,7).getJSONObject(0).getString("text").equals("edited"),"stale edit refuses rather than overwriting a newer value");
        boolean duplicate=false;try{store.edit(id,"edited","recent");}catch(IOException expected){duplicate=true;}check(duplicate,"editing to another existing row text is explicitly refused");
        boolean staleDelete=false;try{store.remove(id,"old value");}catch(IOException expected){staleDelete=true;}check(staleDelete&&findText(store,now,"edited"),"stale deletion refuses a history row whose content changed");
        store.remove(id,"edited");check(store.list(now,7).length()==1&&store.list(now,7).getJSONObject(0).getString("text").equals("recent"),"individual deletion preserves unrelated history");
        for(int i=0;i<110;i++)store.add("row"+i,now+i,7);check(store.list(now+200,7).length()==100,"bounded history evicts only oldest unpinned content");
        JSONArray rows=store.list(now+200,7);for(int i=0;i<rows.length();i++)store.pin(rows.getJSONObject(i).getString("id"),true);check(!store.add("must not evict a pin",now+300,7)&&store.list(now+300,7).length()==100,"all pinned capacity refuses new record without losing pins");
        store.clear();check(new ClipboardStore(path).list(now,7).length()==0,"explicit clear persists an empty history");
        class Host implements SplitSelection.Editor {String text="same|original";int start=5,end=5;public SplitSelection.Snapshot read(){return new SplitSelection.Snapshot(text,start,end);}public boolean replace(int a,int b,String value,int caret){text=text.substring(0,a)+value+text.substring(b);start=end=caret;return true;}}
        Host host=new Host();SplitSelection split=new SplitSelection(host);check(split.toggle(2,"丙")&&split.toggle(0,"甲")&&host.text.equals("same|丙甲original"),"split inserts non-contiguous blocks in click order at existing caret");check(split.toggle(2,"丙")&&host.text.equals("same|甲original"),"deselect removes only its earlier insertion and preserves surrounding text");check(split.toggle(2,"丙")&&host.text.equals("same|甲丙original"),"reselect appends at new selection time");
        check(split.toggle(4,"same")&&split.toggle(5,"same")&&split.toggle(4,"same")&&host.text.equals("same|甲丙sameoriginal"),"identical source strings tracked by distinct insertion IDs without global replacement");
        String before=host.text;host.text="external "+host.text;check(!split.toggle(0,"甲")&&host.text.equals("external "+before),"external host text change invalidates undo and preserves all content");
        Host moved=new Host();SplitSelection movedSession=new SplitSelection(moved);moved.start=moved.end=0;check(!movedSession.toggle(0,"x")&&moved.text.equals("same|original"),"external cursor movement refuses stale split insertion");
        check(PersonalPanel.tokenize("甲DFS，👩‍🔬乙").equals(new ArrayList<>(Arrays.asList("甲","DFS","，","👩‍🔬","乙"))),"split tokenization keeps English word and modified emoji intact");
        return "PASS "+count+" isolated clipboard/split model checks\n"+log+"Synthetic values only; actual OS clipboard listener and native panel still require separate integration\n";
    }
}
