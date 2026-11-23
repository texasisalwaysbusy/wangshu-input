package org.wangshu.ime;

import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Native, scrollable picker. It renders authored Unicode sequences without capturing editor text. */
final class EmojiPanel {
    interface Host {
        void category(String name);
        void choose(EmojiCatalog.Item item);
        void bind(ScrollView items, HorizontalScrollView tabs);
        void openSearch();
        void closeSearch();
        void favorites(boolean open);
        void manageFavorites();
    }
    private static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static View build(Context context,EmojiCatalog.Kind kind,String requested,boolean compact,
                      boolean showFavorites,List<EmojiCatalog.Item> favorites,Host host){
        List<String> names=EmojiCatalog.categories(kind);if(names.isEmpty())return new Space(context);
        String selected=names.contains(requested)?requested:names.get(0);
        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setContentDescription(kind==EmojiCatalog.Kind.EMOJI?"表情选择面板":"颜文字选择面板");
        LinearLayout header = new LinearLayout(context);
        Button search = control(context, "检索", "按拼音检索表情或颜文字", host::openSearch);
        header.addView(search, new LinearLayout.LayoutParams(dp(context, 64), dp(context, 48)));
        if (favorites != null) {
            Button mode = control(context, showFavorites ? "分类" : "收藏",
                    showFavorites ? "返回表情分类" : "查看收藏表情", () -> host.favorites(!showFavorites));
            header.addView(mode, new LinearLayout.LayoutParams(dp(context, 64), dp(context, 48)));
        }
        HorizontalScrollView tabScroller=null;
        if(showFavorites){
            Button manage = control(context, "管理收藏", "打开本地收藏管理", host::manageFavorites);
            header.addView(manage, new LinearLayout.LayoutParams(dp(context, 92), dp(context, 48)));
        }else if(names.size()>1){HorizontalScrollView tabs=new HorizontalScrollView(context);tabScroller=tabs;tabs.setHorizontalScrollBarEnabled(false);tabs.setContentDescription("表情分类，可左右滚动");LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.HORIZONTAL);
            for(String name:names){Button b=new Button(context);b.setAllCaps(false);b.setText(name);b.setTextSize(14);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);b.setSelected(name.equals(selected));b.setContentDescription("表情分类 "+name);KeyboardSkin.function(context,b,name.equals(selected));b.setOnClickListener(v->host.category(name));row.addView(b,new LinearLayout.LayoutParams(dp(context,72),dp(context,48)));}
            tabs.addView(row);header.addView(tabs,new LinearLayout.LayoutParams(0,dp(context,48),1));
        }
        content.addView(header, new LinearLayout.LayoutParams(-1, dp(context, 48)));
        ScrollView scroll=new ScrollView(context);scroll.setContentDescription(kind==EmojiCatalog.Kind.EMOJI?"表情列表，可上下滚动":"颜文字列表，可上下滚动");LinearLayout rows=new LinearLayout(context);rows.setOrientation(LinearLayout.VERTICAL);
        int screen=Math.max(96,context.getResources().getConfiguration().screenWidthDp-8);int columns=Math.max(2,Math.min(kind==EmojiCatalog.Kind.EMOJI?6:3,screen/48));List<EmojiCatalog.Item> items=showFavorites?favorites:EmojiCatalog.category(selected);
        if(showFavorites&&items.isEmpty()){
            TextView empty=new TextView(context);
            empty.setText("这里还没有收藏");
            empty.setTextColor(context.getColor(R.color.muted));
            empty.setPadding(dp(context,8),dp(context,8),dp(context,8),0);
            rows.addView(empty);
        }
        for(int start=0;start<items.size();start+=columns){LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.HORIZONTAL);
            for(int i=0;i<columns;i++){int index=start+i;if(index>=items.size()){row.addView(new Space(context),new LinearLayout.LayoutParams(0,dp(context,48),1));continue;}EmojiCatalog.Item item=items.get(index);Button b=itemButton(context,item,()->host.choose(item));row.addView(b,new LinearLayout.LayoutParams(0,dp(context,48),1));}
            rows.addView(row);
        }
        scroll.addView(rows);content.addView(scroll,new LinearLayout.LayoutParams(-1,dp(context,compact?96:152)));host.bind(scroll,tabScroller);return content;
    }

    private static Button control(Context context, String label, String description, Runnable action) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setMinimumHeight(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription(description);
        KeyboardSkin.function(context, button, false);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private static Button itemButton(Context context, EmojiCatalog.Item item, Runnable action) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(item.text);
        button.setSingleLine(true);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 2), 0, dp(context, 2), 0);
        int size = item.kind == EmojiCatalog.Kind.EMOJI ? 24 : 16;
        button.setTextSize(size);
        button.setAutoSizeTextTypeUniformWithConfiguration(10, size, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        button.setContentDescription((item.kind == EmojiCatalog.Kind.EMOJI ? "表情 " : "颜文字 ")
                + item.description);
        button.setTooltipText(item.description);
        KeyboardSkin.key(context, button, false);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    static final class Search {
        final LinearLayout view;
        private final Host host;
        private final EmojiSearchQuery query;
        private final TextView queryText;
        private final HorizontalScrollView scroller;
        private final LinearLayout results;
        private final Button delete;
        private final int rowHeight;

        Search(Context context, EmojiCatalog.Kind kind, boolean compact, Host host) {
            this.host = host;
            query = new EmojiSearchQuery(kind);
            rowHeight = dp(context, compact ? 36 : 44);
            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setContentDescription(kind == EmojiCatalog.Kind.EMOJI ? "检索表情" : "检索颜文字");

            LinearLayout header = new LinearLayout(context);
            Button back = control(context, "返回", "返回表情分类", this::close);
            header.addView(back, new LinearLayout.LayoutParams(dp(context, 48), rowHeight));
            queryText = new TextView(context);
            queryText.setTextSize(15);
            queryText.setTextColor(context.getColor(R.color.ink));
            queryText.setGravity(Gravity.CENTER_VERTICAL);
            queryText.setSingleLine();
            queryText.setEllipsize(android.text.TextUtils.TruncateAt.START);
            queryText.setPadding(dp(context, 8), 0, dp(context, 8), 0);
            header.addView(queryText, new LinearLayout.LayoutParams(0, rowHeight, 1));
            delete = control(context, "⌫", "删除检索词末字母", this::backspace);
            header.addView(delete, new LinearLayout.LayoutParams(dp(context, 48), rowHeight));
            view.addView(header);

            scroller = new HorizontalScrollView(context);
            scroller.setContentDescription("检索结果，可左右滚动");
            results = new LinearLayout(context);
            scroller.addView(results);
            view.addView(scroller, new LinearLayout.LayoutParams(-1, rowHeight));

            for (String letters : new String[]{"qwertyuiop", "asdfghjkl", "zxcvbnm"}) {
                LinearLayout row = new LinearLayout(context);
                for (char letter : letters.toCharArray()) {
                    String label = String.valueOf(letter);
                    Button key = control(context, label, "检索字母 " + label, () -> append(letter));
                    row.addView(key, new LinearLayout.LayoutParams(0, rowHeight, 1));
                }
                view.addView(row);
            }
            render();
        }

        void append(int letter) { if (query.append(letter)) render(); }
        void backspace() { if (query.backspace()) render(); }
        void close() { host.closeSearch(); }
        void reset() {
            query.reset();
            render();
        }
        void chooseFirst() {
            List<EmojiCatalog.Item> displayed = query.results();
            if (!displayed.isEmpty()) select(displayed, displayed.get(0));
        }

        private void select(List<EmojiCatalog.Item> displayed, EmojiCatalog.Item item) {
            if (query.selectable(displayed, item)) host.choose(item);
        }

        private void render() {
            Context context = view.getContext();
            queryText.setText(query.text().isEmpty() ? "键入拼音" : query.text());
            delete.setEnabled(!query.text().isEmpty());
            results.removeAllViews();
            scroller.scrollTo(0, 0);
            List<EmojiCatalog.Item> displayed = query.results();
            if (displayed.isEmpty() && !query.text().isEmpty()) {
                TextView empty = new TextView(context);
                empty.setText("没有匹配项");
                empty.setTextColor(context.getColor(R.color.muted));
                empty.setGravity(Gravity.CENTER_VERTICAL);
                empty.setPadding(dp(context, 8), 0, dp(context, 8), 0);
                results.addView(empty, new LinearLayout.LayoutParams(-2, rowHeight));
            }
            for (EmojiCatalog.Item item : displayed) {
                int width = item.kind == EmojiCatalog.Kind.EMOJI ? 52 : 112;
                results.addView(itemButton(context, item, () -> select(displayed, item)),
                        new LinearLayout.LayoutParams(dp(context, width), rowHeight));
            }
        }
    }

    private EmojiPanel(){}
}
