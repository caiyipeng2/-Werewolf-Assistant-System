package com.langyu.assistant;
import android.app.Activity;
import android.os.Bundle;
import android.media.*;
import android.widget.*;
import java.io.*;
/** Test-only allowed playback source, never packaged in the delivered application. */
public class AudioSourceActivity extends Activity {
    private volatile boolean playing;
    private AudioTrack track;
    private TextView state;
    @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(30,80,30,30);state=new TextView(this);state.setText("中文离线测试音频 · 仅用于验证系统内录");box.addView(state);Button play=new Button(this);play.setText("播放中文测试音频");play.setOnClickListener(v->play());box.addView(play);Button stop=new Button(this);stop.setText("停止测试音频");stop.setOnClickListener(v->{playing=false;state.setText("已停止");});box.addView(stop);setContentView(box);if(getIntent().getBooleanExtra("play",false))play();}
    private void play(){if(playing)return;playing=true;state.setText("正在播放：三号玩家 / 预言家 / 二号好人 / 警徽流…");new Thread(()->{try{
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=getAssets().open("chinese.wav")){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}byte[] wav=out.toByteArray();int start=-1,length=0;for(int i=12;i+8<wav.length;){int len=(wav[i+4]&255)|((wav[i+5]&255)<<8)|((wav[i+6]&255)<<16)|((wav[i+7]&255)<<24);if(wav[i]=='d'&&wav[i+1]=='a'&&wav[i+2]=='t'&&wav[i+3]=='a'){start=i+8;length=len;break;}i+=8+len+(len&1);}if(start<0)throw new IOException("WAV数据缺失");
        AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build();
        track=new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(16000).setTransferMode(AudioTrack.MODE_STREAM).build();track.play();
        for(int loop=0;loop<5&&playing;loop++){for(int pos=start;pos<start+length&&playing;){int n=track.write(wav,pos,Math.min(3200,start+length-pos),AudioTrack.WRITE_BLOCKING);if(n<0)throw new IOException("AudioTrack "+n);pos+=n;}if(playing)Thread.sleep(1800);}
    }catch(Exception e){runOnUiThread(()->state.setText("测试错误："+e));}finally{playing=false;if(track!=null){try{track.stop();}catch(Exception ignored){}track.release();track=null;}runOnUiThread(()->state.setText("测试音频结束"));}},"langyu-test-audio").start();}
    @Override protected void onDestroy(){playing=false;super.onDestroy();}
}
