package org.wangshu.ime;
import java.util.*;

/** Direct production pure-class tests. No Android layout or recognition claims. */
public final class PresentationPolicyChecks {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void eq(Object actual,Object expected,String message){check(Objects.equals(actual,expected),message+": "+actual+" != "+expected);}
    static boolean contains(String[] rows,String value){for(int i=0;i<rows.length;i+=2)if(rows[i].equals(value))return true;return false;}
    static void catalog(){
        String[] categories=SymbolPanelCatalog.categories();
        eq(categories[0],SymbolPanelCatalog.DEFAULT_CATEGORY,"common is first/default");eq(categories[1],"英文","English punctuation second");eq(categories[2],"标点","native Chinese punctuation third");
        eq(new HashSet<>(Arrays.asList(categories)).size(),categories.length,"unique categories");
        for(String old:new String[]{"希腊","运算","关系","集合","逻辑","箭头","上下标","分数","标点","表情","颜文字"})check(Arrays.asList(categories).contains(old),"existing category retained "+old);
        categories[0]="mutated";eq(SymbolPanelCatalog.categories()[0],"常用","category array defensive copy");
        for(String category:new String[]{"常用","英文"}){
            String[] rows=SymbolPanelCatalog.builtinSymbols(category);check(rows!=null&&rows.length>0&&rows.length%2==0,"complete symbol/name pairs");Set<String> seen=new HashSet<>();
            for(int i=0;i<rows.length;i+=2){check(seen.add(rows[i]),"no duplicate payload "+category+" "+rows[i]);check(!rows[i].isEmpty()&&!rows[i+1].isEmpty(),"payload/accessibility name nonempty");check(rows[i].codePointCount(0,rows[i].length())==1,"one exact punctuation scalar");}
            check(contains(rows,"_"),"underscore directly available");check(Arrays.asList(Arrays.copyOfRange(rows,0,12)).contains("_"),"underscore first visible six-key row");
            rows[0]="mutated";check(!SymbolPanelCatalog.builtinSymbols(category)[0].equals("mutated"),"symbol array defensive copy");
        }
        String[] ascii=SymbolPanelCatalog.builtinSymbols("英文");int count=0;
        for(char c=33;c<127;c++)if(!Character.isLetterOrDigit(c)){check(contains(ascii,String.valueOf(c)),"ASCII punctuation complete "+c);count++;}
        eq(count,32,"all 32 printable ASCII punctuation characters");eq(ascii.length,64,"no unintended additional English payloads");
        eq(SymbolPanelCatalog.label("标点"),"中文标点","Chinese label discoverable");eq(SymbolPanelCatalog.label("英文"),"英文","English label stable");
        for(String nativeCategory:new String[]{"希腊","标点","表情","unknown"})eq(SymbolPanelCatalog.builtinSymbols(nativeCategory),null,"native/other categories delegate unchanged");
    }
    static void scroll(){
        eq(SymbolPanelCatalog.visibleOffset(0,320,0,72),0,"first tab already visible");
        eq(SymbolPanelCatalog.visibleOffset(0,320,576,648),328,"far selected tab reveals right edge");
        eq(SymbolPanelCatalog.visibleOffset(328,320,72,144),72,"returning left reveals selection");
        eq(SymbolPanelCatalog.visibleOffset(100,320,120,192),100,"visible selected tab does not jump");
        eq(SymbolPanelCatalog.visibleOffset(-5,320,-5,60),0,"negative state clamped");
        eq(SymbolPanelCatalog.visibleOffset(100,0,500,572),100,"unmeasured viewport does not invent offset");
        eq(SymbolPanelCatalog.visibleOffset(0,50,100,172),100,"oversized tab aligns start");
        eq(SymbolPanelCatalog.visibleOffset(Integer.MAX_VALUE-20,100,Integer.MAX_VALUE-10,Integer.MAX_VALUE),Integer.MAX_VALUE-20,"no addition overflow");
        for(int width:new int[]{240,280,320,360,420,600})for(int selected=0;selected<13;selected++)for(int previous=0;previous<13;previous++){
            int left=selected*72,right=left+72,offset=SymbolPanelCatalog.visibleOffset(previous*72,width,left,right);
            check(offset>=0&&left>=offset&&right<=offset+width,"selected tab fully visible "+width+"/"+selected+"/"+previous);
        }
    }
    static void voice(){
        for(String raw:new String[]{"","根号大于等于阿尔法","三点一四一百二十三二零二六年十一月三日","Please send the notes on Monday morning.","我把 Python 保存为 version two。","👩‍💻é\n","_ \\"})for(boolean ready:new boolean[]{false,true}){
            final int[] calls={0};String result=VoiceResultPolicy.RAW.apply(raw,ready,x->{calls[0]++;return "wrong";});
            check(result==raw,"RAW retains exact string object/no normalization");eq(calls[0],0,"RAW never calls converter");
        }
        final int[] calls={0};String raw="根号大于等于阿尔法";VoiceResultPolicy.MathConverter converter=text->{calls[0]++;check(text==raw,"converter receives exact raw");return "√≥α";};
        check(VoiceResultPolicy.MATH_SYMBOLS.apply(raw,false,converter)==raw,"unready dictionary preserves raw");eq(calls[0],0,"no converter while dictionary unavailable");
        eq(VoiceResultPolicy.MATH_SYMBOLS.apply(raw,true,converter),"√≥α","math explicit transform");eq(calls[0],1,"converter exactly once");
        VoiceResultPolicy captured=VoiceResultPolicy.MATH_SYMBOLS;VoiceResultPolicy nextRecording=VoiceResultPolicy.RAW;
        eq(captured.apply(raw,true,converter),"√≥α","changing a later recording's choice cannot mutate captured policy");eq(nextRecording.apply(raw,true,converter),raw,"new raw policy is independent");
    }
    public static void main(String[] args){catalog();scroll();voice();System.out.println("PASS "+checks+" direct production catalog/scroll/voice-policy checks; Android rendering and ASR NOT RUN.");}
}
