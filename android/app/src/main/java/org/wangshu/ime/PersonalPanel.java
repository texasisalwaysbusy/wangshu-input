package org.wangshu.ime;

import android.content.*;
import android.icu.text.BreakIterator;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Native keyboard panels. Clipboard is opt-in and every new opening starts there. */
final class PersonalPanel {
    interface Host {Context context();boolean insert(Object owner,String value);void close();void redraw();boolean privateInput();SplitSelection.Snapshot snapshot();Object splitOwner();boolean splitCurrent(Object owner);SplitSelection.Snapshot snapshotSplit(Object owner);boolean replaceSplit(Object owner,int start,int end,String value,int caretAfter);}
    private final Host host;private final Context c;private boolean phrases,confirmClear;private String splitText;private SplitSelection split;private ArrayList<String> tokens;private int tokenPage;
    private long viewGeneration;private Object splitEditorOwner;
    PersonalPanel(Host value){host=value;c=host.context();}
    void open(){phrases=false;confirmClear=false;resetSplit();}
    void resetSplit(){viewGeneration++;splitText=null;split=null;tokens=null;tokenPage=0;splitEditorOwner=null;}
    private boolean current(long generation,Object owner){try{return generation==viewGeneration&&host.splitCurrent(owner)&&generation==viewGeneration;}catch(RuntimeException unavailable){return false;}}
    private boolean historyHidden(){try{return host.privateInput();}catch(RuntimeException unavailable){return true;}}
    private int dp(int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(c);l.setGravity(Gravity.CENTER);return l;}
    private Button button(String text,Runnable click){Button b=new Button(c);b.setText(text);b.setAllCaps(false);b.setTextColor(c.getColor(R.color.ink));b.setTextSize(14);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(4),dp(3),dp(4),dp(3));b.setMaxLines(1);b.setEllipsize(android.text.TextUtils.TruncateAt.END);b.setAutoSizeTextTypeUniformWithConfiguration(10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);KeyboardSkin.key(c,b,false);final long generation=viewGeneration;b.setOnClickListener(v->{if(generation==viewGeneration)click.run();});return b;}
    private void action(LinearLayout row,String title,float weight,int height,Runnable click){row.addView(button(title,click),new LinearLayout.LayoutParams(0,dp(height),weight));}
    private void text(LinearLayout target,String content){TextView v=new TextView(c);v.setText(content);v.setTextColor(c.getColor(R.color.muted));v.setTextSize(14);v.setPadding(dp(12),dp(10),dp(12),dp(10));target.addView(v);}
    private void failure(Exception error){Toast.makeText(c,error instanceof PersonalStore.UnconfirmedSaveException||error instanceof ClipboardStore.UnconfirmedSaveException?error.getMessage():"操作未完成，请重新打开后检查",Toast.LENGTH_LONG).show();}
    View build(boolean compact,int height){
        final long generation=++viewGeneration;
        if(splitText!=null){boolean hidden=historyHidden();if(generation!=viewGeneration)return column();if(hidden)resetSplit();else return splitView(compact,height);}
        LinearLayout panel=column(),header=row();action(header,"返回",1,height,host::close);action(header,"剪贴板",2,height,()->{phrases=false;confirmClear=false;host.redraw();});action(header,"常用语",2,height,()->{phrases=true;confirmClear=false;host.redraw();});action(header,phrases?"＋":"清空",1,height,()->{if(phrases)c.startActivity(new Intent(c,PersonalActivity.class).putExtra("phrases",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));else{confirmClear=!confirmClear;host.redraw();}});header.getChildAt(1).setContentDescription("剪贴板标签");header.getChildAt(2).setContentDescription("常用语标签");header.getChildAt(1).setSelected(!phrases);header.getChildAt(2).setSelected(phrases);KeyboardSkin.key(c,header.getChildAt(1),!phrases);KeyboardSkin.key(c,header.getChildAt(2),phrases);panel.addView(header);
        if(historyHidden()){text(panel,"此输入框不展示个人历史或常用语");return panel;}
        final Object editorOwner=host.splitOwner();
        if(generation!=viewGeneration)return panel;
        if(historyHidden()){text(panel,"此输入框不展示个人历史或常用语");return panel;}
        if(confirmClear){text(panel,"清空全部历史，包括置顶？不会清除系统剪贴板。");LinearLayout confirmation=row();action(confirmation,"取消",1,height,()->{confirmClear=false;host.redraw();});action(confirmation,"确认清空",1,height,()->{try{ClipboardStore.get(c).clear();confirmClear=false;host.redraw();}catch(Exception e){failure(e);}});panel.addView(confirmation);return panel;}
        ScrollView scroll=new ScrollView(c);LinearLayout list=column();scroll.addView(list);panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(compact?100:180)));
        try{
            JSONArray items=phrases?PersonalStore.get(c).snapshot().getJSONArray("phrases"):ClipboardStore.get(c).list(System.currentTimeMillis(),AppPreferences.open(c,"clipboard").getInt("days",7));
            if(!phrases&&!AppPreferences.open(c,"clipboard").getBoolean("enabled",false))text(list,"历史记录已关闭，已有条目仍可管理。可在下方设置中主动开启。");
            if(items.length()==0)text(list,phrases?"还没有常用语，点击右上角＋添加。":"暂无保存的历史。只有开启记录后才保存纯文本。");
            for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);String value=item.getString("text");final int index=i;String id=phrases?"":item.getString("id");boolean pin=!phrases&&item.getBoolean("pinned");
                FrameLayout card=new FrameLayout(c);LinearLayout operations=column();LinearLayout first=row(),second=row();operations.addView(first);operations.addView(second);
                action(first,"拆词",1,48,()->startSplit(value,generation,editorOwner));action(first,"编辑",1,48,()->{if(phrases)c.startActivity(new Intent(c,PersonalActivity.class).putExtra("phrases",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));else edit(id,value);});
                if(!phrases)action(first,pin?"取消置顶":"置顶",1,48,()->{try{ClipboardStore.get(c).pin(id,!pin);host.redraw();}catch(Exception e){failure(e);}});
                if(!phrases)action(second,"＋ 常用语",2,48,()->{try{PersonalStore.get(c).putPhrase(-1,value,"");Toast.makeText(c,"已加入常用语",Toast.LENGTH_SHORT).show();}catch(Exception e){failure(e);}});
                action(second,"删除",1,48,()->{try{if(phrases){JSONArray current=PersonalStore.get(c).snapshot().getJSONArray("phrases");if(index>=current.length()||!current.getJSONObject(index).toString().equals(item.toString()))throw new java.io.IOException("条目已改变");PersonalStore.get(c).remove("phrases",index);}else ClipboardStore.get(c).remove(id,value);host.redraw();}catch(Exception e){failure(e);}});
                FrameLayout.LayoutParams back=new FrameLayout.LayoutParams(dp(280),dp(100),Gravity.END);card.addView(operations,back);
                LinearLayout front=row();front.setBackgroundColor(c.getColor(R.color.paper));Button body=button((pin?"置顶 · ":"")+value,()->{if(!current(generation,editorOwner))return;if(!host.insert(editorOwner,value))Toast.makeText(c,"插入结果未确认或输入位置已变化，请核对；未自动重试",Toast.LENGTH_LONG).show();});body.setMaxLines(3);body.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);body.setContentDescription((phrases?"插入常用语 ":"插入剪贴板条目 ")+(i+1));front.addView(body,new LinearLayout.LayoutParams(0,-1,1));Button more=button("⋯",()->{float distance=Math.min(dp(280),Math.max(0,card.getWidth()-dp(44)));front.animate().translationX(front.getTranslationX()!=0?0:front.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL?distance:-distance).setDuration(180).start();});more.setContentDescription((phrases?"常用语更多操作 ":"剪贴板更多操作 ")+(i+1));front.addView(more,new LinearLayout.LayoutParams(dp(44),-1));card.addView(front,new FrameLayout.LayoutParams(-1,dp(100)));LinearLayout.LayoutParams entry=new LinearLayout.LayoutParams(-1,dp(100));entry.setMargins(dp(3),dp(3),dp(3),dp(3));list.addView(card,entry);
            }
        }catch(Exception e){text(list,e instanceof ClipboardStore.UnconfirmedSaveException?e.getMessage():"本地数据暂不可读，文件保留，请在设置中检查。");}
        LinearLayout footer=row();action(footer,phrases?"管理常用语":"历史设置",1,40,()->c.startActivity(new Intent(c,phrases?PersonalActivity.class:ClipboardSettingsActivity.class).putExtra("phrases",phrases).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));panel.addView(footer);return panel;
    }
    private void edit(String id,String original){c.startActivity(new Intent(c,ClipboardEditActivity.class).putExtra("id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private void startSplit(String text,long generation,Object owner){
        if(!current(generation,owner))return;
        ArrayList<String> nextTokens=tokenize(text);
        SplitSelection next=new SplitSelection(new SplitSelection.Editor(){public boolean current(){return host.splitCurrent(owner);}public SplitSelection.Snapshot read(){return host.snapshotSplit(owner);}public boolean replace(int start,int end,String value,int caretAfter){return host.replaceSplit(owner,start,end,value,caretAfter);}});
        // A snapshot may synchronously trigger lifecycle cleanup or a newer panel.
        if(!current(generation,owner))return;
        splitText=text;tokens=nextTokens;tokenPage=0;split=next;splitEditorOwner=owner;host.redraw();
    }
    static ArrayList<String> tokenize(String value){ArrayList<String> out=new ArrayList<>();BreakIterator iterator=BreakIterator.getCharacterInstance(Locale.ROOT);iterator.setText(value);StringBuilder latin=new StringBuilder();int previous=iterator.first();for(int next=iterator.next();next!=BreakIterator.DONE;previous=next,next=iterator.next()){String piece=value.substring(previous,next);boolean word=piece.length()==1&&(piece.charAt(0)<128)&&(Character.isLetterOrDigit(piece.charAt(0))||piece.charAt(0)=='_');if(word)latin.append(piece);else{if(latin.length()>0){out.add(latin.toString());latin.setLength(0);}out.add(piece);}}if(latin.length()>0)out.add(latin.toString());return out;}
    private View splitView(boolean compact,int height){final SplitSelection active=split;final Object owner=splitEditorOwner;final long generation=viewGeneration;LinearLayout panel=column(),header=row();action(header,"返回",1,height,()->{resetSplit();host.redraw();});action(header,"＋ 常用语",2,height,()->{try{if(!active.ready())return;PersonalStore.get(c).putPhrase(-1,active.selectedText(),"");Toast.makeText(c,"已加入常用语",Toast.LENGTH_SHORT).show();}catch(Exception e){failure(e);}});header.getChildAt(1).setEnabled(active.ready()&&!active.selectedText().isEmpty());panel.addView(header);text(panel,active.ready()?"按点击先后直接输入 · 再点撤回本块":"当前拆词会话暂不可核对；操作已暂停，选中标记已隐藏。文字可能已改变，请检查编辑器；不会自动重试或撤回。");ScrollView scroll=new ScrollView(c);LinearLayout grid=column();scroll.addView(grid);int end=Math.min(tokens.size(),(tokenPage+1)*48);LinearLayout row=null;for(int i=tokenPage*48;i<end;i++){if((i-tokenPage*48)%6==0){row=row();grid.addView(row);}final int index=i;String value=tokens.get(i);Button b=button(value.equals(" ")?"空格":value.equals("\n")?"换行":value,()->{if(!current(generation,owner)||split!=active)return;if(!active.toggle(index,value))Toast.makeText(c,"操作结果未确认，请核对编辑器；文字可能稍后改变，不会自动重试或撤回",Toast.LENGTH_LONG).show();host.redraw();});b.setContentDescription("拆词块 "+i+" "+value);b.setEnabled(active.ready());KeyboardSkin.key(c,b,active.selected(i));row.addView(b,new LinearLayout.LayoutParams(0,dp(height),1));}if(row!=null)while(row.getChildCount()<6)row.addView(new View(c),new LinearLayout.LayoutParams(0,dp(height),1));panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(compact?90:150)));LinearLayout paging=row();Button previous=button("上一页",()->{if(tokenPage>0){tokenPage--;host.redraw();}});previous.setEnabled(tokenPage>0);paging.addView(previous,new LinearLayout.LayoutParams(0,dp(height),1));TextView number=new TextView(c);number.setText(c.getString(R.string.personal_page_number,String.valueOf(tokenPage+1),String.valueOf(Math.max(1,(tokens.size()+47)/48))));number.setGravity(Gravity.CENTER);number.setTextColor(c.getColor(R.color.muted));paging.addView(number,new LinearLayout.LayoutParams(dp(60),dp(height)));Button next=button("下一页",()->{if((tokenPage+1)*48<tokens.size()){tokenPage++;host.redraw();}});next.setEnabled((tokenPage+1)*48<tokens.size());paging.addView(next,new LinearLayout.LayoutParams(0,dp(height),1));panel.addView(paging);return panel;}
}
