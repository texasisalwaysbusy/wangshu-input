package org.wangshu.ime;
import android.app.Instrumentation;
import android.os.*;
import java.io.*;
import java.nio.*;
import java.util.zip.*;

/** Public model WAV -> production CPU recognizer. Does not exercise a microphone. */
final class VoiceChecks {
    static String run(Instrumentation test)throws Exception{return run(test,true);}
    /** Fail closed before any installation when exercising an already available model. */
    static String run(Instrumentation test,boolean mayInstall)throws Exception{
        StringBuilder log=new StringBuilder();android.content.Context c=test.getTargetContext();
        if(!mayInstall&&!VoiceModels.available(c))throw new IOException("Existing local voice model unavailable; no model install attempted");
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
        byte[] wav;try(ParcelFileDescriptor fd=test.getUiAutomation().executeShellCommand("cat /data/local/tmp/wangshu-voice-test.wav");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>1000000)throw new IOException("fixture too large");}wav=out.toByteArray();}
        ByteBuffer b=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);if(b.getInt()!=0x46464952||b.getInt(8)!=0x45564157)throw new IOException("not WAV");int channels=0,rate=0,bits=0,start=-1,length=0;
        for(int pos=12;pos+8<=wav.length;){int id=b.getInt(pos),n=b.getInt(pos+4);if(n<0||pos+8L+n>wav.length)throw new IOException("bad WAV chunk");if(id==0x20746d66){if(b.getShort(pos+8)!=1)throw new IOException("not PCM");channels=b.getShort(pos+10);rate=b.getInt(pos+12);bits=b.getShort(pos+22);}if(id==0x61746164){start=pos+8;length=n;break;}pos+=8+n+(n&1);}
        if(channels!=1||bits!=16||rate!=16000||start<0)throw new IOException("expected mono PCM16 16kHz fixture");float[] samples=new float[length/2];for(int i=0;i<samples.length;i++)samples[i]=b.getShort(start+i*2)/32768f;
        long time=SystemClock.elapsedRealtime();String text=OfflineVoice.transcribe(c,samples,rate);long elapsed=SystemClock.elapsedRealtime()-time;
        if(!text.contains("这是第一种")||!text.contains("什么意思"))throw new AssertionError("actual transcript: "+text);log.append("PASS actual Android offline Chinese/English audio inference: ").append(text).append("\nElapsed cloud software emulator ms: ").append(elapsed).append('\n');
        checkpoint=new Bundle();checkpoint.putString("result",log.toString());test.sendStatus(0,checkpoint);
        if(!OfflineVoice.transcribe(c,new float[16000],16000).isEmpty())throw new AssertionError("silence produced text");log.append("PASS silence produces no insertion text\n");
        log.append(VoiceSessionChecks.run(c));log.append("Real microphone and real phone NOT RUN; no accuracy score inferred from fixture checks\n");return log.toString();
    }
}
