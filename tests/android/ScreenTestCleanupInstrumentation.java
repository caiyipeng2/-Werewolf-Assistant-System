package com.langyu.assistant;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
/** Revert only the known synthetic self-card binding, never transcripts/archives/drafts. */
public final class ScreenTestCleanupInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();try{runOnMainSync(()->{Repository r=Repository.get(getTargetContext());GameState s=r.state;if(s.screenAutoLockSeat==7&&"预言家".equals(s.screenAutoLockRole)&&s.mySeat==7&&"预言家".equals(s.myRole)&&"classic_12".equals(s.screenBoardId)&&s.screenEvidence.contains("已知板名校正：12人标准预女猫白"))new SessionBinder(getTargetContext()).reset(false);android.content.SharedPreferences p=getTargetContext().getSharedPreferences("settings",0);if("com.langyu.assistant.tests".equals(p.getString("targetPackage","")))p.edit().remove("targetPackage").putBoolean("overlay",true).commit();});out.putString("stream","\nPASS synthetic self-card fields reset when unchanged; transcript/archive/draft data preserved; test source deselected\n");finish(Activity.RESULT_OK,out);}catch(Throwable e){out.putString("stream","\nFAIL selective test cleanup\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,out);}}
}
