package com.langyu.assistant;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import android.text.*;
import org.vosk.*;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Playback only: never constructs an AudioRecord with a microphone source. */
public class CaptureService extends Service {
    public static final String ACTION_PAUSE="com.langyu.assistant.PAUSE",ACTION_STOP="com.langyu.assistant.STOP",ACTION_NEXT="com.langyu.assistant.NEXT",ACTION_FLUSH="com.langyu.assistant.FLUSH",ACTION_OVERLAY="com.langyu.assistant.OVERLAY";
    public static final String ACTION_SCAN="com.langyu.assistant.SCAN",ACTION_UNLOCK="com.langyu.assistant.UNLOCK",ACTION_NEW_MATCH="com.langyu.assistant.NEW_MATCH";
    public static volatile boolean running,paused;
    public static volatile String status="未开始监听",partial="";
    public static volatile String screenStatus="等待开始游戏画面识别";
    public static volatile boolean screenAvailable;
    public static volatile int level;
    private final Handler main=new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private ScreenMonitor screenMonitor;
    private SessionBinder sessionBinder;
    private AudioRecord record;
    private Thread worker;
    private Repository repo;
    private WindowManager wm;
    private LinearLayout overlay,expanded;
    private TextView overlayStatus,overlayText,seatText,overlayScreenStatus;
    private EditText overlayDraft;
    private boolean bindingOverlayDraft;
    private String overlaySaveError="";
    private final Runnable persistOverlayDraft=()->saveOverlayDraft();
    private WindowManager.LayoutParams windowParams;
    private volatile int seat=1,day=1;
    private volatile String matchId="";
    private final ConcurrentLinkedQueue<Runnable> boundaries=new ConcurrentLinkedQueue<>();
    private Recognizer recognizer;
    private long lastSignal,lastUpdate;
    private boolean stopping;
    private volatile boolean active;
    private boolean overlayHiddenForFrame;
    public static Model model(Context c)throws java.io.IOException{return OfflineModel.get(c.getApplicationContext());}
    public static boolean modelReady(Context c){return OfflineModel.cached!=null;}
    @Override public void onCreate(){super.onCreate();repo=Repository.get(this);sessionBinder=new SessionBinder(this);wm=(WindowManager)getSystemService(WINDOW_SERVICE);NotificationChannel ch=new NotificationChannel("capture","系统音频监听与画面识别",NotificationManager.IMPORTANCE_LOW);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);}
    private PendingIntent command(String action,int request){return PendingIntent.getService(this,request,new Intent(this,CaptureService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"capture").setSmallIcon(com.langyu.assistant.R.drawable.icon).setContentTitle("狼语复盘 · "+(paused?"已暂停":"系统音频监听")).setContentText(status).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,paused?"继续":"暂停",command(ACTION_PAUSE,1)).build()).addAction(new Notification.Action.Builder(null,"下一位",command(ACTION_NEXT,2)).build()).addAction(new Notification.Action.Builder(null,"停止",command(ACTION_STOP,3)).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null)return START_NOT_STICKY;
        String action=intent.getAction();
        if(ACTION_STOP.equals(action)){stopSelf();return START_NOT_STICKY;}
        if(ACTION_SCAN.equals(action)){exitOverlayEditing();if(running){screenStatus=paused?"监听已暂停，请先点击继续":"已收到识别请求，正在检查游戏画面…";publish();Toast.makeText(this,paused?"请先继续监听，再识别本局":"已请求识别本局",Toast.LENGTH_SHORT).show();startScreenMonitor();if(screenMonitor!=null)screenMonitor.requestScan();}else{screenStatus="请先开始监听并授权系统截图";publish();stopSelf();}return START_NOT_STICKY;}
        if(ACTION_UNLOCK.equals(action)||ACTION_NEW_MATCH.equals(action)){if(screenMonitor!=null)screenMonitor.invalidate();queueChange(()->{sessionBinder.reset(ACTION_NEW_MATCH.equals(action));screenStatus=sessionBinder.summary();syncSegment();if(screenMonitor!=null)screenMonitor.requestScan();publish();});return START_NOT_STICKY;}
        if(ACTION_OVERLAY.equals(action)){removeOverlay();showOverlay();return START_NOT_STICKY;}
        if(ACTION_PAUSE.equals(action)&&running){
            boolean next=!paused;paused=next;if(screenMonitor!=null)screenMonitor.pause(next);status=next?"已暂停 · 音频与画面捕获已暂停":"监听中 · 等待语音";publish();return START_NOT_STICKY;
        }
        if((ACTION_NEXT.equals(action)||ACTION_FLUSH.equals(action))&&running){
            final int desired=intent.getIntExtra("speaker",-1);final int desiredDay=intent.getIntExtra("day",-1);final String phase=intent.getStringExtra("phase");
            boundaries.add(()->{flushRecognized();java.util.concurrent.CountDownLatch applied=new java.util.concurrent.CountDownLatch(1);main.post(()->{try{if(ACTION_NEXT.equals(action))repo.state.nextSpeaker();else if(desired>0&&desired<=repo.state.players())repo.state.speaker=desired;if(desiredDay>0)repo.state.day=desiredDay;if(phase!=null)repo.state.phase=phase;syncSegment();repo.save();publish();}catch(Exception e){status="本地保存失败："+friendly(e);publish();}finally{applied.countDown();}});try{if(!applied.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("切换发言席超时");}catch(InterruptedException e){Thread.currentThread().interrupt();}});return START_NOT_STICKY;
        }
        if(running)return START_NOT_STICKY;
        Intent data=intent.getParcelableExtra("data");int code=intent.getIntExtra("code",Activity.RESULT_CANCELED);
        if(data==null||code!=Activity.RESULT_OK){status="授权未完成 · 请重新开始";publish();stopSelf();return START_NOT_STICKY;}
        try{
            stopping=false;active=true;running=true;paused=false;status="准备离线模型…";startForeground(37,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            MediaProjectionManager mgr=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);projection=mgr.getMediaProjection(code,data);
            projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){if(!stopping){status="系统捕获授权已撤回";stopSelf();}}@Override public void onCapturedContentResize(int w,int h){if(screenMonitor!=null)screenMonitor.resize(w,h);}},main);
            syncSegment();showOverlay();startScreenMonitor();publish();worker=new Thread(this::capture,"langyu-offline-capture");worker.start();
        }catch(Exception e){status="无法开始："+friendly(e);running=false;publish();stopSelf();}
        return START_NOT_STICKY;
    }
    private void syncSegment(){seat=repo.state.speaker;day=repo.state.day;matchId=repo.state.id;}
    private void queueChange(Runnable change){
        if(!active){try{change.run();}catch(Exception e){screenStatus="对局更新失败："+friendly(e);publish();}return;}
        boundaries.add(()->{flushRecognized();java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);main.post(()->{try{if(active)change.run();}catch(Exception e){screenStatus="对局更新失败："+friendly(e);publish();}finally{done.countDown();}});try{if(!done.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("对局切换等待超时");}catch(InterruptedException e){Thread.currentThread().interrupt();}});
    }
    private void startScreenMonitor(){
        if(!active||projection==null)return;
        if(!getSharedPreferences("settings",MODE_PRIVATE).getBoolean("screenRecognition",true)){screenStatus="自动画面识别已关闭";publish();return;}
        if(screenMonitor!=null)return;
        screenMonitor=new ScreenMonitor(this,projection,new ScreenMonitor.Listener(){
            public void onResult(ScreenRecognition.Result result){
                if(!active)return;
                if(result==null||!result.gameScreen){sessionBinder.clearPending();screenStatus="未找到本人身份信息 · 请展示自己的身份牌";publish();return;}
                final long token=screenMonitor.generation(),viewEpoch=MainActivity.visibilityEpoch;final String resultMatch=repo.state.id;queueChange(()->{if(screenMonitor==null||!screenMonitor.canApply(token,resultMatch)||viewEpoch!=MainActivity.visibilityEpoch)return;screenStatus=sessionBinder.accept(result);screenAvailable=true;syncSegment();publish();});
            }
            public void onStatus(String message){screenStatus=message;screenAvailable=!message.startsWith("截图不可用");publish();}
            public boolean beforeFrame(){if(!active||paused||MainActivity.visible)return false;if(overlayDraft!=null&&overlayDraft.hasFocus()&&overlayDraft.hasWindowFocus())return false;if(overlay!=null){overlayHiddenForFrame=true;overlay.setVisibility(View.INVISIBLE);}return true;}
            public void afterFrame(){if(overlayHiddenForFrame&&overlay!=null)overlay.setVisibility(View.VISIBLE);overlayHiddenForFrame=false;}
            public boolean requiresFreshFrame(){return overlay!=null;}
        });screenAvailable=true;screenMonitor.start();screenMonitor.pause(paused);
    }
    private void capture(){
        try{
            Model m=model(this);if(!active)return;recognizer=new Recognizer(m,16000);recognizer.setWords(true);
            String target=getSharedPreferences("settings",MODE_PRIVATE).getString("targetPackage","com.netease.werewolf");
            if(target==null||target.trim().isEmpty())throw new IllegalArgumentException("请先选择音频来源应用");
            int uid=getPackageManager().getApplicationInfo(target,0).uid;
            AudioPlaybackCaptureConfiguration config=new AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).addMatchingUid(uid).build();
            AudioFormat format=new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            record=new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(Math.max(16000,min*2)).setAudioPlaybackCaptureConfig(config).build();
            if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("设备未能初始化系统内录");
            record.startRecording();status="监听中 · 等待来源应用语音";lastSignal=System.currentTimeMillis();main.post(this::publish);short[] audio=new short[1600];long segmentStart=System.currentTimeMillis();boolean wasPaused=paused;
            while(active){
                Runnable boundary;while((boundary=boundaries.poll())!=null)boundary.run();
                if(paused){if(record.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)record.stop();if(!wasPaused)flushRecognized();wasPaused=true;level=0;Thread.sleep(80);continue;}
                if(wasPaused){recognizer.reset();record.startRecording();wasPaused=false;lastSignal=System.currentTimeMillis();segmentStart=lastSignal;}
                int n=record.read(audio,0,audio.length,AudioRecord.READ_BLOCKING);if(n<0){if(active)throw new IllegalStateException("系统音频已中断（"+n+"）");break;}if(n==0||paused)continue;
                double sum=0;for(int i=0;i<n;i++)sum+=(double)audio[i]*audio[i];double rms=Math.sqrt(sum/n);level=Math.min(100,(int)(rms/60));long now=System.currentTimeMillis();if(rms>20)lastSignal=now;
                if(recognizer.acceptWaveForm(audio,n)){emit(recognizer.getResult());segmentStart=now;}else{partial=text(recognizer.getPartialResult(),"partial");}
                if(now-segmentStart>18000){flushRecognized();segmentStart=now;}
                status=now-lastSignal>12000?"未收到音频 · 检查来源是否播放及允许内录":partial.isEmpty()?"监听中 · "+seat+"号":"转写中 · "+seat+"号";
                if(now-lastUpdate>450){lastUpdate=now;main.post(this::publish);}
            }
            flushRecognized();
        }catch(Throwable e){if(active){status="监听中断："+friendly(e);main.post(()->{publish();stopSelf();});}}
        finally{if(record!=null){try{record.stop();}catch(Exception ignored){}record.release();record=null;}if(recognizer!=null){recognizer.close();recognizer=null;}partial="";level=0;}
    }
    private static String friendly(Throwable e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
    private static String text(String json,String key){try{return new JSONObject(json).optString(key,"").replace(" ","").trim();}catch(Exception e){return "";}}
    private void flushRecognized(){if(recognizer!=null){emit(recognizer.getFinalResult());recognizer.reset();}partial="";}
    private void emit(String json){String content=RuleEngine.normalize(text(json,"text"));if(content.trim().isEmpty())return;final int capturedSeat=seat,capturedDay=day;final String capturedMatch=matchId;main.post(()->{if(repo.state.id.equals(capturedMatch)){repo.state.addSpeech(capturedDay,capturedSeat,content);try{repo.save();}catch(Exception e){status="本地保存失败："+friendly(e);paused=true;}publish();}});partial="";}
    private static void setChanged(TextView view,String text){if(!text.contentEquals(view.getText()))view.setText(text);}
    private void publish(){syncOverlayDraft(false);sendBroadcast(new Intent("com.langyu.assistant.UPDATE").setPackage(getPackageName()));if(overlayStatus!=null){setChanged(overlayStatus,(paused?"Ⅱ ":"● ")+status);setChanged(seatText,(repo.state.mySeat>0?"我："+repo.state.mySeat+"号 "+repo.state.myRole+"\n":"")+"第"+repo.state.day+"天 · "+repo.state.speaker+"号发言");if(overlayScreenStatus!=null)setChanged(overlayScreenStatus,screenStatus);String show=partial;if(show.isEmpty()&&!repo.state.speeches.isEmpty()){GameState.Speech s=repo.state.speeches.get(repo.state.speeches.size()-1);show=s.speaker+"号："+s.text;}setChanged(overlayText,show.isEmpty()?"等待语音；点击下一位切换发言席":show);}if(running)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(37,notification());}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView label(String text,int size,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(8),dp(6),dp(8),dp(6));return t;}
    private Button button(String text,Runnable onClick){Button b=new Button(this);b.setText(text);b.setTextSize(12);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setMinHeight(dp(44));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(54,48,91));bg.setCornerRadius(dp(12));b.setBackground(bg);b.setOnClickListener(v->onClick.run());return b;}
    private void showOverlay(){
        if(!running||!getSharedPreferences("settings",MODE_PRIVATE).getBoolean("overlay",true)||!Settings.canDrawOverlays(this))return;
        try{
            overlay=new LinearLayout(this);overlay.setOrientation(LinearLayout.VERTICAL);overlay.setPadding(dp(6),dp(4),dp(6),dp(6));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(22,31,51));bg.setCornerRadius(dp(16));bg.setStroke(dp(1),Color.rgb(116,101,176));overlay.setBackground(bg);
            TextView header=label("◈ 狼语  ·  拖动 / 点击折叠",13,Color.rgb(184,170,255));header.setContentDescription("拖动悬浮窗");overlay.addView(header);
            expanded=new LinearLayout(this);expanded.setOrientation(LinearLayout.VERTICAL);overlay.addView(expanded);overlayStatus=label(status,11,Color.rgb(112,221,199));expanded.addView(overlayStatus);seatText=label("",13,Color.WHITE);expanded.addView(seatText);overlayText=label("等待语音",13,Color.WHITE);overlayText.setMaxLines(4);expanded.addView(overlayText);
            overlayScreenStatus=label(screenStatus,11,Color.rgb(184,170,255));overlayScreenStatus.setMaxLines(2);expanded.addView(overlayScreenStatus);expanded.addView(button("识别本局",()->onStartCommand(new Intent().setAction(ACTION_SCAN),0,0)));
            expanded.addView(button("重新授权截图",()->{exitOverlayEditing();startActivity(new Intent(this,MainActivity.class).putExtra("repairProjection",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));}));
            LinearLayout row=new LinearLayout(this);row.addView(button("暂停/继续",()->onStartCommand(new Intent().setAction(ACTION_PAUSE),0,0)),new LinearLayout.LayoutParams(0,dp(48),1));row.addView(button("下一位",()->onStartCommand(new Intent().setAction(ACTION_NEXT),0,0)),new LinearLayout.LayoutParams(0,dp(48),1));row.addView(button("停止",this::stopSelf),new LinearLayout.LayoutParams(0,dp(48),1));expanded.addView(row);
            Button suggest=button("生成发言框架",()->{
                try{
                    android.content.SharedPreferences preferences=getSharedPreferences("settings",MODE_PRIVATE);
                    String identity=preferences.getBoolean("followIdentity",true)&&!repo.state.myRole.isEmpty()?repo.state.myRole:preferences.getString("identity","好人");repo.state.draft=RuleEngine.framework(repo.state,identity,preferences.getString("style","清晰简练"));
                    syncOverlayDraft(true);saveOverlayDraft();
                }catch(Exception error){showOverlayError("生成失败："+friendly(error));}
            });expanded.addView(suggest);
            overlayDraft=new EditText(this);overlayDraft.setTextColor(Color.WHITE);overlayDraft.setHintTextColor(Color.LTGRAY);overlayDraft.setTextSize(12);overlayDraft.setMinLines(2);overlayDraft.setMaxLines(5);overlayDraft.setHint("在此编辑话术草稿");overlayDraft.setContentDescription("悬浮话术草稿");overlayDraft.setInputType(0x20001);overlayDraft.setFocusable(false);syncOverlayDraft(true);
            overlayDraft.addTextChangedListener(new TextWatcher(){
                public void beforeTextChanged(CharSequence s,int a,int c,int f){}
                public void onTextChanged(CharSequence s,int a,int b,int c){
                    if(bindingOverlayDraft)return;
                    repo.state.draft=s.toString();
                    main.removeCallbacks(persistOverlayDraft);main.postDelayed(persistOverlayDraft,450);
                }
                public void afterTextChanged(Editable e){}
            });expanded.addView(overlayDraft);
            Button edit=button("编辑草稿 / 收起键盘",()->{
                if((windowParams.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)==0){exitOverlayEditing();return;}
                try{
                    syncOverlayDraft(true);
                    windowParams.flags&=~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;wm.updateViewLayout(overlay,windowParams);
                    overlayDraft.setFocusableInTouchMode(true);overlayDraft.setFocusable(true);overlayDraft.requestFocus();
                    overlayDraft.post(()->{if(overlayDraft!=null&&overlayDraft.hasFocus())((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(overlayDraft,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
                }catch(Exception error){exitOverlayEditing();showOverlayError("无法编辑草稿："+friendly(error));}
            });expanded.addView(edit);
            windowParams=new WindowManager.LayoutParams(dp(290),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);windowParams.gravity=Gravity.TOP|Gravity.START;windowParams.x=dp(10);windowParams.y=dp(120);
            header.setOnTouchListener(new View.OnTouchListener(){float x,y;int ox,oy;boolean moved;public boolean onTouch(View v,MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();ox=windowParams.x;oy=windowParams.y;moved=false;return true;}if(e.getAction()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-x,dy=e.getRawY()-y;if(Math.abs(dx)+Math.abs(dy)>dp(7))moved=true;windowParams.x=Math.max(0,Math.min(getResources().getDisplayMetrics().widthPixels-dp(80),ox+(int)dx));windowParams.y=Math.max(0,Math.min(getResources().getDisplayMetrics().heightPixels-dp(70),oy+(int)dy));wm.updateViewLayout(overlay,windowParams);return true;}if(e.getAction()==MotionEvent.ACTION_UP){if(!moved){if(expanded.getVisibility()==View.VISIBLE){exitOverlayEditing();expanded.setVisibility(View.GONE);}else{syncOverlayDraft(true);expanded.setVisibility(View.VISIBLE);}}return true;}return false;}});
            wm.addView(overlay,windowParams);publish();
        }catch(Exception e){removeOverlay();status="悬浮窗不可用；监听状态请看通知栏";}
    }
    private void syncOverlayDraft(boolean force){
        if(overlayDraft==null||(!force&&overlayDraft.hasFocus()&&overlayDraft.hasWindowFocus()))return;
        String latest=repo.state.draft==null?"":repo.state.draft;
        if(latest.equals(overlayDraft.getText().toString()))return;
        bindingOverlayDraft=true;
        try{overlayDraft.setText(latest);overlayDraft.setSelection(overlayDraft.length());}
        finally{bindingOverlayDraft=false;}
    }
    private void saveOverlayDraft(){
        main.removeCallbacks(persistOverlayDraft);
        try{repo.save();overlaySaveError="";if(overlayDraft!=null)overlayDraft.setError(null);}
        catch(Exception error){showOverlayError("本地保存失败："+friendly(error)+"。草稿仍保留在内存，请导出或重试。");}
    }
    private void showOverlayError(String message){
        if(overlayDraft!=null)overlayDraft.setError(message);
        if(!message.equals(overlaySaveError))Toast.makeText(this,message,Toast.LENGTH_LONG).show();
        overlaySaveError=message;
    }
    private void exitOverlayEditing(){
        if(overlayDraft!=null){
            try{((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(overlayDraft.getWindowToken(),0);}catch(Exception ignored){}
            overlayDraft.clearFocus();overlayDraft.setFocusable(false);
            saveOverlayDraft();
        }
        if(windowParams!=null){windowParams.flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;if(overlay!=null&&overlay.isAttachedToWindow())try{wm.updateViewLayout(overlay,windowParams);}catch(Exception ignored){}}
    }
    private void removeOverlay(){exitOverlayEditing();if(overlay!=null)try{wm.removeView(overlay);}catch(Exception ignored){}overlay=null;expanded=null;overlayStatus=null;overlayText=null;seatText=null;overlayScreenStatus=null;overlayDraft=null;windowParams=null;bindingOverlayDraft=false;}
    @Override public void onDestroy(){stopping=true;active=false;running=false;paused=false;if(screenMonitor!=null){screenMonitor.close();screenMonitor=null;}screenAvailable=false;screenStatus=repo.state.screenLocked?sessionBinder.summary():"画面识别已停止";if(record!=null)try{record.stop();}catch(Exception ignored){}if(projection!=null){projection.stop();projection=null;}removeOverlay();if(!status.contains("中断")&&!status.contains("撤回")&&!status.contains("无法"))status="已停止监听";partial="";level=0;stopForeground(STOP_FOREGROUND_REMOVE);publish();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
