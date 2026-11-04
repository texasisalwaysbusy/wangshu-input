package org.wangshu.ime;

import android.app.Instrumentation;
import android.content.Context;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.view.inputmethod.*;
import android.widget.EditText;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Native component tests to run on Android main thread, never run against android.jar stubs. */
public final class BeforeCaretEditorChecks {
    private int checks;
    private final StringBuilder log=new StringBuilder();
    private void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;log.append("PASS ").append(name).append('\n');}
    private enum Fault {
        NONE, OFFSET, PARTIAL, REJECT_SELECTION, REJECT_COMMIT, FALSE_AFTER_COMMIT,
        SELECT_RACE, PREWRITE_RACE, STALE_SESSION, PRIVATE_ON_READ,
        FALSE_END, REJECT_BATCH, IGNORE_COMMIT, READ_THROW, COMMIT_THROW_AFTER_WRITE,
        END_THROW, INVALID_UTF16, COMPOSING, IGNORE_SELECTION
    }
    private static final class Session implements BeforeCaretEditor.Provider {
        InputConnection connection;long id=1;boolean privateInput;
        public BeforeCaretEditor.InputSession current(){return new BeforeCaretEditor.InputSession(connection,id,privateInput);}
    }
    private static final class Fixture {
        final EditText view;final Session session=new Session();final CheckedConnection connection;
        final BeforeCaretEditor adapter;
        Fixture(Context context,String text,int caret,Fault fault){
            view=new EditText(context);view.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            view.setText(text);view.setSelection(caret);
            InputConnection actual=view.onCreateInputConnection(new EditorInfo());
            if(actual==null)throw new AssertionError("real EditText did not create native InputConnection");
            connection=new CheckedConnection(actual,this,fault);session.connection=connection;adapter=new BeforeCaretEditor(session);
        }
        String text(){return view.getText().toString();}
        boolean noWrites(){return connection.selections==0&&connection.commits==0;}
    }
    private static final class CheckedConnection extends InputConnectionWrapper {
        final Fixture fixture;final Fault fault;int extracts,selections,commits,begins,ends;
        CheckedConnection(InputConnection target,Fixture fixture,Fault fault){super(target,true);this.fixture=fixture;this.fault=fault;}
        @Override public ExtractedText getExtractedText(ExtractedTextRequest request,int flags){
            extracts++;if(fault==Fault.READ_THROW)throw new IllegalStateException("synthetic protocol fault");
            ExtractedText original=super.getExtractedText(request,flags);if(original==null)return null;
            ExtractedText copy=new ExtractedText();copy.text=original.text==null?null:original.text.toString();copy.startOffset=original.startOffset;
            copy.partialStartOffset=original.partialStartOffset;copy.partialEndOffset=original.partialEndOffset;copy.selectionStart=original.selectionStart;copy.selectionEnd=original.selectionEnd;copy.flags=original.flags;
            if(fault==Fault.OFFSET)copy.startOffset=1;
            if(fault==Fault.PARTIAL)copy.partialStartOffset=0;
            if(fault==Fault.INVALID_UTF16)copy.text="a\ud800z";
            if(fault==Fault.COMPOSING){SpannableString s=new SpannableString("abcXYZ");s.setSpan(new Object(),1,2,Spanned.SPAN_COMPOSING|Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);copy.text=s;}
            if(extracts==1){
                if(fault==Fault.STALE_SESSION)fixture.session.id++;
                if(fault==Fault.PRIVATE_ON_READ)fixture.session.privateInput=true;
                if(fault==Fault.PREWRITE_RACE){fixture.view.setText("external");fixture.view.setSelection(3);}
            }
            return copy;
        }
        @Override public boolean beginBatchEdit(){begins++;return fault!=Fault.REJECT_BATCH&&super.beginBatchEdit();}
        @Override public boolean endBatchEdit(){ends++;boolean value=fault==Fault.REJECT_BATCH?false:super.endBatchEdit();if(fault==Fault.END_THROW)throw new IllegalStateException("synthetic protocol fault");return fault==Fault.FALSE_END?false:value;}
        @Override public boolean setSelection(int start,int end){
            selections++;if(fault==Fault.REJECT_SELECTION)return false;if(fault==Fault.IGNORE_SELECTION)return true;
            boolean value=super.setSelection(start,end);
            if(fault==Fault.SELECT_RACE){fixture.view.setText("external");fixture.view.setSelection(3);}
            return value;
        }
        @Override public boolean commitText(CharSequence text,int position){
            commits++;if(fault==Fault.REJECT_COMMIT)return false;if(fault==Fault.IGNORE_COMMIT)return true;
            boolean value=super.commitText(text,position);
            if(fault==Fault.COMMIT_THROW_AFTER_WRITE)throw new IllegalStateException("synthetic protocol fault");
            return fault==Fault.FALSE_AFTER_COMMIT?false:value;
        }
    }
    private void all(Context context){
        Fixture previewFixture=new Fixture(context,"abcXYZ",3,Fault.NONE);BeforeCaretEditor.Preview preview=previewFixture.adapter.prepareDelete();
        check(preview!=null&&previewFixture.noWrites(),"batch preview takes a bounded snapshot without editing");
        check(previewFixture.adapter.matchesPreview(preview)&&previewFixture.noWrites(),"preview continuation guard verifies unchanged host without editing");
        previewFixture.view.setText("new host text");previewFixture.view.setSelection(3);
        check(!previewFixture.adapter.matchesPreview(preview)&&previewFixture.noWrites(),"preview continuation guard rejects changed text even with same caret");
        check(previewFixture.adapter.deleteBeforeCaret(preview).state==BeforeCaretEditor.State.REJECTED&&previewFixture.noWrites()&&previewFixture.text().equals("new host text"),"host text changed after highlight is never batch-deleted on release");
        previewFixture=new Fixture(context,"abcXYZ",3,Fault.NONE);preview=previewFixture.adapter.prepareDelete();previewFixture.view.setSelection(2);
        check(!previewFixture.adapter.matchesPreview(preview)&&previewFixture.noWrites(),"preview continuation guard rejects a moved caret");
        check(previewFixture.adapter.deleteBeforeCaret(preview).state==BeforeCaretEditor.State.REJECTED&&previewFixture.noWrites(),"moved caret after preview refuses stale prefix deletion");
        previewFixture=new Fixture(context,"abcXYZ",3,Fault.NONE);preview=previewFixture.adapter.prepareDelete();
        check(previewFixture.adapter.deleteBeforeCaret(preview).state==BeforeCaretEditor.State.APPLIED&&previewFixture.text().equals("XYZ"),"unchanged preview release deletes only its verified prefix");
        previewFixture=new Fixture(context,"abcXYZ",3,Fault.NONE);preview=previewFixture.adapter.prepareDelete();previewFixture.session.id++;
        check(!previewFixture.adapter.matchesPreview(preview)&&previewFixture.noWrites(),"preview continuation guard rejects another input session");
        previewFixture=new Fixture(context,"abcXYZ",3,Fault.NONE);preview=previewFixture.adapter.prepareDelete();previewFixture.adapter.invalidate();
        check(!previewFixture.adapter.matchesPreview(preview)&&previewFixture.noWrites(),"preview continuation guard rejects invalidated gesture identity");
        Fixture f=new Fixture(context,"abcXYZ",3,Fault.NONE);
        BeforeCaretEditor.Result r=f.adapter.deleteBeforeCaret();
        check(r.state==BeforeCaretEditor.State.APPLIED&&f.text().equals("XYZ")&&f.view.getSelectionStart()==0&&r.undoAvailable,"middle caret deletes only prefix and retains exact suffix");
        check(f.connection.selections==1&&f.connection.commits==1&&f.connection.begins==1&&f.connection.ends==1,"one selection/commit and one balanced batch without retries");
        r=f.adapter.undo();check(r.state==BeforeCaretEditor.State.APPLIED&&f.text().equals("abcXYZ")&&f.view.getSelectionStart()==3&&!r.undoAvailable,"one exact undo restores original prefix and caret");
        check(f.adapter.undo().state==BeforeCaretEditor.State.REJECTED&&f.text().equals("abcXYZ"),"second undo is unavailable and does not edit");

        String prefix="数学👩🏽‍💻e\u0301🇨🇦",suffix="后文🙂";
        f=new Fixture(context,prefix+suffix,prefix.length(),Fault.NONE);r=f.adapter.deleteBeforeCaret();
        check(r.state==BeforeCaretEditor.State.APPLIED&&f.text().equals(suffix),"ZWJ skin tone combining accent and flag prefix removed at whole grapheme boundary");
        check(f.adapter.undo().state==BeforeCaretEditor.State.APPLIED&&f.text().equals(prefix+suffix),"multicodepoint grapheme prefix undo preserves original Unicode");
        f=new Fixture(context,"A👩🏽‍💻B",3,Fault.NONE);check(f.adapter.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.noWrites(),"interior ZWJ/surrogate caret refused before any write");
        f=new Fixture(context,"Ae\u0301B",2,Fault.NONE);check(f.adapter.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.noWrites(),"interior combining-grapheme caret refused");
        f=new Fixture(context,"abcXYZ",3,Fault.NONE);f.view.setSelection(1,3);check(f.adapter.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.noWrites(),"existing noncollapsed selection refused without replacing selected text");
        f=new Fixture(context,"abcXYZ",0,Fault.NONE);check(f.adapter.deleteBeforeCaret().state==BeforeCaretEditor.State.NO_OP&&f.noWrites(),"start caret no-op never mutates selection or text");
        f=new Fixture(context,"abcXYZ",3,Fault.NONE);f.session.privateInput=true;check(f.adapter.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.connection.extracts==0&&f.noWrites(),"private input rejected before reading text");

        char[] large=new char[BeforeCaretEditor.MAX_UTF16+1];Arrays.fill(large,'x');
        f=new Fixture(context,new String(large),3,Fault.NONE);check(f.adapter.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.noWrites(),"snapshot above 32768 UTF16 code units rejected");
        f=new Fixture(context,new String(large,0,BeforeCaretEditor.MAX_UTF16),BeforeCaretEditor.MAX_UTF16,Fault.NONE);check(f.adapter.deleteBeforeCaret().state==BeforeCaretEditor.State.APPLIED&&f.text().isEmpty(),"exact 32768-unit complete snapshot remains supported");
        for(Fault fault:new Fault[]{Fault.OFFSET,Fault.PARTIAL,Fault.INVALID_UTF16,Fault.COMPOSING,Fault.READ_THROW}){
            f=new Fixture(context,"abcXYZ",3,fault);check(f.adapter.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.noWrites(),"unsafe snapshot protocol rejected: "+fault.name());
        }
        f=new Fixture(context,"abcXYZ",3,Fault.REJECT_SELECTION);r=f.adapter.deleteBeforeCaret();check(r.state==BeforeCaretEditor.State.REJECTED&&f.text().equals("abcXYZ")&&f.connection.commits==0,"selection refusal with verified unchanged state is rejected");
        f=new Fixture(context,"abcXYZ",3,Fault.IGNORE_SELECTION);r=f.adapter.deleteBeforeCaret();check(r.state==BeforeCaretEditor.State.UNCERTAIN&&f.text().equals("abcXYZ")&&f.connection.commits==0,"accepted selection with unchanged snapshot remains uncertain and is not retried");
        f=new Fixture(context,"abcXYZ",3,Fault.REJECT_COMMIT);r=f.adapter.deleteBeforeCaret();check(r.state==BeforeCaretEditor.State.UNCERTAIN&&f.text().equals("abcXYZ")&&f.connection.commits==1&&!f.adapter.hasUndo(),"commit refusal after selection change is uncertain and never retried");
        f=new Fixture(context,"abcXYZ",3,Fault.FALSE_AFTER_COMMIT);r=f.adapter.deleteBeforeCaret();check(r.state==BeforeCaretEditor.State.UNCERTAIN&&f.text().equals("XYZ")&&!f.adapter.hasUndo(),"false commit response despite applied edit never creates guessed undo");
        f=new Fixture(context,"abcXYZ",3,Fault.IGNORE_COMMIT);r=f.adapter.deleteBeforeCaret();check(r.state==BeforeCaretEditor.State.UNCERTAIN&&!f.adapter.hasUndo()&&f.connection.commits==1,"true commit response without expected after-state is not success");
        f=new Fixture(context,"abcXYZ",3,Fault.SELECT_RACE);r=f.adapter.deleteBeforeCaret();check(r.state==BeforeCaretEditor.State.UNCERTAIN&&f.text().equals("external")&&f.connection.commits==0,"host race after selection aborts before text replacement without global repair");
        f=new Fixture(context,"abcXYZ",3,Fault.PREWRITE_RACE);r=f.adapter.deleteBeforeCaret();check(r.state==BeforeCaretEditor.State.REJECTED&&f.text().equals("external")&&f.noWrites(),"host edit between initial read and write guard is preserved");
        f=new Fixture(context,"abcXYZ",3,Fault.STALE_SESSION);check(f.adapter.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.noWrites(),"session change while extracting prevents every edit");
        f=new Fixture(context,"abcXYZ",3,Fault.PRIVATE_ON_READ);check(f.adapter.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.noWrites(),"transition to private input during read prevents edits");
        f=new Fixture(context,"abcXYZ",3,Fault.FALSE_END);check(f.adapter.deleteBeforeCaret().state==BeforeCaretEditor.State.APPLIED&&f.text().equals("XYZ"),"normal local endBatchEdit false can succeed only with exact after-state");
        f=new Fixture(context,"abcXYZ",3,Fault.REJECT_BATCH);check(f.adapter.deleteBeforeCaret().state==BeforeCaretEditor.State.REJECTED&&f.noWrites()&&f.connection.ends==1,"batch refusal balances call and sends no selection or commit");
        for(Fault fault:new Fault[]{Fault.COMMIT_THROW_AFTER_WRITE,Fault.END_THROW}){
            f=new Fixture(context,"abcXYZ",3,fault);r=f.adapter.deleteBeforeCaret();check(r.state==BeforeCaretEditor.State.UNCERTAIN&&!f.adapter.hasUndo()&&f.connection.commits==1,"post-write host exception has no automatic retry or undo: "+fault.name());
        }

        f=new Fixture(context,"abcXYZ",3,Fault.NONE);f.adapter.deleteBeforeCaret();f.session.id++;
        check(f.adapter.undo().state==BeforeCaretEditor.State.REJECTED&&f.text().equals("XYZ"),"same InputConnection new session refuses old undo");
        f=new Fixture(context,"abcXYZ",3,Fault.NONE);f.adapter.deleteBeforeCaret();f.session.connection=new InputConnectionWrapper(f.connection,true);
        check(f.adapter.undo().state==BeforeCaretEditor.State.REJECTED&&f.text().equals("XYZ"),"different InputConnection with same text/session refuses undo");
        f=new Fixture(context,"abcXYZ",3,Fault.NONE);f.adapter.deleteBeforeCaret();f.view.setText("new host edit");f.view.setSelection(0);
        check(f.adapter.undo().state==BeforeCaretEditor.State.REJECTED&&f.text().equals("new host edit"),"undo never overwrites subsequent host text edit");
        f=new Fixture(context,"abcXYZ",3,Fault.NONE);f.adapter.deleteBeforeCaret();f.view.setSelection(1);
        check(f.adapter.undo().state==BeforeCaretEditor.State.REJECTED&&f.text().equals("XYZ"),"undo rejects changed caret even when suffix text is unchanged");
        f=new Fixture(context,"abcXYZ",3,Fault.NONE);f.adapter.deleteBeforeCaret();f.adapter.invalidate();
        check(!f.adapter.hasUndo()&&f.adapter.undo().state==BeforeCaretEditor.State.REJECTED&&f.text().equals("XYZ"),"lifecycle invalidation drops ephemeral undo references");
        f=new Fixture(context,"abcXYZ",3,Fault.NONE);f.adapter.deleteBeforeCaret();f.session.privateInput=true;
        check(f.adapter.undo().state==BeforeCaretEditor.State.REJECTED&&!f.adapter.hasUndo()&&f.text().equals("XYZ"),"private transition discards undo without reading or restoring private input");
    }
    public static String run(Instrumentation test)throws Exception{
        BeforeCaretEditorChecks suite=new BeforeCaretEditorChecks();AtomicReference<Throwable> failure=new AtomicReference<>();
        test.runOnMainSync(()->{try{suite.all(test.getTargetContext());}catch(Throwable problem){failure.set(problem);}});
        if(failure.get()!=null)throw new AssertionError("BeforeCaretEditor native component checks failed",failure.get());
        return "PASS "+suite.checks+" native EditText/InputConnection component checks\n"+suite.log+"Synthetic owned text only; not full OS IME gesture/UI or third-party host acceptance. No private text logged.\n";
    }
}
