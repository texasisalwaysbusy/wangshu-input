package org.wangshu.ime;

import java.util.*;

/** Bounded lazy exact-then-fuzzy pages. Contains only caller-supplied, instance-local data. */
public final class CandidatePages {
    public static final int PAGE_SIZE=9,MAX_INITIAL_PAIRS=3072,MAX_EXACT_PAIRS=4096,MAX_NATIVE_PAGE=100,MAX_REQUESTED_PAGE=512,MAX_VARIANTS=7,MAX_VARIANT_KEY_LENGTH=64;
    public interface PageSource {String[] page(int index);}
    public interface VariantSource {String[] page(String key);}
    private static final class Entry {
        final String text,note;
        Entry(String value,String annotation){text=value;note=annotation;}
    }
    private static final class Resolved {
        final String[] values;final boolean fuzzy;
        Resolved(String[] pairs,boolean variant){values=pairs;fuzzy=variant;}
    }
    private final PageSource exactSource;private final VariantSource variantSource;private final List<String> keys;
    private final ArrayList<Entry> exact=new ArrayList<>(),fuzzy=new ArrayList<>();
    private final HashSet<String> exactSeen=new HashSet<>(),fuzzySeen=new HashSet<>();
    private int nextExact,nextVariant;private boolean exactEnded,exactCut,loading;private RuntimeException failure;

    public CandidatePages(String[] initialExactPairs,PageSource exact,List<String> variantKeys,VariantSource variants){
        if(exact==null)throw new IllegalArgumentException("Missing exact page source");exactSource=exact;
        String[] initial=checked(initialExactPairs,MAX_INITIAL_PAIRS);
        LinkedHashSet<String> distinct=new LinkedHashSet<>();
        if(variantKeys!=null){if(variantKeys.size()>MAX_VARIANTS)throw new IllegalArgumentException("Too many variant keys");for(String key:variantKeys){if(key==null||key.isEmpty()||key.length()>MAX_VARIANT_KEY_LENGTH)throw new IllegalArgumentException("Invalid variant key");distinct.add(key);}}
        if(!distinct.isEmpty()&&variants==null)throw new IllegalArgumentException("Missing variant page source");
        keys=Collections.unmodifiableList(new ArrayList<>(distinct));variantSource=variants;
        for(int i=0;i<initial.length;i+=2)if(exactSeen.add(initial[i]))this.exact.add(new Entry(initial[i],initial[i+1]));
    }
    /** Each returned array is independent. Invalid indices and malformed provider arrays fail closed. */
    public synchronized String[] page(int index){return resolve(index).values;}
    /** May resolve this page, but never a label-only lookahead. Empty pages are not fuzzy pages. */
    public synchronized boolean isFuzzyPage(int index){return resolve(index).fuzzy;}
    /** Pure read: true only for known exact native/cap cutoff; does not probe any source. */
    public synchronized boolean truncated(){return exactCut;}

    private Resolved resolve(int index){
        if(index<0||index>MAX_REQUESTED_PAGE)throw new IllegalArgumentException("Candidate page out of range");
        if(failure!=null)throw failure;if(loading)throw new IllegalStateException("Reentrant candidate query");
        int needed=(index+1)*PAGE_SIZE;
        while(!exactEnded&&!exactCut&&exact.size()<needed)readExact();
        int start=index*PAGE_SIZE;
        if(start<exact.size())return new Resolved(slice(exact,start),false);
        if(!exactEnded||exactCut)return new Resolved(new String[0],false);
        int firstFuzzy=(exact.size()+PAGE_SIZE-1)/PAGE_SIZE;
        int variantStart=(index-firstFuzzy)*PAGE_SIZE;
        if(variantStart<0)return new Resolved(new String[0],false);
        while(nextVariant<keys.size()&&fuzzy.size()<variantStart+PAGE_SIZE)readVariant();
        String[] result=slice(fuzzy,variantStart);return new Resolved(result,result.length>0);
    }
    private void readExact(){
        if(nextExact>MAX_NATIVE_PAGE){exactCut=true;return;}
        final int index=nextExact;String[] pairs;
        loading=true;
        try{pairs=checked(exactSource.page(index),PAGE_SIZE);}
        catch(RuntimeException invalid){failure=invalid instanceof IllegalArgumentException?new IllegalArgumentException("Malformed exact candidate page"):new IllegalStateException("Exact candidate source failed");throw failure;}
        finally{loading=false;}
        nextExact++;
        for(int i=0;i<pairs.length;i+=2)if(!exactSeen.contains(pairs[i])){
            if(exact.size()>=MAX_EXACT_PAIRS){exactCut=true;return;}
            exactSeen.add(pairs[i]);exact.add(new Entry(pairs[i],pairs[i+1]));
        }
        if(pairs.length<PAGE_SIZE*2)exactEnded=true;
        else if(index==MAX_NATIVE_PAGE)exactCut=true; // Never query 101 and mistake its forced empty result for completion.
    }
    private void readVariant(){
        String key=keys.get(nextVariant);String[] pairs;loading=true;
        try{pairs=checked(variantSource.page(key),PAGE_SIZE);}
        catch(RuntimeException invalid){failure=invalid instanceof IllegalArgumentException?new IllegalArgumentException("Malformed fuzzy candidate page"):new IllegalStateException("Fuzzy candidate source failed");throw failure;}
        finally{loading=false;}
        nextVariant++;
        for(int i=0;i<pairs.length;i+=2)if(!exactSeen.contains(pairs[i])&&fuzzySeen.add(pairs[i]))fuzzy.add(new Entry(pairs[i],pairs[i+1]));
    }
    private static String[] checked(String[] pairs,int max){
        if(pairs==null||(pairs.length&1)!=0||pairs.length/2>max)throw new IllegalArgumentException("Invalid candidate array");
        String[] copy=pairs.clone();for(int i=0;i<copy.length;i+=2)if(copy[i]==null||copy[i].isEmpty()||copy[i+1]==null)throw new IllegalArgumentException("Invalid candidate pair");return copy;
    }
    private static String[] slice(List<Entry> source,int start){
        if(start<0||start>=source.size())return new String[0];int count=Math.min(PAGE_SIZE,source.size()-start);String[] out=new String[count*2];
        for(int i=0;i<count;i++){Entry entry=source.get(start+i);out[i*2]=entry.text;out[i*2+1]=entry.note;}return out;
    }
}
