package org.wangshu.ime;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.*;
import android.view.*;
import android.widget.*;

/** Real controls for automatic selection learning and temporary recording pause. */
public final class LearningSettingsActivity extends Activity {
    private TextView status;private Button pause;private Switch enabled;private boolean restoring;
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(getColor(R.color.ink));view.setPadding(0,dp(8),0,dp(8));return view;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(16),dp(20),dp(24));content.setBackgroundColor(getColor(R.color.paper));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(content);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=30)scroll.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        content.addView(text("候选个性化",26));content.addView(text("默认关闭自动学习。开启后只在能核对候选已上屏时记录该拼音、所选词和次数；不保存整句、按键轨迹或录音，也不发送到网络。密码、编辑器要求不学习或临时暂停时不记录。",15));
        SharedPreferences preferences=getSharedPreferences(LearningStore.PREFERENCES,0);enabled=new Switch(this);enabled.setText("自动学习已确认上屏的候选");enabled.setContentDescription("自动学习已确认上屏的候选");enabled.setTextColor(getColor(R.color.ink));enabled.setMinHeight(dp(56));enabled.setChecked(false);enabled.setEnabled(false);content.addView(enabled);
        enabled.setOnCheckedChangeListener((view,value)->{if(restoring)return;if(!value)LearningStore.disableAutomatic();boolean saved=preferences.edit().putBoolean(LearningStore.ENABLED,value).commit();if(saved){if(value)LearningStore.enableAutomatic();}else{LearningStore.preferenceSaveFailed();Toast.makeText(this,"设置未保存；本次已暂停学习，重启后请核对",Toast.LENGTH_LONG).show();}refresh();});
        pause=new Button(this);pause.setAllCaps(false);pause.setMinHeight(dp(48));pause.setOnClickListener(view->{LearningStore.pause(!LearningStore.paused());refresh();});content.addView(pause);
        content.addView(text("临时暂停仅持续到输入法进程重新启动；已有个人排序仍可用。暂停或关闭时，尚未开始保存的自动记录会取消；已开始保存的此前候选可能仍完成。关闭自动学习不会删除已有记录。长按拼音候选可固定或忘记其个人权重，基础词典及手动词条保持独立。最多保留 256 个个人词，每个拼音最多前置 24 个；长期不用的自动权重会衰减。",15));
        status=text("",14);content.addView(status);Button back=new Button(this);back.setText("返回");back.setOnClickListener(view->finish());content.addView(back);scroll.requestApplyInsets();refresh();java.lang.ref.WeakReference<LearningSettingsActivity> owner=new java.lang.ref.WeakReference<>(this);LearningStore.prepare(this,()->{LearningSettingsActivity live=owner.get();if(live!=null)live.runOnUiThread(()->{LearningSettingsActivity current=owner.get();if(current!=null&&!current.isFinishing()&&!current.isDestroyed())current.refresh();});});
    }
    private void refresh(){if(status==null||pause==null||enabled==null)return;pause.setText(LearningStore.paused()?"结束临时不学习":"临时不学习");pause.setContentDescription(LearningStore.paused()?"结束临时不学习":"临时不学习");if(!LearningStore.ready()){enabled.setEnabled(false);status.setText("正在后台读取个人排序；普通词典和键盘仍可用。");return;}restoring=true;enabled.setChecked(LearningStore.enabled(this));restoring=false;enabled.setEnabled(true);try{LearningStore store=LearningStore.get(this);if(store.writeUncertain())enabled.setEnabled(LearningStore.enabled(this));status.setText(store.writeUncertain()?"个人排序保存结果未确认，已停止后续写入；请重启后检查。普通键盘仍可用。":"本机个人排序记录："+store.size()+" / "+CandidateLearning.MAX_ENTRIES+(LearningStore.paused()?" · 已暂停自动记录":""));}catch(java.io.IOException invalid){enabled.setEnabled(LearningStore.enabled(this));status.setText("已有个人排序文件无法读取，未覆盖。普通词典和键盘仍可用。");}}
}
