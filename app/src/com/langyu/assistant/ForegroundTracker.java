package com.langyu.assistant;

import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Android-independent, ordered usage-event history. Unknown sources always fail closed. */
public final class ForegroundTracker {
    public static final int RESUMED=1, PAUSED=2, SCREEN_ON=15, SCREEN_OFF=16,
            KEYGUARD_SHOWN=17, KEYGUARD_HIDDEN=18, STOPPED=23, SHUTDOWN=26;
    private static final long HISTORY_MILLIS=86_400_000L;
    private static final long OVERLAP_MILLIS=10_000L;
    private static final long RECOVERY_INTERVAL_MILLIS=15_000L;

    public static final class Event {
        public final long timestamp;
        public final int type;
        public final String packageName, activity;
        public Event(long timestamp,int type,String packageName,String activity){
            this.timestamp=timestamp;this.type=type;
            this.packageName=packageName==null?"":packageName;
            this.activity=activity==null?"":activity;
        }
    }

    // Overlapping queries can return a delayed event earlier than a previously seen event.
    // Keep their identities and replay in event order rather than delivery order.
    private static final Comparator<Event> ORDER=Comparator.comparingLong((Event e)->e.timestamp)
            .thenComparingInt(e->e.type).thenComparing(e->e.packageName).thenComparing(e->e.activity);
    private final TreeSet<Event> history=new TreeSet<>(ORDER);
    private final State retired=new State();
    private State current=new State();
    private long lastObservedTimestamp,lastRecoveryQuery,version;
    private boolean initialized,recoveryRequested;

    public synchronized long queryStart(long now){
        long oldest=Math.max(0,now-HISTORY_MILLIS);
        if(!initialized||recoveryRequested||(current.source().isEmpty()
                &&now-lastRecoveryQuery>=RECOVERY_INTERVAL_MILLIS)){
            recoveryRequested=false;lastRecoveryQuery=now;return oldest;
        }
        // Empty queries never advance this cursor. The upper bound of a query is not
        // evidence that UsageStats has already published all events up to that time.
        return Math.max(oldest,lastObservedTimestamp-OVERLAP_MILLIS);
    }

    public synchronized void requestRecovery(){recoveryRequested=true;}

    /** observedThrough includes the newest raw event, even if its type is not a transition. */
    public synchronized void record(List<Event> events,long observedThrough,long now){
        long oldest=Math.max(0,now-HISTORY_MILLIS);
        if(observedThrough>=oldest&&observedThrough<=now)
            lastObservedTimestamp=Math.max(lastObservedTimestamp,observedThrough);
        for(Event event:events){
            if(event==null||event.timestamp<oldest||event.timestamp>now)continue;
            lastObservedTimestamp=Math.max(lastObservedTimestamp,event.timestamp);
            if(isTransition(event.type))history.add(event);
        }
        // Preserve the established state while retiring events outside the storage window.
        // An old still-running game must not disappear merely because a day has elapsed.
        while(!history.isEmpty()&&history.first().timestamp<oldest)retired.apply(history.pollFirst());
        State next=new State(retired);
        for(Event event:history)if(event.timestamp<=now)next.apply(event);
        if(!next.sameAs(current))version++;
        current=next;initialized=true;
    }

    public synchronized boolean isForeground(String target){
        return target!=null&&!target.isEmpty()&&target.equals(current.source());
    }

    public synchronized String foregroundPackage(){return current.source();}
    public synchronized long sourceVersion(){return version;}

    private static boolean isTransition(int type){
        return type==RESUMED||type==PAUSED||type==STOPPED||type==SCREEN_ON||type==SCREEN_OFF
                ||type==KEYGUARD_SHOWN||type==KEYGUARD_HIDDEN||type==SHUTDOWN;
    }

    private static final class State {
        String packageName="",activity="";
        boolean interactive=true,keyguard;
        long transitionTimestamp;
        State(){}
        State(State state){
            packageName=state.packageName;activity=state.activity;interactive=state.interactive;
            keyguard=state.keyguard;transitionTimestamp=state.transitionTimestamp;
        }
        String source(){return interactive&&!keyguard?packageName:"";}
        boolean sameAs(State state){
            return packageName.equals(state.packageName)&&activity.equals(state.activity)
                    &&interactive==state.interactive&&keyguard==state.keyguard
                    &&transitionTimestamp==state.transitionTimestamp;
        }
        void apply(Event event){
            if(event.type==RESUMED){
                packageName=event.packageName;activity=event.activity;
            }else if(event.type==PAUSED||event.type==STOPPED){
                if(!event.packageName.equals(packageName)||(!event.activity.isEmpty()
                        &&!activity.isEmpty()&&!event.activity.equals(activity)))return;
                // Do not fall back to an earlier resumed activity after an overlay or
                // different application closes: there is no evidence it is visible again.
                packageName="";activity="";
            }else if(event.type==SCREEN_ON){interactive=true;
            }else if(event.type==SCREEN_OFF){interactive=false;
            }else if(event.type==KEYGUARD_SHOWN){keyguard=true;
            }else if(event.type==KEYGUARD_HIDDEN){keyguard=false;
            }else if(event.type==SHUTDOWN){packageName="";activity="";
            }else return;
            transitionTimestamp=event.timestamp;
        }
    }
}
