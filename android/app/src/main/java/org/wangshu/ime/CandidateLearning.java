package org.wangshu.ime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * Bounded personal candidate overlay. No I/O, key capture, editor access, audio or network.
 * The caller must gate learning and call recordSelection only after confirmed host insertion.
 * Use copy -> mutate -> encode -> successful atomic persistence -> replace to commit externally.
 */
public final class CandidateLearning {
    public static final int MAX_ENTRIES=256,MAX_CANDIDATES=24,MAX_COUNT=255;
    public static final int MAX_PINYIN_LENGTH=64,MAX_WORD_CODE_POINTS=64,MAX_WORD_UTF8_BYTES=256,MAX_BYTES=128*1024;
    public static final long DECAY_MILLIS=30L*24*60*60*1000;
    private static final String HEADER="wangshu-candidate-learning\t1";
    private static final int MAX_BASE64_LENGTH=((MAX_WORD_UTF8_BYTES+2)/3)*4;
    public enum Update {CHANGED,UNCHANGED,CAPACITY_BLOCKED}
    private static final class Identity {
        final String pinyin,word;
        Identity(String key,String value){pinyin=key;word=value;}
        @Override public int hashCode(){return 31*pinyin.hashCode()+word.hashCode();}
        @Override public boolean equals(Object other){if(!(other instanceof Identity))return false;Identity that=(Identity)other;return pinyin.equals(that.pinyin)&&word.equals(that.word);}
    }
    private static final class Entry {
        final Identity identity;final int count;final boolean pinned;final long updatedMillis;
        Entry(Identity id,int weight,boolean fixed,long time){identity=id;count=weight;pinned=fixed;updatedMillis=time;}
        int effective(long now){long periods=now<=updatedMillis?0:(now-updatedMillis)/DECAY_MILLIS;return periods>=8?0:count>>((int)periods);}
    }
    private final LinkedHashMap<Identity,Entry> entries=new LinkedHashMap<>();
    private long revision;
    public synchronized long revision(){return revision;}
    public synchronized int size(){return entries.size();}
    /** Immutable entries are safe to share; later mutations replace rather than edit them. */
    public synchronized CandidateLearning copy(){CandidateLearning copy=new CandidateLearning();copy.entries.putAll(entries);copy.revision=revision;return copy;}

    /** Counts one verified selection, never an unconfirmed key, candidate impression or ASR guess. */
    public synchronized Update recordSelection(String pinyin,String word,long nowMillis){
        validTime(nowMillis);Identity id=identity(pinyin,word);Entry old=entries.get(id);
        int count=old==null?1:Math.min(MAX_COUNT,old.effective(nowMillis)+1);
        long time=old==null?nowMillis:Math.max(nowMillis,old.updatedMillis);
        if(old!=null&&old.count==count&&old.updatedMillis==time)return Update.UNCHANGED;
        if(old==null&&!canInsert(nowMillis))return Update.CAPACITY_BLOCKED;
        ensureRevision();if(old==null)evictForInsert(nowMillis);
        entries.put(id,new Entry(id,count,old!=null&&old.pinned,time));revision++;return Update.CHANGED;
    }
    /** Explicit user action; may create a pinned overlay with zero observed selections. */
    public synchronized Update pin(String pinyin,String word,long nowMillis){
        validTime(nowMillis);Identity id=identity(pinyin,word);Entry old=entries.get(id);
        if(old!=null&&old.pinned)return Update.UNCHANGED;
        if(old==null&&!canInsert(nowMillis))return Update.CAPACITY_BLOCKED;
        ensureRevision();if(old==null)evictForInsert(nowMillis);
        entries.put(id,new Entry(id,old==null?0:old.count,true,old==null?nowMillis:old.updatedMillis));revision++;return Update.CHANGED;
    }
    public synchronized Update unpin(String pinyin,String word,long nowMillis){
        validTime(nowMillis);Identity id=identity(pinyin,word);Entry old=entries.get(id);
        if(old==null||!old.pinned)return Update.UNCHANGED;
        ensureRevision();if(old.effective(nowMillis)==0)entries.remove(id);else entries.put(id,new Entry(id,old.count,false,old.updatedMillis));
        revision++;return Update.CHANGED;
    }
    /** Deletes only this overlay identity; this class has no access to any manual/base dictionary. */
    public synchronized Update forget(String pinyin,String word){
        Identity id=identity(pinyin,word);if(!entries.containsKey(id))return Update.UNCHANGED;
        ensureRevision();entries.remove(id);revision++;return Update.CHANGED;
    }
    public synchronized boolean isPinned(String pinyin,String word){Entry entry=entries.get(identity(pinyin,word));return entry!=null&&entry.pinned;}
    /** Pinned first, then effective count descending; stable ties preserve first insertion order. */
    public synchronized String[] candidates(String pinyin,long nowMillis){
        validTime(nowMillis);String key=key(pinyin);ArrayList<Entry> values=new ArrayList<>();
        for(Entry entry:entries.values())if(entry.identity.pinyin.equals(key)&&(entry.pinned||entry.effective(nowMillis)>0))values.add(entry);
        values.sort((a,b)->{int fixed=Boolean.compare(b.pinned,a.pinned);return fixed!=0?fixed:Integer.compare(b.effective(nowMillis),a.effective(nowMillis));});
        String[] result=new String[Math.min(MAX_CANDIDATES,values.size())];for(int i=0;i<result.length;i++)result[i]=values.get(i).identity.word;return result;
    }
    private boolean canInsert(long now){return entries.size()<MAX_ENTRIES||victim(now)!=null;}
    private Identity victim(long now){Entry victim=null;for(Entry candidate:entries.values())if(!candidate.pinned&&(victim==null||candidate.effective(now)<victim.effective(now)))victim=candidate;return victim==null?null:victim.identity;}
    private void evictForInsert(long now){if(entries.size()>=MAX_ENTRIES)entries.remove(victim(now));}
    private void ensureRevision(){if(revision==Long.MAX_VALUE)throw new IllegalStateException("Personal overlay revision exhausted");}
    private static void validTime(long value){if(value<0)throw new IllegalArgumentException("Invalid candidate time");}
    private static Identity identity(String pinyin,String value){return new Identity(key(pinyin),word(value));}
    private static String key(String raw){
        if(raw==null||raw.isEmpty()||raw.length()>MAX_PINYIN_LENGTH*2)throw new IllegalArgumentException("Invalid Pinyin key");
        StringBuilder key=new StringBuilder();boolean apostrophe=false;
        for(int i=0;i<raw.length();i++){char c=raw.charAt(i);
            if(c=='\''){if(i==0||i==raw.length()-1||apostrophe)throw new IllegalArgumentException("Invalid Pinyin separator");apostrophe=true;continue;}
            if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')))throw new IllegalArgumentException("Pinyin must use ASCII letters");
            key.append(c>='A'&&c<='Z'?(char)(c+('a'-'A')):c);apostrophe=false;
        }
        if(key.length()==0||key.length()>MAX_PINYIN_LENGTH)throw new IllegalArgumentException("Invalid Pinyin key length");return key.toString();
    }
    private static String word(String raw){
        if(raw==null||raw.isEmpty()||raw.length()>MAX_WORD_CODE_POINTS*4)throw new IllegalArgumentException("Invalid candidate word length");
        for(int i=0;i<raw.length();){int c=raw.codePointAt(i);int type=Character.getType(c);
            if((c>=0xD800&&c<=0xDFFF)||Character.isISOControl(c)||type==Character.LINE_SEPARATOR||type==Character.PARAGRAPH_SEPARATOR||(type==Character.FORMAT&&!allowedFormat(c)))throw new IllegalArgumentException("Invalid candidate character");i+=Character.charCount(c);
        }
        String value=Normalizer.normalize(raw,Normalizer.Form.NFC);
        if(value.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.getType(c)==Character.FORMAT)||value.codePointCount(0,value.length())>MAX_WORD_CODE_POINTS||value.getBytes(StandardCharsets.UTF_8).length>MAX_WORD_UTF8_BYTES)throw new IllegalArgumentException("Invalid candidate word length");return value;
    }
    private static boolean allowedFormat(int codePoint){return codePoint==0x200C||codePoint==0x200D||(codePoint>=0x2061&&codePoint<=0x2064)||(codePoint>=0xE0020&&codePoint<=0xE007F);}

    /** Canonical v1: header LF, then e, ASCII key, Base64 UTF8 NFC word, count, 0/1 pin, timestamp. */
    public synchronized byte[] encode(){
        StringBuilder text=new StringBuilder(HEADER).append('\n');
        for(Entry entry:entries.values())text.append("e\t").append(entry.identity.pinyin).append('\t').append(Base64.getEncoder().encodeToString(entry.identity.word.getBytes(StandardCharsets.UTF_8))).append('\t').append(entry.count).append('\t').append(entry.pinned?'1':'0').append('\t').append(entry.updatedMillis).append('\n');
        byte[] encoded=text.toString().getBytes(StandardCharsets.UTF_8);if(encoded.length>MAX_BYTES)throw new IllegalStateException("Personal overlay exceeds byte bound");return encoded;
    }
    /** Validates the entire value before returning. No partial model is exposed or saved. */
    public static CandidateLearning decode(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw new IOException("Invalid personal overlay byte size");String value=utf8(bytes);
        if(!value.endsWith("\n"))throw new IOException("Personal overlay must end with a newline");
        int lineCount=0;for(int i=0;i<value.length();i++)if(value.charAt(i)=='\n'&&++lineCount>MAX_ENTRIES+1)throw new IOException("Personal overlay exceeds entry bound");
        String[] lines=value.split("\n",-1);if(!line(lines[0]).equals(HEADER))throw new IOException("Unsupported personal overlay kind or version");CandidateLearning result=new CandidateLearning();
        for(int i=1;i<lines.length-1;i++){
            String[] fields=line(lines[i]).split("\t",-1);if(fields.length!=6||!fields[0].equals("e"))throw invalid(i+1,"Unknown or missing fields");
            try{
                String k=key(fields[1]);if(!k.equals(fields[1]))throw new IllegalArgumentException();
                if(fields[2].isEmpty()||fields[2].length()>MAX_BASE64_LENGTH)throw new IllegalArgumentException();
                byte[] encodedWord=Base64.getDecoder().decode(fields[2]);if(!Base64.getEncoder().encodeToString(encodedWord).equals(fields[2]))throw new IllegalArgumentException();
                String decoded=utf8(encodedWord),w=word(decoded);if(!decoded.equals(w))throw new IllegalArgumentException();
                long count=number(fields[3]),time=number(fields[5]);if(count>MAX_COUNT||(!fields[4].equals("0")&&!fields[4].equals("1")))throw new IllegalArgumentException();
                boolean pinned=fields[4].equals("1");if(count==0&&!pinned)throw new IllegalArgumentException();
                Identity id=new Identity(k,w);if(result.entries.putIfAbsent(id,new Entry(id,(int)count,pinned,time))!=null)throw invalid(i+1,"Duplicate candidate identity");
            }catch(IllegalArgumentException|IOException malformed){throw invalid(i+1,"Invalid personal candidate entry");}
        }
        return result;
    }
    private static String line(String value){return value.endsWith("\r")?value.substring(0,value.length()-1):value;}
    private static IOException invalid(int line,String reason){return new IOException("Line "+line+": "+reason);}
    private static long number(String value){if(value.isEmpty()||(value.length()>1&&value.charAt(0)=='0')||value.length()>19)throw new IllegalArgumentException();for(int i=0;i<value.length();i++)if(value.charAt(i)<'0'||value.charAt(i)>'9')throw new IllegalArgumentException();return Long.parseLong(value);}
    private static String utf8(byte[] bytes)throws IOException {try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException invalid){throw new IOException("Invalid UTF-8 personal overlay");}}
}
