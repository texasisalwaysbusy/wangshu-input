package org.wangshu.ime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure test policy. No Android calls, input actions, storage or production hooks. */
final class ImeTestPolicy {
    static final class SetupFailure extends AssertionError {
        SetupFailure(String message){super("SETUP_FAILED: "+message);}
    }
    static final class Facts {
        final boolean editor,selected,application,ime,insets;
        Facts(boolean editor,boolean selected,boolean application,boolean ime,boolean insets){
            this.editor=editor;this.selected=selected;this.application=application;this.ime=ime;this.insets=insets;
        }
        boolean ready(){return editor&&selected&&application&&ime&&insets;}
        public String toString(){return "editor="+editor+" selected="+selected+" ownedApplication="+application+" ownedIme="+ime+" imeInsets="+insets;}
    }
    interface BooleanSlot {boolean get();void set(boolean value);}
    /** Restore the exact captured scalar even when a product assertion throws. */
    static final class BooleanLease implements AutoCloseable {
        final BooleanSlot slot;final boolean before;private boolean closed;
        BooleanLease(BooleanSlot slot){this.slot=slot;before=slot.get();}
        void force(boolean value){if(closed)throw new IllegalStateException("lease closed");slot.set(value);require(value);}
        void require(boolean value){if(closed||slot.get()!=value)throw new SetupFailure("loading-state injection was not held; no valid product conclusion");}
        @Override public void close(){if(!closed){closed=true;slot.set(before);if(slot.get()!=before)throw new SetupFailure("original loading-state restoration not confirmed");}}
    }
    static String escaped(String value,int max){
        if(value==null)return "null";StringBuilder out=new StringBuilder("\"");int limit=Math.min(max,value.length());
        for(int i=0;i<limit;i++){char c=value.charAt(i);switch(c){case '\\':out.append("\\\\");break;case '"':out.append("\\\"");break;case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;default:if(c<32||Character.isSurrogate(c))out.append(String.format("\\u%04x",(int)c));else out.append(c);}}
        return out.append('"').append(value.length()>limit?" [TRUNCATED length="+value.length()+"]":"").toString();
    }
    /** Conservative API36/user0 dump reader; unsupported/partial formats never imply readiness. */
    static boolean serviceMatches(String dump,String owner,int uid,int pid,int fieldId,String expectedIme){
        if(dump==null||uid<0||uid>=100000||pid<=0||fieldId<=0)return false;
        int begin=dump.indexOf("  mUserDataRepository:\n"),end=dump.indexOf("  mStartInputHistory:",Math.max(0,begin));
        if(begin<0||end<=begin)return false;
        String repository=dump.substring(begin,end);int user=repository.indexOf("    userId=0\n");
        if(user<0)return false;int next=repository.indexOf("    userId=",user+1);
        String block=repository.substring(user,next<0?repository.length():next);
        if(!Pattern.compile("(?m)^      boundToMethod=true$").matcher(block).find())return false;
        if(!Pattern.compile("(?m)^      curClient=ClientState\\{[^\\n]*\\bmUid="+uid+"\\b[^\\n]*\\bmPid="+pid+"\\b[^\\n]*}$").matcher(block).find())return false;
        if(!Pattern.compile("(?m)^      enabledSession=SessionState\\{uid="+uid+" pid="+pid+" [^\\n]*\\bmethod=(?!null\\b)[0-9a-fA-F]+\\b[^\\n]*\\bsession=(?!null\\b)[0-9a-fA-F]+\\b[^\\n]*}$").matcher(block).find())return false;
        int editor=block.indexOf("      curEditorInfo:\n"),binding=block.indexOf("      imeBindingState:",Math.max(0,editor));
        if(editor<0||binding<=editor)return false;
        String attributes=block.substring(editor,binding);
        if(!Pattern.compile("(?m)^        packageName="+Pattern.quote(owner)+"(?: |$)").matcher(attributes).find())return false;
        Matcher field=Pattern.compile("\\bfieldId=(-?(?:0x[0-9a-fA-F]+|[0-9]+))(?: |$)").matcher(attributes);
        if(!field.find())return false;
        try{if(Long.decode(field.group(1))!=fieldId)return false;}catch(NumberFormatException invalid){return false;}
        int controller=dump.indexOf("  UserId=0\n    mBindingController:\n");if(controller<0)return false;
        int controllerEnd=dump.indexOf("    mCurClient=",controller);if(controllerEnd<0)return false;
        String bindingBlock=dump.substring(controller,controllerEnd);
        return Pattern.compile("(?m)^      mSelectedMethodId="+Pattern.quote(expectedIme)+"$").matcher(bindingBlock).find()
                &&Pattern.compile("(?m)^      mCurId="+Pattern.quote(expectedIme)+"$").matcher(bindingBlock).find()
                &&Pattern.compile("(?m)^      mCurMethod=com\\.android\\.server\\.inputmethod\\.IInputMethodInvoker@[0-9a-fA-F]+$").matcher(bindingBlock).find();
    }
    static String ownerKind(String value,String owner){return owner.equals(value)?"OWNED":(owner+".test").equals(value)?"TEST_FIXTURE":"OTHER_REDACTED";}
    /** Whitelist only numeric/boolean/known-owner metadata; never return raw lines or titles. */
    static String sanitizedDump(String dump,String owner){
        if(dump==null)return "CAPTURE_UNKNOWN\n";if(dump.startsWith("CAPTURE_"))return "CAPTURE_INCOMPLETE_OR_UNAVAILABLE\n";
        StringBuilder out=new StringBuilder("WHITELISTED_METADATA raw dump intentionally not saved\n");
        int rows=0;for(String line:dump.split("\n")){
            if(rows>=160){out.append("METADATA_ROW_LIMIT\n");break;}
            String value=line.trim();
            if(value.matches("(?:boundToMethod|mBoundToMethod|mHasMainConnection|mVisibleBound|mInputShown|mShowRequested|mInteractive|mSystemReady)=(?:true|false)")){out.append(value).append('\n');rows++;continue;}
            if(value.startsWith("packageName=")){Matcher pkg=Pattern.compile("^packageName=([^ ]+)").matcher(value);if(pkg.find()){out.append("editorOwner=").append(ownerKind(pkg.group(1),owner));Matcher f=Pattern.compile("\\bfieldId=(-?(?:0x[0-9a-fA-F]+|[0-9]+))(?: |$)").matcher(value);if(f.find())out.append(" fieldId=").append(f.group(1));out.append('\n');rows++;}continue;}
            if(value.startsWith("curClient=")||value.startsWith("mCurClient=")||value.startsWith("enabledSession=")||value.startsWith("mEnabledSession=")){
                out.append(value.substring(0,value.indexOf('='))).append(' ');Matcher fields=Pattern.compile("\\b(mUid|mPid|uid|pid|method|session)=([0-9a-fA-F]+|null)(?=[ }])").matcher(value);
                while(fields.find())out.append(fields.group(1)).append('=').append(fields.group(2)).append(' ');out.append('\n');rows++;continue;
            }
            if(value.startsWith("mSelectedMethodId=")||value.startsWith("mCurId=")){out.append(value.substring(0,value.indexOf('='))).append('=').append(value.substring(value.indexOf('=')+1).equals(owner+"/org.wangshu.ime.WangshuIme")?"EXPECTED_IME":"OTHER_OR_UNKNOWN").append('\n');rows++;continue;}
            if(value.matches("(?:mFocusedWindow|mCurrentFocus|mInputMethodTarget|mImeInputTarget|mImeControlTarget|mInputMethodInputTarget|mInputMethodControlTarget)=.*")){
                out.append(value.substring(0,value.indexOf('='))).append(' ');Matcher token=Pattern.compile("(?:Window\\{|BinderProxy@)([0-9a-fA-F]+)").matcher(value);
                out.append(token.find()?"token="+token.group(1):"token=UNKNOWN").append(" owner=").append(value.contains(" "+owner+"/")?"OWNED":"UNKNOWN_REDACTED").append('\n');rows++;
            }
        }
        return out.toString();
    }
}
