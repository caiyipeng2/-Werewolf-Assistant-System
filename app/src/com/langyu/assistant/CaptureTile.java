package com.langyu.assistant;
import android.service.quicksettings.*;
import android.content.Intent;
import android.app.PendingIntent;
import android.os.Build;
public class CaptureTile extends TileService {
    @Override public void onStartListening(){Tile t=getQsTile();if(t!=null){t.setState(CaptureService.running?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);t.setLabel(CaptureService.running?"停止狼语监听":"狼语监听");t.updateTile();}}
    @Override public void onClick(){super.onClick();if(CaptureService.running){startService(new Intent(this,CaptureService.class).setAction(CaptureService.ACTION_STOP));onStartListening();}else{Intent i=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));else startActivityAndCollapse(i);}}
}
