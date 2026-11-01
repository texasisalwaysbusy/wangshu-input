package org.wangshu.ime;

/** Ephemeral offsets only. No editor contents or persistent state are retained. */
final class EmojiScrollMemory {
    static final class Offsets {final int outer,tabs,items;Offsets(int outer,int tabs,int items){this.outer=outer;this.tabs=tabs;this.items=items;}}
    private long session=Long.MIN_VALUE;private String symbol="",category="";private int outer,tabs,items;
    void reset(long session){this.session=session;symbol="";category="";outer=tabs=items=0;}
    void save(long session,String symbol,String category,int outer,int tabs,int items){if(this.session!=session)return;this.symbol=symbol;this.category=category;this.outer=Math.max(0,outer);this.tabs=Math.max(0,tabs);this.items=Math.max(0,items);}
    Offsets get(long session,String symbol,String category){if(this.session!=session)return new Offsets(0,0,0);boolean sameSymbol=this.symbol.equals(symbol),sameCategory=sameSymbol&&this.category.equals(category);return new Offsets(outer,sameSymbol?tabs:0,sameCategory?items:0);}
}
