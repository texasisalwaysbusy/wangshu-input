package org.wangshu.ime;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** User-initiated settings files. Capture/learning permission and content never transfer. */
public final class SettingsBackupActivity extends Activity {
    @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return AppPreferences.managed(name)?AppPreferences.open(this,name):super.getSharedPreferences(name,mode);}

    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private TextView status;
    private boolean busy;
    private int pickerRequest;
    private long generation;
    private AlertDialog previewDialog;
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(20),dp(20),dp(20),dp(24));panel.setBackgroundColor(getColor(R.color.paper));
        ScrollView scroll=new ScrollView(this);scroll.addView(panel);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=30)scroll.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        TextView title=new TextView(this);title.setText("本地设置备份与修复");title.setTextSize(26);title.setTextColor(getColor(R.color.ink));panel.addView(title);
        TextView explanation=new TextView(this);
        explanation.setText("备份键盘布局、字号、符号、模糊音、联想与剪贴板期限。自定义符号列表可能含你手动填写的文字，会随文件明文可逆导出。不包含键入历史、录音、模型、词频记录或剪贴板历史；自动学习和剪贴板记录开关不随文件开启。词库与常用语请在对应页面另行备份。文件提供方若是云盘，所选文件将由该提供方保存。\n\n正常还原保留本机记录与学习开关。设置暂停时，须另行确认修复：先在本机保存当前仍存在的故障文件及校验清单，再整组保存备份或默认值，并关闭记录及学习。系统读取设置前已经改变的文件无法追回。故障副本可能含旧配置中的私人文字，不会自动上传或删除。修复后本次进程仍暂停，请自行重启应用及输入法，再逐项核对；记录和学习如需启用，须分别重新确认。");
        explanation.setTextColor(getColor(R.color.ink));explanation.setPadding(0,dp(16),0,dp(16));panel.addView(explanation);
        status=new TextView(this);status.setText(AppPreferences.writable(this)?"尚未选择文件。还原不会删除现有词库、常用语或历史。":"本地设置已暂停。可选择备份，或预览默认设置修复。尚未修改设置。");status.setTextColor(getColor(R.color.muted));panel.addView(status);
        add(panel,"备份设置到所选文件",()->chooseFile(31));
        add(panel,"预览设置备份（还原或修复）",()->chooseFile(32));
        add(panel,"设置暂停时：预览默认设置修复",this::previewDefaults);
        add(panel,"返回",this::finish);ThemeBars.navigation(this,getWindow());
    }
    private void add(LinearLayout panel,String label,Runnable action){Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setMinHeight(dp(48));KeyboardSkin.key(this,button,false);button.setOnClickListener(view->action.run());panel.addView(button,new LinearLayout.LayoutParams(-1,-2));}
    private boolean live(){return !isFinishing()&&!isDestroyed();}
    private boolean available(){return live()&&!busy&&pickerRequest==0&&previewDialog==null;}
    private void failed(long operation,String message){if(operation!=generation||!live())return;busy=false;status.setText(message);new AlertDialog.Builder(this).setTitle("操作未完成").setMessage(message).setPositiveButton("知道了",null).show();}
    private void chooseFile(int request){
        if(!available())return;
        if(request==31&&!AppPreferences.writable(this)){status.setText("设置已暂停，不能导出安全默认值冒充现有配置。请先预览修复。");return;}
        pickerRequest=request;generation++;
        Intent intent=request==31?new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/tab-separated-values").putExtra(Intent.EXTRA_TITLE,"wangshu-settings-v1.tsv"):
            new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/tab-separated-values","text/plain","application/octet-stream"});
        try{startActivityForResult(intent,request);}catch(RuntimeException unavailable){pickerRequest=0;status.setText("无法打开文件选择器，尚未修改设置。");}
    }
    @Override protected void onActivityResult(int request,int result,Intent intent){
        super.onActivityResult(request,result,intent);
        // Recreated/old activities never reuse an unconfirmed picker or preview.
        if(request!=pickerRequest||pickerRequest==0||!live())return;
        pickerRequest=0;
        if(result!=RESULT_OK||intent==null||intent.getData()==null){status.setText("已取消选择，设置未修改。");return;}
        android.net.Uri uri=intent.getData();busy=true;long operation=++generation;
        status.setText(request==31?"正在写入所选设置文件…":"正在验证，尚未修改设置…");
        io.execute(()->{try{
            if(request==31){byte[] bytes=AppPreferences.snapshot(this);try(OutputStream stream=getContentResolver().openOutputStream(uri,"w")){if(stream==null)throw new IOException("无法打开所选目标");stream.write(bytes);stream.flush();}
                runOnUiThread(()->{if(operation==generation&&live()){busy=false;status.setText("设置备份已写入所选文件；词库与常用语需另行备份。");}});
            }else{
                byte[] bytes;try(InputStream stream=getContentResolver().openInputStream(uri)){bytes=SettingsBackup.read(stream);}
                preparePreview(bytes,operation);
            }
        }catch(Exception error){runOnUiThread(()->failed(operation,"文件操作未完成："+(error instanceof IOException?error.getMessage():"请检查文件权限或存储空间")));}});
    }
    private void previewDefaults(){
        if(!available())return;
        if(AppPreferences.writable(this)){status.setText("本地设置未暂停，无需故障修复。默认修复不会自动执行。");return;}
        busy=true;long operation=++generation;status.setText("正在核对故障状态，尚未修改设置…");
        io.execute(()->{try{preparePreview(SettingsBackup.encode(key->key.fallback),operation);}catch(Exception error){runOnUiThread(()->failed(operation,error instanceof IOException?error.getMessage():"无法预览修复，尚未修改设置。"));}});
    }
    private void preparePreview(byte[] bytes,long operation)throws IOException {
        Map<SettingsBackup.Key,Object> incoming=SettingsBackup.decode(bytes);
        AppPreferences.RecoveryPreview recovery=AppPreferences.writable(this)?null:AppPreferences.previewRecovery(this,bytes);
        byte[] before=recovery==null?AppPreferences.snapshot(this):null;
        runOnUiThread(()->{if(operation==generation&&live()){busy=false;preview(incoming,before,recovery,operation);}});
    }
    private String describe(SettingsBackup.Key key,Object value){
        if(key.name.equals("enterActionsEnabled"))return Boolean.TRUE.equals(value)?"允许回车执行应用发送等动作":"禁止回车发送等动作";
        if(key.name.equals("numberSymbols"))return "自定义符号/手动文字："+value.toString().replace("\n"," · ");
        if(key.name.equals("voiceRightAction"))return value.equals(1)?"只保留语音文字":"保留语音文字并换行";
        if(value instanceof Boolean)return (Boolean)value?"开":"关";return value.toString();
    }
    private void preview(Map<SettingsBackup.Key,Object> incoming,byte[] before,AppPreferences.RecoveryPreview recovery,long operation){
        StringBuilder details=new StringBuilder();int changes=0;
        // Use the frozen preview snapshot, not current preferences, for the old-value display.
        Map<SettingsBackup.Key,Object> previous;
        try{previous=recovery==null?SettingsBackup.decode(before):Collections.emptyMap();}catch(IOException invalid){failed(operation,"预览快照无效，设置未修改。");return;}
        for(Map.Entry<SettingsBackup.Key,Object> item:incoming.entrySet()){
            SettingsBackup.Key key=item.getKey();Object old=previous.get(key);
            if(recovery!=null||!item.getValue().equals(old)){changes++;details.append(key.group).append(" / ").append(key.name).append("\n").append(recovery!=null?"原值未确认":describe(key,old)).append(" → ").append(describe(key,item.getValue())).append("\n\n");}
        }
        status.setText("已验证，等待确认；设置尚未修改。");
        TextView text=new TextView(this);text.setTextIsSelectable(true);text.setTextColor(getColor(R.color.ink));text.setPadding(dp(20),dp(12),dp(20),dp(12));
        String note=recovery==null?"已验证 "+incoming.size()+" 项设置，"+changes+" 项不同。整组一次保存，下次重开键盘生效。\n\n":
            "本地设置暂停，无法可靠比较旧值。以下 "+incoming.size()+" 项将整组写入。先保存当前仍存在的 XML、备份、迁移及修复凭据的故障副本，任何证据保存失败均停止；不从旧配置重新导入。\n\n";
        String privacy=recovery==null?"自动学习和剪贴板记录开关不改变。":"本次修复强制关闭自动学习及剪贴板记录；旧历史与词频文件不删除、不导入。当前进程继续暂停，需自行重启应用及输入法后核对，再到对应页面分别确认开启。失败或结果不明不会自动重试，故障副本保留。";
        text.setText(getString(R.string.settings_backup_preview,note,details,privacy));
        ScrollView scroll=new ScrollView(this);scroll.addView(text);
        previewDialog=new AlertDialog.Builder(this).setTitle(recovery==null?"还原设置预览":"故障修复预览")
            .setView(scroll).setNegativeButton("取消",(dialog,which)->{if(operation==generation)status.setText("已取消，设置未修改。");})
            .setPositiveButton(recovery==null?"确认整组还原":"保留证据并确认修复",(dialog,which)->restore(incoming,before,recovery,operation))
            .setOnCancelListener(dialog->{if(operation==generation)status.setText("已取消，设置未修改。");}).create();
        previewDialog.setOnDismissListener(dialog->previewDialog=null);previewDialog.show();
    }
    private void restore(Map<SettingsBackup.Key,Object> incoming,byte[] before,AppPreferences.RecoveryPreview recovery,long operation){
        if(busy||!live()||operation!=generation)return;
        busy=true;status.setText(recovery==null?"正在整组保存设置…":"正在保留故障证据并修复；请等待结果…");
        io.execute(()->{try{
            File evidence;
            if(recovery==null){AppPreferences.restore(this,before,incoming);evidence=null;}else evidence=AppPreferences.recover(this,recovery);
            runOnUiThread(()->{if(operation==generation&&live()){busy=false;status.setText(evidence==null?
                "整组设置已保存并在本次进程读回一致。请重开键盘；设备重启后的持久性仍需实际核对。自动学习和剪贴板记录开关未改变。":
                "修复已保存并在本次进程读回一致。故障副本留在本应用私有目录："+evidence.getName()+"。本次进程继续暂停，请自行重启应用及输入法后核对；设备持久性仍需实际核对。自动学习及剪贴板记录已关闭，须分别重新确认开启。");}});
        }catch(Exception error){runOnUiThread(()->failed(operation,error instanceof IOException?error.getMessage():"保存结果未确认，请保留现场并核对；未自动重试。"));}});
    }
    @Override protected void onDestroy(){generation++;if(previewDialog!=null)previewDialog.dismiss();io.shutdown();super.onDestroy();}
}
