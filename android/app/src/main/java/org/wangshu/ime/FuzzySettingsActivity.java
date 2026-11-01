package org.wangshu.ime;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.*;

/** Candidate production Activity. Root must register/launch it and connect enabledPairs to lookup. */
public final class FuzzySettingsActivity extends Activity {
    public static final String PREFERENCES="fuzzy-pinyin-v1";
    private final EnumMap<FuzzyPinyin.Pair,Switch> switches=new EnumMap<>(FuzzyPinyin.Pair.class);
    private SharedPreferences preferences;private TextView status,preview;private EditText sample;private boolean restoring;
    public static Set<FuzzyPinyin.Pair> enabledPairs(Context context){SharedPreferences values=context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);return FuzzyPinyin.enabledFrom(key->{try{return values.getBoolean(key,false);}catch(ClassCastException wrongType){return false;}});}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(getColor(R.color.ink));view.setPadding(0,dp(6),0,dp(6));return view;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);preferences=getSharedPreferences(PREFERENCES,MODE_PRIVATE);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(16),dp(20),dp(24));content.setBackgroundColor(getColor(R.color.paper));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(content);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=30)scroll.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets keyboard=insets.getInsets(WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard.bottom));return insets;});
        content.addView(text("模糊拼音",26));content.addView(text("只用于中文全拼候选；精确拼音始终优先。默认全部关闭，按需开启。不会改写英文直输、密码或数学命令。",15));
        status=text("开关只保存在本机，不记录测试文字。",14);content.addView(status);
        String[] examples={"zan ↔ zhan","can ↔ chan","san ↔ shan","nan ↔ lan","fan ↔ han","ban ↔ bang","ben ↔ beng","bin ↔ bing"};
        Set<FuzzyPinyin.Pair> enabled=enabledPairs(this);int index=0;
        for(FuzzyPinyin.Pair pair:FuzzyPinyin.Pair.values()){
            Switch toggle=new Switch(this);toggle.setText(pair.label+"\n"+examples[index++]);toggle.setTextColor(getColor(R.color.ink));toggle.setTextSize(17);toggle.setPadding(0,dp(12),0,dp(12));toggle.setMinHeight(dp(64));toggle.setContentDescription("模糊拼音 "+pair.label);toggle.setChecked(enabled.contains(pair));content.addView(toggle,new LinearLayout.LayoutParams(-1,-2));switches.put(pair,toggle);
            toggle.setOnCheckedChangeListener((button,checked)->{
                if(restoring)return;boolean saved;
                try{saved=preferences.edit().putBoolean(pair.preferenceKey,checked).commit();}catch(RuntimeException unavailable){saved=false;}
                if(!saved){reloadSwitches();status.setText("未能确认保存。当前显示本次会话状态，重启后可能恢复；请检查可用空间。");}
                else status.setText("已在本机保存；精确拼音候选保持优先。");updatePreview();
            });
        }
        Button reset=new Button(this);reset.setText("全部关闭");reset.setAllCaps(false);reset.setOnClickListener(view->{SharedPreferences.Editor edit=preferences.edit();for(FuzzyPinyin.Pair pair:FuzzyPinyin.Pair.values())edit.putBoolean(pair.preferenceKey,false);boolean saved;try{saved=edit.commit();}catch(RuntimeException unavailable){saved=false;}reloadSwitches();status.setText(saved?"已全部关闭，恢复精确拼音。":"未能确认关闭设置已保存；界面显示当前会话状态，重启后可能恢复。");updatePreview();});content.addView(reset,new LinearLayout.LayoutParams(-1,dp(48)));
        content.addView(text("测试拼音（仅预览，不会输入到其他应用）",17));sample=new EditText(this);sample.setSingleLine(true);sample.setHint("例如 zhan、nan、bin");sample.setContentDescription("模糊拼音本地测试");sample.setSaveEnabled(false);sample.setFilters(new InputFilter[]{new InputFilter.LengthFilter(FuzzyPinyin.MAX_KEY_LENGTH)});sample.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);sample.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|EditorInfo.IME_ACTION_DONE);content.addView(sample,new LinearLayout.LayoutParams(-1,-2));
        preview=text("",16);preview.setContentDescription("模糊拼音变体预览");content.addView(preview);content.addView(text("只对可分段的常用全拼尝试少量变化，最多两处。不完整、歧义或较长拼音可能不展开；这不会改变原始输入。",14));
        sample.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence value,int start,int count,int after){}public void onTextChanged(CharSequence value,int start,int before,int count){updatePreview();}public void afterTextChanged(Editable value){}});sample.setText("zhan");scroll.requestApplyInsets();
    }
    @Override protected void onResume(){super.onResume();if(preferences!=null){reloadSwitches();updatePreview();}}
    private void reloadSwitches(){Set<FuzzyPinyin.Pair> enabled=enabledPairs(this);restoring=true;for(Map.Entry<FuzzyPinyin.Pair,Switch> entry:switches.entrySet())entry.getValue().setChecked(enabled.contains(entry.getKey()));restoring=false;}
    private void updatePreview(){if(sample==null||preview==null)return;List<String> variants=FuzzyPinyin.expand(sample.getText().toString(),enabledPairs(this),FuzzyPinyin.DEFAULT_CAP);if(variants.isEmpty()){preview.setText("输入拼音后显示可尝试的变化。");return;}StringBuilder result=new StringBuilder("原始：").append(variants.get(0));if(variants.size()==1)result.append("\n当前没有额外变化。");else{result.append("\n可尝试：");for(int i=1;i<variants.size();i++){if(i>1)result.append("、");result.append(variants.get(i));}}preview.setText(result);}
}
