package com.langyu.assistant;

import android.content.Context;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/** Main-thread match binding; OCR never mutates match state directly. */
public final class SessionBinder {
    interface Store {
        GameState state();
        List<ScreenRecognition.BoardSpec> boards();
        void save();
        void archive();
        void replace(GameState state);
    }
    private final Store store;
    private String matchId="", pendingRole="", pendingBoard="", pendingEvidence="";
    private int pendingSeat, seatVotes, roleVotes, boardVotes;
    public SessionBinder(Context context){this(storeFor(context));}
    SessionBinder(Store store){if(store==null)throw new IllegalArgumentException("缺少本地对局仓库");this.store=store;matchId=store.state().id;}
    private static Store storeFor(Context context){
        final Repository repository=Repository.get(context);
        return new Store(){
            public GameState state(){return repository.state;}
            public List<ScreenRecognition.BoardSpec> boards(){
                List<ScreenRecognition.BoardSpec> all=new ArrayList<>();
                for(Repository.Board b:repository.boards())all.add(new ScreenRecognition.BoardSpec(b.id,b.name,b.roles,b.wolves));
                return all;
            }
            public void save(){repository.save();}
            public void archive(){repository.archive();}
            public void replace(GameState state){repository.state=state;}
        };
    }
    private static void mainThread(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("对局识别须在主线程写入");}
    public String accept(ScreenRecognition.Result result){
        mainThread();GameState state=store.state();
        if(!state.id.equals(matchId)){clearPending();matchId=state.id;}
        if(result!=null&&result.gameScreen&&result.ownConflict){
            clearPending();return (state.screenLocked?summary()+"；":"")+"画面本人座位或身份冲突，已清除待确认结果，等待新的连续一致画面";
        }
        if(result!=null&&result.gameScreen&&result.boardConflict){pendingBoard="";boardVotes=0;}
        if(state.screenLocked){
            // The private identity freezes immediately. A title sometimes appears only on the
            // following game page, so completing an as-yet unknown recipe is separately allowed.
            if(!state.screenBoardId.isEmpty()||state.screenEvidence.startsWith("人工"))return summary();
            if(result==null||!result.gameScreen){clearPending();return summary();}
            if(result.confidence<70)return summary();
            voteBoard(result);
            ScreenRecognition.BoardSpec late=boardVotes>=2?board(pendingBoard):null;
            if(late==null)return summary()+" · 等待板型连续确认";
            if(!fits(state,late.roles.length))return summary()+"；板子与既有记录座位冲突，已保留原配置";
            applyBoard(state,late);store.save();return summary();
        }
        if(result==null||!result.gameScreen){clearPending();return "未发现游戏自身身份画面，等待对局";}
        if(result.confidence<70)return "画面文字置信度不足，等待清晰画面";
        if(result.seat>=1&&result.seat<=20){
            if(result.seat==pendingSeat)seatVotes++;else{pendingSeat=result.seat;seatVotes=1;}
        }
        if(result.role!=null&&!result.role.trim().isEmpty()){
            if(result.role.equals(pendingRole))roleVotes++;else{pendingRole=result.role;roleVotes=1;}
        }
        voteBoard(result);
        if(result.evidence!=null&&!result.evidence.isEmpty()){
            String evidence=result.evidence.length()>400?result.evidence.substring(0,400):result.evidence;
            if(!pendingEvidence.contains(evidence)){if(!pendingEvidence.isEmpty())pendingEvidence+="；";pendingEvidence+=evidence;if(pendingEvidence.length()>800)pendingEvidence=pendingEvidence.substring(pendingEvidence.length()-800);}
        }
        if(seatVotes<2||roleVotes<2)return "识别确认中："+(pendingSeat>0?pendingSeat+"号 "+Math.min(seatVotes,2)+"/2":"座位待显示")+" · "+(!pendingRole.isEmpty()?pendingRole+" "+Math.min(roleVotes,2)+"/2":"身份牌待显示");
        ScreenRecognition.BoardSpec recognized=boardVotes>=2?board(pendingBoard):null;
        boolean compatible=recognized!=null&&fits(state,recognized.roles.length);
        int count=compatible?recognized.roles.length:state.players();
        if(pendingSeat>count)return "座位超出当前板子人数，等待明确板型后确认";
        String previousLock=state.locks.get(pendingSeat);
        if(previousLock!=null&&!sameRole(previousLock,pendingRole))return "自身身份与"+pendingSeat+"号人工锁定冲突，已保留人工设置，请核对后解锁";
        String note="";
        if(compatible){
            // Never call configure here: that would erase the live transcript and generate another match id.
            applyBoard(state,recognized);
        }else if(recognized!=null){
            note="；板子与既有记录座位冲突，已保留原配置";
        }
        state.mySeat=pendingSeat;state.myRole=pendingRole;state.screenLocked=true;
        state.screenEvidence=pendingEvidence;state.screenRecognizedAt=System.currentTimeMillis();
        if(previousLock==null){state.locks.put(pendingSeat,pendingRole);state.screenAutoLockSeat=pendingSeat;state.screenAutoLockRole=pendingRole;}
        store.save();
        return summary()+note;
    }
    private void voteBoard(ScreenRecognition.Result result){
        if(result.boardId!=null&&!result.boardId.isEmpty()&&board(result.boardId)!=null){
            if(result.boardId.equals(pendingBoard))boardVotes++;else{pendingBoard=result.boardId;boardVotes=1;}
        }
    }
    private static void applyBoard(GameState state,ScreenRecognition.BoardSpec board){
        state.boardName=board.name;state.roles=board.roles.clone();state.wolfCount=board.wolves;state.screenBoardId=board.id;
        state.speaker=Math.max(1,Math.min(state.players(),state.speaker));
    }
    private ScreenRecognition.BoardSpec board(String id){
        for(ScreenRecognition.BoardSpec b:store.boards())if(b!=null&&b.id.equals(id)&&b.roles!=null&&b.roles.length>=5&&b.roles.length<=20)return b;
        return null;
    }
    private static boolean fits(GameState s,int count){
        if(s.mySeat>count)return false;
        for(GameState.Speech speech:s.speeches)if(speech.speaker<1||speech.speaker>count)return false;
        for(GameState.Event event:s.events)if(event.actor<0||event.actor>count||event.target<0||event.target>count)return false;
        for(Integer seat:s.locks.keySet())if(seat==null||seat<1||seat>count)return false;
        for(Integer seat:s.dead)if(seat==null||seat<1||seat>count)return false;
        return true;
    }
    private static boolean sameRole(String a,String b){return canonical(a).equals(canonical(b));}
    private static String canonical(String role){
        return role.replace("白痴","愚者").replace("村民","平民").replace("血月使徒","赤月使徒").replace("恶灵骑士","恶夜骑士");
    }
    public void reset(boolean newMatch){
        mainThread();GameState state=store.state();
        if(newMatch){
            store.archive();GameState fresh=new GameState();fresh.configure(state.boardName,state.roles,state.wolfCount);store.replace(fresh);matchId=fresh.id;
        }else{
            // Removal needs both provenance and an unchanged value. A later manual correction remains.
            if(state.screenAutoLockSeat>0&&state.screenAutoLockRole.equals(state.locks.get(state.screenAutoLockSeat)))state.locks.remove(state.screenAutoLockSeat);
            state.mySeat=state.screenAutoLockSeat=0;state.myRole=state.screenEvidence=state.screenBoardId=state.screenAutoLockRole="";
            state.screenLocked=false;state.screenRecognizedAt=0;
        }
        clearPending();store.save();
    }
    public void clearPending(){
        mainThread();pendingSeat=seatVotes=roleVotes=boardVotes=0;pendingRole=pendingBoard=pendingEvidence="";
    }
    public String summary(){
        mainThread();GameState state=store.state();
        String own=state.mySeat>0?state.mySeat+"号 · "+state.myRole:"座位和身份未识别";
        String board=state.screenBoardId==null||state.screenBoardId.isEmpty()?"板子未识别（沿用当前配置）":"板子："+state.boardName;
        return (state.screenLocked?"已固定本局：":"等待识别：")+own+" · "+board;
    }
}
