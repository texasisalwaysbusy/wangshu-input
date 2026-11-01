package org.wangshu.ime;

import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Native, scrollable picker. It renders authored Unicode sequences without capturing editor text. */
final class EmojiPanel {
    interface Host {void category(String name);void choose(EmojiCatalog.Item item);void bind(ScrollView items,HorizontalScrollView tabs);}
    private static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static View build(Context context,EmojiCatalog.Kind kind,String requested,boolean compact,Host host){
        List<String> names=EmojiCatalog.categories(kind);if(names.isEmpty())return new Space(context);
        String selected=names.contains(requested)?requested:names.get(0);
        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setContentDescription(kind==EmojiCatalog.Kind.EMOJI?"表情选择面板":"颜文字选择面板");
        HorizontalScrollView tabScroller=null;
        if(names.size()>1){HorizontalScrollView tabs=new HorizontalScrollView(context);tabScroller=tabs;tabs.setHorizontalScrollBarEnabled(false);tabs.setContentDescription("表情分类，可左右滚动");LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.HORIZONTAL);
            for(String name:names){Button b=new Button(context);b.setAllCaps(false);b.setText(name);b.setTextSize(14);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);b.setSelected(name.equals(selected));b.setContentDescription("表情分类 "+name);KeyboardSkin.function(context,b,name.equals(selected));b.setOnClickListener(v->host.category(name));row.addView(b,new LinearLayout.LayoutParams(dp(context,72),dp(context,48)));}
            tabs.addView(row);content.addView(tabs,new LinearLayout.LayoutParams(-1,dp(context,48)));
        }
        ScrollView scroll=new ScrollView(context);scroll.setContentDescription(kind==EmojiCatalog.Kind.EMOJI?"表情列表，可上下滚动":"颜文字列表，可上下滚动");LinearLayout rows=new LinearLayout(context);rows.setOrientation(LinearLayout.VERTICAL);
        int screen=Math.max(96,context.getResources().getConfiguration().screenWidthDp-8);int columns=Math.max(2,Math.min(kind==EmojiCatalog.Kind.EMOJI?6:3,screen/48));List<EmojiCatalog.Item> items=EmojiCatalog.category(selected);
        for(int start=0;start<items.size();start+=columns){LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.HORIZONTAL);
            for(int i=0;i<columns;i++){int index=start+i;if(index>=items.size()){row.addView(new Space(context),new LinearLayout.LayoutParams(0,dp(context,48),1));continue;}EmojiCatalog.Item item=items.get(index);Button b=new Button(context);b.setAllCaps(false);b.setText(item.text);b.setSingleLine(true);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(context,2),0,dp(context,2),0);b.setTextSize(kind==EmojiCatalog.Kind.EMOJI?24:16);b.setAutoSizeTextTypeUniformWithConfiguration(10,kind==EmojiCatalog.Kind.EMOJI?24:16,1,android.util.TypedValue.COMPLEX_UNIT_SP);b.setContentDescription((kind==EmojiCatalog.Kind.EMOJI?"表情 ":"颜文字 ")+item.description);b.setTooltipText(item.description);KeyboardSkin.key(context,b,false);b.setOnClickListener(v->host.choose(item));row.addView(b,new LinearLayout.LayoutParams(0,dp(context,48),1));}
            rows.addView(row);
        }
        scroll.addView(rows);content.addView(scroll,new LinearLayout.LayoutParams(-1,dp(context,compact?96:152)));host.bind(scroll,tabScroller);return content;
    }
    private EmojiPanel(){}
}
