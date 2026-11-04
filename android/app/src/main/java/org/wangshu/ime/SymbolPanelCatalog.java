package org.wangshu.ime;

/** Static mobile presentation data, independent of dictionary readiness and input mode.
 * Returned arrays are copies; no text, editor state, or preference is retained here. */
final class SymbolPanelCatalog {
    static final String DEFAULT_CATEGORY="常用";
    private static final String[] CATEGORIES={"常用","英文","标点","希腊","运算","关系","集合","逻辑","箭头","上下标","分数","表情","颜文字"};
    private static final String[] COMMON={
        "_","下划线","@","at符号","/","正斜线","-","连字符","，","中文逗号","。","中文句号",
        ",","英文逗号",".","英文句点","?","英文问号","!","英文叹号","？","中文问号","！","中文叹号",
        ":","英文冒号",";","英文分号","'","英文单引号","\"","英文双引号","(","左圆括号",")","右圆括号",
        "+","加号","=","等号","#","井号","%","百分号","&","和号","*","星号"
    };
    private static final String[] ENGLISH={
        "_","下划线","@","at符号","/","正斜线","\\","反斜线","-","连字符","+","加号",
        "=","等号",",","英文逗号",".","英文句点","?","英文问号","!","英文叹号",":","英文冒号",
        ";","英文分号","'","英文单引号","\"","英文双引号","`","反引号","~","波浪号","|","竖线",
        "(","左圆括号",")","右圆括号","[","左方括号","]","右方括号","{","左花括号","}","右花括号",
        "<","小于号",">","大于号","#","井号","$","美元符号","%","百分号","^","脱字符",
        "&","和号","*","星号"
    };
    static String[] categories(){return CATEGORIES.clone();}
    static String label(String category){return "标点".equals(category)?"中文标点":category;}
    /** null means use the existing native symbol category. */
    static String[] builtinSymbols(String category){
        if(DEFAULT_CATEGORY.equals(category))return COMMON.clone();
        if("英文".equals(category))return ENGLISH.clone();
        return null;
    }
    /** Smallest nonnegative scroll change exposing a selected tab. */
    static int visibleOffset(int current,int viewport,int left,int right){
        current=Math.max(0,current);if(viewport<=0)return current;
        left=Math.max(0,left);right=Math.max(left,right);
        if(left<current||right-left>viewport)return left;
        if((long)right>(long)current+viewport)return Math.max(0,right-viewport);
        return current;
    }
    private SymbolPanelCatalog(){}
}
