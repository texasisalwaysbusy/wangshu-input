package org.wangshu.ime;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;

/** Separate normal editor so keyboard keys never edit the previous host by mistake. */
public final class ClipboardEditActivity extends Activity {
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){super.onCreate(state);String id=getIntent().getStringExtra("id");try{JSONArray rows=ClipboardStore.get(this).list(System.currentTimeMillis(),getSharedPreferences("clipboard",0).getInt("days",7));String found=null;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("id").equals(id))found=rows.getJSONObject(i).getString("text");if(found==null)throw new java.io.IOException("条目不存在");final String original=found;
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(20),dp(20),dp(20),dp(20));panel.setBackgroundColor(getColor(R.color.paper));ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(panel);if(Build.VERSION.SDK_INT>=30)scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));return insets;});setContentView(scroll);
        TextView title=new TextView(this);title.setText("编辑历史条目");title.setTextSize(24);title.setTextColor(getColor(R.color.ink));panel.addView(title);TextView info=new TextView(this);info.setText("只修改这条历史，保留系统当前剪贴板及其他条目。");info.setTextColor(getColor(R.color.muted));panel.addView(info);EditText input=new EditText(this);input.setContentDescription("剪贴板历史内容");input.setText(state==null?original:state.getString("draft",original));input.setMinLines(4);input.setMaxLines(12);input.setGravity(Gravity.TOP);panel.addView(input,new LinearLayout.LayoutParams(-1,-2));draft=input;
        Button save=new Button(this);save.setText("保存条目");KeyboardSkin.key(this,save,false);save.setOnClickListener(v->{try{ClipboardStore.get(this).edit(id,original,input.getText().toString());((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);finish();}catch(java.io.IOException e){input.setError(e.getMessage());}});panel.addView(save,new LinearLayout.LayoutParams(-1,dp(56)));Button cancel=new Button(this);cancel.setText("取消");KeyboardSkin.key(this,cancel,false);cancel.setOnClickListener(v->finish());panel.addView(cancel,new LinearLayout.LayoutParams(-1,dp(48)));ThemeBars.navigation(this,getWindow());
    }catch(Exception e){new AlertDialog.Builder(this).setTitle("条目暂不可编辑").setMessage("条目可能已到期或改变，原数据保留，请重新打开历史面板。").setPositiveButton("返回",(d,w)->finish()).setOnCancelListener(d->finish()).show();}}
    private EditText draft;
    @Override protected void onSaveInstanceState(Bundle out){if(draft!=null)out.putString("draft",draft.getText().toString());super.onSaveInstanceState(out);}
}
