#!/usr/bin/env python3
"""Focused source-derived JVM fakes and XML assertions. NOT Android lint or UI."""
from pathlib import Path
import argparse,hashlib,json,re,subprocess,xml.etree.ElementTree as ET
ap=argparse.ArgumentParser();ap.add_argument('--project',type=Path,required=True);ap.add_argument('--baseline',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);a=ap.parse_args()
p=a.project.resolve();b=a.baseline.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
src=out/'src';classes=out/'classes';classes.mkdir()
j=p/'android/app/src/main/java/org/wangshu/ime';oldj=b/'android/app/src/main/java/org/wangshu/ime'
receipt={'scope':'Host-only source-derived JVM fakes/XML assertions. Android lint, aapt resource packaging, real accessibility and device rendering NOT_RUN.','checks':[]}
def need(ok,why):
    assert ok,why
    receipt['checks'].append(why)
def write(name,text):
    target=src/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text,encoding='utf-8')
def method(s,sig):
    start=s.index(sig);opening=s.index('{',start);depth=1;end=opening+1
    # These selected methods contain no brace characters inside string literals.
    while depth:depth+=(s[end]=='{')-(s[end]=='}');end+=1
    return s[start:end]
def sha(x):return hashlib.sha256(x.read_bytes()).hexdigest()
# Check every backup data domain on both generations, without invoking backup.
ns='{http://schemas.android.com/apk/res/android}'
app=ET.parse(p/'android/app/src/main/AndroidManifest.xml').getroot().find('application')
need(app.get(ns+'allowBackup')=='false','automatic backup stays disabled')
need(app.get(ns+'fullBackupContent')=='@xml/backup_rules','legacy backup rule resource is linked')
need(app.get(ns+'dataExtractionRules')=='@xml/data_extraction_rules','Android 12+ extraction resource is linked')
domains={'root','file','database','sharedpref','external','device_root','device_file','device_database','device_sharedpref'}
legacy=ET.parse(p/'android/app/src/main/res/xml/backup_rules.xml').getroot()
modern=ET.parse(p/'android/app/src/main/res/xml/data_extraction_rules.xml').getroot()
for label,rule in [('legacy',legacy),('cloud-backup',modern.find('cloud-backup')),('device-transfer',modern.find('device-transfer'))]:
    need(rule is not None and {n.get('domain') for n in rule.findall('exclude')}==domains and all(n.get('path')=='.' for n in rule.findall('exclude')) and not rule.findall('include'),label+': all 9 data domains explicitly excluded; no includes')
# Preserved consent/persistence boundaries and actual voice setup.
for name in ['AppPreferences.java','SettingsRecoveryFiles.java','SettingsBackup.java','ClipboardStore.java','PersonalStore.java','OfflineVoice.java','VoiceHoldController.java','VoiceHoldSettings.java']:
    need((j/name).read_bytes()==(oldj/name).read_bytes(),'unchanged '+name)
ime=(j/'WangshuIme.java').read_text();oldime=(oldj/'WangshuIme.java').read_text()
for sig in ['private void startVoice(', 'private void showVoiceHold(']:
    need(method(ime,sig)==method(oldime,sig),'unchanged '+sig)
need(method((j/'VoiceHoldOverlay.java').read_text(),'public VoiceHoldOverlay(Context context,Palette palette,boolean landscape)')==method((oldj/'VoiceHoldOverlay.java').read_text(),'public VoiceHoldOverlay(Context context,Palette palette,boolean landscape)'),'production explicit-palette voice constructor unchanged')
need('public VoiceHoldOverlay(Context context)' in (j/'VoiceHoldOverlay.java').read_text(),'standard context voice-view constructor exists')
for folder in ['core','data','windows']:
    baseline={str(x.relative_to(b)):sha(x) for x in (b/folder).rglob('*') if x.is_file()}
    current={str(x.relative_to(p)):sha(x) for x in (p/folder).rglob('*') if x.is_file()}
    need(baseline==current,'unchanged '+folder+'/')
# Parse Android string syntax sufficient for these quoted, newline-only resources.
resources={}
for el in ET.parse(p/'android/app/src/main/res/values/strings.xml').getroot().findall('string'):
    val=el.text or ''
    if val.startswith('"'):
        need(val.endswith('"'),'resource '+el.get('name')+' preserves whitespace with quotes')
        val=val[1:-1].replace('\\n','\n')
    resources[el.get('name')]=val
need(resources['candidate_font_preview']=='候选预览：你好  数学  Example','candidate preview preserves double spaces')
need(resources['fuzzy_sample_pinyin']=='zhan','synthetic pinyin sample unchanged')
# Generate real Java String.format calls from parsed resource values, plus old/new
# setText expressions extracted from each source rather than maintained copies.
def expr(source,marker):
    at=source.index(marker)+len(marker);opening=at;depth=1;quoted=False;escaped=False
    while depth:
        ch=source[at]
        if quoted:
            if escaped:escaped=False
            elif ch=='\\':escaped=True
            elif ch=='"':quoted=False
        elif ch=='"':quoted=True
        elif ch=='(':depth+=1
        elif ch==')':depth-=1
        at+=1
    return source[opening:at-1]
formats='\n'.join('case R.string.'+name+': value='+json.dumps(value,ensure_ascii=False)+';break;' for name,value in resources.items())
rids=''.join('public static final int '+name+'='+str(i+1)+';' for i,name in enumerate(resources))
write('org/wangshu/ime/R.java','package org.wangshu.ime;final class R {static final class color {static int ink=1;}static final class string {'+rids+'}}')
# Explicit selected expression list. Prefix occurrence picks the requested site.
specs=[
 ('ClipboardSettingsActivity.java','limits.setText(','','true'),
 ('FuzzySettingsActivity.java','toggle.setText(','Pair pair=new Pair();String[] examples={"zan ↔ zhan"};int index=0;','index==1'),
 ('FuzzySettingsActivity.java','sample.setText(','','true'),
 ('PersonalActivity.java','b.setText((prefix.',None,None),
 ('PersonalPanel.java','number.setText(','int tokenPage=1;java.util.List<String> tokens=java.util.Collections.nCopies(50,"x");','true'),
 ('SettingsActivity.java','preview.setText(','','true'),
 ('SettingsActivity.java','value.setText(','Slider slider=new Slider();','true'),
 ('SettingsActivity.java','value.setText("每移动 "+(n+8)',None,None),
 ('SettingsActivity.java','status.setText(n>0?','long n=9876543210L;','true'),
 ('SettingsActivity.java','status.setText("本地词典未就绪："',None,None),
 ('SettingsActivity.java','status.setText("导入未完成："',None,None),
 ('SettingsBackupActivity.java','text.setText(note+','String note="已验证 15 项，1 项不同。\\n\\n";StringBuilder details=new StringBuilder("enable false → true\\n");String privacy="开关不改变。";','true'),
 ('WangshuIme.java','transcript.setText(','String voiceReviewReason="输入位置变化";String voiceReview="你好  100%\\n下一行";','true'),
 ('WangshuIme.java','expandedSummary.setText("候选 · 第 "','int page=123;boolean currentPageFuzzy=false;','true'),
 ('WangshuIme.java','expandedSummary.setText((currentPageFuzzy?','int page=123;boolean currentPageFuzzy=true;','true'),
]
# Match corresponding setText calls by their ordinal position in each file. Edits
# deliberately preserve call counts, so this captures the actual replacement.
checks=[]
for file,marker,setup,post in specs:
    old=(oldj/file).read_text();new=(j/file).read_text();pos=old.index(marker);prefix=old[:pos];ordinal=len(re.findall(r'\.setText\(',prefix));old_calls=list(re.finditer(r'\.setText\(',old));new_calls=list(re.finditer(r'\.setText\(',new));assert len(old_calls)==len(new_calls),file
    oo=expr(old[old_calls[ordinal].start():],'.setText(');nn=expr(new[new_calls[ordinal].start():],'.setText(')
    if setup is None:
        if file=='PersonalActivity.java':setup='String prefix="nihao",text="你好  100%";';post='true'
        elif '(n+8)' in marker:setup='int n=40;';post='true'
        else:setup='Exception e=new Exception((String)null);';post='true'
    # setText(int) resolves a string resource on Android; explicit resolution here.
    if re.fullmatch(r'R\.string\.\w+',nn):nn='getString('+nn+')'
    checks.append('{String expected;{'+setup+'expected='+oo+';need('+post+',"original side-effect count");}{'+setup+'String actual='+nn+';need(expected.equals(actual),'+json.dumps(file+' '+marker,ensure_ascii=False)+');need('+post+',"replacement side-effect count");}}')
# Extra branch/locale/null cases for nontrivial templates.
checks+=['{String prefix="",text="测试";need((prefix.isEmpty()?text:getString(R.string.personal_entry_label,prefix,text)).equals(text),"empty prefix");}',
'{need(getString(R.string.voice_review_default,"100% \\nX").equals("请核对：100% \\nX"),"literal percent/newline in voice result");}',
'{need(getString(R.string.personal_page_number,1,1).equals("1 / 1"),"empty split paging");}',
'{need(getString(R.string.cursor_step_description,8).equals("每移动 8 dp 移动一个字（小值更灵敏）"),"cursor minimum");}']
write('org/wangshu/ime/StringChecks.java','''package org.wangshu.ime;import java.util.*;public final class StringChecks{
 static int count;static final StringChecks c=new StringChecks();static class Pair{String label="z ↔ zh";}static class Slider{int getProgress(){return 40;}}
 static void need(boolean value,String label){count++;if(!value)throw new AssertionError(label);}
 static String getString(int id,Object...args){String value;switch(id){'''+formats+'''default:throw new AssertionError(id);}return args.length==0?value:String.format(Locale.getDefault(),value,args);}
 public static void main(String[]a){for(Locale locale:new Locale[]{Locale.US,Locale.CHINA,Locale.forLanguageTag("ar-EG"),Locale.forLanguageTag("fa-IR")}){Locale.setDefault(locale);'''+''.join(checks)+''' }System.out.println("PASS "+count+" actual old/new label expression checks across 4 locales; XML decoding is a host model, not aapt");}}
''')
# Small Android stand-ins used solely for source mechanics, not platform acceptance.
fakes={
'android/content/Context.java':'package android.content;public class Context{public android.content.res.Resources getResources(){return new android.content.res.Resources();}public int getColor(int x){return x;}public SharedPreferences getSharedPreferences(String s,int m){return new SharedPreferences();}}',
'android/content/SharedPreferences.java':'package android.content;public class SharedPreferences{public int getInt(String s,int d){return d;}}',
'android/content/res/Resources.java':'package android.content.res;public class Resources{public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}}',
'android/util/DisplayMetrics.java':'package android.util;public class DisplayMetrics{public float density=1;}',
'android/graphics/Paint.java':'package android.graphics;public class Paint{public static final int ANTI_ALIAS_FLAG=1;public enum Style{STROKE}public enum Join{ROUND}public enum Cap{ROUND}public Paint(int x){}public void setStyle(Style s){}public void setStrokeWidth(float w){}public void setStrokeJoin(Join x){}public void setStrokeCap(Cap x){}public void setColor(int c){}}',
'android/graphics/Path.java':'package android.graphics;public class Path{public static int allocations;public String commands="";public Path(){allocations++;}public void reset(){commands="";}public void moveTo(float x,float y){commands+="M"+x+","+y+";";}public void lineTo(float x,float y){commands+="L"+x+","+y+";";}public void close(){commands+="Z;";}}',
'android/graphics/Canvas.java':'package android.graphics;public class Canvas{public String path="";public int saves,restores;public void save(){saves++;}public void restore(){restores++;}public void translate(float x,float y){}public void scale(float x,float y){}public void drawPath(Path p,Paint ink){path=p.commands;}}',
'android/graphics/Rect.java':'package android.graphics;public class Rect{public int l,t,r,b;public Rect(){this(0,0,0,0);}public Rect(int l,int t,int r,int b){set(l,t,r,b);}public void set(int a,int c,int d,int e){l=a;t=c;r=d;b=e;}public void set(Rect x){set(x.l,x.t,x.r,x.b);}public void offset(int x,int y){l+=x;r+=x;t+=y;b+=y;}public void setEmpty(){set(0,0,0,0);}public boolean empty(){return l>=r||t>=b;}public boolean intersect(Rect x){int a=Math.max(l,x.l),c=Math.max(t,x.t),d=Math.min(r,x.r),e=Math.min(b,x.b);if(a>=d||c>=e)return false;set(a,c,d,e);return true;}}',
'android/view/View.java':'''package android.view;import android.content.Context;public class View{public static final int LAYOUT_DIRECTION_RTL=1;public int clicks,width=48,height=48,screenX,screenY,scrollX,scrollY;public View root=this;public android.graphics.Rect global=new android.graphics.Rect();public boolean globalShown=true;public void getLocationOnScreen(int[] out){out[0]=screenX;out[1]=screenY;}public View getRootView(){return root;}public boolean getGlobalVisibleRect(android.graphics.Rect out){out.set(global);return globalShown;}public int getScrollX(){return scrollX;}public int getScrollY(){return scrollY;}public boolean pressed;public Context context;public OnClickListener click;public OnTouchListener touch;public View(Context c){context=c;}public interface OnClickListener{void onClick(View v);}public interface OnTouchListener{boolean onTouch(View v,MotionEvent e);}public void setOnClickListener(OnClickListener l){click=l;}public void setOnTouchListener(OnTouchListener l){touch=l;}public boolean performClick(){clicks++;if(click!=null){click.onClick(this);return true;}return false;}public void setPressed(boolean p){pressed=p;}public boolean isPressed(){return pressed;}public int getWidth(){return width;}public int getHeight(){return height;}public android.content.res.Resources getResources(){return context.getResources();}public void setPadding(int a,int b,int c,int d){}public void setMinimumWidth(int w){}public void setMinimumHeight(int h){}public void setContentDescription(String s){}public void setTooltipText(String s){}public void setSelected(boolean b){}protected void onDraw(android.graphics.Canvas c){} }''',
'android/view/ViewConfiguration.java':'package android.view;public class ViewConfiguration{public static int getLongPressTimeout(){return 500;}public static ViewConfiguration get(android.content.Context c){return new ViewConfiguration();}public int getScaledTouchSlop(){return 8;}}',
'android/view/MotionEvent.java':'package android.view;public class MotionEvent{public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;int action;float x;long t;public MotionEvent(int a,float x,long t){action=a;this.x=x;this.t=t;}public int getActionMasked(){return action;}public float getX(){return x;}public long getEventTime(){return t;}}',
'android/widget/ImageView.java':'package android.widget;public class ImageView extends android.view.View{public enum ScaleType{CENTER}public ImageView(android.content.Context c){super(c);}public void setImageDrawable(Object d){}public void setScaleType(ScaleType t){}}',
'android/widget/ImageButton.java':'package android.widget;public class ImageButton extends ImageView{public ImageButton(android.content.Context c){super(c);}}',
'android/widget/Button.java':'package android.widget;public class Button extends android.view.View{public int fallbackDraws;CharSequence text="";public Button(android.content.Context c){super(c);}public void setText(CharSequence s){text=s;}public CharSequence getText(){return text;}public int getCurrentTextColor(){return 1;}protected void onDraw(android.graphics.Canvas c){fallbackDraws++;}}'
}
for path,text in fakes.items():write(path,text)
write('org/wangshu/ime/GlyphKey.java',(j/'GlyphKey.java').read_text())
write('org/wangshu/ime/CursorControlButton.java',(j/'CursorControlButton.java').read_text())
# Execute the actual test-only capture method with root and screen API outputs.
# The fake deliberately keeps editor internal scroll separate from its view bounds.
math_source=(p/'android/app/src/androidTest/java/org/wangshu/ime/MathImePrecondition.java').read_text()
old_math=(b/'android/app/src/androidTest/java/org/wangshu/ime/MathImePrecondition.java').read_text()
start=old_math.index('if(!editor.getGlobalVisibleRect(state.visible))')
old_clip=old_math[start:old_math.index('state.softInputMode=',start)]
need('captureVisibleScreen(editor,state.full,state.visible)' in math_source,'snapshot uses the tested screen-coordinate capture')
write('org/wangshu/ime/RectChecks.java','''package org.wangshu.ime;import android.graphics.Rect;import android.view.View;public final class RectChecks{
 static int checks;static void need(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static class State{Rect visible=new Rect(),full;State(Rect f){full=f;}}
 static void oldClip(State state,View editor){'''+old_clip+'''}
 '''+method(math_source,'static void captureVisibleScreen(')+'''
 static View editor(int dx,int dy,Rect global){View root=new View(new android.content.Context());root.screenX=dx;root.screenY=dy;View e=new View(root.context);e.root=root;e.screenX=dx+20;e.screenY=dy+100;e.width=200;e.height=80;e.global=global;return e;}
 static void expect(View e,int l,int t,int r,int b,String name){Rect full=new Rect(),visible=new Rect();captureVisibleScreen(e,full,visible);need(full.l==e.screenX&&full.t==e.screenY&&full.r==e.screenX+e.width&&full.b==e.screenY+e.height,name+" full is screen-relative");need(visible.l==l&&visible.t==t&&visible.r==r&&visible.b==b,name+" visible is screen-relative");}
 public static void main(String[]a){
 // The old unchecked intersection keeps a disjoint root-relative rectangle.
 View oldEditor=editor(0,600,new Rect(20,100,220,180));State old=new State(new Rect(20,700,220,780));oldClip(old,oldEditor);need(old.visible.t==100,"reproduced original root/screen mismatch");
 // Retained v1 statement checked the result but still failed this coordinate case.
 State v1=new State(new Rect(20,700,220,780));if(!oldEditor.getGlobalVisibleRect(v1.visible)||!v1.visible.intersect(v1.full))v1.visible.setEmpty();need(v1.visible.empty(),"reproduced v1 false-negative readiness");
 for(int[]offset:new int[][]{{0,0},{0,600},{75,600},{-30,240}}){int dx=offset[0],dy=offset[1];String name="root("+dx+","+dy+")";
 expect(editor(dx,dy,new Rect(20,100,220,180)),dx+20,dy+100,dx+220,dy+180,name+" full visible");
 expect(editor(dx,dy,new Rect(35,120,200,160)),dx+35,dy+120,dx+200,dy+160,name+" partial clip");
 expect(editor(dx,dy,new Rect(0,80,300,220)),dx+20,dy+100,dx+220,dy+180,name+" clamp to full");
 expect(editor(dx,dy,new Rect(400,400,450,450)),0,0,0,0,name+" disjoint");
 expect(editor(dx,dy,new Rect(220,100,260,180)),0,0,0,0,name+" touching edge");
 expect(editor(dx,dy,new Rect(20,100,20,100)),0,0,0,0,name+" empty");
 View hidden=editor(dx,dy,new Rect(20,100,220,180));hidden.globalShown=false;expect(hidden,0,0,0,0,name+" hidden clears undefined rectangle");
 View scrolled=editor(dx,dy,new Rect(35,120,200,160));scrolled.scrollX=37;scrolled.scrollY=250;expect(scrolled,dx+35,dy+120,dx+200,dy+160,name+" editor self-scroll does not shift viewport");
 View zero=editor(dx,dy,new Rect(20,100,220,180));zero.width=0;expect(zero,0,0,0,0,name+" zero width");
 }
 System.out.println("PASS "+checks+" source-derived Rect coordinate checks including nonzero root offsets, clipping, disjoint and self-scroll; fake API outputs only");}}
''')
write('org/wangshu/ime/GlyphChecks.java','''package org.wangshu.ime;import android.graphics.*;public final class GlyphChecks{
 static int checks;static void need(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}public static void main(String[]a){GlyphKey key=new GlyphKey(new android.content.Context());Canvas c=new Canvas();int paths=Path.allocations;
 String[] labels={"↵","⌫","⌄","⌃","↵"};String[] expected={"M20.0,4.0;L20.0,14.0;L4.0,14.0;M9.0,9.0;L4.0,14.0;L9.0,19.0;","M9.0,5.0;L21.0,5.0;L21.0,19.0;L9.0,19.0;L2.0,12.0;Z;M11.0,9.0;L17.0,15.0;M17.0,9.0;L11.0,15.0;","M6.0,9.0;L12.0,15.0;L18.0,9.0;","M6.0,15.0;L12.0,9.0;L18.0,15.0;","M20.0,4.0;L20.0,14.0;L4.0,14.0;M9.0,9.0;L4.0,14.0;L9.0,19.0;"};
 for(int n=0;n<100;n++)for(int i=0;i<labels.length;i++){key.setText(labels[i]);key.onDraw(c);need(c.path.equals(expected[i]),"shape or reset");}need(Path.allocations==paths,"draw allocated a Path");need(c.saves==c.restores,"unbalanced canvas");key.setText("ABC");key.onDraw(c);need(key.fallbackDraws==1,"normal label fallback");key.setText(new CharSequence(){public int length(){return 1;}public char charAt(int i){return '↵';}public CharSequence subSequence(int x,int y){throw new AssertionError();}public String toString(){throw new AssertionError("draw converted label");}});key.onDraw(c);need(c.path.equals(expected[0]),"CharSequence glyph");key.width=2;key.onDraw(c);need(c.saves==c.restores,"zero-size canvas");System.out.println("PASS "+checks+" glyph shape/reuse/label checks; recorded fake canvas, no Android pixels");}}
''')
write('org/wangshu/ime/WangshuIme.java','''package org.wangshu.ime;import android.view.*;import android.widget.*;public final class WangshuIme extends android.content.Context{
 static class Handler{java.util.List<Runnable> callbacks=new java.util.ArrayList<>();void postDelayed(Runnable r,long n){callbacks.add(r);}void removeCallbacks(Runnable r){callbacks.remove(r);}void fire(){for(Runnable r:new java.util.ArrayList<>(callbacks))r.run();callbacks.clear();}}
 static class Actions{int resets,moves;void reset(){resets++;}boolean move(int d,boolean b){moves+=d;return true;}}
 int inputSession=1,rebuilds,toasts;boolean editing,phrasesOpen=true,finish=true;final Handler keyHandler=new Handler();final Actions editorActions=new Actions();int dp(int n){return n;}void toast(String s){toasts++;}boolean finishRaw(){return finish;}void rebuild(){rebuilds++;}
 '''+method(ime,'private ImageButton cursorButton()')+'''
 static int checks;static void need(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}static void event(ImageButton b,int a,float x,long t){need(b.touch.onTouch(b,new MotionEvent(a,x,t)),"consumes gesture");}
 public static void main(String[]args){
 WangshuIme w=new WangshuIme();ImageButton b=w.cursorButton();need(b.performClick()&&w.editing&&w.rebuilds==1&&b.clicks==1,"accessibility click exactly once");
 w=new WangshuIme();b=w.cursorButton();event(b,0,10,100);event(b,1,10,200);need(w.editing&&b.clicks==1&&w.keyHandler.callbacks.isEmpty(),"short tap exactly once");
 w=new WangshuIme();b=w.cursorButton();event(b,0,10,100);event(b,3,10,200);need(!w.editing&&b.clicks==0&&!b.pressed&&w.keyHandler.callbacks.isEmpty(),"cancel no click");
 w=new WangshuIme();b=w.cursorButton();event(b,0,10,100);w.keyHandler.fire();event(b,1,10,700);need(!w.editing&&b.clicks==0,"hold no click");
 w=new WangshuIme();b=w.cursorButton();event(b,0,10,100);event(b,2,42,650);event(b,1,42,700);need(w.editorActions.moves==2&&b.clicks==0&&!w.editing,"drag moves without click");
 w=new WangshuIme();b=w.cursorButton();event(b,0,10,100);w.inputSession++;event(b,1,10,200);need(!w.editing&&b.clicks==0&&w.keyHandler.callbacks.isEmpty(),"stale session no click");
 w=new WangshuIme();b=w.cursorButton();w.finish=false;b.performClick();need(!w.editing&&w.rebuilds==0,"failed composition preserves editor");
 CursorControlButton empty=new CursorControlButton(w);need(!empty.performClick()&&empty.clicks==1,"super boolean return preserved");System.out.println("PASS "+checks+" actual cursor handler/subclass checks on fake views; no Android accessibility-service acceptance");}}
 class CursorIcon{CursorIcon(float d,int c){}}class KeyboardSkin{static void tool(Object c,Object b,boolean s){}}
''')
# Source-derived RTL expression is evaluated with fake direction/translation values.
panel=(j/'PersonalPanel.java').read_text();needle='front.animate().translationX(';pos=panel.index(needle);rtl=expr(panel[pos:],needle)
need('Gravity.END' in panel and 'Gravity.RIGHT' not in panel,'end gravity replaces absolute right')
write('org/wangshu/ime/RtlChecks.java','''package org.wangshu.ime;import android.view.View;public class RtlChecks{static class Front{float x;int dir;float getTranslationX(){return x;}int getLayoutDirection(){return dir;}}static float next(Front front,float distance){return '''+rtl+''';}public static void main(String[]a){Front f=new Front();int count=0;for(int dir:new int[]{0,1})for(float x:new float[]{0,-30,30}){f.dir=dir;f.x=x;float expected=x!=0?0:dir==1?200:-200;if(next(f,200)!=expected)throw new AssertionError();count++;}System.out.println("PASS "+count+" source-derived RTL reveal/reclose checks; no Android layout acceptance");}}
''')
receipt['sources']={str(x.relative_to(p)):sha(x) for x in sorted(p.rglob('*')) if x.is_file() and '/build/' not in str(x)}
receipt['baseline']=str(b)
def run(name,cmd):
    r=subprocess.run([str(x) for x in cmd],capture_output=True,text=True);(out/(name+'.log')).write_text(r.stdout+r.stderr);receipt[name]=r.returncode;print(r.stdout+r.stderr,end='');return r.returncode
try:
    need(run('compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-source','17','-target','17','-encoding','UTF-8','-d',classes,*sorted(src.rglob('*.java'))])==0,'focused JVM sources compile')
    for cls in ['StringChecks','RectChecks','GlyphChecks','WangshuIme','RtlChecks']:
        need(run(cls,['java','-cp',classes,'org.wangshu.ime.'+cls])==0,cls+' passes')
    receipt['success']=True
finally:
    (out/'RESULT.json').write_text(json.dumps(receipt,indent=2)+'\n')
