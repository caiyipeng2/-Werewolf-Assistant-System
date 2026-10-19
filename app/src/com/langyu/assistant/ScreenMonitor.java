package com.langyu.assistant;

import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** One projection display per token, bounded local OCR and no screenshot files. */
public final class ScreenMonitor implements AutoCloseable {
    public interface Listener {
        void onResult(ScreenRecognition.Result result);
        void onStatus(String message);
        /** Called on main: hide only our overlay; return false while user is editing it. */
        boolean beforeFrame();
        void afterFrame();
        boolean requiresFreshFrame();
    }
    private final Context context;
    private final MediaProjection projection;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final HandlerThread captureThread=new HandlerThread("langyu-screen-frames");
    private Handler frames;
    private final ExecutorService ocrWorker=Executors.newSingleThreadExecutor(r->new Thread(r,"langyu-screen-ocr"));
    private volatile boolean closed,paused,busy,requested=true;
    private volatile int sourceWidth,sourceHeight;
    private ImageReader reader;
    private VirtualDisplay display;
    private int width,height,density;
    private OfflineOcr ocr;
    private String lastStatus="",foreground="";
    private final ForegroundTracker foregroundTracker=new ForegroundTracker();
    private long lastSourceEvent,lastImageTimestamp;
    private volatile long imageSignals,framesRead;
    private int emptyAttempts;
    private final AtomicLong epoch=new AtomicLong();
    private final Runnable tick=this::tick;
    public ScreenMonitor(Context c,MediaProjection p,Listener l){context=c.getApplicationContext();projection=p;listener=l;}
    public void start(){captureThread.start();frames=new Handler(captureThread.getLooper());frames.post(()->{try{createDisplay();status("等待游戏前台 · 请展示本人身份牌");frames.post(tick);}catch(Throwable e){status("截图不可用："+message(e));}});}
    public void requestScan(){requested=true;resetForegroundLookup();lastStatus="";status(paused?"监听已暂停，请先点击继续":"已收到识别请求，正在检查游戏画面…");if(frames!=null)frames.post(tick);}
    public boolean needsProjectionReconnect(){return emptyAttempts>=3;}
    private void watchImages(ImageReader source){source.setOnImageAvailableListener(r->{imageSignals++;},frames);}
    public void invalidate(){epoch.incrementAndGet();if(frames!=null)frames.post(this::forgetFrame);}
    public long generation(){return epoch.get();}
    public boolean canApply(long token,String match){String target=context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("targetPackage","com.netease.werewolf");return !closed&&!paused&&!MainActivity.visible&&token==epoch.get()&&Repository.get(context).state.id.equals(match)&&sourceIsForeground(target);}
    public void pause(boolean value){paused=value;invalidate();if(value){status("画面识别已暂停");}else requestScan();}
    public void resize(int w,int h){if(w<=0||h<=0)return;sourceWidth=w;sourceHeight=h;if(frames!=null)frames.post(()->{if(!closed)try{resizeDisplay(w,h);}catch(Exception e){status("画面尺寸更新失败："+message(e));}});}
    public static boolean hasUsageAccess(Context c){AppOpsManager app=(AppOpsManager)c.getSystemService(Context.APP_OPS_SERVICE);return app!=null&&app.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),c.getPackageName())==AppOpsManager.MODE_ALLOWED;}
    private void createDisplay(){DisplayMetrics dm=new DisplayMetrics();((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);density=dm.densityDpi;int w=sourceWidth>0?sourceWidth:dm.widthPixels,h=sourceHeight>0?sourceHeight:dm.heightPixels;int[] size=fit(w,h);width=size[0];height=size[1];reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);watchImages(reader);display=projection.createVirtualDisplay("Langyu match screen",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,frames);}
    private static int[] fit(int w,int h){double scale=Math.min(1,1600.0/Math.max(w,h));return new int[]{Math.max(1,(int)Math.round(w*scale)),Math.max(1,(int)Math.round(h*scale))};}
    private void resizeDisplay(int w,int h){if(display==null)return;int[] next=fit(w,h);if(width==next[0]&&height==next[1])return;ImageReader old=reader,fresh=ImageReader.newInstance(next[0],next[1],PixelFormat.RGBA_8888,3);watchImages(fresh);try{display.resize(next[0],next[1],density);display.setSurface(fresh.getSurface());reader=fresh;width=next[0];height=next[1];}finally{if(reader==fresh){if(old!=null)old.close();}else fresh.close();}forgetFrame();requested=true;}
    private void tick(){
        if(closed||frames==null)return;frames.removeCallbacks(tick);
        if(Build.VERSION.SDK_INT<34||sourceWidth<=0){DisplayMetrics dm=new DisplayMetrics();((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);if(dm.widthPixels>0&&dm.heightPixels>0)resizeDisplay(dm.widthPixels,dm.heightPixels);}
        if(paused){status("监听已暂停，请先点击继续");frames.postDelayed(tick,1000);return;}
        if(!context.getSharedPreferences("settings",Context.MODE_PRIVATE).getBoolean("screenRecognition",true)){forgetFrame();status("自动画面识别已关闭");frames.postDelayed(tick,1500);return;}
        if(!hasUsageAccess(context)){forgetFrame();status("需允许“使用情况访问”后确认游戏前台并截图");frames.postDelayed(tick,1500);return;}
        if(MainActivity.visible){forgetFrame();status("等待切换到游戏画面");frames.postDelayed(tick,1000);return;}
        String target=context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("targetPackage","com.netease.werewolf");
        if(!sourceIsForeground(target)){forgetFrame();status("等待所选游戏进入前台");frames.postDelayed(tick,1000);return;}
        if(Repository.get(context).state.screenLocked&&!Repository.get(context).state.screenBoardId.isEmpty()&&!requested){status("本局已固定 · "+Repository.get(context).state.mySeat+"号 "+Repository.get(context).state.myRole);frames.postDelayed(tick,2000);return;}
        if(busy){if(requested)status("正在识别上一张画面，完成后将补扫");frames.postDelayed(tick,1000);return;}
        requested=false;busy=true;status("正在读取游戏画面…");
        if(listener.requiresFreshFrame())forgetFrame();
        main.post(()->{
            try{if(closed||paused||MainActivity.visible||!listener.beforeFrame()){busy=false;status(paused?"监听已暂停，请先点击继续":MainActivity.visible?"请切换回游戏后识别":"正在编辑草稿，请收起键盘后识别");if(frames!=null&&!closed)frames.postDelayed(tick,1000);return;}frames.postDelayed(()->captureFrame(target),180);}
            catch(Throwable error){busy=false;listener.afterFrame();status("暂无法读取画面："+message(error));if(!closed)frames.postDelayed(tick,1500);}
        });
    }
    public void resetForegroundLookup(){foregroundTracker.requestRecovery();}
    private synchronized boolean sourceIsForeground(String target){
        if(target==null||target.isEmpty())return false;
        PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard=(KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE);
        if(power==null||!power.isInteractive()||keyguard==null||keyguard.isKeyguardLocked()){foreground="";return false;}
        long now=System.currentTimeMillis();
        UsageStatsManager manager=(UsageStatsManager)context.getSystemService(Context.USAGE_STATS_SERVICE);
        if(manager==null){foreground="";return false;}
        UsageEvents events=manager.queryEvents(foregroundTracker.queryStart(now),now);
        if(events==null){foreground="";foregroundTracker.requestRecovery();return false;}
        UsageEvents.Event event=new UsageEvents.Event();
        List<ForegroundTracker.Event> observations=new ArrayList<>();long observedThrough=0;int count=0;
        while(events.hasNextEvent()){
            // An incomplete history cannot authenticate the current screen. Limit both
            // extraction work and retained transitions rather than trusting a partial tail.
            if(++count>100000||observations.size()>=20000){foreground="";foregroundTracker.requestRecovery();return false;}
            events.getNextEvent(event);long timestamp=event.getTimeStamp();
            if(timestamp<=now)observedThrough=Math.max(observedThrough,timestamp);
            int type=event.getEventType();
            if(type==UsageEvents.Event.ACTIVITY_RESUMED||type==UsageEvents.Event.ACTIVITY_PAUSED
                    ||type==UsageEvents.Event.ACTIVITY_STOPPED||type==UsageEvents.Event.SCREEN_INTERACTIVE
                    ||type==UsageEvents.Event.SCREEN_NON_INTERACTIVE||type==UsageEvents.Event.KEYGUARD_SHOWN
                    ||type==UsageEvents.Event.KEYGUARD_HIDDEN||type==UsageEvents.Event.DEVICE_SHUTDOWN)
                observations.add(new ForegroundTracker.Event(timestamp,type,event.getPackageName(),event.getClassName()));
        }
        foregroundTracker.record(observations,observedThrough,now);
        foreground=foregroundTracker.foregroundPackage();lastSourceEvent=foregroundTracker.sourceVersion();
        return foregroundTracker.isForeground(target);
    }
    private void captureFrame(String target){captureFrame(target,0,false);}
    private void captureFrame(String target,int poll,boolean refreshed){
        Bitmap bitmap=null;boolean submitted=false,deferred=false;
        try{
            if(closed||paused||MainActivity.visible||!sourceIsForeground(target))return;
            try(Image image=reader.acquireLatestImage()){
                if(image!=null)android.util.Log.i("LangyuScreen","Image timestamp="+image.getTimestamp()+" previous="+lastImageTimestamp+" available="+imageSignals);
                if(image!=null&&image.getTimestamp()>lastImageTimestamp){lastImageTimestamp=image.getTimestamp();framesRead++;emptyAttempts=0;Image.Plane plane=image.getPlanes()[0];int pixel=plane.getPixelStride(),row=plane.getRowStride();if(pixel!=4)throw new IllegalStateException("设备截图像素格式不支持");ByteBuffer source=plane.getBuffer();ByteBuffer packed=ByteBuffer.allocateDirect(image.getWidth()*image.getHeight()*4);byte[] rowBytes=new byte[image.getWidth()*4];for(int y=0;y<image.getHeight();y++){source.position(y*row);source.get(rowBytes);packed.put(rowBytes);}packed.rewind();bitmap=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);bitmap.copyPixelsFromBuffer(packed);}
            }
            if(bitmap==null){
                if(poll<5&&!closed&&!paused){deferred=true;frames.postDelayed(()->captureFrame(target,poll+1,refreshed),100);return;}
                if(!refreshed&&!closed&&!paused){ImageReader old=reader;ImageReader fresh=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);watchImages(fresh);try{display.setSurface(fresh.getSurface());reader=fresh;}finally{if(reader==fresh)old.close();else fresh.close();}deferred=true;frames.postDelayed(()->captureFrame(target,0,true),200);return;}
                emptyAttempts++;android.util.Log.i("LangyuScreen","No frame attempt="+emptyAttempts+" available="+imageSignals+" read="+framesRead+" size="+width+"x"+height);
                status(emptyAttempts>=3?"未收到系统截图，请点击“重新授权截图”":"正在等待系统提供截图，可点击“重新授权截图”重连");return;
            }
            if(nearlyBlack(bitmap)){status("游戏画面不可读或禁止截图；等待可见身份牌");bitmap.recycle();bitmap=null;return;}
            final Bitmap input=bitmap;bitmap=null;List<ScreenRecognition.BoardSpec> boards=new ArrayList<>();for(Repository.Board b:Repository.get(context).boards())boards.add(new ScreenRecognition.BoardSpec(b.id,b.name,b.roles,b.wolves));
            final long capturedEpoch=epoch.get(),assistantEpoch=MainActivity.visibilityEpoch,sourceEvent=lastSourceEvent;final String capturedMatch=Repository.get(context).state.id;
            try{ocrWorker.execute(()->{
                try{if(closed||paused)return;if(ocr==null)ocr=new OfflineOcr(context);List<ScreenRecognition.Line> lines=ocr.recognize(input);ScreenRecognition.Result first=ScreenRecognition.parse(lines,input.getWidth(),input.getHeight(),boards);ScreenRecognition.Result combined=first;if(!closed&&!paused&&(first.seat==0||first.role.isEmpty()||first.boardId.isEmpty())){List<ScreenRecognition.Line> dense=ocr.recognizeDense(input);combined=ScreenRecognition.merge(first,ScreenRecognition.parse(dense,input.getWidth(),input.getHeight(),boards));}final ScreenRecognition.Result result=combined;android.util.Log.i("LangyuScreen","OCR complete frames="+framesRead+" lines="+lines.size()+" ownSeat="+result.seat+" roleFound="+!result.role.isEmpty()+" boardFound="+!result.boardId.isEmpty());if(!closed&&!paused)main.post(()->{if(canApply(capturedEpoch,capturedMatch)&&MainActivity.visibilityEpoch==assistantEpoch&&lastSourceEvent==sourceEvent)listener.onResult(result);else if(!closed&&!paused)status("画面已切换，本次结果未写入，等待下一次识别");});}
                catch(Throwable e){status("画面识别失败，可重试："+message(e));}
                finally{input.recycle();busy=false;if(!closed&&frames!=null)frames.postDelayed(tick,3500);}
            });submitted=true;}catch(RuntimeException error){input.recycle();throw error;}
        }catch(Throwable e){status("截图失败，可重试："+message(e));}
        finally{if(bitmap!=null)bitmap.recycle();if(!deferred)main.post(listener::afterFrame);if(!submitted&&!deferred){busy=false;if(!closed)frames.postDelayed(tick,3500);}}
    }
    private static boolean nearlyBlack(Bitmap b){int light=0,total=0;for(int y=0;y<b.getHeight();y+=Math.max(1,b.getHeight()/20))for(int x=0;x<b.getWidth();x+=Math.max(1,b.getWidth()/20)){int p=b.getPixel(x,y);if(((p>>16)&255)+((p>>8)&255)+(p&255)>30)light++;total++;}return light<Math.max(2,total/100);}
    private void forgetFrame(){if(reader!=null)try(Image stale=reader.acquireLatestImage()){if(stale!=null)lastImageTimestamp=Math.max(lastImageTimestamp,stale.getTimestamp());}catch(IllegalStateException ignored){}}
    private void status(String message){if(closed||message.equals(lastStatus))return;lastStatus=message;main.post(()->{if(!closed)listener.onStatus(message);});}
    private static String message(Throwable t){return t.getMessage()==null?t.getClass().getSimpleName():t.getMessage();}
    @Override public void close(){if(closed)return;closed=true;paused=true;main.post(listener::afterFrame);if(frames!=null){frames.removeCallbacksAndMessages(null);frames.post(()->{forgetFrame();if(display!=null){display.release();display=null;}if(reader!=null){reader.close();reader=null;}captureThread.quitSafely();});}ocrWorker.execute(()->{if(ocr!=null){ocr.close();ocr=null;}});ocrWorker.shutdown();}
}
