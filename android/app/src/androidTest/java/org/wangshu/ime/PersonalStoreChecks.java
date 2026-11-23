package org.wangshu.ime;

import android.app.Instrumentation;
import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private static void confirmedImportChecks(File folder)throws Exception {
        File path=new File(folder,"import-confirm.json");
        PersonalStore store=new PersonalStore(path);
        store.putWord(-1,"before","原词");
        JSONObject incoming=PersonalStore.parseImport(utf8("pending\t预览词"));
        byte[] before=store.export();
        PersonalStore.ImportPreview inProcess=store.prepareImport(incoming);
        check(inProcess.wordCount==2&&inProcess.phraseCount==0&&Arrays.equals(before,store.export()),
                "personal import preview is additive and read-only");
        store.putPhrase(-1,"其他窗口更新","later");
        byte[] current=store.export();
        rejects(()->store.confirmImport(inProcess),"in-process change invalidates earlier import preview");
        check(Arrays.equals(current,new PersonalStore(path).export()),"stale in-process preview writes nothing");

        PersonalStore.ImportPreview external=store.prepareImport(incoming);
        PersonalStore other=new PersonalStore(path);
        other.putWord(-1,"fromother","另一词");
        current=other.export();
        rejects(()->store.confirmImport(external),"different store instance invalidates earlier import preview on disk");
        check(Arrays.equals(current,store.export())&&Arrays.equals(current,new PersonalStore(path).export()),
                "stale import refreshes the cached view while retaining exact saved bytes");
        PersonalStore.ImportPreview fresh=store.prepareImport(incoming);
        check(fresh.wordCount==3&&fresh.phraseCount==1,"fresh preview accounts for the concurrent records");
        incoming.getJSONArray("words").getJSONObject(0).put("text","改变了外部对象");
        store.confirmImport(fresh);
        PersonalStore reopened=new PersonalStore(path);
        check(Arrays.equals(reopened.words("pending"),new String[]{"预览词"})&&reopened.words("later").length==1,
                "confirmation writes the frozen preview and survives reopen");
        rejects(()->store.confirmImport(fresh),"a previously committed Android preview cannot be replayed");
        rejects(()->other.confirmImport(fresh),"Android preview is bound to its original store identity");
    }
    private static void recoveryChecks(File folder)throws Exception {
        File path=new File(folder,"recovery.json");
        File marker=new File(path+".pending"),backup=new File(path+".bak"),staged=new File(path+".new");
        PersonalStore store=new PersonalStore(path);
        store.putWord(-1,"saved","合成词条");
        store.putPhrase(-1,"合成常用语\nα","demo");
        byte[] saved=store.export(), damaged=utf8("{partial"), previousMarker=utf8("interrupted"), stagedBytes=utf8("{unconfirmed");
        long revision=store.revision();
        rejects(()->PersonalRecoveryFiles.prepare(path,saved),"healthy data can only use additive import");
        Files.write(path.toPath(),damaged);
        Files.write(backup.toPath(),saved);
        Files.write(staged.toPath(),stagedBytes);
        Files.write(marker.toPath(),previousMarker);
        rejects(()->new PersonalStore(path),"pending marker blocks automatic opening and AtomicFile cleanup");
        rejects(store::snapshot,"an open instance stops serving data after the pending marker appears");
        long initialFiles;
        try(java.util.stream.Stream<java.nio.file.Path> entries=Files.list(folder.toPath())){initialFiles=entries.count();}
        PersonalRecoveryFiles.Plan plan=PersonalRecoveryFiles.prepare(path,null);
        check(plan.words==1&&plan.phrases==1,"local recovery previews validated original backup counts");
        try(java.util.stream.Stream<java.nio.file.Path> entries=Files.list(folder.toPath())){
            check(entries.count()==initialFiles&&Arrays.equals(damaged,Files.readAllBytes(path.toPath()))
                    &&Arrays.equals(stagedBytes,Files.readAllBytes(staged.toPath())),
                    "preview without confirmation retains the original files byte for byte and creates no output");
        }
        Files.write(staged.toPath(),utf8("changed"));
        rejects(()->store.restore(plan),"change to a staged sidecar invalidates the recovery preview");
        check(Arrays.equals(damaged,Files.readAllBytes(path.toPath()))&&marker.exists(),
                "stale confirmation preserves the live original and blocked marker");
        Files.write(staged.toPath(),stagedBytes);
        PersonalRecoveryFiles.Plan current=PersonalRecoveryFiles.prepare(path,null);
        try(PersonalRecoveryFiles.Lock ignored=PersonalRecoveryFiles.lock(path)){
            rejects(()->store.restore(current),"recovery cannot run while the store writer holds the lock");
            File competing=new File(folder,"competing.json");
            PersonalStore writer=new PersonalStore(competing);
            try(PersonalRecoveryFiles.Lock other=PersonalRecoveryFiles.lock(competing)){
                rejects(()->writer.putWord(-1,"blocked","不得写入"),"normal store mutations use the same exclusive lock");
            }
            check(!competing.exists()&&!new File(competing+".pending").exists(),"writer lock refusal writes no live data or marker");
        }
        File evidence=store.restore(current);
        check(Arrays.equals(damaged,Files.readAllBytes(new File(evidence,path.getName()).toPath()))
                &&Arrays.equals(saved,Files.readAllBytes(new File(evidence,backup.getName()).toPath()))
                &&Arrays.equals(stagedBytes,Files.readAllBytes(new File(evidence,staged.getName()).toPath()))
                &&Arrays.equals(previousMarker,Files.readAllBytes(new File(evidence,marker.getName()).toPath())),
                "confirmed recovery preserves exact primary, backup, staged and pending originals");
        check(!marker.exists()&&!backup.exists()&&!staged.exists()&&Arrays.equals(saved,new PersonalStore(path).export()),
                "confirmed recovery consumes only live fault sidecars and survives a fresh open");
        check(store.revision()==revision+1&&Arrays.equals(saved,store.export()),"successful recovery refreshes the live cached data revision");
        rejects(()->store.restore(current),"a confirmed recovery cannot be replayed");

        File external=new File(folder,"external-recovery.json");
        Files.write(external.toPath(),damaged);
        rejects(()->new PersonalStore(external),"malformed base is reported without resetting it");
        rejects(()->PersonalRecoveryFiles.prepare(external,null),"damaged base with no usable local backup requires an external backup");
        rejects(()->PersonalRecoveryFiles.prepare(external,utf8("only\t一个词")),"TSV is refused as a recovery replacement");
        rejects(()->PersonalRecoveryFiles.prepare(external,utf8("{\"kind\":\"wangshu-personal\",\"version\":1}")),
                "partial external JSON is refused");
        PersonalRecoveryFiles.Plan externalPlan=PersonalRecoveryFiles.prepare(external,saved);
        rejects(()->PersonalRecoveryFiles.restore(path,externalPlan),"recovery confirmation is bound to the original target");
        File externalEvidence=PersonalRecoveryFiles.restore(external,externalPlan);
        check(Arrays.equals(saved,new PersonalStore(external).export())
                &&Arrays.equals(damaged,Files.readAllBytes(new File(externalEvidence,external.getName()).toPath())),
                "external JSON restores a damaged base while preserving the damaged bytes");

        File stageOnly=new File(folder,"stage-only.json");
        Files.write(new File(stageOnly+".new").toPath(),saved);
        rejects(()->new PersonalStore(stageOnly),"a lone stage is treated as an unconfirmed write");
        rejects(()->PersonalRecoveryFiles.prepare(stageOnly,null),"a lone stage is never offered as a saved local copy");
        File huge=new File(folder,"huge-evidence.json");
        try(RandomAccessFile sparse=new RandomAccessFile(huge,"rw")){sparse.setLength(16L*PersonalStore.MAX_BYTES+1);}
        rejects(()->PersonalRecoveryFiles.prepare(huge,saved),"over-budget evidence blocks replacement");
        check(huge.length()==16L*PersonalStore.MAX_BYTES+1,"evidence budget refusal leaves the original file untouched");
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
        confirmedImportChecks(folder);
        recoveryChecks(folder);
        try(FileOutputStream out=new FileOutputStream(new File(folder,"result.txt"))){out.write(log.toString().getBytes(StandardCharsets.UTF_8));}
        return "PASS "+count+" isolated Android AtomicFile/JSON personal-data checks\n"+log+"No real user input or clipboard capture; test-owned files retained\n";
    }
}
