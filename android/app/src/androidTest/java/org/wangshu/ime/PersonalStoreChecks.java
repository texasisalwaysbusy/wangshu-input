package org.wangshu.ime;

import android.app.Instrumentation;
import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class PersonalStoreChecks {
    private static int count;private static StringBuilder log;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);count++;log.append("PASS ").append(label).append('\n');System.out.println("PERSONAL_STORE_PASS "+label);}
    interface Action{void run()throws Exception;}
    private static void rejects(Action action,String label)throws Exception {boolean refused=false;try{action.run();}catch(IOException expected){refused=true;}check(refused,label);}
    private static String repeated(String value,int count){StringBuilder out=new StringBuilder(value.length()*count);for(int i=0;i<count;i++)out.append(value);return out.toString();}
    private static byte[] utf8(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static void rejectsLine(Action action,int line,String label)throws Exception {String reason=null;try{action.run();}catch(IOException expected){reason=expected.getMessage();}check(reason!=null&&reason.contains("第 "+line+" 行"),label);}
    private static void rejectsReason(Action action,String reason,String label)throws Exception {String actual=null;try{action.run();}catch(IOException expected){actual=expected.getMessage();}check(actual!=null&&actual.contains(reason),label);}
    private static void extended(File folder)throws Exception {
        PersonalStore unicode=new PersonalStore(new File(folder,"unicode.json"));unicode.putWord(-1,"cafe","Cafe\u0301");unicode.putWord(-1,"CAFE","Café");
        check(unicode.snapshot().getJSONArray("words").length()==1&&unicode.words("cafe")[0].equals("Café"),"dictionary words canonicalize NFC before deduplicating");
        unicode.putWord(-1,"math","𝕏");unicode.putWord(-1,"math","X");check(Arrays.equals(unicode.words("math"),new String[]{"𝕏","X"}),"NFC preserves distinct mathematical compatibility characters instead of NFKC folding");
        String phrase="Cafe\u0301\n 𝕏";unicode.putPhrase(-1,phrase,"exact");check(unicode.words("exact")[0].equals(phrase),"phrase content remains exact without NFC normalization");check(PersonalStore.text(phrase,4000,true).equals(phrase),"shared clipboard text validator retains original Unicode sequence");
        PersonalStore.ImportData tsv=PersonalStore.importFile(utf8("\uFEFFpinyin\ttext\r\n# comment\r\n\r\nCAFE\tCafe\u0301\r\ncafe\tCafé\r\nni'hao\t你好\r\n"));
        check(tsv.format.equals("TSV")&&tsv.data.getJSONArray("words").length()==2,"TSV accepts BOM header blank lines comments and NFC duplicates");
        check(tsv.data.getJSONArray("words").getJSONObject(0).getString("text").equals("Café")&&tsv.data.getJSONArray("words").getJSONObject(1).getString("pinyin").equals("nihao"),"TSV preserves first unique record order and canonical pinyin");
        check(PersonalStore.parseImport(utf8("quantum\t量子\nalpha\tα")).getJSONArray("words").length()==2,"headerless TSV remains a usable two-column import");
        check(PersonalStore.parseImport(utf8("拼音\t词条\nmath\t𝕏")).getJSONArray("words").getJSONObject(0).getString("text").equals("𝕏"),"Chinese TSV header and non-BMP math word accepted");
        check(PersonalStore.importFile(unicode.export()).format.equals("JSON"),"existing strict JSON v1 backup remains an import format");
        check(PersonalStore.importFile(utf8("\uFEFF"+new String(unicode.export(),StandardCharsets.UTF_8))).data.getJSONArray("phrases").getJSONObject(0).getString("text").equals(phrase),"BOM JSON import preserves exact multiline phrases");
        rejectsLine(()->PersonalStore.parseImport(utf8("# comment\npinyin\ttext\nnihao\t你好\t999")),3,"unsupported TSV weight/source column reports physical row instead of ignoring it");
        rejectsLine(()->PersonalStore.parseImport(utf8("pinyin\ttext\nni3hao3\t你好")),2,"invalid TSV pinyin reports exact row");
        rejectsLine(()->PersonalStore.parseImport(utf8("pinyin\ttext\nnihao\t")),2,"empty TSV word reports exact row");
        rejectsLine(()->PersonalStore.parseImport(utf8("pinyin\ttext\nmissing delimiter")),2,"missing TSV tab reports exact row");
        rejectsLine(()->PersonalStore.parseImport(utf8("pinyin\ttext\npinyin\ttext")),2,"repeated TSV header is not silently inserted as a word");
        rejects(()->PersonalStore.parseImport(utf8("# comment only\npinyin\ttext\n")),"empty TSV import is explicitly refused");
        byte[] validPrefix=utf8("pinyin\ttext\r\nnihao\t你好\r\nbad\t");byte[] invalidUtf8=Arrays.copyOf(validPrefix,validPrefix.length+1);invalidUtf8[invalidUtf8.length-1]=(byte)0xff;
        rejectsLine(()->PersonalStore.parseImport(invalidUtf8),3,"malformed UTF8 locates physical row without leaking its contents");
        byte[] stable=unicode.export();rejectsLine(()->unicode.merge(PersonalStore.parseImport(utf8("pinyin\ttext\nvalid\t应不写入\ninvalid line"))),3,"later invalid TSV row prevents the entire merge");check(Arrays.equals(stable,unicode.export())&&unicode.words("valid").length==0,"failed TSV batch preserves every saved byte and makes no partial insert");
        JSONObject preview=unicode.previewMerge(tsv.data);check(preview.getJSONArray("words").length()==4&&Arrays.equals(stable,unicode.export()),"validated TSV preview can be discarded without changing stored bytes");
        String schema=new String(PersonalStore.empty().toString().getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8);
        rejects(()->PersonalStore.parse(utf8(schema.replaceFirst("\\{","{/*comment*/"))),"JSON comments refused rather than accepted by permissive tokenizer");
        rejects(()->PersonalStore.parse(utf8(schema.replace('\"','\''))),"single-quoted JSON is refused before parsing");
        rejects(()->PersonalStore.parse(utf8(schema.substring(0,schema.length()-1)+",}")),"trailing JSON comma is refused");
        rejectsReason(()->PersonalStore.parse(utf8(repeated("[",17)+"0"+repeated("]",17))),"层级","deep JSON is refused before recursive object allocation");
        rejectsReason(()->PersonalStore.parse(utf8("["+repeated("{},",4096)+"{}]")),"数量","wide JSON container budget prevents disproportionate allocation");
        rejectsReason(()->PersonalStore.parse(utf8("["+repeated("0,",32001)+"0]")),"数量","wide scalar array budget is bounded before tokenizer allocation");
        new PersonalStore.JsonSyntax("\""+repeated(",",32001)+"\"").validate();check(true,"quoted commas do not consume structural separator budget");
        JSONObject full=PersonalStore.empty();for(int i=0;i<2000;i++)full.getJSONArray("words").put(new JSONObject().put("pinyin","same").put("text","word-"+i));for(int i=0;i<1000;i++)full.getJSONArray("phrases").put(new JSONObject().put("shortcut","").put("text","phrase-"+i));
        JSONObject checked=PersonalStore.parse(utf8(full.toString()));check(checked.getJSONArray("words").length()==2000&&checked.getJSONArray("phrases").length()==1000,"full legal 2000-word and 1000-phrase schema fits JSON structural budgets");
        PersonalStore capacity=new PersonalStore(new File(folder,"capacity.json"));capacity.merge(full);byte[] maximum=capacity.export();JSONObject idempotent=capacity.previewMerge(full);check(idempotent.getJSONArray("words").length()==2000&&idempotent.getJSONArray("phrases").length()==1000&&Arrays.equals(maximum,capacity.export()),"full-capacity repeated preview deduplicates before final capacity checking");capacity.merge(full);check(Arrays.equals(maximum,capacity.export()),"full-capacity repeated commit remains byte stable");
        rejects(()->capacity.merge(PersonalStore.parseImport(utf8("extra\tnew distinct word"))),"combined unique entries over capacity are refused");check(Arrays.equals(maximum,new PersonalStore(new File(folder,"capacity.json")).export()),"over-capacity merge leaves prior complete file unchanged on disk");
    }
    static String run(Instrumentation test)throws Exception {
        count=0;log=new StringBuilder();Context context=test.getTargetContext();File folder=new File(context.getFilesDir(),"personal-store-checks-"+UUID.randomUUID());if(!folder.mkdir())throw new IOException("Test-owned folder unavailable");File path=new File(folder,"data.json");PersonalStore store=new PersonalStore(path);
        check(store.snapshot().getJSONArray("words").length()==0,"new isolated store starts empty without collecting input");
        store.putWord(-1,"WANG'SHU","望舒测试");check(Arrays.equals(store.words("wangshu"),new String[]{"望舒测试"}),"full pinyin normalization and explicit word lookup");
        check(Arrays.equals(new PersonalStore(path).words("wangshu"),new String[]{"望舒测试"}),"atomic data survives a fresh store instance");
        store.putWord(-1,"wangshu","望舒测试");check(store.snapshot().getJSONArray("words").length()==1,"same pinyin and text deduplicate");
        store.putWord(-1,"wangshu","同音另一词");check(store.words("wangshu").length==2,"same pinyin retains distinct words in insertion order");
        store.putPhrase(-1,"First line\n数学 α + 👩‍🔬","hello");check(store.words("hello")[0].contains("👩‍🔬"),"phrase shortcut preserves multiline Unicode and emoji");
        byte[] original=store.export();check(Arrays.equals(new PersonalStore(path).export(),original),"export is byte stable across reopen");
        JSONObject incoming=PersonalStore.empty();incoming.getJSONArray("words").put(new JSONObject().put("pinyin","quantum").put("text","量子"));
        JSONObject preview=store.previewMerge(incoming);check(preview.getJSONArray("words").length()==3&&Arrays.equals(original,store.export()),"import preview computes additive result without writing");
        store.merge(incoming);store.merge(incoming);check(store.snapshot().getJSONArray("words").length()==3,"repeated import is idempotent and retains existing words");
        byte[] before=store.export();rejects(()->store.merge(new JSONObject().put("version",99)),"unsupported or incomplete import refused");check(Arrays.equals(before,store.export()),"invalid import leaves all saved bytes unchanged");
        rejects(()->PersonalStore.parse("{} trailing".getBytes(StandardCharsets.UTF_8)),"trailing data refused");
        JSONObject future=PersonalStore.empty().put("future",true);rejects(()->PersonalStore.parse(future.toString().getBytes(StandardCharsets.UTF_8)),"unknown top-level fields refused without silent loss");
        rejects(()->store.putWord(-1,"ni3hao3","你好"),"invalid pinyin rejected instead of silently changing keys");
        rejects(()->store.putWord(-1,"abc","a\u0000b"),"control characters refused");
        rejects(()->store.putWord(-1,"abc","\ud800"),"unpaired Unicode surrogate refused");
        rejects(()->PersonalStore.parse(new byte[]{(byte)0xff}),"malformed UTF-8 refused");
        rejects(()->PersonalStore.read(new ByteArrayInputStream(new byte[PersonalStore.MAX_BYTES+1])),"oversized stream refused before unbounded allocation");
        rejects(()->store.putPhrase(-1,"text","not a shortcut"),"invalid phrase shortcut refused");
        store.putWord(1,"wangshu","修改的词");check(Arrays.equals(store.words("wangshu"),new String[]{"望舒测试","修改的词"}),"editing one word preserves sibling with same pinyin");
        store.remove("words",1);check(store.words("wangshu").length==1&&store.words("quantum").length==1,"scoped delete preserves unrelated word and phrase");
        byte[] stable=store.export();AtomicFile atomic=new AtomicFile(path);try(FileOutputStream partial=atomic.startWrite()){partial.write("partial interrupted write".getBytes(StandardCharsets.UTF_8));partial.getFD().sync();}
        check(Arrays.equals(new PersonalStore(path).export(),stable),"interrupted AtomicFile write restores last complete backup");
        File parentFile=new File(folder,"not-a-directory");try(FileOutputStream out=new FileOutputStream(parentFile)){out.write(1);}PersonalStore unwritable=new PersonalStore(new File(parentFile,"data.json"));rejects(()->unwritable.putWord(-1,"abc","未保存"),"write failure reported rather than claiming success");check(unwritable.words("abc").length==0,"failed write does not publish unsaved in-memory word");
        extended(folder);
        try(FileOutputStream out=new FileOutputStream(new File(folder,"result.txt"))){out.write(log.toString().getBytes(StandardCharsets.UTF_8));}
        return "PASS "+count+" isolated Android AtomicFile/JSON personal-data checks\n"+log+"No real user input or clipboard capture; test-owned files retained\n";
    }
}
