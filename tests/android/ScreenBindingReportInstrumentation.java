package com.langyu.assistant;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
/** Reads persisted output of the actual projection test; never fabricates a binding. */
public final class ScreenBindingReportInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();try{GameState s=Repository.get(getTargetContext()).state;if(s.mySeat!=7||!"预言家".equals(s.myRole)||!s.screenLocked||!"classic_12".equals(s.screenBoardId))throw new AssertionError("Unexpected actual captured binding: seat="+s.mySeat+" role="+s.myRole+" board="+s.screenBoardId+" locked="+s.screenLocked);if(s.speaker==s.mySeat)throw new AssertionError("Current speaker was conflated with own seat");if(s.screenRecognizedAt<=0||!s.screenEvidence.contains("身份"))throw new AssertionError("Missing screen evidence");if(!s.myRole.equals(s.locks.get(s.mySeat)))throw new AssertionError("Own identity not supplied to analysis");out.putString("stream","\nPASS actual MediaProjection -> native OCR -> automatic persisted binding: mySeat="+s.mySeat+", myRole="+s.myRole+", board="+s.screenBoardId+", currentSpeaker="+s.speaker+", locked="+s.screenLocked+", recognizedAt="+s.screenRecognizedAt+"\nEvidence: "+s.screenEvidence+"\n");finish(Activity.RESULT_OK,out);}catch(Throwable e){out.putString("stream","\nFAIL actual screen binding\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,out);}}
}
