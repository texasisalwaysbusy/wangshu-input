package org.wangshu.ime;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Exact, local, user-authored phrase continuation; not a general language model. */
final class PersonalPhraseSuggestions {
    enum Language { CHINESE, ENGLISH }
    private static final int MAX_CONTEXT=256,MAX_PHRASES=1000,MAX_PHRASE=128,MAX_OUTPUT=32;
    // Saved phrases only. This bounded ASCII-English grammar is not a language model.
    private static final Pattern ENGLISH_PHRASE=Pattern.compile("[A-Za-z]+(?:'[A-Za-z]+)*(?: [A-Za-z]+(?:'[A-Za-z]+)*)*");
    private PersonalPhraseSuggestions(){}
    static String suggest(String before,String after,Language language,List<String> phrases){
        if(before==null||after==null||language==null||phrases==null||before.length()>32768||after.length()>32768||before.isEmpty())return null;
        if(!after.isEmpty()&&!safeMiddleContext(before,after))return null;
        String tail=before.substring(Math.max(0,before.length()-MAX_CONTEXT));String best=null;int bestPrefix=0,limit=Math.min(MAX_PHRASES,phrases.size());
        for(int n=0;n<limit;n++){
            if(Thread.currentThread().isInterrupted())return null;
            String phrase=phrases.get(n);if(phrase==null||phrase.length()<3||phrase.length()>MAX_PHRASE)continue;
            if(language==Language.ENGLISH){
                if(!ENGLISH_PHRASE.matcher(phrase).matches())continue;
                for(int length=Math.min(tail.length(),phrase.length()-1);length>=2;length--){
                    int start=tail.length()-length;
                    if(length<=bestPrefix||!wordBoundary(tail,start)||!englishPrefix(tail,start,phrase,length))continue;
                    boolean nextWord=phrase.charAt(length-1)==' ';
                    // A complete word without a following space is not a next-word request.
                    if(phrase.charAt(length)==' ')continue;
                    int wordStart=phrase.lastIndexOf(' ',length-1)+1;
                    String typed=tail.substring(start+wordStart);
                    if(!nextWord&&letterCount(typed)<2)continue;
                    int next=phrase.indexOf(' ',length);
                    String suffix=phrase.substring(length,next<0?phrase.length():next);
                    if(!nextWord&&allUppercase(typed))suffix=suffix.toUpperCase(Locale.ROOT);
                    if(!suffix.isEmpty()&&suffix.length()<=MAX_OUTPUT&&safeSuffix(suffix,phrase.substring(wordStart,next<0?phrase.length():next),after)){best=suffix;bestPrefix=length;}break;
                }
            }else{
                boolean chinese=true;for(int i=0;i<phrase.length();){int cp=phrase.codePointAt(i);if(Character.UnicodeScript.of(cp)!=Character.UnicodeScript.HAN){chinese=false;break;}i+=Character.charCount(cp);}if(!chinese)continue;
                for(int end=phrase.offsetByCodePoints(0,phrase.codePointCount(0,phrase.length())-1);end>=2;){
                    int start=tail.length()-end;if(phrase.codePointCount(0,end)>=2&&start>=0&&end>bestPrefix&&(after.isEmpty()||wordBoundary(tail,start))&&tail.regionMatches(start,phrase,0,end)){String rest=phrase.substring(end);if(rest.length()<=MAX_OUTPUT&&safeSuffix(rest,phrase,after)){best=rest;bestPrefix=end;}break;}
                    if(end==0)break;end=phrase.offsetByCodePoints(end,-1);
                }
            }
        }
        return Thread.currentThread().isInterrupted()?null:best;
    }
    // Conservative mid-field support: append at a clear prose boundary only.
    // Never replace suffix text, infer a word split, or complete URL/code tokens.
    private static boolean safeMiddleContext(String before,String after){
        int first=after.codePointAt(0);
        if(!proseSeparator(first))return false;
        int lineStart=Math.max(before.lastIndexOf('\n'),before.lastIndexOf('\r'))+1;
        String line=before.substring(Math.max(lineStart,before.length()-MAX_CONTEXT));
        for(int i=0;i<line.length();i++)if("@/\\_`=<>[]{}():|&+$#%*^~".indexOf(line.charAt(i))>=0)return false;
        // A dot in the current token may be a domain, filename or member access.
        int tokenStart=line.length();while(tokenStart>0&&!Character.isWhitespace(line.charAt(tokenStart-1)))tokenStart--;
        return line.indexOf('.',tokenStart)<0;
    }
    private static boolean proseSeparator(int cp){
        return Character.isWhitespace(cp)||",!?;，。！？；、…".indexOf(cp)>=0;
    }
    private static boolean safeSuffix(String suffix,String completed,String after){
        if(after.isEmpty())return true;
        int start=0;while(start<after.length()&&proseSeparator(after.codePointAt(start)))start+=Character.charCount(after.codePointAt(start));
        // Suppress duplicates even across spaces/punctuation rather than rewriting them.
        return start>=after.length()||(!after.regionMatches(true,start,suffix,0,suffix.length())&&!after.regionMatches(true,start,completed,0,completed.length()));
    }
    private static boolean wordBoundary(String text,int start){
        if(start==0)return true;
        int cp=text.codePointBefore(start),type=Character.getType(cp);
        return !Character.isLetterOrDigit(cp)&&cp!='_'&&cp!='\''&&cp!='\u2019'
                &&cp!='@'&&cp!='.'&&cp!='/'&&cp!='\\'
                &&type!=Character.NON_SPACING_MARK&&type!=Character.COMBINING_SPACING_MARK&&type!=Character.ENCLOSING_MARK;
    }
    private static boolean englishPrefix(String text,int start,String phrase,int length){
        for(int i=0;i<length;i++){
            char a=text.charAt(start+i),b=phrase.charAt(i);
            if(a>='A'&&a<='Z')a=(char)(a+'a'-'A');if(b>='A'&&b<='Z')b=(char)(b+'a'-'A');
            if(a!=b)return false;
        }
        return true;
    }
    private static int letterCount(String value){int n=0;for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c>='A'&&c<='Z'||c>='a'&&c<='z')n++;}return n;}
    private static boolean allUppercase(String value){
        boolean letter=false;for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c>='a'&&c<='z')return false;if(c>='A'&&c<='Z')letter=true;}return letter;
    }
}
