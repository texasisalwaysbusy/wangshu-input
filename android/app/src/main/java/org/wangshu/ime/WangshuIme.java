package org.wangshu.ime;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.*;
import android.speech.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.util.*;

/** OS-bound keyboard, not an overlay or a text-entry web application. */
public final class WangshuIme extends InputMethodService implements PersonalPanel.Host {
    private LinearLayout root,keys,candidates,voiceReviewPanel,toolbar,candidateLine,mathSuggestionBar,phraseSuggestionBar;
    private MathAssociation mathAssociation;
    private PersonalPhraseAssociation phraseAssociation;
    private TextView composition,expandedSummary;
    private HorizontalScrollView candidateScroll;
    private final StringBuilder buffer=new StringBuilder();
    private boolean english=false,password=false,math=false,numbers=false,mathVoice=false;
    private boolean compositionWriteWarned,pendingCommitInFlight;
    private final EditorActions editorActions=new EditorActions(this);
    private boolean editing=false,phrasesOpen=false;
    private PersonalPanel personalPanel;
    private ClipboardManager clipboardManager;private boolean clipboardListening,suppressClipboardUntilChange;private String lastClipboardText;
    private ClipboardManager.OnPrimaryClipChangedListener clipboardListener;
    private Object clipboardRegistration;
    private boolean clipboardUnregisterUncertain;
    private final SharedPreferences.OnSharedPreferenceChangeListener clipboardPreferences=(p,k)->{syncClipboard();if(phrasesOpen)rebuild();};
    private int caseMode=0; // lowercase -> next letter uppercase -> caps lock
    private int page=0;
    private String[] current=new String[0];
    private CandidatePages candidatePages;
    private String candidateInput;
    private String[] candidatePersonal;
    private Set<FuzzyPinyin.Pair> candidateRules;
    private int candidateSession=-1,candidateModes=-1;
    private boolean currentPageFuzzy,shownFuzzy,shownDictionaryReady;
    private long candidateLearningRevision=-1,shownLearningRevision=-1;
    private String[] candidateLearned;
    private boolean learningWriteWarned,learningOwnerDestroyed;
    private void learningDone(LearningStore.Outcome outcome,int session,boolean manual){
        if(outcome==LearningStore.Outcome.CANCELLED)return;
        java.lang.ref.WeakReference<WangshuIme> owner=new java.lang.ref.WeakReference<>(this);
        keyHandler.post(()->{WangshuIme live=owner.get();if(live==null||live.learningOwnerDestroyed)return;
            if(session==live.inputSession){if(outcome==LearningStore.Outcome.FAILED||outcome==LearningStore.Outcome.REJECTED||outcome==LearningStore.Outcome.CAPACITY_BLOCKED)live.showLearningNotice();if(manual&&outcome==LearningStore.Outcome.CHANGED)live.toast("个人排序已确认保存");else if(manual&&outcome==LearningStore.Outcome.UNCHANGED)live.toast("个人排序已是所选状态");live.clearCandidatePages();live.refresh();}
        });
    }
    private void showLearningNotice(){LearningStore.Outcome notice=LearningStore.takeNotice();if(notice==null)return;learningWriteWarned=true;toast(notice==LearningStore.Outcome.REJECTED?"个人排序任务已满，文字不会重试":notice==LearningStore.Outcome.CAPACITY_BLOCKED?"固定候选已达容量上限，请先取消固定部分词":"个人排序未确认，文字不会重试；可在设置查看");}
    private LearningStore.Completion learningCompletion(int session,boolean manual){java.lang.ref.WeakReference<WangshuIme> owner=new java.lang.ref.WeakReference<>(this);return outcome->{WangshuIme live=owner.get();if(live!=null)live.learningDone(outcome,session,manual);};}
    private long learningRevision(){if(privateInput())return -1;try{return LearningStore.get(this).revision();}catch(java.io.IOException unavailable){return -1;}}
    private String[] learnedCandidates(String key){if(privateInput())return new String[0];try{return LearningStore.get(this).candidates(key,System.currentTimeMillis());}catch(java.io.IOException|IllegalArgumentException unavailable){return new String[0];}}
    private long candidateRevision=-1,shownRevision=-1,shownGeneration;
    private String shownInput="";
    private int shownSession=-1,shownPage=-1,shownModes=-1;
    private Set<FuzzyPinyin.Pair> shownRules=Collections.emptySet();
    private int candidateModes(){return (english?1:0)|(password?2:0)|(math?4:0)|(numbers?8:0);}
    private long personalRevision(){try{return PersonalStore.get(this).revision();}catch(java.io.IOException unavailable){return -1;}}
    private void snapshotCandidateDisplay(){shownGeneration++;shownInput=buffer.toString();shownPage=page;shownSession=inputSession;shownModes=candidateModes();shownFuzzy=currentPageFuzzy;shownLearningRevision=candidatePages==null?learningRevision():candidateLearningRevision;shownRevision=candidateRevision;shownRules=candidateRules!=null?candidateRules:FuzzySettingsActivity.enabledPairs(this);}
    private Runnable displayedCandidate(int index){long generation=shownGeneration;return ()->chooseBound(index,generation);}
    private SpeechRecognizer recognizer;
    private OfflineVoice.Task offlineTask;
    private String voiceReview=null,voiceState="";
    private ImageButton voiceButton,hideButton;
    private Button candidateToggle;
    private boolean expandedCandidates=false;
    private int inputSession=0;
    private final Handler keyHandler=new Handler(Looper.getMainLooper());
    private BackspaceGesture backspaceGesture;
    private BeforeCaretEditor beforeCaret;
    private BeforeCaretEditor.Preview prefixPreview;
    private TextView deleteHint;
    private View deleteKey;
    private VoiceHoldController voiceHold;
    private VoiceHoldTouchSurface inputSurface;
    private VoiceHoldOverlay voiceOverlay;
    private HeldVoice heldVoice;
    private int reviewSession=-1;
    private InputConnection reviewConnection;
    private String voiceReviewReason="";
    private boolean reviewCanInsert=true;
    private String symbolCategory="希腊", emojiCategory="笑脸";
    private long emojiViewGeneration=0, emojiSelectionRevision=0;
    private final EmojiScrollMemory emojiScrollMemory=new EmojiScrollMemory();
    private HorizontalScrollView emojiOuterScroll,emojiTabsScroll;private ScrollView emojiItemsScroll;private EmojiScrollMemory.Offsets emojiPendingOffsets;
    private long emojiScrollSession;private String emojiScrollSymbol="",emojiScrollCategory="";
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private boolean compact(){return getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;}
    private int keyHeight(){return compact()?30:48;}
    @Override public boolean onEvaluateFullscreenMode(){return false;}
    @Override public void onConfigureWindow(Window window,boolean fullscreen,boolean candidatesOnly){super.onConfigureWindow(window,false,candidatesOnly);window.setLayout(-1,-2);}
    @Override public void onCreate(){super.onCreate();learningOwnerDestroyed=false;java.lang.ref.WeakReference<WangshuIme> learningOwner=new java.lang.ref.WeakReference<>(this);LearningStore.prepare(this,()->{WangshuIme live=learningOwner.get();if(live!=null)live.keyHandler.post(()->{WangshuIme current=learningOwner.get();if(current!=null&&!current.learningOwnerDestroyed){current.clearCandidatePages();current.refresh();}});});createVoiceHold();createBackspace();createMathAssociation();createPhraseAssociation();personalPanel=new PersonalPanel(this);clipboardManager=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);getSharedPreferences("clipboard",0).registerOnSharedPreferenceChangeListener(clipboardPreferences);new Thread(()->{try{DataFiles.initialize(this);new Handler(Looper.getMainLooper()).post(this::rebuild);}catch(Exception|LinkageError e){new Handler(Looper.getMainLooper()).post(()->{rebuild();toast("词典未加载，英文键仍可用；请打开设置查看状态");});}},"ime-data-load").start();}
    @Override public View onCreateInputView(){
        if(voiceHold!=null)voiceHold.cancel(VoiceHoldController.CancelReason.WINDOW_CHANGED);
        cancelBackspace();if(mathAssociation!=null)mathAssociation.clear();if(beforeCaret!=null)beforeCaret.invalidate();
        inputSurface=new VoiceHoldTouchSurface(this);inputSurface.controller(voiceHold);inputSurface.backspaceController(backspaceGesture);
        root=column();root.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1,-2));root.setBackgroundColor(getColor(R.color.paper));root.setPadding(dp(4),dp(3),dp(4),dp(3));inputSurface.addView(root);voiceOverlay=null;
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.navigationBars());v.setPadding(dp(4)+bars.left,dp(3),dp(4)+bars.right,dp(3)+bars.bottom);return insets;});
        rebuild();root.requestApplyInsets();return inputSurface;
    }
    @Override public void onStartInput(EditorInfo info,boolean restarting){stopClipboard();super.onStartInput(info,restarting);if(phraseAssociation!=null)phraseAssociation.clear();LearningStore.cancelPendingAutomatic();inputSession++;emojiScrollMemory.reset(inputSession);compositionWriteWarned=false;editing=false;phrasesOpen=false;if(personalPanel!=null)personalPanel.resetSplit();editorActions.reset();cancelVoice();buffer.setLength(0);page=0;int type=info.inputType&InputType.TYPE_MASK_CLASS,variation=info.inputType&InputType.TYPE_MASK_VARIATION;password=(type==InputType.TYPE_CLASS_TEXT&&(variation==InputType.TYPE_TEXT_VARIATION_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))||(type==InputType.TYPE_CLASS_NUMBER&&variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD);numbers=type==InputType.TYPE_CLASS_NUMBER||type==InputType.TYPE_CLASS_PHONE;math=false;if(voiceHold!=null)voiceHold.bindInputSession(inputSession);if(backspaceGesture!=null)backspaceGesture.bind(inputSession);if(beforeCaret!=null)beforeCaret.invalidate();if(privateInput())suppressClipboardUntilChange=true;rebuild();syncClipboard();}
    @Override public void onFinishInput(){if(phraseAssociation!=null)phraseAssociation.clear();LearningStore.cancelPendingAutomatic();if(mathAssociation!=null)mathAssociation.clear();stopClipboard();if(personalPanel!=null)personalPanel.resetSplit();inputSession++;emojiScrollMemory.reset(inputSession);if(voiceHold!=null)voiceHold.clearInputSession();if(backspaceGesture!=null)backspaceGesture.unbind();if(beforeCaret!=null)beforeCaret.invalidate();keyHandler.removeCallbacksAndMessages(null);cancelVoice();buffer.setLength(0);current=new String[0];super.onFinishInput();}
    @Override public void onWindowShown(){super.onWindowShown();clearCandidatePages();if(voiceHold!=null&&getCurrentInputConnection()!=null)voiceHold.bindInputSession(inputSession);if(backspaceGesture!=null&&getCurrentInputConnection()!=null)backspaceGesture.bind(inputSession);keyHandler.post(()->{showLearningNotice();syncClipboard();captureClipboard(false);if(mathAssociation!=null)mathAssociation.request();if(phraseAssociation!=null)phraseAssociation.request();});if(getWindow()!=null)ThemeBars.navigation(this,getWindow().getWindow());}
    @Override public void onWindowHidden(){if(phraseAssociation!=null)phraseAssociation.clear();LearningStore.cancelPendingAutomatic();if(mathAssociation!=null)mathAssociation.clear();stopClipboard();if(personalPanel!=null)personalPanel.resetSplit();inputSession++;if(voiceHold!=null)voiceHold.clearInputSession();if(backspaceGesture!=null)backspaceGesture.unbind();if(beforeCaret!=null)beforeCaret.invalidate();cancelVoice();super.onWindowHidden();}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)OfflineVoice.trimMemory();}
    @Override public void onDestroy(){if(phraseAssociation!=null)phraseAssociation.close();learningOwnerDestroyed=true;LearningStore.cancelPendingAutomatic();if(mathAssociation!=null)mathAssociation.close();stopClipboard();getSharedPreferences("clipboard",0).unregisterOnSharedPreferenceChangeListener(clipboardPreferences);if(voiceHold!=null)voiceHold.clearInputSession();if(backspaceGesture!=null)backspaceGesture.unbind();if(beforeCaret!=null)beforeCaret.invalidate();OfflineVoice.trimMemory();keyHandler.removeCallbacksAndMessages(null);cancelVoice();super.onDestroy();}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER);return l;}
    private boolean mathAssociationsEnabled(){try{return getSharedPreferences("associations",0).getBoolean("math",true);}catch(ClassCastException invalid){return false;}}
    private void createMathAssociation(){mathAssociation=new MathAssociation(new MathAssociation.Host(){
        public boolean allowed(){return !privateInput()&&buffer.length()==0&&!phrasesOpen&&!emojiPanelActive()&&!hasVoiceWork()&&isInputViewShown()&&mathAssociationsEnabled()&&(backspaceGesture==null||backspaceGesture.phase()==BackspaceGesture.Phase.IDLE)&&(voiceHold==null||voiceHold.state().phase==VoiceHoldController.Phase.IDLE);}
        public int session(){return inputSession;}
        public InputConnection connection(){return getCurrentInputConnection();}
        public SplitSelection.Snapshot read(InputConnection owner){return connectionSnapshot(owner);}
        public void show(MathAssociation.Display display){showMathSuggestion(display);}
        public boolean insert(String value){return commit(value);}
        public void unavailable(){toast("候选已过期或编辑器未确认，请核对当前位置；未自动重试");}
    });}
    private void showMathSuggestion(MathAssociation.Display display){
        if(mathSuggestionBar==null)return;mathSuggestionBar.removeAllViews();
        if(display==null||buffer.length()>0||privateInput()||phrasesOpen){mathSuggestionBar.setVisibility(View.GONE);return;}
        Button value=button(display.candidate.label+" · "+display.candidate.text,()->mathAssociation.accept(display));value.setPadding(dp(12),0,dp(12),0);value.setMinWidth(dp(120));value.setContentDescription("数学联想 "+display.candidate.label+" "+display.candidate.text);value.setTooltipText(display.candidate.text);HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.addView(value,new ViewGroup.LayoutParams(-2,dp(keyHeight())));mathSuggestionBar.addView(scroll,new LinearLayout.LayoutParams(-1,dp(keyHeight())));mathSuggestionBar.setVisibility(View.VISIBLE);
    }
    private boolean phraseAssociationsEnabled(){try{return getSharedPreferences("associations",0).getBoolean(english?"phraseEnglish":"phraseChinese",false);}catch(ClassCastException invalid){return false;}}
    private void createPhraseAssociation(){phraseAssociation=new PersonalPhraseAssociation(new PersonalPhraseAssociation.Host(){
        public boolean allowed(){EditorInfo info=getCurrentInputEditorInfo();return info!=null&&(info.inputType&InputType.TYPE_MASK_CLASS)==InputType.TYPE_CLASS_TEXT&&!privateInput()&&!math&&!numbers&&!editing&&buffer.length()==0&&!phrasesOpen&&!emojiPanelActive()&&!hasVoiceWork()&&isInputViewShown()&&phraseAssociationsEnabled()&&(backspaceGesture==null||backspaceGesture.phase()==BackspaceGesture.Phase.IDLE)&&(voiceHold==null||voiceHold.state().phase==VoiceHoldController.Phase.IDLE);}
        public int session(){return inputSession;}public InputConnection connection(){return getCurrentInputConnection();}
        public PersonalPhraseSuggestions.Language language(){return english?PersonalPhraseSuggestions.Language.ENGLISH:PersonalPhraseSuggestions.Language.CHINESE;}
        public SplitSelection.Snapshot read(InputConnection owner){return connectionSnapshot(owner);}
        public PersonalPhraseAssociation.PhraseData phrases()throws Exception{return PersonalStore.get(WangshuIme.this).associationPhrases();}
        public long revision(){try{return PersonalStore.get(WangshuIme.this).phraseRevision();}catch(java.io.IOException unavailable){return -1;}}
        public void show(PersonalPhraseAssociation.Display display){showPhraseSuggestion(display);}
        public boolean insert(String value){return commit(value);}public void unavailable(){toast("续写已过期或编辑器未确认，请核对；未自动重试");}
    });}
    private void showPhraseSuggestion(PersonalPhraseAssociation.Display display){
        if(phraseSuggestionBar==null)return;phraseSuggestionBar.removeAllViews();
        if(display==null||buffer.length()>0||privateInput()||phrasesOpen){phraseSuggestionBar.setVisibility(View.GONE);return;}
        Button value=button("常用语续写 · "+display.suffix,()->phraseAssociation.accept(display));value.setPadding(dp(12),0,dp(12),0);value.setMinWidth(dp(120));value.setContentDescription("常用语续写 "+display.suffix);HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.addView(value,new ViewGroup.LayoutParams(-2,dp(keyHeight())));phraseSuggestionBar.addView(scroll,new LinearLayout.LayoutParams(-1,dp(keyHeight())));phraseSuggestionBar.setVisibility(View.VISIBLE);
    }
    private void createBackspace(){
        beforeCaret=new BeforeCaretEditor(()->new BeforeCaretEditor.InputSession(getCurrentInputConnection(),inputSession,privateInput()||buffer.length()>0));
        backspaceGesture=new BackspaceGesture(new BackspaceGesture.Host(){
            public BackspaceGesture.DeleteResult deleteOne(long session){if(session!=inputSession)return new BackspaceGesture.DeleteResult(false,false);return backspaceStep();}
            public boolean deleteBeforeCaret(long session){
                if(session!=inputSession)return false;
                if(buffer.length()>0){toast("请先确认拼音，再删除光标前内容");return false;}
                BeforeCaretEditor.Preview expected=prefixPreview;if(expected==null){toast("无法安全确认当前输入位置，未批量删除");return false;}BeforeCaretEditor.Result result=beforeCaret.deleteBeforeCaret(expected);
                if(result.state==BeforeCaretEditor.State.APPLIED){editorActions.reset();rebuild();return true;}
                if(result.state==BeforeCaretEditor.State.NO_OP){toast("光标已经在开头");return true;}
                toast(result.state==BeforeCaretEditor.State.UNCERTAIN?"编辑器结果不明，请核对原文；未自动重试":"当前输入位置不支持安全批量删除，原文未主动重试");return false;
            }
            public void preview(boolean visible){if(!visible&&backspaceGesture.phase()==BackspaceGesture.Phase.REPEATING&&!beforeCaret.matchesPreview(prefixPreview)){prefixPreview=null;showDeleteHint(false);backspaceGesture.cancel();return;}prefixPreview=visible?beforeCaret.prepareDelete():null;showDeleteHint(visible);}
        },(delay,action)->{keyHandler.postDelayed(action,delay);return ()->keyHandler.removeCallbacks(action);},ViewConfiguration.getLongPressTimeout(),dp(64),dp(64),ViewConfiguration.get(this).getScaledTouchSlop());
    }
    private void cancelBackspace(){if(backspaceGesture!=null)backspaceGesture.cancel();prefixPreview=null;showDeleteHint(false);}
    private Button backspaceButton(){Button b=button("⌫",this::backspace);b.setContentDescription("退格；按住加速，上滑提示后松手删除光标前内容");deleteKey=b;if(inputSurface!=null)inputSurface.backspaceKey(b);return b;}
    private void addBackspace(LinearLayout row,float weight){row.addView(backspaceButton(),new LinearLayout.LayoutParams(0,dp(keyHeight()),weight));}
    private void showDeleteHint(boolean visible){
        if(!visible){if(deleteHint!=null&&deleteHint.getParent() instanceof ViewGroup)((ViewGroup)deleteHint.getParent()).removeView(deleteHint);deleteHint=null;return;}
        if(inputSurface==null)return;
        if(deleteHint==null){deleteHint=new TextView(this);deleteHint.setText("松手删除光标前内容 · 移回取消");deleteHint.setTextSize(14);deleteHint.setTextColor(Color.WHITE);deleteHint.setPadding(dp(12),dp(10),dp(12),dp(10));deleteHint.setMaxLines(2);deleteHint.setMaxWidth(Math.max(dp(160),inputSurface.getWidth()-dp(16)));deleteHint.setClickable(false);deleteHint.setFocusable(false);deleteHint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(Color.rgb(162,43,48));background.setCornerRadius(dp(12));deleteHint.setBackground(background);inputSurface.addView(deleteHint);}
        int top=dp(4);if(deleteKey!=null&&deleteKey.isShown()){android.graphics.Rect bounds=new android.graphics.Rect();deleteKey.getDrawingRect(bounds);try{inputSurface.offsetDescendantRectToMyCoords(deleteKey,bounds);top=Math.max(dp(4),bounds.top-dp(54));}catch(IllegalArgumentException detached){/* pending rebuild: keep hint within the attached surface */}}
        android.widget.FrameLayout.LayoutParams params=new android.widget.FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);params.topMargin=top;params.rightMargin=dp(8);deleteHint.setLayoutParams(params);deleteHint.bringToFront();
    }
    private void undoBeforeCaret(){
        cancelBackspace();BeforeCaretEditor.Result result=beforeCaret.undo();
        if(result.state!=BeforeCaretEditor.State.APPLIED)toast(result.state==BeforeCaretEditor.State.UNCERTAIN?"撤销结果不明，请核对原文；未自动重试":"文字、光标或输入会话已变化，未覆盖当前内容");rebuild();
    }
    private boolean functionLabel(String label){return java.util.Arrays.asList("↵","⌫","⌄","⌃","123","符号","ABC","中","英","密码·英","字母","数学","剪贴板","语音","数学语音","设置","完成","停止","返回").contains(label);}
    private Button button(String text,Runnable action){
        Button b=(text.equals("↵")||text.equals("⌫")||text.equals("⌄")||text.equals("⌃"))?new GlyphKey(this):new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(compact()?14:16);b.setMaxLines(1);b.setHorizontallyScrolling(false);b.setAutoSizeTextTypeUniformWithConfiguration(10,compact()?14:16,1,android.util.TypedValue.COMPLEX_UNIT_SP);b.setTextColor(getColor(R.color.ink));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);if(functionLabel(text))KeyboardSkin.function(this,b,false);else KeyboardSkin.key(this,b,false);b.setOnClickListener(v->{cancelBackspace();if(beforeCaret!=null)beforeCaret.invalidate();cancelHoldForOtherInput();action.run();});
        if(text.equals("空格")){b.setContentDescription("空格；按住本地语音，松手结束");if(inputSurface!=null)inputSurface.spaceKey(b);}
        return b;
    }
    private void add(LinearLayout r,String label,float weight,Runnable action){r.addView(button(label,action),new LinearLayout.LayoutParams(0,dp(keyHeight()),weight));}
    private void rebuild(){
        if(root==null)return;rememberEmojiScroll();emojiViewGeneration++;if(voiceHold!=null&&voiceHold.state().showVoiceLayer()){showVoiceHold(voiceHold.state());return;}voiceOverlay=null;if(inputSurface!=null){inputSurface.spaceKey(null);inputSurface.backspaceKey(null);}deleteKey=null;root.removeAllViews();toolbar=row();LinearLayout left=row(),right=row();toolbar.addView(left,new LinearLayout.LayoutParams(0,dp(48),1));ImageButton cursor=cursorButton();toolbar.addView(cursor,new LinearLayout.LayoutParams(dp(48),dp(48)));toolbar.addView(right,new LinearLayout.LayoutParams(0,dp(48),1));
        tool(left,ToolbarIcon.Kind.LANGUAGE,password?"密码·英":english?"英":"中","切换中文 / 英文输入",1,false,()->{if(!finishRaw())return;english=!english;math=false;numbers=false;rebuild();});
        tool(left,math?ToolbarIcon.Kind.LETTERS:ToolbarIcon.Kind.MATH,math?"字母":"数学",math?"返回字母键盘":"打开数学符号面板",1,math,()->{if(!finishRaw())return;math=!math;numbers=false;phrasesOpen=false;rebuild();});
        tool(left,ToolbarIcon.Kind.CLIPBOARD,"剪贴板与常用语","剪贴板与常用语",1,phrasesOpen,()->{if(!finishRaw())return;editing=false;phrasesOpen=!phrasesOpen;if(phrasesOpen){personalPanel.open();captureClipboard(true);}rebuild();});
        voiceButton=tool(right,offlineTask!=null||recognizer!=null?ToolbarIcon.Kind.STOP:ToolbarIcon.Kind.MICROPHONE,offlineTask!=null?"完成":recognizer!=null?"停止":math?"数学语音":"语音","本地语音输入；也可按住空格说话",1,offlineTask!=null||recognizer!=null,()->{if(password){toast("密码字段不启用语音");return;}startVoice();});voiceButton.setEnabled(!password);
        tool(right,ToolbarIcon.Kind.SETTINGS,"设置","打开输入法设置",1,false,()->startActivity(new Intent(this,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        hideButton=tool(right,ToolbarIcon.Kind.HIDE,"收起输入键盘","收起输入键盘",1,false,()->requestHideSelf(0));root.addView(toolbar);
        composition=new TextView(this);composition.setTextSize(compact()?12:14);composition.setSingleLine(true);composition.setEllipsize(android.text.TextUtils.TruncateAt.START);composition.setTextColor(getColor(R.color.ink));composition.setPadding(dp(8),0,dp(8),0);root.addView(composition);
        candidateLine=row();add(candidateLine,"‹",.7f,()->{if(page>0){page--;refresh();}});candidateScroll=new HorizontalScrollView(this);candidateScroll.setHorizontalScrollBarEnabled(false);candidates=row();candidateScroll.addView(candidates);candidateLine.addView(candidateScroll,new LinearLayout.LayoutParams(0,dp(keyHeight()),7));expandedSummary=new TextView(this);expandedSummary.setGravity(Gravity.CENTER);expandedSummary.setTextSize(compact()?12:14);expandedSummary.setTextColor(getColor(R.color.muted));candidateLine.addView(expandedSummary,new LinearLayout.LayoutParams(0,dp(keyHeight()),7));candidateToggle=button("⌄",()->{expandedCandidates=!expandedCandidates;rebuild();});candidateLine.addView(candidateToggle,new LinearLayout.LayoutParams(0,dp(keyHeight()),.9f));root.addView(candidateLine);mathSuggestionBar=row();mathSuggestionBar.setVisibility(View.GONE);root.addView(mathSuggestionBar,new LinearLayout.LayoutParams(-1,-2));phraseSuggestionBar=row();phraseSuggestionBar.setVisibility(View.GONE);root.addView(phraseSuggestionBar,new LinearLayout.LayoutParams(-1,-2));
        voiceReviewPanel=null;if(voiceReview!=null){voiceReviewPanel=column();root.addView(voiceReviewPanel);TextView transcript=new TextView(this);transcript.setText((voiceReviewReason.isEmpty()?"请核对：":voiceReviewReason+"\n")+voiceReview);transcript.setTextSize(16);transcript.setTextColor(getColor(R.color.ink));transcript.setTextIsSelectable(false);ScrollView voiceScroll=new ScrollView(this);voiceScroll.addView(transcript);voiceReviewPanel.addView(voiceScroll,new LinearLayout.LayoutParams(-1,dp(compact()?55:90)));LinearLayout review=row();Button insert=button("插入语音结果",this::insertVoiceReview);insert.setEnabled(reviewCanInsert);review.addView(insert,new LinearLayout.LayoutParams(0,dp(keyHeight()),1.6f));add(review,"复制结果",1.2f,this::copyVoiceReview);add(review,"取消",.8f,()->{cancelVoice();rebuild();});voiceReviewPanel.addView(review);}
        keys=column();root.addView(keys);
        if(phrasesOpen){phraseKeys();}else if(editing){editorKeys();}else if(!expandedCandidates||buffer.length()==0){if(math)mathKeys();else if(numbers)numberKeys();else letters();}refresh();
    }
    private ImageButton tool(LinearLayout parent,ToolbarIcon.Kind icon,String name,String help,float weight,boolean selected,Runnable action){
        ImageButton b=new ImageButton(this);b.setScaleType(ImageView.ScaleType.CENTER);b.setPadding(0,0,0,0);b.setMinimumWidth(0);b.setMinimumHeight(0);
        b.setContentDescription(name);b.setTooltipText(help);b.setSelected(selected);KeyboardSkin.tool(this,b,selected);setToolIcon(b,icon);
        b.setOnClickListener(v->{cancelBackspace();if(beforeCaret!=null)beforeCaret.invalidate();cancelHoldForOtherInput();action.run();});
        parent.addView(b,new LinearLayout.LayoutParams(0,dp(48),weight));return b;
    }
    private void setToolIcon(ImageButton b,ToolbarIcon.Kind icon){
        b.setImageDrawable(new ToolbarIcon(icon,getResources().getDisplayMetrics().density,getColor(R.color.ink)));
        b.setImageTintList(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{getColor(R.color.muted),getColor(R.color.ink)}));
    }
    private void voiceToolState(String name,ToolbarIcon.Kind icon){if(voiceButton!=null){voiceButton.setContentDescription(name);voiceButton.setTooltipText(name);setToolIcon(voiceButton,icon);}}
    private ImageButton cursorButton(){
        ImageButton b=new ImageButton(this);b.setImageDrawable(new CursorIcon(getResources().getDisplayMetrics().density,getColor(R.color.ink)));b.setScaleType(ImageView.ScaleType.CENTER);b.setPadding(0,0,0,0);b.setMinimumWidth(0);b.setMinimumHeight(0);b.setContentDescription("光标与文本编辑");b.setSelected(editing);b.setTooltipText("单击编辑面板；按住后左右拖动移动光标");KeyboardSkin.tool(this,b,editing);
        b.setOnClickListener(v->{if(!finishRaw())return;phrasesOpen=false;editing=!editing;editorActions.reset();rebuild();});
        b.setOnTouchListener(new View.OnTouchListener(){float origin,last;boolean held,moved;Runnable arm;int gestureSession;long gestureDownTime;
            public boolean onTouch(View v,MotionEvent e){if(e.getActionMasked()!=MotionEvent.ACTION_DOWN&&gestureSession!=inputSession){if(arm!=null)keyHandler.removeCallbacks(arm);b.setPressed(false);return true;}switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:origin=last=e.getX();held=false;moved=false;gestureSession=inputSession;gestureDownTime=e.getEventTime();int session=inputSession;b.setPressed(true);arm=()->{if(session==inputSession&&b.isPressed()){held=true;toast("左右拖动移动光标；松手结束");}};keyHandler.postDelayed(arm,android.view.ViewConfiguration.getLongPressTimeout());return true;
                case MotionEvent.ACTION_MOVE:held|=e.getEventTime()-gestureDownTime>=android.view.ViewConfiguration.getLongPressTimeout();if(held){float step=dp(getSharedPreferences("keyboard",0).getInt("cursorStepDp",16));while(Math.abs(e.getX()-last)>=step){if(!moved){if(!finishRaw())return true;moved=true;}int direction=e.getX()>last?1:-1;if(!editorActions.move(direction,false)){toast("此编辑器暂不支持精确移动");last=e.getX();break;}last+=direction*step;}}return true;
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:if(arm!=null)keyHandler.removeCallbacks(arm);b.setPressed(false);if(e.getActionMasked()==MotionEvent.ACTION_UP&&!held&&e.getEventTime()-gestureDownTime<android.view.ViewConfiguration.getLongPressTimeout()&&Math.abs(e.getX()-origin)<android.view.ViewConfiguration.get(WangshuIme.this).getScaledTouchSlop())b.performClick();return true;
            }return true;}
        });return b;
    }
    private void editorResult(boolean ok){if(!ok)toast("当前编辑器未执行此操作，原文保留");}
    private void editorKeys(){
        String[][] labels={{"Tab","复制","↑","粘贴","⌫"},{"开头","←","选择","→","Del"},{"末尾","全选","↓","剪切","返回"}};
        for(int line=0;line<3;line++){LinearLayout r=row();for(int cell=0;cell<5;cell++){final String label=labels[line][cell];Button b=button(label,()->{switch(label){
            case "Tab":commit("\t");break;
            case "复制":editorResult(!password&&editorActions.menu(android.R.id.copy));break;
            case "粘贴":editorResult(editorActions.menu(android.R.id.paste));break;
            case "剪切":editorResult(!password&&editorActions.menu(android.R.id.cut));break;
            case "全选":editorResult(editorActions.menu(android.R.id.selectAll));break;
            case "←":editorResult(editorActions.move(-1,false));break;case "→":editorResult(editorActions.move(1,false));break;
            case "开头":editorResult(editorActions.move(-1,true));break;case "末尾":editorResult(editorActions.move(1,true));break;
            case "↑":editorResult(editorActions.vertical(KeyEvent.KEYCODE_DPAD_UP));break;case "↓":editorResult(editorActions.vertical(KeyEvent.KEYCODE_DPAD_DOWN));break;
            case "⌫":backspace();break;case "Del":editorResult(editorActions.delete(true));break;
            case "选择":editorResult(editorActions.toggle());rebuild();break;
            case "返回":editing=false;editorActions.reset();rebuild();break;
        }});if(label.equals("⌫"))b=backspaceButton();if(password&&(label.equals("复制")||label.equals("剪切")))b.setEnabled(false);if(label.equals("选择")){b.setSelected(editorActions.selecting);b.setContentDescription(editorActions.selecting?"取消选择并收为光标":"开始选择文字");KeyboardSkin.key(this,b,editorActions.selecting);}r.addView(b,new LinearLayout.LayoutParams(0,dp(keyHeight()+4),1));}keys.addView(r);}
    }
    private void expandedKeys(){
        keys.removeAllViews();ScrollView scroll=new ScrollView(this);LinearLayout grid=column();
        int total=current.length/2;
        for(int start=0;start<total;start+=3){LinearLayout line=row();for(int cell=0;cell<3;cell++){
            final int index=start+cell;
            if(index<total){String value=current[index*2];Button b=button(value,displayedCandidate(index));bindCandidateOptions(b,index);b.setTooltipText(value);b.setContentDescription("展开候选 "+(index+1)+" "+value);line.addView(b,new LinearLayout.LayoutParams(0,dp(keyHeight()+4),1));}
            else line.addView(new Space(this),new LinearLayout.LayoutParams(0,dp(keyHeight()+4),1));
        }grid.addView(line);}
        scroll.addView(grid);keys.addView(scroll,new LinearLayout.LayoutParams(-1,dp(compact()?100:176)));
        LinearLayout navigation=row();Button previous=button("上一组",()->{if(page>0){page--;refresh();}});previous.setEnabled(page>0);navigation.addView(previous,new LinearLayout.LayoutParams(0,dp(keyHeight()),1));
        Button next=button("下一组",()->{if(DataFiles.ready&&lookup(buffer.toString(),page+1).length>0){page++;refresh();}});next.setEnabled(DataFiles.ready&&lookup(buffer.toString(),page+1).length>0);navigation.addView(next,new LinearLayout.LayoutParams(0,dp(keyHeight()),1));
        addBackspace(navigation,1);keys.addView(navigation);
    }
    private void addCaseKey(LinearLayout row,float weight){ImageButton b=new ImageButton(this,null,android.R.attr.buttonStyle);b.setPadding(0,0,0,0);b.setMinimumWidth(0);b.setMinimumHeight(0);b.setScaleType(ImageView.ScaleType.CENTER);KeyboardSkin.function(this,b,caseMode==2);b.setSelected(caseMode==2);b.setImageDrawable(new CaseIcon(caseMode,getResources().getDisplayMetrics().density,getColor(R.color.ink)));b.setOnClickListener(v->{caseMode=(caseMode+1)%3;rebuild();});b.setContentDescription(caseMode==0?"小写":caseMode==1?"下一字母大写":"大写锁定");row.addView(b,new LinearLayout.LayoutParams(0,dp(keyHeight()),weight));}
    private void letters(){for(String letters:new String[]{"qwertyuiop","asdfghjkl","zxcvbnm"}){LinearLayout r=row();boolean last=letters.equals("zxcvbnm");if(last)addCaseKey(r,1.5f);for(char c:letters.toCharArray()){String key=String.valueOf(caseMode!=0?Character.toUpperCase(c):c);add(r,key,1,()->{key(key);if(caseMode==1){caseMode=0;rebuild();}});}if(last)addBackspace(r,1.5f);keys.addView(r);}bottom();}
    private void numberKeys(){
        LinearLayout layout=row();LinearLayout sidebar=column();ScrollView symbols=new ScrollView(this);symbols.setContentDescription("数字面板常用数学符号，可上下滚动");LinearLayout list=column();for(String symbol:MathSidebar.load(this)){Button b=button(symbol,()->key(symbol));b.setContentDescription("数字面板符号 "+symbol);list.addView(b,new LinearLayout.LayoutParams(-1,dp(keyHeight())));}symbols.addView(list);sidebar.addView(symbols,new LinearLayout.LayoutParams(-1,dp(keyHeight()*3)));Button more=button("符号",()->{numbers=false;math=true;rebuild();});sidebar.addView(more,new LinearLayout.LayoutParams(-1,dp(keyHeight())));layout.addView(sidebar,new LinearLayout.LayoutParams(dp(48),-2));
        LinearLayout grid=column();for(String line:new String[]{"1 2 3","4 5 6","7 8 9"}){LinearLayout r=row();for(String key:line.split(" "))add(r,key,1,()->key(key));grid.addView(r);}LinearLayout last=row();add(last,"返回",1,()->{numbers=false;math=false;rebuild();});add(last,"0",1,()->key("0"));add(last,".",1,()->key("."));grid.addView(last);layout.addView(grid,new LinearLayout.LayoutParams(0,-2,3));
        LinearLayout actions=column();actions.addView(backspaceButton(),new LinearLayout.LayoutParams(-1,dp(keyHeight())));actions.addView(button("空格",this::space),new LinearLayout.LayoutParams(-1,dp(keyHeight())));Button enter=button("↵",this::enter);enter.setContentDescription("数字面板换行或编辑器动作");actions.addView(enter,new LinearLayout.LayoutParams(-1,dp(keyHeight()*2)));layout.addView(actions,new LinearLayout.LayoutParams(0,-2,1));keys.addView(layout);
    }
    private void rememberEmojiScroll(){
        if(emojiOuterScroll!=null){EmojiScrollMemory.Offsets pending=emojiPendingOffsets;emojiScrollMemory.save(emojiScrollSession,emojiScrollSymbol,emojiScrollCategory,pending==null?emojiOuterScroll.getScrollX():pending.outer,pending==null?(emojiTabsScroll==null?0:emojiTabsScroll.getScrollX()):pending.tabs,pending==null?(emojiItemsScroll==null?0:emojiItemsScroll.getScrollY()):pending.items);}
        emojiOuterScroll=null;emojiTabsScroll=null;emojiItemsScroll=null;emojiPendingOffsets=null;
    }
    private boolean emojiPanelActive(){return math&&(symbolCategory.equals("表情")||symbolCategory.equals("颜文字"))&&!phrasesOpen&&!editing&&(!expandedCandidates||buffer.length()==0);}
    private void mathKeys(){
        final long viewGeneration=emojiViewGeneration;final int viewSession=inputSession;
        HorizontalScrollView tabs=new HorizontalScrollView(this);LinearLayout categories=row();for(String category:new String[]{"希腊","运算","关系","集合","逻辑","箭头","上下标","分数","标点","表情","颜文字"}){Button b=button(category,()->{if(viewGeneration!=emojiViewGeneration||viewSession!=inputSession)return;if((category.equals("表情")||category.equals("颜文字"))&&!finishRaw())return;symbolCategory=category;rebuild();});categories.addView(b,new LinearLayout.LayoutParams(dp(65),dp(compact()?28:36)));}tabs.addView(categories);keys.addView(tabs);
        emojiOuterScroll=tabs;emojiScrollSession=inputSession;emojiScrollSymbol=symbolCategory;emojiScrollCategory=symbolCategory.equals("颜文字")?"颜文字":emojiCategory;
        if(emojiPanelActive()){
            final int session=inputSession;final long generation=emojiViewGeneration,selection=emojiSelectionRevision;final InputConnection editor=getCurrentInputConnection();
            EmojiSelection.Host guard=new EmojiSelection.Host(){
                public long session(){return inputSession;}public long generation(){return emojiViewGeneration;}public long selectionRevision(){return emojiSelectionRevision;}public Object connection(){return getCurrentInputConnection();}public boolean active(){return emojiPanelActive();}
                public boolean send(Object expected,String text){cancelBackspace();if(beforeCaret!=null)beforeCaret.invalidate();cancelHoldForOtherInput();if(hasVoiceWork())cancelVoice();if(session!=inputSession||generation!=emojiViewGeneration||selection!=emojiSelectionRevision||editor==null||expected!=editor||editor!=getCurrentInputConnection()||!emojiPanelActive()||!isInputViewShown())return false;return editor.commitText(text,1);}
            };
            EmojiSelection.Token token=EmojiSelection.capture(guard);EmojiCatalog.Kind kind=symbolCategory.equals("颜文字")?EmojiCatalog.Kind.KAOMOJI:EmojiCatalog.Kind.EMOJI;String selected=kind==EmojiCatalog.Kind.KAOMOJI?"颜文字":emojiCategory;
            keys.addView(EmojiPanel.build(this,kind,selected,compact(),new EmojiPanel.Host(){
                public void bind(ScrollView items,HorizontalScrollView innerTabs){emojiItemsScroll=items;emojiTabsScroll=innerTabs;}
                public void category(String name){if(!EmojiSelection.current(guard,token)){toast("输入位置已变化，请重新选择");return;}emojiCategory=name;rebuild();}
                public void choose(EmojiCatalog.Item item){if(item.kind!=kind||!EmojiCatalog.category(selected).contains(item))return;EmojiSelection.Outcome result=EmojiSelection.select(guard,token,item.text);if(result==EmojiSelection.Outcome.STALE)toast("输入位置已变化，请重新选择");else if(result==EmojiSelection.Outcome.UNCERTAIN)toast("插入结果未确认，请检查；不会自动重试");}
            }));
            final ScrollView items=emojiItemsScroll;final HorizontalScrollView innerTabs=emojiTabsScroll;final String viewSymbol=symbolCategory,viewCategory=selected;
            EmojiScrollMemory.Offsets offsets=emojiScrollMemory.get(inputSession,viewSymbol,viewCategory);
            emojiPendingOffsets=offsets;
            tabs.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){public void onLayoutChange(View view,int left,int top,int right,int bottom,int oldLeft,int oldTop,int oldRight,int oldBottom){view.removeOnLayoutChangeListener(this);view.post(()->{if(inputSession!=session||emojiViewGeneration!=generation||emojiOuterScroll!=tabs||emojiItemsScroll!=items||emojiTabsScroll!=innerTabs||emojiPendingOffsets!=offsets||!emojiPanelActive()||!symbolCategory.equals(viewSymbol)||(kind==EmojiCatalog.Kind.EMOJI&&!emojiCategory.equals(viewCategory)))return;tabs.scrollTo(offsets.outer,0);if(innerTabs!=null)innerTabs.scrollTo(offsets.tabs,0);if(items!=null)items.scrollTo(0,offsets.items);emojiPendingOffsets=null;});}});
        }else{
            ScrollView scroll=new ScrollView(this);LinearLayout rows=column();String[] values=DataFiles.ready?NativeBridge.symbols(symbolCategory):new String[0];LinearLayout r=null;for(int i=0;i<values.length;i+=2){if((i/2)%6==0){r=row();rows.addView(r);}String symbol=values[i],alias=values[i+1];Button b=button(symbol,()->commit(symbol));b.setContentDescription(symbol+" \\"+alias);r.addView(b,new LinearLayout.LayoutParams(0,dp(keyHeight()),1));}scroll.addView(rows);keys.addView(scroll,new LinearLayout.LayoutParams(-1,dp(compact()?72:152)));
        }
        if(!compact()){LinearLayout control=row();add(control,"\\ 命令",1,()->{math=false;buffer.setLength(0);buffer.append('\\');rebuild();});addBackspace(control,1);keys.addView(control);}bottom();
    }
    private void bottom(){LinearLayout r=row();if(compact())add(r,"\\",.8f,()->{math=false;numbers=false;key("\\");rebuild();});
        add(r,math?"ABC":"符号",1,()->{if(!finishRaw())return;math=!math;numbers=false;rebuild();});add(r,"123",1,()->{if(!buffer.toString().startsWith("\\")&&!finishRaw())return;numbers=true;math=false;rebuild();});
        add(r,english||password?",":"，",.8f,()->punctuation(','));add(r,"空格",3,this::space);add(r,english||password?".":"。",.8f,()->punctuation('.'));
        Button language=button(english||password?"英":"中",()->{if(!finishRaw())return;english=!english;rebuild();});language.setContentDescription(password?"密码字段使用英文直输":"中英文切换");language.setEnabled(!password);r.addView(language,new LinearLayout.LayoutParams(0,dp(keyHeight()),1));
        if(compact()&&math)addBackspace(r,1);add(r,"↵",1,this::enter);keys.addView(r);
    }
    private void key(String key){
        if(getCurrentInputConnection()==null)return;
        if(hasVoiceWork())cancelVoice();
        boolean command=buffer.toString().startsWith("\\");
        if(password||(english&&!command)||((numbers||math)&&!command)){if(finishRaw())commit(key);return;}
        char c=key.length()==1?key.charAt(0):0;
        if(c=='\\'){if(!finishRaw())return;buffer.append(c);page=0;updateComposition();return;}
        if((c>='a'&&c<='z')||(c>='A'&&c<='Z')||c=='\''||(command&&c>='0'&&c<='9')){if(buffer.length()<128){buffer.append(c);page=0;updateComposition();}}else if(candidatesReady()){if(buffer.length()>0&&!choose(0))return;commit(key);}
    }
    private boolean candidatesReady(){if(buffer.length()==0||DataFiles.ready)return true;toast("词典尚在准备，拼音已保留，请稍后按空格确认；也可切换英文");return false;}
    private void punctuation(char c){if(!candidatesReady())return;if(buffer.length()>0&&!choose(0))return;commit(DataFiles.ready?NativeBridge.punctuation(c,!english&&!password&&!math&&!numbers):String.valueOf(c));}
    private void updateComposition(){
        InputConnection connection=getCurrentInputConnection();int session=inputSession;String pending=buffer.toString();boolean accepted=false;
        // The editor may retain this CharSequence or call back synchronously. Never
        // expose the live StringBuilder to a previously issued request.
        try{accepted=connection!=null&&connection.setComposingText(pending,1);}catch(RuntimeException uncertain){/* Keep pending text; never replay an uncertain editor request. */}
        if(session==inputSession&&connection==getCurrentInputConnection()&&pending.equals(buffer.toString())){
            if(accepted)compositionWriteWarned=false;
            else if(!compositionWriteWarned){compositionWriteWarned=true;toast("编辑器未确认拼音，待输入内容已保留；请核对后确认");}
        }
        refresh();
    }
    private void refresh(){if(root==null||composition==null||candidates==null)return;if(expandedCandidates&&buffer.length()==0){expandedCandidates=false;rebuild();return;}if(toolbar!=null)toolbar.setVisibility(buffer.length()==0?View.VISIBLE:View.GONE);if(candidateLine!=null)candidateLine.setVisibility(buffer.length()>0?View.VISIBLE:View.GONE);if(candidateScroll!=null)candidateScroll.setVisibility(expandedCandidates?View.GONE:View.VISIBLE);if(expandedSummary!=null){expandedSummary.setVisibility(expandedCandidates?View.VISIBLE:View.GONE);expandedSummary.setText("候选 · 第 "+(page+1)+" 组");}if(candidateToggle!=null){candidateToggle.setText(expandedCandidates?"⌃":"⌄");candidateToggle.setContentDescription(expandedCandidates?"收回候选框":"展开候选框");}if(hideButton!=null)hideButton.setVisibility(buffer.length()==0?View.VISIBLE:View.GONE);composition.setText(offlineTask!=null?voiceState:buffer.length()>0?buffer.toString():editing?"光标与文本编辑 · 按住中央按钮左右移动":numbers?"数字与常用符号 · 左侧可滚动":DataFiles.ready?"本地中文 · "+(math?"数学符号":english||password?"英文直输":"拼音 / \\数学命令"):DataFiles.failure.isEmpty()?"本地词典正在加载…":"词典初始化失败 · 可切英文，请打开设置");if(beforeCaret!=null&&beforeCaret.hasUndo()&&buffer.length()==0){composition.setText("已删除光标前内容 · 点此撤销");composition.setContentDescription("撤销本次删除光标前内容");composition.setTextColor(getColor(R.color.accent));composition.setMinHeight(dp(36));composition.setOnClickListener(v->undoBeforeCaret());}else{composition.setContentDescription(null);composition.setTextColor(getColor(buffer.length()>0||offlineTask!=null?R.color.ink:R.color.muted));composition.setTextSize(compact()?12:buffer.length()>0?14:12);composition.setMinHeight(0);composition.setOnClickListener(null);}candidates.removeAllViews();shownDictionaryReady=DataFiles.ready;current=!password&&shownDictionaryReady&&buffer.length()>0?lookup(buffer.toString(),page):new String[0];snapshotCandidateDisplay();if(expandedSummary!=null)expandedSummary.setText((currentPageFuzzy?"模糊候选":"候选")+" · 第 "+(page+1)+" 组");for(int i=0;i<current.length;i+=2){int n=i/2;Button b=button((n+1)+" "+current[i],displayedCandidate(n));bindCandidateOptions(b,n);b.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);b.setTextSize(compact()?14:16);b.setPadding(dp(12),0,dp(12),0);b.setMinWidth(dp(48));b.setMinimumWidth(dp(48));b.setContentDescription("候选 "+(n+1)+" "+current[i]);if(currentPageFuzzy)b.setTooltipText(current[i+1]);candidates.addView(b,new LinearLayout.LayoutParams(-2,dp(keyHeight())));}if(expandedCandidates)expandedKeys();if(mathAssociation!=null)mathAssociation.request();if(phraseAssociation!=null)phraseAssociation.request();}
    private void clearCandidatePages(){candidatePages=null;candidateInput=null;candidatePersonal=null;candidateRules=null;currentPageFuzzy=false;candidateRevision=-1;candidateLearningRevision=-1;candidateLearned=null;}
    private String[] lookup(String input,int requestedPage){
        currentPageFuzzy=false;
        if(input.startsWith("\\")){candidateRevision=-1;return NativeBridge.lookup(input,requestedPage);}
        try{
            PersonalStore store=PersonalStore.get(this);long revision=store.revision();String[] personal=store.words(input);long learning=learningRevision();String[] learned=learnedCandidates(input);
            Set<FuzzyPinyin.Pair> rules=FuzzySettingsActivity.enabledPairs(this);
            int modes=candidateModes();
            if(candidatePages==null||!input.equals(candidateInput)||candidateSession!=inputSession||candidateModes!=modes||candidateRevision!=revision||candidateLearningRevision!=learning||!Arrays.equals(learned,candidateLearned)||!Arrays.equals(personal,candidatePersonal)||!rules.equals(candidateRules)){
                LinkedHashMap<String,String> initialValues=new LinkedHashMap<>();for(String word:learned){boolean pinned=false;try{pinned=LearningStore.get(this).pinned(input,word);}catch(java.io.IOException unavailable){}initialValues.put(word,pinned?"个人固定":"个人常用");}for(String word:personal)initialValues.putIfAbsent(word,"个人");String[] initial=new String[initialValues.size()*2];int at=0;for(Map.Entry<String,String> item:initialValues.entrySet()){initial[at++]=item.getKey();initial[at++]=item.getValue();}
                List<String> expanded=FuzzyPinyin.forInput(input,rules,FuzzyPinyin.DEFAULT_CAP,!english&&!numbers&&!math&&!password,password,math);
                List<String> variants=expanded.size()>1?new ArrayList<>(expanded.subList(1,expanded.size())):Collections.emptyList();
                candidatePages=new CandidatePages(initial,n->NativeBridge.lookup(input,n),variants,key->fuzzyCandidates(key));
                candidateLearningRevision=learning;candidateLearned=learned.clone();candidateRevision=revision;candidateInput=input;candidatePersonal=personal.clone();candidateRules=rules;candidateSession=inputSession;candidateModes=modes;
            }
            String[] values=candidatePages.page(requestedPage);currentPageFuzzy=candidatePages.isFuzzyPage(requestedPage);return values;
        }catch(java.io.IOException|RuntimeException unavailable){clearCandidatePages();return NativeBridge.lookup(input,requestedPage);}
    }
    private String[] fuzzyCandidates(String key){
        LinkedHashMap<String,String> values=new LinkedHashMap<>();
        try{for(String word:PersonalStore.get(this).words(key)){if(values.size()>=9)break;values.put(word,"模糊 "+key);}}catch(java.io.IOException unavailable){/* Exact/native candidates remain available. */}
        String[] base=NativeBridge.lookup(key,0);for(int i=0;i+1<base.length&&values.size()<9;i+=2)if(!values.containsKey(base[i]))values.put(base[i],"模糊 "+key);
        ArrayList<String> result=new ArrayList<>();for(Map.Entry<String,String> value:values.entrySet()){result.add(value.getKey());result.add(value.getValue());}return result.toArray(new String[0]);
    }
    private void phraseKeys(){keys.addView(personalPanel.build(compact(),keyHeight()));}
    public Context context(){return this;}
    public void insert(String value){if(!privateInput())commit(value);}
    public void close(){phrasesOpen=false;personalPanel.resetSplit();rebuild();}
    public void redraw(){rebuild();}
    public boolean privateInput(){EditorInfo info=getCurrentInputEditorInfo();return password||info==null||(info.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0;}
    public SplitSelection.Snapshot snapshot(){try{InputConnection connection=getCurrentInputConnection();if(connection==null||privateInput())return null;ExtractedTextRequest request=new ExtractedTextRequest();request.hintMaxChars=32768;request.hintMaxLines=1000;ExtractedText value=connection.getExtractedText(request,0);if(value==null||value.text==null||value.text.length()>32768||value.startOffset!=0||value.partialStartOffset>=0||value.partialEndOffset>=0||value.selectionStart<0||value.selectionEnd<0||value.selectionStart>value.text.length()||value.selectionEnd>value.text.length())return null;android.icu.text.BreakIterator boundary=android.icu.text.BreakIterator.getCharacterInstance(Locale.ROOT);boundary.setText(value.text.toString());if(!boundary.isBoundary(value.selectionStart)||!boundary.isBoundary(value.selectionEnd))return null;return new SplitSelection.Snapshot(value.text.toString(),value.selectionStart,value.selectionEnd);}catch(RuntimeException unavailable){return null;}}
    public boolean replace(int start,int end,String value,int caretAfter){InputConnection connection=getCurrentInputConnection();if(connection==null||privateInput())return false;connection.beginBatchEdit();try{return connection.setSelection(start,end)&&connection.commitText(value,1)&&connection.setSelection(caretAfter,caretAfter);}finally{connection.endBatchEdit();}}
    private void stopClipboard(){
        ClipboardManager.OnPrimaryClipChangedListener old=clipboardListener;ClipboardManager manager=clipboardManager;
        clipboardRegistration=null;clipboardListener=null;clipboardListening=false; // invalidate queued callbacks before unregistering
        if(old!=null&&manager!=null)try{manager.removePrimaryClipChangedListener(old);}catch(RuntimeException unavailable){clipboardUnregisterUncertain=true;/* Old callback remains inert. */}
    }
    private void syncClipboard(){
        boolean enabled=clipboardManager!=null&&isInputViewShown()&&!privateInput()&&getSharedPreferences("clipboard",0).getBoolean("enabled",false);
        if(!enabled){stopClipboard();return;}if(clipboardListening)return;if(clipboardUnregisterUncertain){toast("剪贴板监听暂不可用，历史未自动记录");return;}
        ClipboardManager manager=clipboardManager;Object ticket=new Object();int session=inputSession;clipboardRegistration=ticket;
        java.lang.ref.WeakReference<WangshuIme> owner=new java.lang.ref.WeakReference<>(this);
        java.lang.ref.WeakReference<ClipboardManager> managerRef=new java.lang.ref.WeakReference<>(manager);
        ClipboardManager.OnPrimaryClipChangedListener listener=()->{
            WangshuIme live=owner.get();ClipboardManager source=managerRef.get();if(live==null||source==null)return;
            if(live.clipboardRegistration!=ticket||!live.clipboardListening||live.inputSession!=session||live.clipboardManager!=source)return;
            try{if(!live.isInputViewShown()||live.privateInput()||!live.getSharedPreferences("clipboard",0).getBoolean("enabled",false))return;}catch(RuntimeException unavailable){return;}
            live.clipboardChanged();
        };clipboardListener=listener;
        try{manager.addPrimaryClipChangedListener(listener);
            if(clipboardRegistration==ticket&&clipboardListener==listener&&inputSession==session)clipboardListening=true;
            else try{manager.removePrimaryClipChangedListener(listener);}catch(RuntimeException unavailable){clipboardUnregisterUncertain=true;/* Invalid identity remains inert. */}
        }catch(RuntimeException unavailable){
            if(clipboardRegistration==ticket){clipboardRegistration=null;clipboardListener=null;clipboardListening=false;toast("剪贴板监听暂不可用，历史未自动记录");}
            try{manager.removePrimaryClipChangedListener(listener);}catch(RuntimeException ignored){clipboardUnregisterUncertain=true;/* Invalid identity remains inert. */}
        }
    }
    private void clipboardChanged(){if(privateInput()){suppressClipboardUntilChange=true;return;}suppressClipboardUntilChange=false;lastClipboardText=null;captureClipboard(false);}
    private void captureClipboard(boolean userRequested){if((!userRequested&&(!clipboardListening||clipboardRegistration==null||clipboardListener==null))||clipboardManager==null||!isInputViewShown()||!getSharedPreferences("clipboard",0).getBoolean("enabled",false)||privateInput()||suppressClipboardUntilChange)return;try{String value=ClipboardStore.allowedText(clipboardManager.getPrimaryClip(),true,false);if(value!=null&&!value.equals(lastClipboardText)){if(ClipboardStore.get(this).add(value,System.currentTimeMillis(),getSharedPreferences("clipboard",0).getInt("days",7))){lastClipboardText=value;if(phrasesOpen)rebuild();}else if(phrasesOpen)toast("历史已满且全部置顶，请先取消置顶或删除一条");}}catch(Exception ignored){/* unavailable clipboard or corrupt store: no reset, no logging of contents */}}
    private boolean choose(int index){return chooseBound(index,shownGeneration);}
    private boolean chooseBound(int index,long generation){
        if(!candidatesReady())return false;
        boolean alias=shownInput.startsWith("\\");
        if(!shownDictionaryReady||generation!=shownGeneration||shownSession!=inputSession||shownPage!=page||shownModes!=candidateModes()||!shownInput.equals(buffer.toString())||(!alias&&(shownRevision!=personalRevision()||shownLearningRevision!=learningRevision()||!shownRules.equals(FuzzySettingsActivity.enabledPairs(this))))){refresh();toast("候选已更新，请重新选择");return false;}
        if(index<0||index*2>=current.length)return index==0&&current.length==0&&buffer.length()>0&&finishRaw();
        String value=current[index*2],raw=buffer.toString();boolean record=!shownFuzzy&&!alias&&LearningStore.mayLearn(this,privateInput());InputConnection connection=getCurrentInputConnection();int session=inputSession;SplitSelection.Snapshot before=record?snapshot():null;if(session!=inputSession||connection!=getCurrentInputConnection()){refresh();toast("输入位置已改变，请重新选择");return false;}boolean accepted=commitPending(value);SplitSelection.Snapshot after=record&&accepted&&session==inputSession&&connection==getCurrentInputConnection()&&LearningStore.mayLearn(this,privateInput())?snapshot():null;if(record&&accepted&&verifiedCandidateInsertion(before,after,raw,value)&&session==inputSession&&connection==getCurrentInputConnection()&&LearningStore.mayLearn(this,privateInput())){try{LearningStore.get(this).enqueueAutomatic(raw,value,System.currentTimeMillis(),learningCompletion(session,false));}catch(java.io.IOException|IllegalArgumentException failure){if(!learningWriteWarned){learningWriteWarned=true;toast("文字已输入，个人排序未确认保存");}}}refresh();return accepted;
    }
    static boolean verifiedCandidateInsertion(SplitSelection.Snapshot before,SplitSelection.Snapshot after,String raw,String word){
        if(before==null||after==null||raw==null||word==null||before.start!=before.end||after.start!=after.end||raw.length()>before.start)return false;
        int start=before.start-raw.length();if(!before.text.regionMatches(start,raw,0,raw.length()))return false;
        String expected=before.text.substring(0,start)+word+before.text.substring(before.end);return after.text.equals(expected)&&after.start==start+word.length();
    }
    private void bindCandidateOptions(Button button,int index){long generation=shownGeneration;button.setOnLongClickListener(view->{showCandidateOptions(button,index,generation);return true;});}
    /** A menu can outlive its displayed word; never mutate a stale or unreadable personal snapshot. */
    private boolean candidateOptionsCurrent(long generation,String raw){
        try{return generation==shownGeneration&&shownSession==inputSession&&shownPage==page&&shownModes==candidateModes()&&raw.equals(shownInput)&&shownInput.equals(buffer.toString())&&shownRevision>=0&&shownLearningRevision>=0&&shownRevision==personalRevision()&&shownLearningRevision==learningRevision()&&shownRules!=null&&shownRules.equals(FuzzySettingsActivity.enabledPairs(this));}
        catch(RuntimeException unavailable){return false;}
    }
    private void showCandidateOptions(View anchor,int index,long generation){
        if(privateInput()||buffer.length()==0||buffer.charAt(0)=='\\'||index<0||index*2>=current.length)return;
        if(!candidateOptionsCurrent(generation,shownInput)){try{refresh();}catch(RuntimeException unavailable){/* No mutation is allowed on an unreadable snapshot. */}toast("候选已更新或暂不可核对，请重新选择");return;}
        final String raw=shownInput,word=current[index*2];final long session=inputSession;boolean pinned;try{pinned=LearningStore.get(this).pinned(raw,word);}catch(java.io.IOException|RuntimeException unavailable){toast("个人排序暂不可核对，未打开候选菜单");return;}if(session!=inputSession||privateInput()||!candidateOptionsCurrent(generation,raw)){toast("候选已更新或暂不可核对，请重新选择");return;}PopupMenu menu=new PopupMenu(this,anchor);final boolean wasPinned=pinned;
        menu.getMenu().add(0,1,0,pinned?"取消固定此候选":"固定到此拼音前面");menu.getMenu().add(0,2,1,"忘记此词的个人权重");menu.getMenu().add(0,3,2,"记入个人词库");menu.getMenu().add(0,4,3,LearningStore.paused()?"结束临时不学习":"临时不学习");
        menu.setOnMenuItemClickListener(item->{if(session!=inputSession||privateInput()){toast("输入位置已改变，未保存");return true;}if(item.getItemId()!=4&&!candidateOptionsCurrent(generation,raw)){try{refresh();}catch(RuntimeException unavailable){/* Keep the mutation blocked even if the view cannot refresh. */}toast("候选已更新或暂不可核对，请重新选择");return true;}try{if(item.getItemId()==4)LearningStore.pause(!LearningStore.paused());else if(item.getItemId()==3)PersonalStore.get(this).putWord(-1,raw,word);else{LearningStore.Action action=item.getItemId()==2?LearningStore.Action.FORGET:wasPinned?LearningStore.Action.UNPIN:LearningStore.Action.PIN;if(LearningStore.get(this).enqueueManual(action,raw,word,System.currentTimeMillis(),learningCompletion((int)session,true)))toast("正在保存个人排序…");}clearCandidatePages();refresh();}catch(java.io.IOException|IllegalArgumentException unsupported){toast("此候选未保存；自动排序词最多64字，手动词条最多200字");}return true;});menu.show();
    }
    private boolean commit(String text){boolean hadUndo=beforeCaret!=null&&beforeCaret.hasUndo();if(beforeCaret!=null)beforeCaret.invalidate();if(hasVoiceWork())cancelVoice();InputConnection c=getCurrentInputConnection();boolean accepted=false;try{accepted=c!=null&&c.commitText(text,1);}catch(RuntimeException uncertain){toast("输入结果未确认，请核对编辑器；未自动重试");}if(hadUndo)refresh();return accepted;}
    private boolean commitPending(String value){
        if(pendingCommitInFlight){toast("上一次候选确认尚未结束，请稍候核对");return false;}
        if(beforeCaret!=null)beforeCaret.invalidate();if(hasVoiceWork())cancelVoice();
        pendingCommitInFlight=true;PendingTextCommit.Result result;
        try{result=PendingTextCommit.send(new PendingTextCommit.Host(){
            public Object connection(){return getCurrentInputConnection();}
            public int session(){return inputSession;}
            public String pending(){return buffer.toString();}
            public boolean commit(Object owner,String text){return ((InputConnection)owner).commitText(text,1);}
            public void clearPending(){buffer.setLength(0);page=0;}
        },value);}finally{pendingCommitInFlight=false;}
        if(result!=PendingTextCommit.Result.ACCEPTED){
            toast(result==PendingTextCommit.Result.REJECTED||result==PendingTextCommit.Result.UNAVAILABLE?"编辑器未接受文字，待输入内容已保留":"输入状态已改变，请核对编辑器；未自动重试");
            refresh();return false;
        }
        return true;
    }
    private boolean finishRaw(){if(hasVoiceWork())cancelVoice();if(buffer.length()>0&&!commitPending(buffer.toString()))return false;page=0;refresh();return true;}
    private void space(){
        if(buffer.length()==0){commit(" ");return;}
        // A loading view has no selectable candidate yet. Confirming with Space
        // after the dictionary becomes ready must resolve the first candidate,
        // not treat that obsolete empty view as a genuine raw-text fallback.
        InputConnection owner=getCurrentInputConnection();int session=inputSession;String pending=buffer.toString();
        if(!shownDictionaryReady&&DataFiles.ready&&shownSession==session&&shownInput.equals(pending)&&shownModes==candidateModes()){
            refresh();
            if(owner!=getCurrentInputConnection()||session!=inputSession||!pending.equals(buffer.toString()))return;
        }
        choose(0);
    }
    private void backspace(){cancelBackspace();backspaceStep();}
    private BackspaceGesture.DeleteResult backspaceStep(){
        boolean hadUndo=beforeCaret!=null&&beforeCaret.hasUndo(),selected=editorActions.selecting;
        if(beforeCaret!=null)beforeCaret.invalidate();if(hasVoiceWork())cancelVoice();
        if(buffer.length()>0){buffer.deleteCharAt(buffer.length()-1);page=0;updateComposition();return new BackspaceGesture.DeleteResult(true,false);}
        EditorActions.Deletion result=editorActions.deleteDetails(false);if(!result.accepted)toast("此编辑器暂未提供可安全删除的文字");if(selected&&editing)rebuild();else if(hadUndo)refresh();return new BackspaceGesture.DeleteResult(result.changed,result.newline);
    }
    private void enter(){if(hasVoiceWork())cancelVoice();if(buffer.length()>0){choose(0);return;}boolean hadUndo=beforeCaret!=null&&beforeCaret.hasUndo();if(beforeCaret!=null)beforeCaret.invalidate();InputConnection c=getCurrentInputConnection();if(c==null){if(hadUndo)refresh();return;}int action=getCurrentInputEditorInfo().imeOptions&EditorInfo.IME_MASK_ACTION;if(action!=EditorInfo.IME_ACTION_NONE&&action!=EditorInfo.IME_ACTION_UNSPECIFIED)c.performEditorAction(action);else c.commitText("\n",1);if(hadUndo)refresh();}
    @Override public void onUpdateSelection(int oldStart,int oldEnd,int newStart,int newEnd,int candidatesStart,int candidatesEnd){super.onUpdateSelection(oldStart,oldEnd,newStart,newEnd,candidatesStart,candidatesEnd);boolean moved=oldStart!=newStart||oldEnd!=newEnd;if(moved)emojiSelectionRevision++;if(mathAssociation!=null&&(oldStart!=newStart||oldEnd!=newEnd))mathAssociation.request();if(phraseAssociation!=null&&(oldStart!=newStart||oldEnd!=newEnd))phraseAssociation.request();editorActions.selection(newStart,newEnd);if(backspaceGesture!=null&&backspaceGesture.phase()==BackspaceGesture.Phase.BULK_PREVIEW&&(oldStart!=newStart||oldEnd!=newEnd))cancelBackspace();if(heldVoice!=null&&heldVoice.before!=null&&(newStart!=heldVoice.before.start||newEnd!=heldVoice.before.end))heldVoice.contextChanged=true;if(buffer.length()>0&&(newStart!=candidatesEnd||newEnd!=candidatesEnd)){buffer.setLength(0);page=0;InputConnection c=getCurrentInputConnection();if(c!=null)c.finishComposingText();refresh();}if(moved&&emojiPanelActive())rebuild();}
    @Override public boolean onKeyDown(int keyCode,KeyEvent event){cancelBackspace();
        cancelHoldForOtherInput();
        if(getCurrentInputConnection()==null||event.isCtrlPressed()||event.isAltPressed())return super.onKeyDown(keyCode,event);
        if(keyCode==KeyEvent.KEYCODE_ESCAPE&&buffer.length()>0){if(commitPending(""))refresh();return true;}
        if((keyCode==KeyEvent.KEYCODE_PAGE_UP||keyCode==KeyEvent.KEYCODE_PAGE_DOWN)&&buffer.length()>0){if(keyCode==KeyEvent.KEYCODE_PAGE_UP&&page>0)page--;else if(keyCode==KeyEvent.KEYCODE_PAGE_DOWN&&DataFiles.ready&&lookup(buffer.toString(),page+1).length>0)page++;refresh();return true;}
        if(keyCode==KeyEvent.KEYCODE_DEL){backspace();return true;}
        if(keyCode==KeyEvent.KEYCODE_SPACE){if(event.isShiftPressed()){if(!finishRaw())return true;english=!english;rebuild();}else space();return true;}
        if(keyCode==KeyEvent.KEYCODE_ENTER){enter();return true;}
        int unicode=event.getUnicodeChar();
        if(unicode>='0'&&unicode<='9'&&buffer.toString().startsWith("\\")&&DataFiles.ready&&NativeBridge.lookup(buffer.toString()+(char)unicode,0).length>0){key(String.valueOf((char)unicode));return true;}
        if(unicode>='1'&&unicode<='9'&&buffer.length()>0){choose(unicode-'1');return true;}
        if(unicode>0&&unicode<128){if(",.?!;:".indexOf(unicode)>=0)punctuation((char)unicode);else key(String.valueOf((char)unicode));return true;}return super.onKeyDown(keyCode,event);
    }
    private boolean hasVoiceWork(){return recognizer!=null||offlineTask!=null||voiceReview!=null||heldVoice!=null||(voiceHold!=null&&voiceHold.busy());}
    private void cancelHoldForOtherInput(){if(voiceHold!=null&&voiceHold.busy())voiceHold.cancel(VoiceHoldController.CancelReason.OTHER_INPUT);}
    /** One engine object belongs to one immutable gesture, independently of the legacy click-to-review path. */
    private final class HeldVoice implements OfflineVoice.Listener {
        final VoiceHoldController.Token token;final OfflineVoice.Task task;final InputConnection connection;
        final SplitSelection.Snapshot before;final String composing;final boolean mathematical;boolean contextChanged;
        HeldVoice(VoiceHoldController.Token owned,InputConnection editor){token=owned;connection=editor;before=voiceSnapshot(editor);composing=buffer.toString();mathematical=math;task=new OfflineVoice.Task(WangshuIme.this,this);}
        private boolean owns(){return heldVoice==this&&heldVoice.task==task&&token.inputSession==inputSession&&!password;}
        public void state(String text){if(owns())voiceHold.engineState(token,text.contains("点击")?"本地聆听 · 按住说话，松手结束":text);}
        public void result(String text){if(owns())voiceHold.result(token,mathematical&&DataFiles.ready?NativeBridge.voiceMath(text):text);}
        public void failure(String text){if(owns())voiceHold.failure(token,text);}
    }
    private HeldVoice ownedVoice(VoiceHoldController.Token token){return heldVoice!=null&&heldVoice.token.equals(token)?heldVoice:null;}
    private void createVoiceHold(){
        VoiceHoldController.Scheduler timing=(delay,action)->{keyHandler.postDelayed(action,delay);return ()->keyHandler.removeCallbacks(action);};
        voiceHold=new VoiceHoldController(new VoiceHoldController.Host(){
            public boolean beginVoice(VoiceHoldController.Token token){
                if(token.inputSession!=inputSession||password||offlineTask!=null||recognizer!=null||voiceReview!=null||heldVoice!=null||!VoiceModels.available(WangshuIme.this)||checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return false;
                InputConnection editor=getCurrentInputConnection();if(editor==null)return false;HeldVoice capture=new HeldVoice(token,editor);heldVoice=capture;capture.task.start();return true;
            }
            public void finishVoice(VoiceHoldController.Token token){HeldVoice capture=ownedVoice(token);if(capture!=null)capture.task.finish();}
            public void cancelVoice(VoiceHoldController.Token token,VoiceHoldController.CancelReason reason){HeldVoice capture=ownedVoice(token);if(capture!=null){heldVoice=null;capture.task.cancel();}}
            public void tapSpace(long input){if(input==inputSession)space();}
            public void commitPreview(VoiceHoldController.Token token,String text,VoiceHoldController.Completion completion){
                HeldVoice capture=ownedVoice(token);if(capture==null)return;heldVoice=null;capture.task.cancel();if(!voiceScope(token.inputSession,capture.connection))return;
                String value=text+(completion.appendNewline()?"\n":"");
                if(completion.releasedAt==VoiceHoldController.Target.RIGHT_ACTION&&completion.rightAction==VoiceHoldController.RightAction.CUSTOM){showVoiceReview(value,"自定义动作尚未连接，结果保留供核对",capture.connection,token.inputSession,true);return;}
                if(!capture.contextChanged&&capture.composing.isEmpty()&&buffer.length()==0&&sameVoiceSnapshot(capture.before,voiceSnapshot(capture.connection))&&capture.before.start==capture.before.end){attemptVoiceCommit(value,capture.connection,capture.before,token.inputSession);}
                else showVoiceReview(value,"输入位置、选区或内容需核对；请确认光标后再插入",capture.connection,token.inputSession,true);
            }
            public void discardPreview(VoiceHoldController.Token token,VoiceHoldController.CancelReason reason){/* No host text was inserted while held, so no host deletion is authorized. */}
            public void update(VoiceHoldController.State state){if(root==null)return;if(state.showVoiceLayer())showVoiceHold(state);else if(voiceOverlay!=null){voiceOverlay=null;rebuild();}}
            public void failed(VoiceHoldController.Token token,String message){if(token.inputSession==inputSession)toast(message);}
        },timing,ViewConfiguration.getLongPressTimeout(),ViewConfiguration.get(this).getScaledTouchSlop());
    }
    private void showVoiceHold(VoiceHoldController.State state){
        if(root==null||inputSurface==null)return;
        if(voiceOverlay==null){
            emojiViewGeneration++; // Replacing the key surface invalidates its queued taps.
            int height=Math.max(dp(compact()?150:220),root.getHeight()-root.getPaddingTop()-root.getPaddingBottom());
            cancelBackspace();if(beforeCaret!=null)beforeCaret.invalidate();inputSurface.spaceKey(null);inputSurface.backspaceKey(null);root.removeAllViews();composition=null;candidates=null;voiceReviewPanel=null;
            boolean dark=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
            voiceOverlay=new VoiceHoldOverlay(this,new VoiceHoldOverlay.Palette(getColor(R.color.paper),getColor(R.color.surface),getColor(R.color.ink),getColor(R.color.muted),getColor(R.color.selected),dark?Color.rgb(244,147,147):Color.rgb(177,48,48)),compact());
            voiceOverlay.setContentDescription("按住空格本地语音；文字仅在本次预览中");root.addView(voiceOverlay,new LinearLayout.LayoutParams(-1,height));voiceOverlay.coordinateSpace(inputSurface,(token,left,right)->voiceHold.targets(token,left,right));voiceOverlay.waitingCancel(token->voiceHold.cancel(token,VoiceHoldController.CancelReason.USER_ABORT));
        }
        voiceOverlay.setState(state);
    }
    /** Null means the host did not expose a bounded, complete, Unicode-boundary-safe snapshot. */
    private SplitSelection.Snapshot voiceSnapshot(InputConnection editor){return password?null:connectionSnapshot(editor);}
    static SplitSelection.Snapshot connectionSnapshot(InputConnection editor){
        if(editor==null)return null;
        try{ExtractedTextRequest request=new ExtractedTextRequest();request.hintMaxChars=32768;request.hintMaxLines=1000;ExtractedText x=editor.getExtractedText(request,0);if(x==null||x.text==null||x.text.length()>32768||x.startOffset!=0||x.partialStartOffset>=0||x.partialEndOffset>=0||x.selectionStart<0||x.selectionEnd<0||x.selectionStart>x.text.length()||x.selectionEnd>x.text.length())return null;String text=x.text.toString();android.icu.text.BreakIterator b=android.icu.text.BreakIterator.getCharacterInstance(Locale.ROOT);b.setText(text);if(!b.isBoundary(x.selectionStart)||!b.isBoundary(x.selectionEnd))return null;return new SplitSelection.Snapshot(text,x.selectionStart,x.selectionEnd);}catch(RuntimeException unavailable){return null;}
    }
    static boolean sameVoiceSnapshot(SplitSelection.Snapshot first,SplitSelection.Snapshot second){return first!=null&&second!=null&&first.start==second.start&&first.end==second.end&&first.text.equals(second.text);}
    static boolean voiceCommitVisible(SplitSelection.Snapshot before,String text,SplitSelection.Snapshot actual){
        if(before==null||actual==null||text==null||before.start!=before.end)return false;
        int caret=before.start+text.length();
        return actual.start==caret&&actual.end==caret&&actual.text.equals(before.text.substring(0,before.start)+text+before.text.substring(before.end));
    }
    private boolean voiceScope(long session,InputConnection editor){return session==inputSession&&!password&&editor!=null&&editor==getCurrentInputConnection();}
    private void showVoiceReview(String text,String reason,InputConnection editor,long session,boolean allowInsert){if(!voiceScope(session,editor)||text==null||text.isEmpty())return;voiceReview=text;voiceReviewReason=reason;reviewConnection=editor;reviewSession=(int)session;reviewCanInsert=allowInsert;rebuild();}
    private void attemptVoiceCommit(String text,InputConnection editor,SplitSelection.Snapshot expected,long session){
        if(!voiceScope(session,editor))return;
        if(buffer.length()>0||expected==null||expected.start!=expected.end||!sameVoiceSnapshot(expected,voiceSnapshot(editor))){showVoiceReview(text,"请先确认输入位置并结束拼音组合",editor,session,true);return;}
        if(text.length()>32768-expected.text.length()){showVoiceReview(text,"结果超出安全编辑范围，请选择复制后分段使用",editor,session,false);return;}
        try{
            if(!editor.finishComposingText()||!voiceScope(session,editor)||!sameVoiceSnapshot(expected,voiceSnapshot(editor))){showVoiceReview(text,"编辑器状态变化，结果保留供核对",editor,session,true);return;}
            boolean accepted=editor.commitText(text,1);
            if(!voiceScope(session,editor))return;
            // A host can acknowledge asynchronously or return a misleading bool.
            // Retain an explicit copy path unless the actual resulting text and
            // caret match. Never offer a blind second insertion after dispatch.
            if(!accepted||!voiceCommitVisible(expected,text,voiceSnapshot(editor))){showVoiceReview(text,"插入请求已发出，结果暂无法核实；请核对原文，可复制本次结果",editor,session,false);return;}
            voiceReview=null;voiceReviewReason="";reviewConnection=null;reviewSession=-1;rebuild();
        }catch(RuntimeException refused){showVoiceReview(text,"编辑器未完成插入，请核对原文后选择复制",editor,session,false);}
    }
    private void insertVoiceReview(){String text=voiceReview;InputConnection editor=reviewConnection;int session=reviewSession;if(text==null||!reviewCanInsert||!voiceScope(session,editor)){toast("输入会话已改变，未自动插入");return;}SplitSelection.Snapshot current=voiceSnapshot(editor);if(current==null||current.start!=current.end||buffer.length()>0){toast("请先结束拼音并将选区收为光标；结果仍保留，可选择复制");return;}attemptVoiceCommit(text,editor,current,session);}
    private void copyVoiceReview(){if(voiceReview==null||password||clipboardManager==null)return;try{ClipData clip=ClipData.newPlainText("语音识别结果",voiceReview);PersistableBundle flags=new PersistableBundle();flags.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(flags);clipboardManager.setPrimaryClip(clip);toast("已复制本次结果，未自动写入编辑器");}catch(RuntimeException unavailable){toast("系统剪贴板暂不可用，本次结果仍保留");}}
    private void startVoice(){
        cancelHoldForOtherInput();
        if(recognizer!=null){cancelVoice();toast("已停止本地语音");return;}
        if(offlineTask!=null){offlineTask.finish();voiceToolState("识别中…",ToolbarIcon.Kind.BUSY);return;}
        if(VoiceModels.available(this)){
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){toast("请在设置中允许本地语音权限");startActivity(new Intent(this,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
            if(!finishRaw())return;mathVoice=math;int session=inputSession;voiceState="正在准备本地语音…";
            final OfflineVoice.Task[] owner=new OfflineVoice.Task[1];OfflineVoice.Task created=new OfflineVoice.Task(this,new OfflineVoice.Listener(){
                public void state(String text){if(session!=inputSession||offlineTask!=owner[0])return;voiceState=text;refresh();}
                public void result(String text){if(session!=inputSession||offlineTask!=owner[0])return;offlineTask=null;if(text.isEmpty()){toast("没有识别到文字");rebuild();return;}showVoiceReview(mathVoice&&DataFiles.ready?NativeBridge.voiceMath(text):text,"请核对识别文字后插入",getCurrentInputConnection(),session,true);}
                public void failure(String text){if(session!=inputSession||offlineTask!=owner[0])return;offlineTask=null;toast(text);rebuild();}
            });owner[0]=created;offlineTask=created;created.start();rebuild();return;
        }
        if(Build.VERSION.SDK_INT<31||!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)){toast("请先在设置导入离线中文语音包；当前不录音、不转云");startActivity(new Intent(this,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){toast("请在望舒输入法设置中允许本地语音权限");startActivity(new Intent(this,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
        if(!finishRaw())return;mathVoice=math;int session=inputSession;SpeechRecognizer created=SpeechRecognizer.createOnDeviceSpeechRecognizer(this);recognizer=created;recognizer.setRecognitionListener(new RecognitionListener(){public void onReadyForSpeech(Bundle b){if(recognizer!=created)return;voiceToolState("停止",ToolbarIcon.Kind.STOP);toast(mathVoice?"本地数学语音：正在聆听":"本地语音：正在聆听");}public void onBeginningOfSpeech(){}public void onRmsChanged(float f){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){}public void onError(int e){if(recognizer!=created)return;toast("设备端识别未完成（"+e+"），未转云");cancelVoice();}public void onResults(Bundle b){if(recognizer!=created)return;ArrayList<String> values=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(session==inputSession&&!password&&values!=null&&!values.isEmpty()){String text=mathVoice&&DataFiles.ready?NativeBridge.voiceMath(values.get(0)):values.get(0);cancelVoice();showVoiceReview(text,"请核对识别文字后插入",getCurrentInputConnection(),session,true);}else cancelVoice();}public void onPartialResults(Bundle b){}public void onEvent(int t,Bundle b){}});
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN");i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);try{recognizer.startListening(i);}catch(RuntimeException e){cancelVoice();toast("设备端语音不可用，键盘输入仍可用");}
    }
    private void cancelVoice(){cancelHoldForOtherInput();HeldVoice held=heldVoice;heldVoice=null;if(held!=null)held.task.cancel();OfflineVoice.Task task=offlineTask;offlineTask=null;if(task!=null)task.cancel();voiceReview=null;voiceReviewReason="";reviewConnection=null;reviewSession=-1;reviewCanInsert=true;voiceState="";if(voiceReviewPanel!=null)voiceReviewPanel.setVisibility(View.GONE);SpeechRecognizer old=recognizer;recognizer=null;if(old!=null){old.cancel();old.destroy();}voiceToolState(math?"数学语音":"语音",ToolbarIcon.Kind.MICROPHONE);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
}
