package org.wangshu.ime;

import java.util.*;

/** Original bounded, conservative full-Pinyin expansion. No context, audio, dictionary or network access. */
public final class FuzzyPinyin {
    public static final int DEFAULT_CAP=8,MAX_KEYS=16,MAX_EDITS=2,MAX_KEY_LENGTH=64,MAX_SYLLABLES=16,MAX_ATTEMPTS=1024,MAX_CANDIDATE_PAIRS=128,MAX_CANDIDATE_SCAN=2048;
    private FuzzyPinyin(){}
    public enum Pair {
        Z_ZH("z_zh","z / zh","z","zh",true),C_CH("c_ch","c / ch","c","ch",true),S_SH("s_sh","s / sh","s","sh",true),
        N_L("n_l","n / l","n","l",true),F_H("f_h","f / h","f","h",true),
        AN_ANG("an_ang","an / ang","an","ang",false),EN_ENG("en_eng","en / eng","en","eng",false),IN_ING("in_ing","in / ing","in","ing",false);
        public final String preferenceKey,label,left,right;final boolean initial;
        Pair(String id,String name,String a,String b,boolean onset){preferenceKey="fuzzy."+id;label=name;left=a;right=b;initial=onset;}
    }
    public interface BooleanReader {boolean enabled(String preferenceKey);}
    /** The Activity and host tests use this same mapping: absent booleans must return false. */
    public static Set<Pair> enabledFrom(BooleanReader reader){EnumSet<Pair> out=EnumSet.noneOf(Pair.class);if(reader!=null)for(Pair pair:Pair.values())if(reader.enabled(pair.preferenceKey))out.add(pair);return Collections.unmodifiableSet(out);}
    public static List<String> expand(String key,Set<Pair> enabledPairs,int cap){return analyze(key,enabledPairs,cap).keys;}
    /** Callers must supply actual input mode; a string alone cannot distinguish English 'fan' from Pinyin. */
    public static List<String> forInput(String key,Set<Pair> pairs,int cap,boolean chineseFullPinyin,boolean password,boolean mathematical){return expand(key,chineseFullPinyin&&!password&&!mathematical?pairs:Collections.emptySet(),cap);}
    static final class Expansion {
        final List<String> keys;final int attempts,syllables,maxEdits;final boolean truncated;
        Expansion(Collection<String> values,int work,int units,int edits,boolean limited){keys=Collections.unmodifiableList(new ArrayList<>(values));attempts=work;syllables=units;maxEdits=edits;truncated=limited;}
    }
    private static final class Unit {
        final String text;final boolean apostrophe;
        Unit(String value,boolean separator){text=value;apostrophe=separator;}
    }
    private static final class Path {
        final String text;final Path next;final int count;
        Path(String value,Path tail){text=value;next=tail;count=1+(tail==null?0:tail.count);}
    }
    private static final class Node {
        final String[] parts;final int edits;
        Node(String[] values,int depth){parts=values;edits=depth;}
    }
    /** A handwritten conservative common-Mandarin table, not extracted from third-party dictionary data. */
    private static final Set<String> SYLLABLES=syllables();
    private static Set<String> syllables(){
        HashSet<String> out=new HashSet<>();
        add(out,"","a ai an ang ao e ei en eng er o ou");
        add(out,"b","a ai an ang ao ei en eng i ian iao ie in ing o u");
        add(out,"p","a ai an ang ao ei en eng i ian iao ie in ing o ou u");
        add(out,"m","a ai an ang ao e ei en eng i ian iao ie in ing iu o ou u");
        add(out,"f","a an ang ei en eng o ou u");
        add(out,"d","a ai an ang ao e ei en eng i ia ian iao ie ing iu ong ou u uan ui un uo");
        add(out,"t","a ai an ang ao e eng i ian iao ie ing ong ou u uan ui un uo");
        add(out,"n","a ai an ang ao e ei en eng i ian iang iao ie in ing iu ong ou u uan uo v ve");
        add(out,"l","a ai an ang ao e ei eng i ia ian iang iao ie in ing iu ong ou u uan un uo v ve");
        add(out,"g","a ai an ang ao e ei en eng ong ou u ua uai uan uang ui un uo");
        add(out,"k","a ai an ang ao e en eng ong ou u ua uai uan uang ui un uo");
        add(out,"h","a ai an ang ao e ei en eng ong ou u ua uai uan uang ui un uo");
        for(String onset:new String[]{"j","q","x"})add(out,onset,"i ia ian iang iao ie in ing iong iu u uan ue un");
        add(out,"zh","a ai an ang ao e ei en eng i ong ou u ua uai uan uang ui un uo");
        add(out,"ch","a ai an ang ao e en eng i ong ou u ua uai uan uang ui un uo");
        add(out,"sh","a ai an ang ao e ei en eng i ou u ua uai uan uang ui un uo");
        add(out,"r","an ang ao e en eng i ong ou u uan ui un uo");
        add(out,"z","a ai an ang ao e ei en eng i ong ou u uan ui un uo");
        add(out,"c","a ai an ang ao e en eng i ong ou u uan ui un uo");
        add(out,"s","a ai an ang ao e en eng i ong ou u uan ui un uo");
        add(out,"y","a an ang ao e i in ing ong ou u uan ue un");
        add(out,"w","a ai an ang ei en eng o u");
        // Standalone nasal interjections are left to exact lookup: treating a trailing
        // n/m/ng as another syllable would turn incomplete strings such as cin into chin.
        return Collections.unmodifiableSet(out);
    }
    private static void add(Set<String> out,String onset,String finals){for(String end:finals.split(" "))out.add(onset+end);}
    private static boolean letters(String key){if(key.charAt(0)=='\''||key.charAt(key.length()-1)=='\'')return false;char previous=0;for(int i=0;i<key.length();i++){char c=key.charAt(i);if(c=='\''){if(previous=='\'')return false;}else if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')))return false;previous=c;}return true;}
    /** Minimal syllable count, then longest first unit. This is a limited heuristic, not a linguistic parser. */
    private static List<Unit> segment(String key){
        ArrayList<Unit> out=new ArrayList<>();int offset=0;
        for(String chunk:key.split("'",-1)){
            Path[] best=new Path[chunk.length()+1];
            for(int at=chunk.length()-1;at>=0;at--)for(int length=1;length<=6&&at+length<=chunk.length();length++){
                String part=chunk.substring(at,at+length);if(!SYLLABLES.contains(part))continue;
                int end=at+length;if(end<chunk.length()&&best[end]==null)continue;
                Path candidate=new Path(part,best[end]);Path previous=best[at];
                if(previous==null||candidate.count<previous.count||(candidate.count==previous.count&&part.length()>previous.text.length()))best[at]=candidate;
            }
            if(best[0]==null)return null;
            boolean first=true;for(Path path=best[0];path!=null;path=path.next){out.add(new Unit(path.text,first&&offset>0));if(out.size()>MAX_SYLLABLES)return null;first=false;}
            offset+=chunk.length()+1;
        }
        return out;
    }
    private static String initial(String syllable){if(syllable.startsWith("zh")||syllable.startsWith("ch")||syllable.startsWith("sh"))return syllable.substring(0,2);char c=syllable.charAt(0);return "bpmfdtnlgkhjqxrzcsyw".indexOf(c)>=0?syllable.substring(0,1):"";}
    private static String substitute(String syllable,Pair pair){
        String onset=initial(syllable),end=syllable.substring(onset.length()),changed=null;
        if(pair.initial){if(onset.equals(pair.left))changed=pair.right+end;else if(onset.equals(pair.right))changed=pair.left+end;}
        else if(end.endsWith(pair.right))changed=onset+end.substring(0,end.length()-pair.right.length())+pair.left;
        else if(end.endsWith(pair.left))changed=onset+end.substring(0,end.length()-pair.left.length())+pair.right;
        return changed!=null&&SYLLABLES.contains(changed)?changed:null;
    }
    private static String join(String[] parts,List<Unit> original){StringBuilder out=new StringBuilder();for(int i=0;i<parts.length;i++){if(original.get(i).apostrophe)out.append('\'');out.append(parts[i]);}return out.toString();}
    static Expansion analyze(String raw,Set<Pair> enabledPairs,int cap){
        int limit=Math.max(0,Math.min(MAX_KEYS,cap));LinkedHashSet<String> out=new LinkedHashSet<>();
        if(raw==null||raw.isEmpty()||limit==0)return new Expansion(out,0,0,0,false);out.add(raw);
        EnumSet<Pair> enabled=EnumSet.noneOf(Pair.class);if(enabledPairs!=null)for(Pair pair:Pair.values())if(enabledPairs.contains(pair))enabled.add(pair);
        if(limit==1||enabled.isEmpty()||raw.length()>MAX_KEY_LENGTH)return new Expansion(out,0,0,0,false);
        // Validate raw ASCII before lowercasing: Unicode case conversion can map the
        // Kelvin sign to ASCII k, which must not turn mathematical text into Pinyin.
        if(!letters(raw))return new Expansion(out,0,0,0,false);String key=raw.toLowerCase(Locale.ROOT);
        List<Unit> units=segment(key);if(units==null)return new Expansion(out,0,0,0,false);
        String[] first=new String[units.size()];for(int i=0;i<first.length;i++)first[i]=units.get(i).text;
        ArrayDeque<Node> queue=new ArrayDeque<>();queue.add(new Node(first,0));HashSet<String> seen=new HashSet<>();seen.add(key);int work=0,maxDepth=0;
        while(!queue.isEmpty()){
            Node node=queue.removeFirst();if(node.edits>=MAX_EDITS)continue;
            for(int i=0;i<node.parts.length;i++)for(Pair pair:enabled){
                if(work>=MAX_ATTEMPTS)return new Expansion(out,work,units.size(),maxDepth,true);work++;
                String changed=substitute(node.parts[i],pair);if(changed==null)continue;
                String[] parts=node.parts.clone();parts[i]=changed;String variant=join(parts,units);
                if(variant.length()>MAX_KEY_LENGTH||!seen.add(variant))continue;
                out.add(variant);maxDepth=Math.max(maxDepth,node.edits+1);
                if(out.size()>=limit)return new Expansion(out,work,units.size(),maxDepth,true);
                queue.addLast(new Node(parts,node.edits+1));
            }
        }
        return new Expansion(out,work,units.size(),maxDepth,false);
    }
    /** Flat [text, annotation] pairs. Exact candidates must be complete in the caller's bounded pool. */
    public static String[] mergeExactFirst(String[] exact,List<String[]> variants,int pairCap){
        return mergeExactFirst(exact,variants,pairCap,true);
    }
    /** When another exact page may exist, callers must pass false: no fuzzy candidate is appended. */
    public static String[] mergeExactFirst(String[] exact,List<String[]> variants,int pairCap,boolean exactPoolComplete){
        int cap=Math.max(0,Math.min(pairCap,MAX_CANDIDATE_PAIRS));LinkedHashMap<String,String> out=new LinkedHashMap<>();
        int[] budget={MAX_CANDIDATE_SCAN};boolean exactComplete=append(out,exact,cap,budget);
        if(exactPoolComplete&&exactComplete&&variants!=null&&out.size()<cap)for(int i=0;i<Math.min(variants.size(),MAX_KEYS-1)&&out.size()<cap;i++)if(!append(out,variants.get(i),cap,budget))break;
        ArrayList<String> result=new ArrayList<>(out.size()*2);for(Map.Entry<String,String> pair:out.entrySet()){result.add(pair.getKey());result.add(pair.getValue());}return result.toArray(new String[0]);
    }
    private static boolean append(LinkedHashMap<String,String> out,String[] pairs,int cap,int[] budget){
        if(pairs==null)return true;if((pairs.length&1)!=0)throw new IllegalArgumentException("Malformed candidate pairs");
        int i=0;for(;i<pairs.length&&out.size()<cap;i+=2){if(budget[0]<=0)return false;budget[0]--;if(pairs[i]==null||pairs[i].isEmpty()||pairs[i+1]==null)throw new IllegalArgumentException("Malformed candidate pair");if(!out.containsKey(pairs[i]))out.put(pairs[i],pairs[i+1]);}return i==pairs.length;
    }
}
