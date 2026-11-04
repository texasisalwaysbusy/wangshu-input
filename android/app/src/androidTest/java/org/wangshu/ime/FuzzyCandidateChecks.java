package org.wangshu.ime;

import android.app.Instrumentation;
import android.content.*;
import java.util.*;

/** Optional native suite: actual JNI dictionary and isolated test prefs, no editor/microphone/UI claims. */
final class FuzzyCandidateChecks {
    private static int count;private static StringBuilder log;
    private static void check(boolean yes,String label){if(!yes)throw new AssertionError(label);count++;log.append("PASS ").append(label).append('\n');}
    private static void add(LinkedHashMap<String,String> out,String[] pairs){if(pairs==null||(pairs.length&1)!=0||pairs.length>18)throw new AssertionError("JNI candidate array malformed");for(int i=0;i<pairs.length;i+=2){if(pairs[i]==null||pairs[i].isEmpty()||pairs[i+1]==null)throw new AssertionError("JNI candidate pair malformed");out.putIfAbsent(pairs[i],pairs[i+1]);}}
    private static String[] flat(LinkedHashMap<String,String> values){ArrayList<String> out=new ArrayList<>();for(Map.Entry<String,String> entry:values.entrySet()){out.add(entry.getKey());out.add(entry.getValue());}return out.toArray(new String[0]);}
    private static String[] slice(String[] pairs,int page){int from=page*18,to=Math.min(pairs.length,from+18);return from>=pairs.length?new String[0]:Arrays.copyOfRange(pairs,from,to);}
    static String run(Instrumentation test)throws Exception{
        count=0;log=new StringBuilder();Context context=test.getTargetContext();
        PersonalStore revisionStore=new PersonalStore(new java.io.File(context.getCacheDir(),"test-revision-"+UUID.randomUUID()+".json"));
        long revision=revisionStore.revision();revisionStore.putWord(-1,"zen","测试词");check(revisionStore.revision()>revision,"actual isolated successful word save advances candidate revision");
        revision=revisionStore.revision();revisionStore.putPhrase(-1,"test-only phrase","zhen");check(revisionStore.revision()>revision,"actual isolated phrase save advances variant candidate revision");
        revision=revisionStore.revision();boolean rejected=false;try{revisionStore.putWord(-1,"invalid1","rejected");}catch(java.io.IOException expected){rejected=true;}
        check(rejected&&revisionStore.revision()==revision,"rejected personal mutation never advances the committed revision");
        check(DataFiles.initialize(context)>100000,"actual Android dictionary/JNI initialized from existing local assets");
        SharedPreferences actual=AppPreferences.open(context,FuzzySettingsActivity.PREFERENCES);Map<String,?> original=new HashMap<>(actual.getAll());
        String fixtureName="test-fuzzy-candidates-"+UUID.randomUUID();SharedPreferences fixture=context.getSharedPreferences(fixtureName,Context.MODE_PRIVATE);
        java.io.File isolatedFiles=new java.io.File(context.getFilesDir(),fixtureName);if(!isolatedFiles.mkdir())throw new AssertionError("settings fixture folder");Context isolated=new ContextWrapper(context){@Override public Context getApplicationContext(){return this;}@Override public java.io.File getFilesDir(){return isolatedFiles;}@Override public java.io.File getDataDir(){return isolatedFiles;}@Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(name.equals(FuzzySettingsActivity.PREFERENCES)?fixtureName:(AppPreferences.managed(name)||name.equals(AppPreferences.FILE))?fixtureName+"-"+name:name,mode);}};
        try{
            check(FuzzySettingsActivity.enabledPairs(isolated).isEmpty(),"new isolated actual SharedPreferences starts with all fuzzy pairs disabled");
            check(fixture.edit().putBoolean(FuzzyPinyin.Pair.Z_ZH.preferenceKey,true).commit(),"test-owned fuzzy preference successfully committed in isolated namespace");
            Set<FuzzyPinyin.Pair> enabled=FuzzySettingsActivity.enabledPairs(isolated);check(enabled.size()==1&&enabled.contains(FuzzyPinyin.Pair.Z_ZH),"production Settings reader consumes only the isolated enabled pair");
            List<String> expansion=FuzzyPinyin.forInput("zen",enabled,8,true,false,false);check(expansion.equals(List.of("zen","zhen")),"saved isolated pair affects the production expander");
            check(FuzzyPinyin.forInput("zen",enabled,8,false,false,false).equals(List.of("zen"))&&FuzzyPinyin.forInput("zen",enabled,8,true,true,false).equals(List.of("zen"))&&FuzzyPinyin.forInput("\\alpha",enabled,8,true,false,true).equals(List.of("\\alpha")),"English/password/math guards bypass fuzzy expansion with real saved flags");
            ArrayList<Integer> exactCalls=new ArrayList<>();ArrayList<String> variantCalls=new ArrayList<>();
            CandidatePages pager=new CandidatePages(new String[0],page->{exactCalls.add(page);return NativeBridge.lookup("zen",page);},expansion.subList(1,expansion.size()),key->{variantCalls.add(key);return NativeBridge.lookup(key,0);});
            check(exactCalls.isEmpty()&&variantCalls.isEmpty()&&!pager.truncated(),"native-backed pager construction/flag performs no eager query");
            String[] nativeFirst=NativeBridge.lookup("zen",0);check(nativeFirst!=null&&nativeFirst.length>0,"fixed nonsensitive native spelling has exact candidates");
            check(Arrays.equals(pager.page(0),nativeFirst)&&exactCalls.equals(List.of(0))&&variantCalls.isEmpty(),"actual first JNI page is preserved without scanning later native pages");
            // Independent direct JNI oracle, explicitly outside the measured pager calls.
            LinkedHashMap<String,String> allExact=new LinkedHashMap<>();int lastNative=-1;
            for(int index=0;index<=CandidatePages.MAX_NATIVE_PAGE;index++){String[] pairs=NativeBridge.lookup("zen",index);add(allExact,pairs);if(pairs.length<18){lastNative=index;break;}}
            check(lastNative>=0,"fixed JNI fixture completes before the hard native page cutoff");String[] expectedExact=flat(allExact);int exactPages=(allExact.size()+8)/9;
            for(int index=0;index<exactPages;index++)check(Arrays.equals(pager.page(index),slice(expectedExact,index))&&!pager.isFuzzyPage(index),"actual JNI exact page "+index+" precedes every fuzzy candidate");
            check(variantCalls.isEmpty(),"native exact tail remains pure without querying a fuzzy spelling");
            LinkedHashMap<String,String> expectedFuzzy=new LinkedHashMap<>();String[] nativeVariant=NativeBridge.lookup("zhen",0);add(expectedFuzzy,nativeVariant);for(String text:allExact.keySet())expectedFuzzy.remove(text);check(!expectedFuzzy.isEmpty(),"actual fuzzy spelling supplies distinct native dictionary candidates");
            check(Arrays.equals(pager.page(exactPages),flat(expectedFuzzy))&&pager.isFuzzyPage(exactPages),"actual JNI fuzzy page appends after all exact text with global exact dedup");int calls=variantCalls.size();pager.page(exactPages);pager.isFuzzyPage(exactPages);
            check(calls==1&&variantCalls.equals(List.of("zhen"))&&pager.page(exactPages+1).length==0&&!pager.isFuzzyPage(exactPages+1),"repeated/native fuzzy tail reads query that variant once only");
            ArrayList<Integer> expectedIndices=new ArrayList<>();for(int i=0;i<=lastNative;i++)expectedIndices.add(i);check(exactCalls.equals(expectedIndices)&&!pager.truncated(),"actual native raw pages are queried once in order without a forced empty sentinel");
            CandidatePages manual=new CandidatePages(new String[]{nativeFirst[0],"manual-first","𝕏","manual-math"},page->NativeBridge.lookup("zen",page),List.of(),null);String[] merged=manual.page(0);check(merged.length>=4&&merged[0].equals(nativeFirst[0])&&merged[1].equals("manual-first")&&merged[2].equals("𝕏")&&merged[3].equals("manual-math"),"initial manual identity/annotation/math text precedes real native duplicates");
            check(original.equals(actual.getAll()),"real application fuzzy preferences remain unchanged by the isolated test namespace");
            return "PASS "+count+" actual JNI / isolated preference protocol checks\n"+log+"Scope: existing native dictionary and production preference reader/expander/pager only. Not registered-IME candidate UI, editor insertion, visible switch interaction, screenshot, ASR, microphone or real-phone acceptance.\n";
        }catch(Exception|AssertionError failure){throw new AssertionError("FuzzyCandidate partial count="+count+"\n"+log,failure);}
        finally{/* Keep the UUID-named synthetic fixture for audit; never alter or delete the real user preferences. */}
    }
}
