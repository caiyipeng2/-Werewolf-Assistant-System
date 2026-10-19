package com.langyu.assistant;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Real parser and binder behavior, with only the Android disk boundary substituted. */
public final class ScreenRecognitionTests {
    private static int checks;
    private static final String[] STANDARD={"狼人","狼人","狼人","狼人","预言家","女巫","猎人","愚者","平民","平民","平民","平民"};
    private static final List<ScreenRecognition.BoardSpec> BOARDS=Arrays.asList(
        board("classic_12","12人预女猎白（标准场）",12),
        board("miracle_12","12人奇迹商人",12),board("miracle_10","10人奇迹商人",10),
        board("mirror_12","12人镜隐迷踪",12),board("bloodmoon_hunter_12","12人赤月猎魔人（血月猎魔人）",12),
        board("wolfking_guard_12","12人狼王守卫",12),board("white_wolfking_guard_12","12人白狼王守卫",12),
        board("awakened_witch_12","12人觉醒女巫",12));
    public static void main(String[] args) throws Exception {
        explicitSelfLabelsAndBoard();
        seatFormsAndChineseNumbers();
        chatAndPublicLabelsAreNotOwnIdentity();
        unrelatedScreensAreRejected();
        confidenceAndConflictsAreConservative();
        boardAliasesCountsAndVariants();
        nearbySplitLabelsUseGeometry();
        splitFramesBindOnlyAfterRepeatedEvidence();
        lateBoardBindingKeepsOwnIdentityFrozen();
        conflictingAndUntrustedFramesNeverBind();
        existingMatchHistoryAndManualLocksArePreserved();
        resetHasExplicitArchiveAndUnlockSemantics();
        configurationClearsRecognition();
        privateRoleLockExplainsItsActualSource();
        observedMotoPunctuationAndBoardGlyphAreHandledConservatively();
        alternateSegmentationComplementsOnlyNonConflictingFields();
        conflictingIdentityFramesInvalidatePendingVotes();
        if(args.length>0)allEmbeddedBoardTitlesAreSupported(args[0]);
        System.out.println("PASS: "+checks+" screen recognition behavior assertions");
    }
    private static ScreenRecognition.BoardSpec board(String id,String name,int n) {
        String[] r=new String[n];Arrays.fill(r,"平民");r[0]="狼人";r[1]="预言家";
        return new ScreenRecognition.BoardSpec(id,name,r,n==10?3:4);
    }
    private static ScreenRecognition.Line line(String text,int y,int confidence) {
        return new ScreenRecognition.Line(text,80,y,900,y+48,confidence);
    }
    private static ScreenRecognition.Result parse(String... words) {
        List<ScreenRecognition.Line> lines=new ArrayList<>();int y=80;
        for(String word:words){lines.add(line(word,y,95));y+=65;}
        return ScreenRecognition.parse(lines,1080,2400,BOARDS);
    }
    private static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    private static void explicitSelfLabelsAndBoard(){
        ScreenRecognition.Result r=parse("狼人杀","12人预女猎白","你的号码：7号","你的身份：预言家","第2天","警长竞选");
        check(r.gameScreen,"game anchors admit explicit own labels");
        check(r.seat==7&&r.role.equals("预言家"),"seat and private identity are recognized");
        check(r.boardId.equals("classic_12")&&r.players==12,"board recipe is identified");
        check(r.day==2&&r.confidence>=70&&!r.evidence.isEmpty(),"metadata is traceable");
        check(parse("狼人杀","本局身份：觉醒女巫").role.equals("觉醒女巫"),"full awakened role is not shortened");
        check(parse("狼人杀","自己的身份牌：愚者").role.equals("愚者"),"own identity card label");
        check(parse("狼人杀","你是 4号 狼人").role.equals("狼人"),"combined own seat and identity");
    }
    private static void seatFormsAndChineseNumbers(){
        check(parse("狼人杀","你是十二号").seat==12,"Chinese seat number");
        check(parse("狼人杀","我的座位：５号").seat==5,"full width digit");
        check(parse("狼人杀","8号（我）").seat==8,"explicit self seat marker");
        check(parse("狼人杀","我的座位：20号").seat==20,"custom board seat remains possible before board detection");
        check(parse("狼人杀","你的号码：0号").seat==0,"zero seat rejected");
        check(parse("狼人杀","你的号码：21号").seat==0,"out of supported seat range rejected");
    }
    private static void chatAndPublicLabelsAreNotOwnIdentity(){
        for(String text:new String[]{"我是预言家","5号查杀","7号说：你的身份是狼人","聊天：你的身份：预言家","发言记录 你是3号狼人","你是狼人吗？","我不是狼人","当前发言：5号","3号 预言家","身份：女巫"}){
            ScreenRecognition.Result r=parse("狼人杀","12人预女猎白",text);
            check(r.role.isEmpty()&&r.seat==0,"public or ambiguous text is not private identity: "+text);
        }
        ScreenRecognition.Result table=parse("狼人杀","12人明牌局","1号狼人","2号预言家","3号猎人","4号平民");
        check(table.role.isEmpty()&&table.seat==0,"whole-room identity table does not select a player");
    }
    private static void unrelatedScreensAreRejected(){
        for(String forbidden:new String[]{"狼语复盘","话术工作台","设置与数据"}){
            ScreenRecognition.Result r=parse("狼人杀",forbidden,"你的号码：6号","你的身份：预言家");
            check(!r.gameScreen&&r.seat==0&&r.role.isEmpty(),"assistant screen excluded: "+forbidden);
        }
        check(parse("你的号码：6号","你的身份：预言家").gameScreen,"exact own UI labels authenticate source-gated identity reveal frames");
        check(parse("你的身份：女巫").gameScreen,"single private identity reveal works without visible game branding");
        check(!parse("身份：女巫","7号").gameScreen,"generic role and arbitrary seat require an independent game anchor");
        check(!parse("购物","我是预言家","12号").gameScreen,"ordinary application is not a game");
    }
    private static void confidenceAndConflictsAreConservative(){
        ScreenRecognition.Result low=ScreenRecognition.parse(Arrays.asList(line("狼人杀",80,95),line("你的号码：7号",160,40),line("你的身份：预言家",240,40)),1080,2400,BOARDS);
        check(low.seat==0&&low.role.isEmpty(),"low confidence own labels never bind");
        ScreenRecognition.Result conflicting=parse("狼人杀","你的号码：7号","我的座位：8号","你的身份：女巫","本局身份：狼人");
        check(conflicting.seat==0&&conflicting.role.isEmpty(),"conflicting own labels remain unknown");
        check(conflicting.ownConflict,"conflicting own labels carry an explicit identity conflict marker");
        check(ScreenRecognition.parse(null,1080,2400,BOARDS).confidence==0,"empty OCR is harmless");
        check(ScreenRecognition.parse(Arrays.asList(line("狼人杀",80,95)),0,0,BOARDS).confidence==0,"invalid geometry is harmless");
    }
    private static void boardAliasesCountsAndVariants(){
        check(parse("狼人杀","10人奇迹商人").boardId.equals("miracle_10"),"ten-player variant selected");
        check(parse("狼人杀","12人奇迹商人").boardId.equals("miracle_12"),"twelve-player variant selected");
        check(parse("狼人杀","奇迹商人").boardId.isEmpty(),"ambiguous player-count variants not guessed");
        check(parse("狼人杀","9人奇迹商人").boardId.isEmpty(),"incompatible stated player count not replaced with twelve");
        check(parse("狼人杀","镜影迷踪").boardId.equals("mirror_12"),"historical alias supported");
        check(parse("狼人杀","血月猎魔人").boardId.equals("bloodmoon_hunter_12"),"legacy board alias supported");
        check(parse("狼人杀","12人赤月猎魔人（血月猎魔人）").boardId.equals("bloodmoon_hunter_12"),"complete title including a parenthesized alias is supported");
        check(parse("狼人杀","12人白狼王守卫").boardId.equals("white_wolfking_guard_12"),"longest board title prevents nested-name collision");
        check(parse("12人标准预女猎白").boardId.equals("classic_12"),"standard modifier in a game title is allowed");
        check(parse("狼人杀","你的身份：觉醒女巫").boardId.isEmpty(),"own role is not automatically a board title");
        check(parse("狼人杀","预女猎白","奇迹商人").boardId.isEmpty(),"multi-board menu not mistaken for current board");
        check(parse("12人预女猎白").gameScreen,"unambiguous board title authenticates a source-gated game frame");
    }
    private static void nearbySplitLabelsUseGeometry(){
        List<ScreenRecognition.Line> lines=Arrays.asList(line("狼人杀",40,95),line("你的身份",200,95),line("预言家",260,95),line("你的号码",380,95),line("7号",440,95));
        ScreenRecognition.Result r=ScreenRecognition.parse(lines,1080,2400,BOARDS);
        check(r.role.equals("预言家")&&r.seat==7,"nearby standalone label/value lines can be paired");
        r=ScreenRecognition.parse(Arrays.asList(line("狼人杀",40,95),line("你的身份",200,95),line("预言家",1300,95)),1080,2400,BOARDS);
        check(r.role.isEmpty(),"distant role label not attached to own identity");
    }
    private static final class FakeStore implements SessionBinder.Store {
        GameState current=new GameState();int saves,archives;String archivedId="";
        public GameState state(){return current;}
        public List<ScreenRecognition.BoardSpec> boards(){return BOARDS;}
        public void save(){saves++;}
        public void archive(){archives++;archivedId=current.id;}
        public void replace(GameState state){current=state;}
    }
    private static ScreenRecognition.Result observation(int seat,String role,String board){
        ScreenRecognition.Result r=new ScreenRecognition.Result();r.gameScreen=true;r.seat=seat;r.role=role;r.boardId=board;r.confidence=93;r.evidence="测试屏幕自身信息";return r;
    }
    private static void splitFramesBindOnlyAfterRepeatedEvidence(){
        FakeStore disk=new FakeStore();SessionBinder binder=new SessionBinder(disk);String originalId=disk.current.id;disk.current.speaker=3;
        ScreenRecognition.Result seat=observation(7,"","classic_12"), role=observation(0,"预言家","");
        binder.accept(seat);check(!disk.current.screenLocked&&disk.saves==0,"one observation cannot write own identity");
        binder.accept(seat);binder.accept(role);check(!disk.current.screenLocked,"each independent own label requires repeated evidence");
        binder.accept(role);
        check(disk.current.screenLocked&&disk.current.mySeat==7&&disk.current.myRole.equals("预言家"),"different screen frames accumulate into one own identity");
        check(disk.current.id.equals(originalId)&&disk.current.speaker==3,"binding preserves current match and active speaker");
        check(disk.current.locks.get(7).equals("预言家"),"actually visible private role becomes known own-role lock");
        check(disk.current.screenRecognizedAt>0&&disk.current.screenBoardId.equals("classic_12"),"recognized board and timestamp are durable fields");
        check(binder.summary().contains("7号")&&binder.summary().contains("预言家"),"summary describes detected self");
        int saves=disk.saves;binder.accept(observation(1,"狼人","miracle_10"));binder.accept(observation(1,"狼人","miracle_10"));
        check(disk.current.mySeat==7&&disk.current.myRole.equals("预言家")&&disk.saves==saves,"locked match never drifts with later frames");
        SessionBinder resumed=new SessionBinder(disk);resumed.accept(observation(2,"狼人","miracle_10"));
        check(disk.current.mySeat==7,"lock survives binder restart");
    }
    private static void conflictingAndUntrustedFramesNeverBind(){
        FakeStore disk=new FakeStore();SessionBinder binder=new SessionBinder(disk);
        ScreenRecognition.Result r=observation(7,"预言家","");r.gameScreen=false;binder.accept(r);binder.accept(r);
        check(!disk.current.screenLocked,"untrusted application frames rejected");
        r.gameScreen=true;r.confidence=40;binder.accept(r);binder.accept(r);check(!disk.current.screenLocked,"low confidence never binds");
        binder.accept(observation(7,"预言家",""));binder.accept(observation(8,"预言家",""));
        check(!disk.current.screenLocked,"inconsistent seat does not accumulate votes");
        binder.accept(observation(8,"预言家",""));
        check(disk.current.screenLocked&&disk.current.mySeat==8,"new consistent seat may eventually bind");
        check(binder.summary().contains("板子未识别"),"default twelve-player configuration is not reported as detected");
        FakeStore replacement=new FakeStore();SessionBinder b=new SessionBinder(replacement);b.accept(observation(7,"预言家",""));replacement.current=new GameState();b.accept(observation(7,"预言家",""));
        check(!replacement.current.screenLocked,"votes from old match cannot bind newly loaded match");
    }
    private static void lateBoardBindingKeepsOwnIdentityFrozen(){
        FakeStore disk=new FakeStore();SessionBinder binder=new SessionBinder(disk);ScreenRecognition.Result self=observation(7,"预言家","");binder.accept(self);binder.accept(self);
        check(disk.current.screenLocked&&disk.current.screenBoardId.isEmpty(),"self locks promptly while board is unknown");
        ScreenRecognition.Result late=observation(2,"狼人","miracle_10");binder.accept(late);
        check(disk.current.players()==12,"one late-board frame is insufficient");
        binder.accept(late);
        check(disk.current.players()==10&&disk.current.screenBoardId.equals("miracle_10"),"two late-board observations can complete board setup");
        check(disk.current.mySeat==7&&disk.current.myRole.equals("预言家")&&disk.current.locks.get(7).equals("预言家"),"late board completion cannot move self or change the private identity");
        FakeStore manual=new FakeStore();manual.current.mySeat=7;manual.current.myRole="预言家";manual.current.screenLocked=true;manual.current.screenEvidence="人工确认";
        SessionBinder m=new SessionBinder(manual);m.accept(late);m.accept(late);
        check(manual.current.players()==12&&manual.current.screenBoardId.isEmpty(),"manual confirmation keeps manual recipe authoritative");
    }
    private static void existingMatchHistoryAndManualLocksArePreserved(){
        FakeStore disk=new FakeStore();GameState s=disk.current;s.addSpeech(3,2,"保留发言");s.day=3;s.phase="放逐投票";s.speaker=9;s.draft="保留草稿";s.locks.put(4,"好人");s.dead.add(5);String id=s.id;
        SessionBinder binder=new SessionBinder(disk);ScreenRecognition.Result r=observation(7,"预言家","miracle_10");binder.accept(r);binder.accept(r);
        check(s.id.equals(id)&&s.speeches.size()==1&&s.draft.equals("保留草稿")&&s.day==3&&s.phase.equals("放逐投票"),"safe board correction never configures away active history");
        check(s.players()==10&&s.locks.get(4).equals("好人")&&s.dead.contains(5)&&s.speaker==9,"safe board correction retains facts and speaker");
        FakeStore conflict=new FakeStore();conflict.current.addSpeech(1,12,"已有12号发言");SessionBinder b=new SessionBinder(conflict);r=observation(7,"预言家","miracle_10");b.accept(r);String status=b.accept(r);
        check(conflict.current.players()==12&&conflict.current.speeches.size()==1&&conflict.current.screenBoardId.isEmpty(),"incompatible board preserves old match recipe and records");
        check(status.contains("冲突")||status.contains("保留"),"unsafe board correction is explained");
        FakeStore manual=new FakeStore();manual.current.locks.put(7,"狼人");b=new SessionBinder(manual);r=observation(7,"预言家","");b.accept(r);status=b.accept(r);
        check(manual.current.locks.get(7).equals("狼人")&&!manual.current.screenLocked,"manual own-seat conflict is never overwritten");
    }
    private static void resetHasExplicitArchiveAndUnlockSemantics(){
        FakeStore disk=new FakeStore();SessionBinder binder=new SessionBinder(disk);GameState state=disk.current;
        state.addSpeech(1,2,"保留");state.locks.put(3,"好人");ScreenRecognition.Result r=observation(7,"预言家","");binder.accept(r);binder.accept(r);binder.reset(false);
        check(!state.screenLocked&&state.mySeat==0&&state.myRole.isEmpty()&&!state.locks.containsKey(7),"unlock removes only auto-added own identity lock");
        check(state.locks.containsKey(3)&&state.speeches.size()==1&&disk.archives==0,"unlock retains records and unrelated manual locks");
        binder.accept(r);binder.accept(r);state.locks.put(7,"女巫");binder.reset(false);
        check(state.locks.get(7).equals("女巫"),"unlock does not remove manually corrected own-role lock");
        binder.accept(observation(5,"狼人",""));binder.accept(observation(5,"狼人",""));String id=state.id;String board=state.boardName;binder.reset(true);
        check(disk.archives==1&&disk.archivedId.equals(id)&&!disk.current.id.equals(id),"next match archives before allocating a fresh match");
        check(disk.current.boardName.equals(board)&&disk.current.speeches.isEmpty()&&disk.current.locks.isEmpty()&&!disk.current.screenLocked,"next match keeps recipe and clears old match facts");
        FakeStore preexisting=new FakeStore();preexisting.current.locks.put(7,"预言家");SessionBinder p=new SessionBinder(preexisting);p.accept(r);p.accept(r);p.reset(false);
        check(preexisting.current.locks.containsKey(7),"matching pre-existing manual lock is not owned or removed by recognition");
    }
    private static void configurationClearsRecognition(){
        GameState s=new GameState();s.mySeat=4;s.myRole="狼人";s.screenLocked=true;s.screenBoardId="classic_12";s.screenRecognizedAt=42;s.screenEvidence="旧帧";s.screenAutoLockSeat=4;s.screenAutoLockRole="狼人";
        s.configure("新板",STANDARD,4);
        check(s.mySeat==0&&s.myRole.isEmpty()&&!s.screenLocked&&s.screenRecognizedAt==0&&s.screenEvidence.isEmpty()&&s.screenBoardId.isEmpty()&&s.screenAutoLockSeat==0&&s.screenAutoLockRole.isEmpty(),"explicit configuration clears all old recognition and provenance");
    }
    private static void allEmbeddedBoardTitlesAreSupported(String fixture) throws Exception {
        List<ScreenRecognition.BoardSpec> all=new ArrayList<>();
        for(String row:Files.readAllLines(Paths.get(fixture),StandardCharsets.UTF_8)){
            if(row.isEmpty())continue;String[] parts=row.split("\\t");
            all.add(new ScreenRecognition.BoardSpec(parts[0],parts[1],parts[3].split(","),Integer.parseInt(parts[2])));
        }
        check(all.size()>=30,"test includes every shipped board");
        for(ScreenRecognition.BoardSpec b:all){
            ScreenRecognition.Result r=ScreenRecognition.parse(Arrays.asList(line(b.name,120,95)),1080,2400,all);
            check(r.gameScreen&&r.boardId.equals(b.id)&&r.players==b.roles.length,"exact embedded board title: "+b.id);
            String primary=b.name.replaceAll("[（(].*","");
            r=ScreenRecognition.parse(Arrays.asList(line(primary,120,95)),1080,2400,all);
            check(r.boardId.equals(b.id),"primary embedded board title: "+b.id);
        }
    }
    private static void privateRoleLockExplainsItsActualSource(){
        FakeStore disk=new FakeStore();SessionBinder binder=new SessionBinder(disk);ScreenRecognition.Result own=observation(7,"预言家","");binder.accept(own);binder.accept(own);
        RuleEngine.Assessment self=RuleEngine.analyze(disk.current).get(6);
        check(self.probability==0&&self.reasons.get(0).contains("屏幕已读本人身份")&&!self.reasons.get(0).contains("手动"),"private identity lock describes screen provenance");
        disk.current.locks.put(7,"女巫");self=RuleEngine.analyze(disk.current).get(6);
        check(self.reasons.get(0).contains("手动"),"manual correction supersedes previous screen provenance");
    }
    private static void observedMotoPunctuationAndBoardGlyphAreHandledConservatively(){
        ScreenRecognition.Result punctuation=parse("我的座位; 7号","我的身份;: 预言家");
        check(punctuation.gameScreen&&punctuation.seat==7&&punctuation.role.equals("预言家"),"actual Moto OCR semicolon and doubled separators after own labels");
        ScreenRecognition.Result r=ScreenRecognition.parse(Arrays.asList(
                new ScreenRecognition.Line("狠人杀",72,84,229,134,71),
                new ScreenRecognition.Line("12人标准预女猫白",71,228,487,279,86),
                new ScreenRecognition.Line("我的座位: 7号",72,374,421,424,94),
                new ScreenRecognition.Line("我的身份: 预言家",72,519,500,569,95)),1080,2400,BOARDS);
        check(r.seat==7&&r.role.equals("预言家")&&r.boardId.equals("classic_12"),"actual native OCR lines include one known board glyph substitution");
        check(r.evidence.contains("预女猫白"),"corrected board title retains actual OCR text as evidence");
        check(parse("我的身份;:预女猫白").role.isEmpty()&&parse("我的身份;:预女猫白").boardId.isEmpty(),"board-only correction is never applied to private-role text");
        check(parse("聊天:12人标准预女猫白","我是预言家").boardId.isEmpty(),"board glyph correction does not authenticate chat");
        check(parse("10人标准预女猫白").boardId.isEmpty(),"board glyph correction requires the actual board player count");
        check(parse("标准预女猫白").boardId.isEmpty(),"board glyph correction requires an explicit player count");
        ScreenRecognition.Result low=ScreenRecognition.parse(Arrays.asList(line("12人标准预女猫白",100,36)),1080,2400,BOARDS);
        check(low.boardId.isEmpty()&&!low.gameScreen,"low-confidence board glyphs are still rejected");
        ScreenRecognition.Result medium=ScreenRecognition.parse(Arrays.asList(line("12人标准预女猫白",100,79)),1080,2400,BOARDS);
        check(medium.boardId.isEmpty(),"glyph correction requires high confidence beyond the general line threshold");
        List<ScreenRecognition.BoardSpec> duplicate=new ArrayList<>(BOARDS);duplicate.add(board("custom_same","12人预女猎白",12));
        ScreenRecognition.Result ambiguous=ScreenRecognition.parse(Arrays.asList(line("12人标准预女猫白",100,86)),1080,2400,duplicate);
        check(ambiguous.boardId.isEmpty(),"corrected board title still requires exactly one catalog candidate");
    }
    private static void alternateSegmentationComplementsOnlyNonConflictingFields(){
        ScreenRecognition.Result primary=observation(7,"","");primary.confidence=94;primary.evidence="稀疏原始座位字样";
        ScreenRecognition.Result fallback=observation(0,"预言家","classic_12");fallback.players=12;fallback.confidence=86;fallback.evidence="密集原始身份和板型字样";
        ScreenRecognition.Result merged=ScreenRecognition.merge(primary,fallback);
        check(merged.gameScreen&&merged.seat==7&&merged.role.equals("预言家")&&merged.boardId.equals("classic_12")&&merged.players==12,"alternate segmentation fills missing fields");
        check(merged.confidence==86&&merged.evidence.contains(primary.evidence)&&merged.evidence.contains(fallback.evidence),"only useful sources determine confidence and both original evidence strings survive");
        check(primary.role.isEmpty()&&fallback.seat==0,"merge does not mutate either source result");
        primary.role="狼人";primary.day=2;primary.phase="黑夜";fallback.seat=8;fallback.day=3;fallback.phase="白天发言";
        merged=ScreenRecognition.merge(primary,fallback);
        check(merged.seat==0&&merged.role.isEmpty()&&merged.day==0&&merged.phase.isEmpty(),"conflicting private identity and round fields are cleared individually");
        check(merged.ownConflict,"segmentation own-identity disagreement is explicit to the binder");
        check(merged.boardId.equals("classic_12")&&merged.players==12&&merged.confidence==86,"uncontested recipe survives self-field conflicts");
        check(merged.evidence.contains("分段冲突")&&merged.evidence.contains("座位")&&merged.evidence.contains("身份"),"segmentation conflicts are explicit evidence");
        primary.boardId="miracle_10";primary.players=10;merged=ScreenRecognition.merge(primary,fallback);
        check(merged.boardId.isEmpty()&&merged.players==0,"different board identities also clear their player counts");
        check(merged.confidence==0,"sources with no remaining useful fields do not leave apparent confidence");
        primary=observation(0,"","");primary.confidence=0;merged=ScreenRecognition.merge(primary,fallback);
        check(merged.role.equals("预言家")&&merged.confidence==86,"empty zero-confidence primary does not destroy a useful fallback");
        primary.gameScreen=false;primary.confidence=99;primary.seat=13;primary.role="狼人";primary.boardId="miracle_10";
        merged=ScreenRecognition.merge(primary,fallback);
        check(merged.seat==8&&merged.role.equals("预言家")&&merged.boardId.equals("classic_12")&&merged.confidence==86,"non-game input never contributes or conflicts with game fields");
        primary.gameScreen=true;primary.confidence=40;merged=ScreenRecognition.merge(primary,fallback);
        check(merged.seat==8&&merged.role.equals("预言家"),"low-confidence fields cannot replace or conflict with high-confidence fields");
        primary=observation(0,"","classic_12");primary.players=0;primary.confidence=90;merged=ScreenRecognition.merge(primary,fallback);
        check(merged.boardId.equals("classic_12")&&merged.players==12,"matching board identity can obtain its missing count from fallback");
        primary.players=10;merged=ScreenRecognition.merge(primary,fallback);
        check(merged.boardId.equals("classic_12")&&merged.players==0,"conflicting counts clear only the count when board identifiers agree");
        merged=ScreenRecognition.merge(null,fallback);check(merged.role.equals("预言家")&&merged.confidence==86,"missing primary is harmless");
        merged=ScreenRecognition.merge(null,null);check(!merged.gameScreen&&merged.confidence==0&&merged.seat==0,"missing inputs produce an empty result");
        primary=observation(7,"预言家","");fallback=observation(7,"狼人","");primary.confidence=72;fallback.confidence=94;
        merged=ScreenRecognition.merge(primary,fallback);check(merged.seat==7&&merged.role.isEmpty()&&merged.confidence==72,"agreement preserves the conservative confidence even when another field conflicts");
        primary.seat=0;fallback.seat=8;merged=ScreenRecognition.merge(primary,fallback);
        check(merged.confidence==94,"a source contributing only a discarded conflict does not depress useful-field confidence");
        primary.evidence=repeat('甲',1200);fallback.evidence=repeat('乙',1200);merged=ScreenRecognition.merge(primary,fallback);
        check(merged.evidence.length()<=800&&merged.evidence.contains("甲")&&merged.evidence.contains("乙")&&merged.evidence.contains("分段冲突"),"bounded evidence keeps both segmentations and conflict markers");
    }
    private static String repeat(char letter,int count){char[] chars=new char[count];Arrays.fill(chars,letter);return new String(chars);}
    private static void conflictingIdentityFramesInvalidatePendingVotes(){
        FakeStore disk=new FakeStore();SessionBinder binder=new SessionBinder(disk);ScreenRecognition.Result old=observation(7,"预言家","");binder.accept(old);
        ScreenRecognition.Result rival=observation(7,"女巫","");ScreenRecognition.Result conflict=ScreenRecognition.merge(old,rival);binder.accept(conflict);binder.accept(old);
        check(!disk.current.screenLocked,"own conflict invalidates votes from the frame before it");
        binder.accept(old);check(disk.current.screenLocked,"two fresh consistent observations after conflict may bind");
        FakeStore single=new FakeStore();SessionBinder one=new SessionBinder(single);one.accept(old);
        one.accept(parse("我的身份：女巫","我的身份：预言家"));one.accept(old);
        check(!single.current.screenLocked,"one-mode OCR contradictions also invalidate the previous own-role vote");
        FakeStore boardDisk=new FakeStore();SessionBinder boardBinder=new SessionBinder(boardDisk);
        boardBinder.accept(observation(7,"预言家","classic_12"));
        ScreenRecognition.Result a=observation(7,"预言家","classic_12"),b=observation(7,"预言家","miracle_10");a.players=12;b.players=10;
        ScreenRecognition.Result boardConflict=ScreenRecognition.merge(a,b);
        check(boardConflict.boardConflict&&!boardConflict.ownConflict,"board-only disagreement is separate from identity disagreement");
        boardBinder.accept(boardConflict);
        check(boardDisk.current.screenLocked&&boardDisk.current.screenBoardId.isEmpty(),"board conflict clears board votes while preserving consecutive own identity");
        boardBinder.accept(observation(0,"","classic_12"));
        check(boardDisk.current.screenBoardId.isEmpty(),"old pre-conflict board vote cannot combine with a new board observation");
        boardBinder.accept(observation(0,"","classic_12"));check(boardDisk.current.screenBoardId.equals("classic_12"),"two fresh board observations complete the board after conflict");
        ScreenRecognition.Result chained=ScreenRecognition.merge(conflict,new ScreenRecognition.Result());
        check(chained.ownConflict,"known identity conflict survives an empty subsequent merge");
    }
}
