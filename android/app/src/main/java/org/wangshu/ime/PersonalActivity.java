package org.wangshu.ime;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;

/** Local authored dictionary and phrases, with validated additive import preview. */
public final class PersonalActivity extends Activity {
    private String tab="words";
    private LinearLayout panel;
    private PersonalStore store;
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean busy;
    private TextView operationStatus;
    private String operationMessage="导入只在预览确认后写入；备份不包含剪贴板或录音。";
    @Override public void onCreate(Bundle saved){super.onCreate(saved);tab=saved!=null?saved.getString("tab","words"):getIntent().getBooleanExtra("phrases",false)?"phrases":"words";try{store=PersonalStore.get(this);show();}catch(IOException e){new AlertDialog.Builder(this).setTitle("个人数据暂不可读").setMessage(e instanceof PersonalStore.UnconfirmedSaveException?e.getMessage():"原文件保留，未重置。请先保留备份再排查存储问题。").setPositiveButton("返回",(d,w)->finish()).setOnCancelListener(d->finish()).show();}}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(getColor(R.color.ink));v.setPadding(0,dp(8),0,dp(8));return v;}
    private void button(LinearLayout target,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setMinHeight(dp(48));KeyboardSkin.key(this,b,false);b.setOnClickListener(v->action.run());target.addView(b,new LinearLayout.LayoutParams(-1,-2));}
    private void show(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);if(android.os.Build.VERSION.SDK_INT>=30)scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));return insets;});panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setFocusableInTouchMode(true);panel.setPadding(dp(18),dp(16),dp(18),dp(24));panel.setBackgroundColor(getColor(R.color.paper));scroll.addView(panel);setContentView(scroll);panel.requestFocus();
        panel.addView(label("你的词语，留在本机",24));panel.addView(label("只保存你主动添加或导入的词条。不记录输入历史、剪贴板或录音。",14));
        LinearLayout tabs=new LinearLayout(this);panel.addView(tabs);for(String type:new String[]{"words","phrases"}){Button b=new Button(this);b.setText(type.equals("words")?"个人词库":"常用语");KeyboardSkin.key(this,b,tab.equals(type));b.setOnClickListener(v->{tab=type;show();});tabs.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}
        operationStatus=label(operationMessage,12);panel.addView(operationStatus);
        button(panel,tab.equals("words")?"添加词条":"添加常用语",()->edit(-1,null));
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);panel.addView(list);
        try{JSONArray entries=store.snapshot().getJSONArray(tab);panel.addView(label("本地文件 · "+entries.length()+" 条 · JSON v1",12));if(entries.length()==0)list.addView(label(tab.equals("words")?"为专业术语添加全拼，之后可直接从候选栏选择。":"保存多行文字；点击键盘工具栏“剪贴板”，切换到“常用语”即可调用。",15));
            for(int i=0;i<entries.length();i++){final int index=i;JSONObject item=entries.getJSONObject(i);String text=item.getString("text"),prefix=tab.equals("words")?item.getString("pinyin"):item.optString("shortcut","");Button b=new Button(this);b.setAllCaps(false);b.setText((prefix.isEmpty()?"":prefix+"  ·  ")+text);b.setMaxLines(3);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setPadding(dp(12),dp(10),dp(12),dp(10));b.setMinHeight(dp(56));b.setContentDescription("编辑个人条目 "+(i+1));KeyboardSkin.key(this,b,false);b.setOnClickListener(v->edit(index,item));list.addView(b,new LinearLayout.LayoutParams(-1,-2));}
        }catch(Exception e){error(e instanceof PersonalStore.UnconfirmedSaveException?e.getMessage():"读取失败，文件保留");}
        button(panel,"从 JSON / TSV 文件合并导入",()->{if(!busy)startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/tab-separated-values","text/plain","application/octet-stream"}),21);});
        button(panel,"备份词库与常用语",()->{if(!busy)startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"wangshu-personal-v1.json"),22);});
        panel.addView(label("导入只合并，不替换已有数据。JSON v1 支持词库和常用语；TSV 仅支持拼音、词条两列（制表符分隔），可带表头，不支持权重或来源列。词条按 NFC 去重，不折叠数学字符；同拼音不同词条保留。上限：1 MiB、2000 词条、1000 常用语。本备份暂不包含设置。",12));button(panel,"返回",this::finish);ThemeBars.navigation(this,getWindow());
    }
    private void edit(int index,JSONObject item){
        if(busy){Toast.makeText(this,"文件操作进行中，请稍候",Toast.LENGTH_SHORT).show();return;}
        boolean words=tab.equals("words");String type=tab;LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(20),dp(8),dp(20),dp(8));
        EditText key=new EditText(this);key.setSingleLine(true);key.setHint(words?"全拼，例如 wangshu（ü 用 v）":"可选英文简称，例如 thanks");key.setContentDescription(words?"词条拼音":"常用语简称");key.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);fields.addView(key);
        EditText value=new EditText(this);value.setHint(words?"词条文字":"常用语内容（支持换行）");value.setContentDescription("个人条目内容");value.setMinLines(words?1:3);value.setMaxLines(8);value.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(words?0:android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE));fields.addView(value);
        if(item!=null){key.setText(item.optString(words?"pinyin":"shortcut",""));value.setText(item.optString("text",""));}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(index<0?(words?"添加词条":"添加常用语"):"编辑条目").setView(fields).setPositiveButton("保存",null).setNegativeButton("取消",null).setNeutralButton(index>=0?"删除":null,null).create();
        dialog.setOnShowListener(d->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{checkRevision(type,index,item);if(words)store.putWord(index,key.getText().toString(),value.getText().toString());else store.putPhrase(index,value.getText().toString(),key.getText().toString());((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(value.getWindowToken(),0);dialog.dismiss();show();}catch(IOException e){value.setError(e.getMessage());}});
            if(index>=0)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->new AlertDialog.Builder(this).setTitle("删除此条目？").setMessage("只删除这条个人数据，不影响内置词库。").setNegativeButton("取消",null).setPositiveButton("删除",(a,w)->{try{checkRevision(type,index,item);store.remove(type,index);dialog.dismiss();show();}catch(IOException e){error(e instanceof PersonalStore.UnconfirmedSaveException?e.getMessage():"删除未完成，请重新打开后检查");}}).show());});dialog.show();
    }
    private void checkRevision(String type,int index,JSONObject expected)throws IOException {if(index<0)return;try{org.json.JSONArray current=store.snapshot().getJSONArray(type);if(index>=current.length()||!current.getJSONObject(index).toString().equals(expected.toString()))throw new IOException("条目已经改变，请关闭后重新打开编辑");}catch(org.json.JSONException e){throw new IOException("条目暂不可读，未修改");}}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("tab",tab);super.onSaveInstanceState(out);}
    private void error(String message){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("未完成").setMessage(message).setPositiveButton("知道了",null).show();}
    private void status(String message){operationMessage=message;if(operationStatus!=null)operationStatus.setText(message);}
    private byte[] importBytes(android.net.Uri uri)throws IOException {
        try(InputStream in=getContentResolver().openInputStream(uri)){return PersonalStore.read(in);}
        catch(IOException e){if("文件超过 1 MiB 限制".equals(e.getMessage()))throw e;throw new IOException("无法读取所选文件，请检查文件权限后重新选择",e);}
    }
    private void importPreview(PersonalStore.ImportData parsed,JSONObject merged)throws JSONException {
        int words=merged.getJSONArray("words").length(),phrases=merged.getJSONArray("phrases").length();busy=false;status("已验证 "+parsed.format+" 文件，等待确认；尚未写入。");
        AlertDialog preview=new AlertDialog.Builder(this).setTitle("合并导入预览").setMessage("已验证 "+parsed.format+" 格式。合并后将有 "+words+" 个词条、"+phrases+" 条常用语。已有顺序和同音的不同词条保留，相同记录去重。尚未写入；确认时会与最新本机数据再次合并。").setNegativeButton("取消",(d,w)->status("已取消导入，本机数据未修改。")).setPositiveButton("确认合并",(d,w)->confirmMerge(parsed.data)).create();
        preview.setOnCancelListener(d->status("已取消导入，本机数据未修改。"));preview.show();
    }
    private void confirmMerge(JSONObject incoming){
        if(busy||isFinishing()||isDestroyed())return;busy=true;status("正在合并保存，请稍候…");
        io.execute(()->{try{store.merge(incoming);runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed())return;status("合并已完成，词库与常用语已保存在本机。");show();});}
            catch(IOException e){runOnUiThread(()->{busy=false;status(e instanceof PersonalStore.UnconfirmedSaveException?e.getMessage():"合并未完成，请检查本机存储。");error(e instanceof PersonalStore.UnconfirmedSaveException?e.getMessage():"未能完成合并，请检查容量或本机存储空间。");});}});
    }
    @Override protected void onActivityResult(int request,int result,Intent intent){super.onActivityResult(request,result,intent);if(result!=RESULT_OK||intent==null||intent.getData()==null||busy)return;if(request!=21&&request!=22)return;android.net.Uri uri=intent.getData();busy=true;status(request==21?"正在验证导入文件，尚未写入…":"正在写入所选备份文件…");
        io.execute(()->{try{
            if(request==21){PersonalStore.ImportData parsed=PersonalStore.importFile(importBytes(uri));JSONObject merged=store.previewMerge(parsed.data);runOnUiThread(()->{if(isFinishing()||isDestroyed()){busy=false;return;}try{importPreview(parsed,merged);}catch(JSONException e){busy=false;error("导入预览未完成，原数据保留。");}});}
            else {byte[] bytes=store.export();try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IOException("无法打开目标文件");out.write(bytes);out.flush();}runOnUiThread(()->{busy=false;if(!isFinishing()&&!isDestroyed()){status("备份已写入所选文件。");Toast.makeText(this,"备份已写入所选文件",Toast.LENGTH_LONG).show();}});}
        }catch(Exception e){runOnUiThread(()->{busy=false;status("文件操作未完成，个人数据未替换。");error(request==21&&e instanceof IOException?"导入未写入。\n"+e.getMessage():"文件操作未完成，个人词库未替换。请检查所选文件权限或本机存储空间。");});}});
    }
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
