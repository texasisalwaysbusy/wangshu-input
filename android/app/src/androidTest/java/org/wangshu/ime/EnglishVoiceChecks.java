package org.wangshu.ime;

import android.app.Instrumentation;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/** One test-owned synthetic English clip. Never installs a model or opens a microphone. */
final class EnglishVoiceChecks {
    static String run(Instrumentation test)throws Exception{
        android.content.Context context=test.getTargetContext();
        if(!VoiceModels.available(context))throw new IOException("Existing local model unavailable; no install attempted");
        byte[] wav;try(InputStream input=test.getContext().getAssets().open("voice/english-synthetic.wav");ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] chunk=new byte[8192];int n;while((n=input.read(chunk))!=-1){if(output.size()+n>1000000)throw new IOException("Test WAV exceeds bound");output.write(chunk,0,n);}wav=output.toByteArray();
        }
        ByteBuffer buffer=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if(wav.length<12||buffer.getInt()!=0x46464952||buffer.getInt(8)!=0x45564157)throw new IOException("Invalid test WAV");
        int channels=0,rate=0,bits=0,start=-1,length=0;
        for(int pos=12;pos+8<=wav.length;){int id=buffer.getInt(pos),size=buffer.getInt(pos+4);if(size<0||pos+8L+size>wav.length)throw new IOException("Invalid WAV chunk");
            if(id==0x20746d66){if(size<16||buffer.getShort(pos+8)!=1)throw new IOException("Expected PCM");channels=buffer.getShort(pos+10);rate=buffer.getInt(pos+12);bits=buffer.getShort(pos+22);}
            if(id==0x61746164){start=pos+8;length=size;break;}pos+=8+size+(size&1);
        }
        if(channels!=1||bits!=16||rate!=16000||start<0||(length&1)!=0)throw new IOException("Expected mono PCM16 16kHz fixture");
        float[] samples=new float[length/2];for(int i=0;i<samples.length;i++)samples[i]=buffer.getShort(start+i*2)/32768f;
        long started=SystemClock.elapsedRealtime();String result=OfflineVoice.transcribe(context,samples,rate);long elapsed=SystemClock.elapsedRealtime()-started;
        String expected="PLEASE SEND THE NOTES ON MONDAY MORNING";String actual=result==null?"":result.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+"," ");
        String details="reference="+expected+"\nhypothesis="+actual+"\nsoftware_emulator_ms="+elapsed+"\nSynthetic local TTS sample only; no actual microphone, human speech, installation or phone accuracy claim.\n";
        if(!actual.equals(expected))throw new AssertionError("English synthetic phrase differs; no generated correction or automatic retry\n"+details);
        return "PASS exact single synthetic English utterance through actual Android local model\n"+details;
    }
}
