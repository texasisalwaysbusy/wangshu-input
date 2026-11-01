package org.wangshu.ime;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;

/** Explicit consent and retention controls; no automatic enablement. */
public final class ClipboardSettingsActivity extends Activity {
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void error(){new AlertDialog.Builder(this).setTitle("操作未完成").setMessage("未能保存设置或历史文件，请检查本机存储空间。原文件不会重置。").setPositiveButton("知道了",null).show();}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);android.content.SharedPreferences preferences=getSharedPreferences("clipboard",0);LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(20),dp(20),dp(20),dp(24));panel.setBackgroundColor(getColor(R.color.paper));ScrollView scroll=new ScrollView(this);scroll.addView(panel);if(Build.VERSION.SDK_INT>=30)scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});setContentView(scroll);
        TextView title=new TextView(this);title.setText("剪贴板历史");title.setTextSize(26);title.setTextColor(getColor(R.color.ink));panel.addView(title);
        TextView explanation=new TextView(this);explanation.setText("默认关闭。开启后只在键盘显示时记录纯文本，打开面板也会读取当前纯文本。不会读取图片、文件或链接指向的内容。\n\n跳过系统敏感标记、密码输入框和禁止个性化学习的输入框；无法保证辨认所有未标记的秘密。关闭记录会保留已有历史，仍可手动清空。");explanation.setTextColor(getColor(R.color.muted));explanation.setPadding(0,dp(12),0,dp(16));panel.addView(explanation);
        Switch enabled=new Switch(this);enabled.setText("记录剪贴板历史");enabled.setContentDescription("记录剪贴板历史");enabled.setMinHeight(dp(56));enabled.setTextColor(getColor(R.color.ink));enabled.setChecked(preferences.getBoolean("enabled",false));panel.addView(enabled);
        enabled.setOnClickListener(v->{if(enabled.isChecked()){enabled.setChecked(false);new AlertDialog.Builder(this).setTitle("开启本机剪贴板历史？").setMessage("普通文本可能包含敏感信息。只在键盘显示时记录，最多100条；你可随时关闭、删除或清空。不会上传或包含在词库备份中。").setNegativeButton("取消",null).setPositiveButton("开启",(d,w)->{boolean savedOk=preferences.edit().putBoolean("enabled",true).commit();enabled.setChecked(savedOk);if(!savedOk)error();}).show();}else if(!preferences.edit().putBoolean("enabled",false).commit()){enabled.setChecked(true);error();}});
        TextView duration=new TextView(this);duration.setText("未置顶内容的保存期限");duration.setTextColor(getColor(R.color.ink));duration.setPadding(0,dp(20),0,dp(8));panel.addView(duration);RadioGroup group=new RadioGroup(this);int chosen=preferences.getInt("days",7);for(int days:new int[]{1,7,30}){RadioButton option=new RadioButton(this);option.setId(days);option.setText(days==1?"24 小时":days+" 天");option.setTextColor(getColor(R.color.ink));option.setMinHeight(dp(48));group.addView(option);if(days==chosen)option.setChecked(true);}panel.addView(group);group.setOnCheckedChangeListener((g,id)->{if(!preferences.edit().putInt("days",id).commit())error();});
        TextView limits=new TextView(this);limits.setText("到期内容在下次使用键盘历史时清理。置顶条目保留到取消置顶或手动删除。达到100条时只淘汰最旧的未置顶内容；全部置顶则停止新增。编辑、置顶、删除、添加到常用语可在键盘面板中完成。");limits.setTextColor(getColor(R.color.muted));limits.setPadding(0,dp(12),0,dp(16));panel.addView(limits);
        Button clear=new Button(this);clear.setText("清空全部历史（含置顶）");KeyboardSkin.key(this,clear,false);clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清空全部剪贴板历史？").setMessage("包括置顶条目，不会清除系统当前剪贴板，也不影响常用语或词库。").setNegativeButton("取消",null).setPositiveButton("确认清空",(d,w)->{try{ClipboardStore.get(this).clear();Toast.makeText(this,"历史已清空",Toast.LENGTH_SHORT).show();}catch(java.io.IOException e){error();}}).show());panel.addView(clear,new LinearLayout.LayoutParams(-1,dp(56)));
        Button back=new Button(this);back.setText("返回");KeyboardSkin.key(this,back,false);back.setOnClickListener(v->finish());panel.addView(back,new LinearLayout.LayoutParams(-1,dp(48)));ThemeBars.navigation(this,getWindow());
    }
}
