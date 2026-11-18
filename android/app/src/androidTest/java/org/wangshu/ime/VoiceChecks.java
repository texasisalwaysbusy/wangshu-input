package org.wangshu.ime;
import android.app.Instrumentation;
import android.os.*;
import java.io.*;
import java.util.zip.*;

/** Public model WAV -> production CPU recognizer. Does not exercise a microphone. */
final class VoiceChecks {
    static String run(Instrumentation test)throws Exception{return run(test,true);}
    /** Fail closed before any installation when exercising an already available model. */
    static String run(Instrumentation test,boolean mayInstall)throws Exception{
        StringBuilder log=new StringBuilder();android.content.Context c=test.getTargetContext();
        if(!mayInstall&&!VoiceModels.available(c))throw new IOException("Existing local voice model unavailable; no model install attempted");
        PublicVoiceFixture fixture=readFixture(test);
        log.append("PASS bound public fixture ").append(fixture.name)
            .append(" SHA-256 ").append(fixture.sha256).append('\n');
        byte[] bad;try(ByteArrayOutputStream b=new ByteArrayOutputStream();ZipOutputStream z=new ZipOutputStream(b)){z.putNextEntry(new ZipEntry("../escape.onnx"));z.write(1);z.closeEntry();z.finish();bad=b.toByteArray();}
        boolean repairFixture=false;java.io.File prior=new java.io.File(VoiceModels.folder(c),"prior-test-model.txt");
        if(mayInstall&&!VoiceModels.available(c)&&!VoiceModels.folder(c).exists()){if(!VoiceModels.folder(c).mkdirs())throw new IOException("cannot prepare test-owned incomplete model");try(FileOutputStream out=new FileOutputStream(prior)){out.write("test-owned incomplete public model".getBytes(java.nio.charset.StandardCharsets.UTF_8));}repairFixture=true;}
        if(!VoiceModels.available(c)){
            if(!mayInstall)throw new IOException("Existing voice model disappeared; no model install attempted");
            boolean rejected=false;try{VoiceModels.install(c,new ByteArrayInputStream(bad));}catch(IOException e){rejected=true;}if(!rejected||VoiceModels.available(c))throw new AssertionError("untrusted model accepted");log.append("PASS traversal/unrecognized model package rejected before native parsing\n");
            try(ParcelFileDescriptor fd=test.getUiAutomation().executeShellCommand("cat /data/local/tmp/wangshu-voice-test.zip");InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){VoiceModels.install(c,input);}
        }
        if(!VoiceModels.available(c))throw new AssertionError("verified voice model not ready");log.append(mayInstall?"PASS pinned model installed with SHA-256 and exact size checks\n":"PASS existing model size and verified marker precondition; no installation attempted\n");
        if(repairFixture){File[] previous=VoiceModels.folder(c).getParentFile().listFiles(f->f.getName().startsWith("previous-"));boolean retained=false;if(previous!=null)for(File f:previous)retained|=new File(f,"prior-test-model.txt").isFile();if(!retained)throw new AssertionError("incomplete old model was not preserved");log.append("PASS actual Android incomplete-model repair preserves old directory\n");}
        Bundle checkpoint=new Bundle();checkpoint.putString("result",log.toString());test.sendStatus(0,checkpoint);
        long time=SystemClock.elapsedRealtime();
        String text=OfflineVoice.transcribe(c,fixture.samples,fixture.sampleRate);
        long elapsed=SystemClock.elapsedRealtime()-time;
        log.append("Raw public WAV result: ").append(text).append("\nInference elapsed ms: ").append(elapsed).append('\n');
        checkpoint=new Bundle();checkpoint.putString("result",log.toString());test.sendStatus(0,checkpoint);
        if(!fixture.matchesSmoke(text))throw new AssertionError("Public WAV smoke mismatch: "+fixture.name);
        log.append("PASS Android offline inference smoke for ").append(fixture.name).append('\n');
        if(!OfflineVoice.transcribe(c,new float[16000],16000).isEmpty())throw new AssertionError("silence produced text");log.append("PASS silence produces no insertion text\n");
        log.append(VoiceSessionChecks.run(c));log.append("Real microphone NOT_RUN. Public WAV smoke and synthetic session checks do not measure accuracy.\n");return log.toString();
    }

    private static PublicVoiceFixture readFixture(Instrumentation test)throws IOException {
        try(ParcelFileDescriptor fd=test.getUiAutomation().executeShellCommand("cat /data/local/tmp/wangshu-voice-test.wav");
            InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd);
            ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];
            int count;
            while((count=in.read(buffer))!=-1){
                if(out.size()+count>1000000)throw new IOException("Public WAV fixture too large");
                out.write(buffer,0,count);
            }
            return PublicVoiceFixture.parse(out.toByteArray());
        }
    }
}
