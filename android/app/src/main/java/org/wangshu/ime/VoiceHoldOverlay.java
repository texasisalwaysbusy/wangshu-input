package org.wangshu.ime;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/** Original native voice layer. Targets come from actual laid-out child bounds. */
public final class VoiceHoldOverlay extends LinearLayout {
    public static final class Palette {
        public final int background,surface,ink,muted,selected,danger;
        public Palette(int bg,int card,int text,int secondary,int active,int cancel){background=bg;surface=card;ink=text;muted=secondary;selected=active;danger=cancel;}
    }
    public interface CancelListener {void cancel(VoiceHoldController.Token token);}
    public interface RegionsListener {void changed(VoiceHoldController.Token token,VoiceHoldController.Region left,VoiceHoldController.Region right);}
    private final Palette colors;private final boolean compact;private final TextView status,preview,footer,left,right;private final LinearLayout targets;private final Button cancel;
    private final Level level;private VoiceHoldController.State state;private ViewGroup coordinates;private RegionsListener regions;
    /** Standard constructor for Android view tools; production passes its palette explicitly. */
    public VoiceHoldOverlay(Context context){this(context,defaultPalette(context),context.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE);}
    private static Palette defaultPalette(Context context){
        boolean dark=(context.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return new Palette(context.getColor(R.color.paper),context.getColor(R.color.surface),context.getColor(R.color.ink),context.getColor(R.color.muted),context.getColor(R.color.selected),dark?Color.rgb(244,147,147):Color.rgb(177,48,48));
    }
    public VoiceHoldOverlay(Context context,Palette palette,boolean landscape){super(context);colors=palette;compact=landscape;setOrientation(VERTICAL);setPadding(dp(8),dp(compact?4:12),dp(8),dp(compact?4:12));setBackgroundColor(colors.background);setClickable(true);setMinimumHeight(dp(compact?120:210));
        status=text(compact?12:14,1);status.setTextColor(colors.muted);status.setGravity(Gravity.CENTER);addView(status,new LayoutParams(-1,-2));
        targets=new LinearLayout(context);targets.setGravity(Gravity.CENTER_VERTICAL);left=target("撤回");right=target("换行");level=new Level(context);targets.addView(left,new LayoutParams(0,-1,1));targets.addView(level,new LayoutParams(0,-1,1.1f));targets.addView(right,new LayoutParams(0,-1,1));addView(targets,new LayoutParams(-1,0,1));
        preview=text(compact?13:16,compact?1:2);preview.setGravity(Gravity.CENTER);preview.setTextIsSelectable(false);addView(preview,new LayoutParams(-1,-2));
        footer=text(12,compact?1:2);footer.setGravity(Gravity.CENTER);footer.setTextColor(colors.muted);addView(footer,new LayoutParams(-1,-2));
        cancel=new Button(context);cancel.setText("取消本次语音");cancel.setAllCaps(false);cancel.setTextColor(colors.ink);cancel.setContentDescription("取消本次语音识别，不改之前输入");cancel.setVisibility(GONE);addView(cancel,new LayoutParams(-1,dp(48)));
        OnLayoutChangeListener changed=(v,l,t,r,b,ol,ot,or,ob)->post(this::publishRegions);addOnLayoutChangeListener(changed);left.addOnLayoutChangeListener(changed);right.addOnLayoutChangeListener(changed);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(int size,int lines){TextView v=new TextView(getContext());v.setTextSize(size);v.setTextColor(colors.ink);v.setMaxLines(lines);v.setEllipsize(TextUtils.TruncateAt.END);v.setPadding(dp(4),dp(3),dp(4),dp(3));return v;}
    private TextView target(String label){TextView v=text(compact?16:18,2);v.setGravity(Gravity.CENTER);v.setText(label);v.setAutoSizeTextTypeUniformWithConfiguration(10,compact?16:18,1,android.util.TypedValue.COMPLEX_UNIT_SP);v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);card(v,false,false);return v;}
    private void card(TextView view,boolean active,boolean discard){GradientDrawable shape=new GradientDrawable();shape.setCornerRadius(dp(compact?18:28));shape.setColor(active?(discard?blend(colors.surface,colors.danger,.18f):colors.selected):colors.surface);shape.setStroke(dp(active?2:1),active?(discard?colors.danger:colors.ink):colors.muted);view.setBackground(shape);view.setSelected(active);}
    private static int blend(int base,int tint,float part){return Color.rgb(Math.round(Color.red(base)*(1-part)+Color.red(tint)*part),Math.round(Color.green(base)*(1-part)+Color.green(tint)*part),Math.round(Color.blue(base)*(1-part)+Color.blue(tint)*part));}
    public void coordinateSpace(ViewGroup stableSurface,RegionsListener listener){coordinates=stableSurface;regions=listener;post(this::publishRegions);}
    public void waitingCancel(CancelListener action){cancel.setOnClickListener(v->{if(action!=null&&state!=null&&state.token!=null&&state.phase==VoiceHoldController.Phase.WAITING_RESULT)action.cancel(state.token);});}
    public void setState(VoiceHoldController.State value){state=value;boolean waiting=value.phase==VoiceHoldController.Phase.WAITING_RESULT;status.setText(value.finalResult?"识别已完成，松手确认":waiting?"本地识别中，完成后按松手位置处理":value.status);preview.setText(value.preview);left.setText("撤回");right.setText(value.rightLabel);left.setContentDescription("拖入后高亮，松手仅丢弃本次语音预览");right.setContentDescription("拖入后高亮，松手结束并"+value.rightLabel);card(left,value.highlighted==VoiceHoldController.Target.LEFT_DISCARD,true);card(right,value.highlighted==VoiceHoldController.Target.RIGHT_ACTION,false);targets.setVisibility(waiting?GONE:VISIBLE);cancel.setVisibility(waiting?VISIBLE:GONE);
        footer.setText(value.highlighted==VoiceHoldController.Target.LEFT_DISCARD?"松手撤回本次语音":value.highlighted==VoiceHoldController.Target.RIGHT_ACTION?"松手结束并"+value.rightLabel:value.finalResult?"松手确认；左侧仅撤回本次语音":"按住说话 · 松手结束");adapt();post(this::publishRegions);}
    private void adapt(){if(state==null)return;boolean waiting=state.phase==VoiceHoldController.Phase.WAITING_RESULT;boolean tight=getHeight()>0&&getHeight()<dp(160);status.setVisibility(waiting||!tight?VISIBLE:GONE);preview.setVisibility(state.preview.isEmpty()?GONE:VISIBLE);footer.setVisibility(waiting?GONE:VISIBLE);}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);adapt();post(this::publishRegions);}
    private VoiceHoldController.Region bounds(View child){if(coordinates==null||!child.isShown()||child.getWidth()<1||child.getHeight()<1)return null;Rect r=new Rect();child.getDrawingRect(r);try{coordinates.offsetDescendantRectToMyCoords(child,r);}catch(IllegalArgumentException detached){return null;}if(r.width()<1||r.height()<1)return null;return new VoiceHoldController.Region(r.left,r.top,r.right,r.bottom);}
    private void publishRegions(){if(regions==null||state==null||state.token==null||state.phase!=VoiceHoldController.Phase.HOLDING)return;regions.changed(state.token,bounds(left),bounds(right));}
    /** Call only with an actual local PCM/RMS measurement; default is a static quiet baseline. */
    public void realLevel(float value){level.value=Float.isNaN(value)?0:Math.max(0,Math.min(1,value));level.invalidate();}
    private final class Level extends View {
        float value;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Level(Context context){super(context);setContentDescription("本地语音音量");}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);paint.setColor(colors.muted);paint.setStrokeWidth(dp(3));paint.setStrokeCap(Paint.Cap.ROUND);float middle=getHeight()/2f,spacing=getWidth()/10f;for(int i=1;i<=9;i++){float envelope=1-Math.abs(i-5)/5f;float height=dp(2)+Math.max(0,getHeight()*.65f-dp(4))*value*envelope;canvas.drawLine(spacing*i,middle-height/2,spacing*i,middle+height/2,paint);}}
    }
}
