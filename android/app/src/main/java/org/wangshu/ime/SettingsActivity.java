package org.wangshu.ime;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.Manifest;
import android.content.pm.PackageManager;

public final class SettingsActivity extends Activity {
    @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return AppPreferences.managed(name)?AppPreferences.open(this,name):super.getSharedPreferences(name,mode);}

    private TextView status;private EditText testEditor;private volatile Thread voiceImport;
    // Main-thread-only requests survive a later non-shrinking layout, but not focus loss.
    static final class RevealRequest {
        private long generation;private boolean pending,imeVisible;
        void cancel(){pending=false;++generation;}
        void ime(boolean visible,boolean focused){
            if(!visible){cancel();}else if(!imeVisible&&focused){pending=true;++generation;}
            imeVisible=visible;
        }
        long layout(boolean shrank,boolean grew,boolean focused){
            long token=++generation;
            if(!focused||grew)pending=false;else if(shrank)pending=true;
            return pending?token:-1;
        }
        boolean current(long token){return pending&&token==generation;}
        void complete(long token){if(current(token))pending=false;}
    }
    private final RevealRequest trialReveal=new RevealRequest();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20),dp(20),dp(20),dp(20));panel.setBackgroundColor(getColor(R.color.paper));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(panel);
        // Insets must reduce the measured viewport, not only add ScrollView padding.
        // Platform ScrollView rectangle reveal uses getHeight(), including its padding.
        FrameLayout viewport=new FrameLayout(this);
        viewport.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            viewport.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard=insets.getInsets(android.view.WindowInsets.Type.ime());
                trialReveal.ime(insets.isVisible(android.view.WindowInsets.Type.ime()),testEditor!=null&&testEditor.hasFocus());
                v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard.bottom));
                return insets;
            });
        }
        scroll.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
            final long token=trialReveal.layout(bottom-top<oldBottom-oldTop,bottom-top>oldBottom-oldTop,
                    testEditor!=null&&testEditor.hasFocus());
            if(token<0)return;
            final EditText editor=testEditor;
            scroll.post(()->{
                if(!trialReveal.current(token))return;
                if(!scroll.isAttachedToWindow()||editor!=testEditor||!editor.hasFocus()||!editor.hasWindowFocus()){
                    trialReveal.cancel();return;
                }
                // A real later layout will reschedule pending work; never spin/repost here.
                if(viewport.isLayoutRequested()||scroll.isLayoutRequested()||editor.getWidth()<=0||editor.getHeight()<=0)return;
                trialReveal.complete(token); // Clear before a possibly reentrant ancestor scroll.
                editor.requestRectangleOnScreen(new android.graphics.Rect(0,0,editor.getWidth(),editor.getHeight()),true);
            });
        });
        setContentView(viewport);viewport.requestApplyInsets();
        TextView title=new TextView(this);title.setText(BuildConfig.BASIC_TYPING_PREVIEW?PreviewFeatures.LABEL:"望舒输入法");title.setTextSize(28);title.setTextColor(getColor(R.color.ink));title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));title.setPadding(0,0,0,dp(8));panel.addView(title);status=new TextView(this);status.setText("正在准备本地词典…");status.setTextColor(getColor(R.color.muted));status.setPadding(0,0,0,dp(12));panel.addView(status);
        if(!AppPreferences.writable(this)){TextView warning=new TextView(this);warning.setText("设置保存已暂停：本地配置完整性或迁移结果未确认。自动采集与学习保持关闭，原文件保留；本页设置写入及设置备份不会生效。暂勿清除应用数据。可到“本地设置备份与还原”预览修复：先保留故障副本，再由你确认使用备份或默认设置；不会自动开启记录或学习。普通输入及独立词库/常用语仍可使用。");warning.setTextColor(getColor(R.color.ink));warning.setPadding(0,dp(8),0,dp(16));panel.addView(warning);}
        add(panel,"1 · 打开系统输入法设置",()->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        add(panel,"2 · 选择望舒输入法",()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker());
        add(panel,"候选个性化",()->startActivity(new Intent(this,LearningSettingsActivity.class)));
        add(panel,"数字与光标数学联想",()->{android.content.SharedPreferences preferences=getSharedPreferences("associations",0);Switch toggle=new Switch(this);boolean current;try{current=preferences.getBoolean("math",true);}catch(ClassCastException invalid){current=false;}toggle.setChecked(current);toggle.setText("本地精确计算与赋值代入");toggle.setTextColor(getColor(R.color.ink));toggle.setPadding(dp(20),dp(12),dp(20),dp(12));new AlertDialog.Builder(this).setTitle("数字与光标数学联想").setMessage("算式以 = 结尾时显示可点选结果；支持精确分数、四则和受限小整数幂。赋值代入保留未知变量，不猜数值。计算在本机进行，不保存上下文；密码及要求不学习的输入框停用。它不代表通用中英文下一词预测。").setView(toggle).setPositiveButton("保存",(dialog,which)->{if(!preferences.edit().putBoolean("math",toggle.isChecked()).commit())Toast.makeText(this,"设置未完成保存，重启后请核对",Toast.LENGTH_LONG).show();}).setNegativeButton("取消",null).show();});
        add(panel,"常用语上下文续写",()->{android.content.SharedPreferences preferences=getSharedPreferences("associations",0);LinearLayout choices=new LinearLayout(this);choices.setOrientation(LinearLayout.VERTICAL);Switch chinese=new Switch(this),english=new Switch(this);boolean zh=false,en=false;try{zh=preferences.getBoolean("phraseChinese",false);en=preferences.getBoolean("phraseEnglish",false);}catch(ClassCastException invalid){zh=false;en=false;}chinese.setChecked(zh);english.setChecked(en);chinese.setText("中文常用语续写");english.setText("英文常用语补全与下一词");choices.addView(chinese);choices.addView(english);new AlertDialog.Builder(this).setTitle("常用语上下文续写").setMessage("仅使用你自己保存的常用语。本地匹配光标前文字；中文续写剩余短语，英文可补全已输入至少两个字母的词，或在空格后建议下一词。只追加候选后缀，不替换你已输入的内容；当前词全大写时继续大写。密码及要求不学习的输入框停用；不会上传或保存上下文。这不是通用语言预测。支持框末，以及后文有明确分隔边界的框中位置；保留光标后内容，不插入词内或重复后缀。普通文本框切到数字面板仍可显示；数字专用、密码、网址、邮箱等输入框不启用。英文仅识别英文字母和直撇号，网址或代码样式上下文停用。").setView(choices).setPositiveButton("保存",(dialog,which)->{if(!preferences.edit().putBoolean("phraseChinese",chinese.isChecked()).putBoolean("phraseEnglish",english.isChecked()).commit())Toast.makeText(this,"设置未完成保存，重启后请核对",Toast.LENGTH_LONG).show();}).setNegativeButton("取消",null).show();});
        add(panel,"本地例句下一词与整句",()->{android.content.SharedPreferences preferences=getSharedPreferences("associations",0);Switch toggle=new Switch(this);boolean enabled=false;try{enabled=preferences.getBoolean("builtinPredictions",false);}catch(ClassCastException invalid){}toggle.setChecked(enabled);toggle.setText("启用有限原创例句候选（默认关闭）");new AlertDialog.Builder(this).setTitle("本地例句下一词与整句").setMessage("只使用随应用发布的原创 CC0 例句和确定性词边界规则，不联网、不保存输入、不是通用语言模型。会同时显示可点选的下一词及整句后缀，常用语候选仍优先。只追加，不替换后文；支持有明确边界的光标位置和普通文本框的数字面板。密码、临时不学习、网址、邮箱及代码样式上下文停用。点击后可在原文字和光标仍一致时撤销本次续写；不确定结果不自动重试。此新增开关暂不随设置备份迁移。中文模式长按原有中英键可开始或结束组合内原文英段，键盘布局不变。").setView(toggle).setPositiveButton("保存",(dialog,which)->{if(!preferences.edit().putBoolean("builtinPredictions",toggle.isChecked()).commit())Toast.makeText(this,"设置未完成保存，请核对",Toast.LENGTH_LONG).show();}).setNegativeButton("取消",null).show();});
        add(panel,"模糊拼音设置",()->startActivity(new Intent(this,FuzzySettingsActivity.class)));
        add(panel,"剪贴板历史设置",()->startActivity(new Intent(this,ClipboardSettingsActivity.class)));
        add(panel,"个人词库与常用语",()->startActivity(new Intent(this,PersonalActivity.class)));
        add(panel,"本地设置备份与还原",()->startActivity(new Intent(this,SettingsBackupActivity.class)));
        add(panel,"数字面板常用符号",()->{EditText input=new EditText(this);input.setContentDescription("每行一个常用符号");input.setMinLines(6);input.setMaxLines(12);input.setGravity(android.view.Gravity.TOP);input.setText(getSharedPreferences("keyboard",0).getString("numberSymbols",MathSidebar.DEFAULTS));ScrollView list=new ScrollView(this);list.setPadding(dp(20),0,dp(20),0);list.addView(input);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("每行一个常用符号 · 本地保存").setView(list).setPositiveButton("保存",null).setNeutralButton("恢复默认",(d,w)->getSharedPreferences("keyboard",0).edit().remove("numberSymbols").apply()).setNegativeButton("取消",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{String[] values=MathSidebar.parse(input.getText().toString());if(!getSharedPreferences("keyboard",0).edit().putString("numberSymbols",android.text.TextUtils.join("\n",values)).commit()){input.setError("保存未完成，请检查存储空间后重试");return;}dialog.dismiss();}catch(IllegalArgumentException e){input.setError(e.getMessage());}}));dialog.show();});
        add(panel,"键盘内拼音提示位置",()->{
            SharedPreferences preferences=getSharedPreferences("keyboard",0);
            int current=PinyinDisplayPosition.read(()->preferences.getInt("pinyinDisplayPosition",PinyinDisplayPosition.ABOVE));
            final int[] selected={current};String[] choices={"候选条上方（默认）", "候选条下方"};
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("键盘内拼音提示位置 · 下次重建生效")
                .setSingleChoiceItems(choices,current,(whichDialog,which)->selected[0]=PinyinDisplayPosition.normalize(which))
                .setPositiveButton("保存",null).setNegativeButton("取消",null).create();
            dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
                if(!preferences.edit().putInt("pinyinDisplayPosition",selected[0]).commit()){Toast.makeText(this,"保存未确认，设置可能已在本次会话生效；请重新打开核对",Toast.LENGTH_LONG).show();return;}dialog.dismiss();
            }));
            dialog.show();
        });
        add(panel,"回车键执行应用动作",()->{
            SharedPreferences preferences=getSharedPreferences("keyboard",0);
            boolean current=EnterPolicy.read(()->preferences.getBoolean("enterActionsEnabled",false));
            final int[] selected={current?1:0};String[] choices={"关闭（默认）：允许下一项/上一项；多行换行，其他单行使用应用按钮", "开启：允许应用执行发送等回车动作"};
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("回车键执行应用动作")
                .setSingleChoiceItems(choices,selected[0],(whichDialog,which)->selected[0]=which==1?1:0)
                .setPositiveButton("保存",null).setNegativeButton("取消",null).create();
            dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
                if(!preferences.edit().putBoolean("enterActionsEnabled",selected[0]==1).commit()){Toast.makeText(this,"保存未确认，设置可能已在本次会话生效；请重新打开核对",Toast.LENGTH_LONG).show();return;}dialog.dismiss();
            }));
            dialog.show();
        });
        add(panel,"底部中英文切换键位置",()->{
            SharedPreferences preferences=getSharedPreferences("keyboard",0);
            int current=LanguageKeyPosition.read(()->preferences.getInt("languageKeyPosition",LanguageKeyPosition.RIGHT));
            final int[] selected={current};String[] choices={"空格右侧（默认）","空格左侧"};
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("底部中英文切换键位置 · 下次重建生效")
                .setSingleChoiceItems(choices,current,(whichDialog,which)->selected[0]=LanguageKeyPosition.normalize(which))
                .setPositiveButton("保存",null).setNegativeButton("取消",null).create();
            dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
                if(!preferences.edit().putInt("languageKeyPosition",selected[0]).commit()){Toast.makeText(this,"保存未确认，设置可能已在本次会话生效；请重新打开核对",Toast.LENGTH_LONG).show();return;}dialog.dismiss();
            }));
            dialog.show();
        });
        add(panel,"候选文字大小",()->{
            SharedPreferences preferences=getSharedPreferences("keyboard",0);
            LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),dp(8));
            TextView preview=new TextView(this);preview.setText(R.string.candidate_font_preview);preview.setTextColor(getColor(R.color.ink));preview.setContentDescription("候选文字大小预览");
            TextView description=new TextView(this);SeekBar slider=new SeekBar(this);slider.setMax(CandidateTextSize.MAX-CandidateTextSize.MIN);
            slider.setProgress(CandidateTextSize.toProgress(CandidateTextSize.read(()->preferences.getInt("candidateFontDelta",CandidateTextSize.DEFAULT))));slider.setContentDescription("调整候选文字大小");
            Runnable update=()->{int delta=CandidateTextSize.fromProgress(slider.getProgress());description.setText(CandidateTextSize.describe(delta));preview.setTextSize(CandidateTextSize.sizeSp(getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE,delta));};
            update.run();box.addView(description);box.addView(preview);box.addView(slider);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int n,boolean user){update.run();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("候选文字大小").setMessage("滑动查看预览；保存后键盘下次更新候选时生效。未点保存时取消不会修改设置。")
                .setView(box).setPositiveButton("保存",null).setNeutralButton("恢复默认",null).setNegativeButton("取消",null).create();
            dialog.setOnShowListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{int delta=CandidateTextSize.fromProgress(slider.getProgress());if(!preferences.edit().putInt("candidateFontDelta",delta).commit()){Toast.makeText(this,"保存未确认，设置可能已在本次会话生效；请重新打开核对",Toast.LENGTH_LONG).show();return;}dialog.dismiss();});dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(w->slider.setProgress(CandidateTextSize.toProgress(CandidateTextSize.DEFAULT)));});
            dialog.show();
        });
        add(panel,"光标移动灵敏度",()->{LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),dp(8));TextView value=new TextView(this);SeekBar slider=new SeekBar(this);slider.setMax(40);slider.setProgress(getSharedPreferences("keyboard",0).getInt("cursorStepDp",16)-8);value.setText(getString(R.string.cursor_step_description,String.valueOf(slider.getProgress()+8)));box.addView(value);box.addView(slider);slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int n,boolean user){value.setText(getString(R.string.cursor_step_description,String.valueOf(n+8)));}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});new AlertDialog.Builder(this).setTitle("按住光标按钮后左右拖动").setView(box).setPositiveButton("保存",(d,w)->getSharedPreferences("keyboard",0).edit().putInt("cursorStepDp",slider.getProgress()+8).apply()).setNegativeButton("取消",null).show();});
        if(BuildConfig.BASIC_TYPING_PREVIEW){add(panel,"语音 / 语音包：此预览不包含",()->new AlertDialog.Builder(this).setTitle(PreviewFeatures.LABEL).setMessage(PreviewFeatures.VOICE_UNAVAILABLE).setPositiveButton("知道了",null).show());}else{
        add(panel,"按住语音右侧动作",this::showVoiceRightActionSettings);
        add(panel,"本地语音权限",()->{if(VoiceModels.available(this)||(Build.VERSION.SDK_INT>=31&&android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this))){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);else Toast.makeText(this,"已允许麦克风；仅在您点击语音键时识别",Toast.LENGTH_LONG).show();}else Toast.makeText(this,"当前系统没有可用的设备端识别服务，键盘输入仍可使用",Toast.LENGTH_LONG).show();});
        add(panel,"导入 / 修复离线语音包（约 200 MB）",()->{if(voiceImport!=null&&voiceImport.isAlive()){Toast.makeText(this,"正在导入，可先取消当前任务",Toast.LENGTH_LONG).show();return;}if(VoiceModels.available(this)){new AlertDialog.Builder(this).setTitle("重新校验离线模型").setMessage("可重新导入同一语音包。旧副本会保留，需要额外约200 MB空间；选错文件或取消不会覆盖现有模型。").setPositiveButton("选择本地语音包",(d,w)->pickVoicePackage()).setNegativeButton("返回",null).show();}else pickVoicePackage();});
        add(panel,"取消当前语音包导入",()->{Thread task=voiceImport;if(task!=null&&task.isAlive()){task.interrupt();status.setText("正在取消导入，现有模型和键盘数据保留…");}else Toast.makeText(this,"当前没有正在导入的语音包",Toast.LENGTH_SHORT).show();});
        }
        add(panel,"使用方法与隐私",()->new AlertDialog.Builder(this).setTitle("真实系统键盘 · 默认离线").setMessage(BuildConfig.BASIC_TYPING_PREVIEW?PreviewFeatures.HELP:"输入 nihao 选“你好”；空格选首项，左右箭头翻页，退格改拼音。ü 使用 v。中/英切换，123 输入数字标点。按住空格进入本地语音，松手停止：滑到左侧先高亮，松手仅丢弃本次预览；右侧默认保留并换行，可在设置中改为只保留文字；滑回中心取消目标动作。系统输入法选择器仍可通过本页“2 · 选择望舒输入法”打开。\n\n数学面板直接插入符号；中文模式输入 \\alpha 再选 α。大小写数学别名区分，如 \\Gamma 和 \\gamma。支持非 BMP 数学字符。\n\n可导入附带的 Apache 2.0 中文/英文离线语音包，支持 Android 8+。按住空格仅使用已就绪的本地包与已允许的录音权限，不自动下载或打开权限界面；文字先留在键盘预览，松手后在输入位置未变化时插入。输入位置、选区或内容变化则保留结果供核对，不删除之前输入；有未结束的拼音时须先核对处理。当前不是边说边上屏或宿主文字流式撤回。\n\n独立“语音”按钮仍支持先核对再插入；未导入语音包时仅尝试 Android 12+ 设备端识别。录音仅在内存中，不保存，不转云；当前本地录音最长约30秒。数学语音会把“根号／大于等于”等说法转换为符号，请核对再使用。\n\n不记录按键或密码，不请求网络权限。系统启用任何输入法时的安全提示都应由您自行阅读和决定。词典来自 MDBG CC-CEDICT，经过拼音与权重转换，按 CC BY-SA 4.0 提供。Android 与 Windows 使用同一数据和核心规则。").setPositiveButton("知道了",null).show());
        add(panel,"开源词典与语音许可",()->{try{StringBuilder notices=new StringBuilder();String[] files=getAssets().list("");if(files!=null)for(String name:files)if(name.endsWith(".txt")&&(name.contains("LICENSE")||name.contains("NOTICE")||name.contains("Notices"))){notices.append("\n—— ").append(name).append(" ——\n").append(DataFiles.read(getAssets().open(name)));}String[] extras=getAssets().list("notices");if(extras!=null)for(String name:extras)notices.append("\n—— ").append(name).append(" ——\n").append(DataFiles.read(getAssets().open("notices/"+name)));TextView text=new TextView(this);text.setText(notices.toString());text.setTextIsSelectable(true);ScrollView licenseScroll=new ScrollView(this);licenseScroll.addView(text);new AlertDialog.Builder(this).setTitle("词典与本地语音 · 开源许可").setView(licenseScroll).setPositiveButton("关闭",null).show();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}});
        TextView hint=new TextView(this);hint.setTextColor(getColor(R.color.muted));hint.setPadding(0,dp(16),0,dp(8));hint.setText("系统键盘试打（仅用合成文字；状态可能恢复，启用学习可能保存所选词）：");panel.addView(hint);EditText test=new EditText(this);testEditor=test;test.setOnFocusChangeListener((v,focused)->{if(!focused)trialReveal.cancel();});test.setId(R.id.test_editor);test.setHint("在任何应用中也可选择此系统输入法");test.setMinLines(3);panel.addView(test);
        new Thread(()->{try{long n=DataFiles.initialize(this);runOnUiThread(()->status.setText(n>0?getString(R.string.local_dictionary_ready,String.valueOf(n)):"词典未加载，查看项目状态"));}catch(Exception|LinkageError e){runOnUiThread(()->status.setText(getString(R.string.local_dictionary_unavailable,e.getMessage())));}},"ime-data-load").start();
    }
    private void showVoiceRightActionSettings(){
        SharedPreferences preferences=getSharedPreferences(VoiceHoldSettings.PREFERENCES,0);
        int current=VoiceHoldSettings.read(()->preferences.getInt(VoiceHoldSettings.RIGHT_ACTION,VoiceHoldSettings.NEWLINE));
        // AlertDialog's message replaces its built-in choice list on Android.
        // Put the explanation and both radio rows in one scrollable custom view.
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView explanation=new TextView(this);
        explanation.setText("拖入右侧只高亮，松手才结束并执行所选动作。左侧仍仅丢弃本次语音预览；滑回中央不执行右侧动作。保存后下次打开键盘生效，不改变正在进行的语音。");
        explanation.setTextColor(getColor(R.color.ink));content.addView(explanation);
        RadioGroup choices=new RadioGroup(this);choices.setOrientation(LinearLayout.VERTICAL);
        RadioButton newline=new RadioButton(this),keepText=new RadioButton(this);
        newline.setId(android.view.View.generateViewId());keepText.setId(android.view.View.generateViewId());
        newline.setText("保留识别文字并换行（默认）");keepText.setText("保留识别文字，不换行");
        newline.setTextColor(getColor(R.color.ink));keepText.setTextColor(getColor(R.color.ink));
        newline.setMinHeight(dp(48));keepText.setMinHeight(dp(48));
        choices.addView(newline);choices.addView(keepText);
        choices.check(current==VoiceHoldSettings.KEEP_TEXT?keepText.getId():newline.getId());
        content.addView(choices);ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("按住语音右侧动作")
            .setView(scroll).setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
            int selected=choices.getCheckedRadioButtonId()==keepText.getId()?VoiceHoldSettings.KEEP_TEXT:VoiceHoldSettings.NEWLINE;
            if(!preferences.edit().putInt(VoiceHoldSettings.RIGHT_ACTION,selected).commit()){
                Toast.makeText(this,"保存未确认，设置可能已在本次会话生效；请重新打开核对",Toast.LENGTH_LONG).show();return;
            }
            dialog.dismiss();
        }));
        dialog.show();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(!hasFocus)trialReveal.cancel();}
    @Override protected void onResume(){super.onResume();ThemeBars.navigation(this,getWindow());}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void pickVoicePackage(){if(BuildConfig.BASIC_TYPING_PREVIEW){Toast.makeText(this,PreviewFeatures.VOICE_UNAVAILABLE,Toast.LENGTH_LONG).show();return;}Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/zip");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(BuildConfig.BASIC_TYPING_PREVIEW){Toast.makeText(this,PreviewFeatures.VOICE_UNAVAILABLE,Toast.LENGTH_LONG).show();return;}if(request!=10||result!=RESULT_OK||data==null||data.getData()==null)return;if(voiceImport!=null&&voiceImport.isAlive()){status.setText("已有语音包导入任务，请等待或取消后再选择");return;}status.setText("正在校验本地语音包，请稍候…");voiceImport=new Thread(()->{try{VoiceModels.install(this,getContentResolver().openInputStream(data.getData()));runOnUiThread(()->status.setText("离线语音包已校验 · 可在键盘中点击语音"));}catch(Exception e){runOnUiThread(()->status.setText(getString(R.string.voice_import_incomplete,e.getMessage())));}finally{voiceImport=null;}},"ime-voice-import");voiceImport.start();}
    private void add(LinearLayout panel,String label,Runnable task){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setMinHeight(dp(48));b.setPadding(dp(14),dp(10),dp(14),dp(10));KeyboardSkin.key(this,b,false);b.setOnClickListener(v->task.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(3),0,dp(3));panel.addView(b,lp);}
}
