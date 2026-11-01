package org.wangshu.ime;

import java.util.List;
import java.util.Locale;

/** Exact, local, user-authored phrase continuation; not a general language model. */
final class PersonalPhraseSuggestions {
    enum Language { CHINESE, ENGLISH }
    private static final int MAX_CONTEXT=256,MAX_PHRASES=1000,MAX_PHRASE=128,MAX_OUTPUT=32;
    private PersonalPhraseSuggestions(){}
    static String suggest(String before,String after,Language language,List<String> phrases){
        if(before==null||after==null||language==null||phrases==null||before.length()>32768||after.length()>32768||before.isEmpty()||!after.isEmpty())return null;
        String tail=before.substring(Math.max(0,before.length()-MAX_CONTEXT));String best=null;int bestPrefix=0,limit=Math.min(MAX_PHRASES,phrases.size());
        for(int n=0;n<limit;n++){
            if(Thread.currentThread().isInterrupted())return null;
            String phrase=phrases.get(n);if(phrase==null||phrase.length()<3||phrase.length()>MAX_PHRASE)continue;
            if(language==Language.ENGLISH){
                if(!phrase.matches("[A-Za-z]+(?:[ '][A-Za-z]+)+")||!tail.endsWith(" "))continue;
                for(int end=phrase.lastIndexOf(' ');end>0;end=phrase.lastIndexOf(' ',end-1)){
                    int length=end+1,start=tail.length()-length;if(start<0||length<=bestPrefix||!tail.regionMatches(true,start,phrase,0,length))continue;
                    if(start>0&&Character.isLetterOrDigit(tail.charAt(start-1)))continue;
                    int next=phrase.indexOf(' ',length);String word=phrase.substring(length,next<0?phrase.length():next);
                    if(!word.isEmpty()&&word.length()<=MAX_OUTPUT){best=word;bestPrefix=length;}break;
                }
            }else{
                boolean chinese=true;for(int i=0;i<phrase.length();){int cp=phrase.codePointAt(i);if(Character.UnicodeScript.of(cp)!=Character.UnicodeScript.HAN){chinese=false;break;}i+=Character.charCount(cp);}if(!chinese)continue;
                for(int end=phrase.offsetByCodePoints(0,phrase.codePointCount(0,phrase.length())-1);end>=2;){
                    int start=tail.length()-end;if(phrase.codePointCount(0,end)>=2&&start>=0&&end>bestPrefix&&tail.regionMatches(start,phrase,0,end)){String rest=phrase.substring(end);if(rest.length()<=MAX_OUTPUT){best=rest;bestPrefix=end;}break;}
                    if(end==0)break;end=phrase.offsetByCodePoints(end,-1);
                }
            }
        }
        return best;
    }
}
