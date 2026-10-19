import com.langyu.assistant.GameState;
import com.langyu.assistant.RuleEngine;
import java.util.*;

/** JVM behavior checks; deliberately independent of the Android runtime. */
public final class CoreTests {
    private static int checks;
    public static void main(String[] args) {
        neutralPriorUsesWolfCountAndNotRecipeOrder();
        configurationIsAtomicAndClearsTheMatch();
        speakerSkipsDeadSeatsWithoutInventingADay();
        manualLocksAcceptCampAndRoleAliases();
        publicCheckClaimsAreNeverVerifiedFacts();
        repeatedPublicClaimsCannotFarmEvidence();
        competingUniqueClaimsAreLightEvidence();
        aSpeakersContradictoryChecksAreExplainable();
        negatedOrQuotedRolesAreNotOwnClaims();
        explicitRoleLocksExposeRecipeConflicts();
        variableAndThirdPartyRolesDoNotImplyGoodCamp();
        voteEvidenceIsBoundedAndTraceable();
        countConstraintsAndConflictsPreserveManualTruth();
        allUnlockedEstimatesRemainFiniteAndNonCertain();
        draftFrameworkUsesTheActualBoardAndStyle();
        chineseSpeechCorrectionsPreserveMeaning();
        System.out.println("PASS: " + checks + " behavior assertions");
    }
    private static GameState standard() { return new GameState(); }
    private static RuleEngine.Assessment seat(GameState s, int seat) {
        List<RuleEngine.Assessment> all=RuleEngine.analyze(s);
        check(all.size()==s.players(),"one assessment per real seat");
        for (RuleEngine.Assessment a:all) if (a.seat==seat) return a;
        throw new AssertionError("seat absent: "+seat);
    }
    private static String reasons(RuleEngine.Assessment a) { return String.join(";",a.reasons); }
    private static void check(boolean yes,String why) { checks++; if(!yes)throw new AssertionError(why); }
    private static void neutralPriorUsesWolfCountAndNotRecipeOrder() {
        GameState s=standard();
        check(s.players()==12,"standard has twelve seats");
        int sum=0,min=100,max=0;
        for(RuleEngine.Assessment a:RuleEngine.analyze(s)) {
            sum+=a.probability;min=Math.min(min,a.probability);max=Math.max(max,a.probability);
            check(a.label.contains("估计"),"unverified label says estimate");
            check(!a.label.contains("已锁定"),"recipe is not an assignment");
        }
        check(sum==400 && min==33 && max==34,"four wolves imply neutral one-third prior");
        String[] roles=new String[20];Arrays.fill(roles,"平民");roles[0]="机械狼";
        s.configure("自定义20人",roles,6);
        for(RuleEngine.Assessment a:RuleEngine.analyze(s)) check(a.probability==30,"custom count controls prior");
        check(seat(s,1).probability==seat(s,20).probability,"composition order never reveals identities");
        for(String role:new String[]{"狼人","狼巫","石像鬼","血月使徒","赤月使徒","恶灵骑士","恶夜骑士","噩梦之影","寂夜导师","狼鸦之爪","蚀时狼妃","隐狼","机械狼","蚀日侍女","夜之贵族","寻香魅影"})
            check(RuleEngine.isWolfRole(role),"recognizes wolf role "+role);
        check(RuleEngine.isWolfRole("觉醒狼美人"),"wolf role variants retain their camp");
        for(String role:new String[]{"平民","预言家","猎人","猎魔人","白痴","愚者","纯白之女","守墓人"})
            check(!RuleEngine.isWolfRole(role),"does not make good role a wolf "+role);
    }
    private static void configurationIsAtomicAndClearsTheMatch() {
        GameState s=standard();String before=s.id;
        s.addSpeech(1,2,"我来发言");s.locks.put(2,"good");s.dead.add(8);s.draft="草稿";s.day=3;
        GameState.Event e=new GameState.Event();s.events.add(e);
        String[] recipe={"狼人","狼人","预言家","猎人","平民","平民"};
        s.configure("六人板",recipe,2);recipe[0]="平民";
        check(s.players()==6 && s.roles[0].equals("狼人"),"recipe copied");
        check(!s.id.equals(before),"new configuration has new session id");
        check(s.speeches.isEmpty()&&s.events.isEmpty()&&s.locks.isEmpty()&&s.dead.isEmpty(),"new game clears all old evidence");
        check(s.day==1&&s.speaker==1&&s.draft.isEmpty(),"new game resets current UI state");
        boolean rejected=false;try{s.configure("bad",new String[3],9);}catch(IllegalArgumentException ex){rejected=true;}
        check(rejected&&s.players()==6&&s.boardName.equals("六人板"),"bad configuration leaves previous match intact");
        GameState.Speech speech=s.addSpeech(2,3,"十二号查沙");
        check(speech.day==2&&speech.speaker==3&&speech.timestamp>0&&!speech.id.isEmpty(),"speech has durable attribution");
    }
    private static void speakerSkipsDeadSeatsWithoutInventingADay() {
        GameState s=standard();s.speaker=12;s.dead.add(1);s.dead.add(2);s.nextSpeaker();
        check(s.speaker==3&&s.day==1,"speaker wraps and skips dead seats, day remains manual");
        for(int i=1;i<=s.players();i++)s.dead.add(i);s.nextSpeaker();
        check(s.speaker>=1&&s.speaker<=s.players(),"all-dead state does not loop or create invalid seat");
    }
    private static void manualLocksAcceptCampAndRoleAliases() {
        GameState s=standard();s.locks.put(1,"good");s.locks.put(2,"wolf");s.locks.put(3,"女巫");s.locks.put(4,"好人");s.locks.put(5,"石像鬼");s.locks.put(6,"真预言家");
        for(int i:new int[]{1,3,4,6})check(seat(s,i).probability==0,"manual good/role lock is honored");
        for(int i:new int[]{2,5})check(seat(s,i).probability==100,"manual wolf/role lock is honored");
        check(seat(s,3).label.contains("已锁定")&&reasons(seat(s,3)).contains("手动"),"verified provenance is explicit");
        s.locks.put(7,"未确定");s.locks.put(99,"狼人");
        check(seat(s,7).probability>0&&seat(s,7).probability<100,"unknown lock text stays unknown");
        check(seat(s,7).reasons.size()<=3,"explanations are readable");
        for(String identity:new String[]{"魔镜少女","觉醒骑士","觉醒愚者","羊驼","白猫","子狐","河豚"}) {
            s.locks.put(8,identity);check(seat(s,8).probability==0,"preset good roles can be locked: "+identity);
        }
    }
    private static void publicCheckClaimsAreNeverVerifiedFacts() {
        GameState s=standard();s.addSpeech(1,1,"我是预言家，昨晚查验2号是查杀，3号是金水，警徽流4后5。");
        check(seat(s,2).probability<100&&seat(s,2).probability>0,"someone else's check is not truth");
        check(seat(s,3).probability>0,"someone else's gold water is not truth");
        check(reasons(seat(s,2)).contains("未核实"),"public check uncertainty is explained");
        s.locks.put(1,"预言家");
        check(seat(s,2).probability<100,"locking claimant identity does not silently verify every quoted result");
        s.locks.put(2,"狼人");check(seat(s,2).probability==100,"explicit result lock alone verifies target");
    }
    private static void repeatedPublicClaimsCannotFarmEvidence() {
        GameState s=standard();s.addSpeech(1,1,"我查验2号是查杀");int once=seat(s,2).probability;
        for(int i=0;i<200;i++){s.addSpeech(1,1,"我查验2号是查杀");s.addSpeech(2,1,"我查了2号是查杀！");}
        check(seat(s,2).probability==once,"repetitions and same-source paraphrases have no extra weight");
        GameState many=standard();for(int speaker=1;speaker<=12;speaker++)if(speaker!=2)many.addSpeech(1,speaker,"2号是狼人");
        check(seat(many,2).probability<=50,"chorus of accusations has a capped contribution");
    }
    private static void competingUniqueClaimsAreLightEvidence() {
        GameState s=standard();s.addSpeech(1,1,"我是预言家");s.addSpeech(1,2,"我这里是预言家");
        check(seat(s,1).probability>seat(s,3).probability&&seat(s,2).probability>seat(s,3).probability,"both competing claims receive light scrutiny");
        check(reasons(seat(s,1)).contains("对跳"),"counterclaim reason is visible");
        check(seat(s,1).probability<70,"counterclaim does not decide which speaker is wolf");
        int sum=0;for(RuleEngine.Assessment a:RuleEngine.analyze(s))sum+=a.probability;
        check(sum==400,"ordinary evidence redistributes the fixed wolf total");
    }
    private static void aSpeakersContradictoryChecksAreExplainable() {
        GameState s=standard();s.addSpeech(1,1,"我验了2号是金水");s.addSpeech(2,1,"我查验2号是查杀");
        check(seat(s,1).probability>seat(s,3).probability,"same speaker reverses own check result");
        check(reasons(seat(s,1)).contains("矛盾"),"contradiction can be traced");
        int p=seat(s,1).probability;for(int i=0;i<100;i++)s.addSpeech(3,1,"我查验2号是查杀");
        check(seat(s,1).probability==p,"contradiction is not counted per repetition");
    }
    private static void negatedOrQuotedRolesAreNotOwnClaims() {
        GameState s=standard();s.addSpeech(1,1,"我不是预言家，3号说自己是预言家。");s.addSpeech(1,2,"我是预言家");
        check(!reasons(seat(s,1)).contains("对跳")&&!reasons(seat(s,2)).contains("对跳"),"negation and quotation don't manufacture a rival");
        s.addSpeech(2,1,"我认为4号不是狼人");
        check(!reasons(seat(s,4)).contains("指认"),"negation does not become an accusation");
        GameState quoted=standard();quoted.addSpeech(1,1,"2号说：我是预言家。但我没有跳。");quoted.addSpeech(1,3,"我是预言家");
        check(!reasons(seat(quoted,1)).contains("对跳"),"a quoted first-person role is not the current speaker's role");
    }
    private static void explicitRoleLocksExposeRecipeConflicts() {
        GameState s=standard();s.locks.put(1,"预言家");s.addSpeech(1,2,"我是预言家");
        check(reasons(seat(s,2)).contains("对跳"),"role lock supplies a verified rival even without a transcript");
        s.locks.put(3,"真预言家");
        check(seat(s,1).probability==0&&seat(s,3).probability==0,"impossible double-lock does not replace user facts");
        check(reasons(seat(s,4)).contains("冲突"),"singleton role over-locks are surfaced");
        check(seat(s,4).label.contains("核对"),"conflict label calls for checking configuration instead of inventing a leaning");
    }
    private static void variableAndThirdPartyRolesDoNotImplyGoodCamp() {
        GameState s=standard();
        for(String identity:new String[]{"丘比特","咒狐","觉醒孤独少女","孤独少女","暗恋者","野孩子","狼狗","混血儿"}) {
            s.locks.put(1,identity);
            RuleEngine.Assessment a=seat(s,1);
            check(a.probability>0&&a.probability<100&&a.label.contains("估计"),"relationship/transforming role alone does not verify camp: "+identity);
        }
        s.locks.put(1,"好人");check(seat(s,1).probability==0,"explicit actual good camp can still be verified");
        s.locks.put(1,"狼人");check(seat(s,1).probability==100,"explicit actual wolf camp can still be verified");
        s.configure("特殊阵营复盘",new String[]{"狼人","狼人","狼人","丘比特","咒狐","觉醒孤独少女","女巫","猎人","平民"},3);
        String framework=RuleEngine.framework(s,"觉醒孤独少女","关键词提纲");
        check(framework.contains("实际阵营")&&framework.contains("狼数")&&framework.contains("调整"),"special boards explain actual camp and adjustable current wolf count");
    }
    private static void voteEvidenceIsBoundedAndTraceable() {
        GameState s=standard();s.locks.put(2,"狼人");
        GameState.Event vote=new GameState.Event();vote.type="投票";vote.actor=1;vote.target=2;vote.day=1;s.events.add(vote);
        check(reasons(seat(s,1)).contains("投票"),"user-recorded votes affect explanation");
        check(seat(s,1).probability>0,"voting wolf does not prove good");
        int p=seat(s,1).probability;for(int i=0;i<100;i++)s.events.add(vote);
        check(seat(s,1).probability==p,"duplicate events do not multiply weight");
    }
    private static void countConstraintsAndConflictsPreserveManualTruth() {
        GameState s=standard();s.locks.put(1,"狼人");s.locks.put(2,"好人");
        int sum=0;for(RuleEngine.Assessment a:RuleEngine.analyze(s))sum+=a.probability;
        check(sum==400,"remaining three wolves normalized across ten unknown seats");
        for(int i=1;i<=5;i++)s.locks.put(i,"狼人");
        check(seat(s,1).probability==100&&seat(s,5).probability==100,"conflicting manual facts never overwritten");
        check(reasons(seat(s,6)).contains("冲突"),"impossible wolf total explicitly flagged");
        GameState exhausted=standard();for(int i=1;i<=4;i++)exhausted.locks.put(i,"狼人");
        check(seat(exhausted,5).probability==1&&reasons(seat(exhausted,5)).contains("配方"),"count boundary retains non-certain estimate and explains it");
    }
    private static void allUnlockedEstimatesRemainFiniteAndNonCertain() {
        GameState s=standard();for(int d=1;d<=50;d++)for(int i=1;i<=12;i++)s.addSpeech(d,i,"我是预言家，2号是查杀，3号是金水，4号是狼人。");
        for(RuleEngine.Assessment a:RuleEngine.analyze(s)){
            check(a.probability>=1&&a.probability<=99,"ordinary evidence never yields certainty");
            check(a.reasons.size()>=1&&a.reasons.size()<=3,"one to three explainable reasons");
        }
        s.dead.add(4);check(seat(s,4).probability>0,"death alone does not prove camp");
    }
    private static void draftFrameworkUsesTheActualBoardAndStyle() {
        GameState s=standard();String text=RuleEngine.framework(s,"预言家","逻辑清晰");
        for(String word:new String[]{"身份","回应","站边","依据","查验","警徽流","待核实"})check(text.contains(word),"full framework contains "+word);
        String keyword=RuleEngine.framework(s,"平民","关键词");
        check(keyword.contains("关键词")&&keyword.length()<text.length(),"keyword mode is concise");
        String shortForm=RuleEngine.framework(s,"预言家","清晰简练");
        check(shortForm.length()<text.length()*0.8&&shortForm.contains("回应")&&shortForm.contains("依据")&&shortForm.contains("验证"),"concise style keeps the full reasoning structure");
        check(RuleEngine.framework(s,"平民","温和沟通").contains("先复述"),"gentle style frames an open conversation");
        check(RuleEngine.framework(s,"平民","重点质询").contains("一次围绕"),"questioning style asks specific traceable questions");
        s.configure("狼巫纯白",new String[]{"狼人","狼人","狼巫","纯白之女","女巫","猎人","平民","平民","平民"},3);
        String special=RuleEngine.framework(s,"纯白之女","稳健");
        check(special.contains("纯白之女")&&special.contains("狼巫")&&special.contains("板规"),"role-specific interactions follow current composition");
        s.configure("机械通灵",new String[]{"机械狼","狼人","狼人","通灵师","守卫","猎人","平民","平民","平民"},3);
        special=RuleEngine.framework(s,"守卫","稳健");
        check(special.contains("机械狼")&&special.contains("通灵师")&&special.contains("守护"),"another board has its own skill prompts");
        check(RuleEngine.framework(null,null,null).length()>0,"framework handles missing optional inputs");
    }
    private static void chineseSpeechCorrectionsPreserveMeaning() {
        String normalized=RuleEngine.normalize("十二号查沙，二号津水，汗跳，警会流，战边，倒勾，退税；２０号");
        for(String word:new String[]{"12号查杀","2号金水","悍跳","警徽流","站边","倒钩","退水","20号"})check(normalized.contains(word),"ASR correction "+word);
        check(RuleEngine.normalize("我不是狼人").equals("我不是狼人"),"normalization preserves negation");
        check(RuleEngine.normalize(null).equals(""),"normalization accepts null");
    }
}
