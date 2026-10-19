package com.langyu.assistant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses OCR data without Android or network dependencies. */
public final class ScreenRecognition {
    private ScreenRecognition() { }
    private static final String NUMBER="([0-9零〇一二两三四五六七八九十]{1,3})";
    private static final Pattern OWN_ROLE=Pattern.compile("^(?:你的身份(?:牌)?|我的身份(?:牌)?|本局身份|自己的身份牌)(?:是|为)?[:：;；]*(.+)$");
    private static final Pattern OWN_SEAT=Pattern.compile("^(?:你的号码|你的座位|我的座位|我的号码|本局座位)(?:是|为)?[:：;；]*"+NUMBER+"(?:号)?$");
    private static final Pattern YOU_SEAT=Pattern.compile("^你是"+NUMBER+"号(.*)$");
    private static final Pattern SELF_MARK=Pattern.compile("^"+NUMBER+"号?\\(我\\)$");
    private static final Pattern PLAYER_COUNT=Pattern.compile("(?<![0-9])"+NUMBER+"人(?:局|场|模式)?");
    private static final Pattern DAY=Pattern.compile("^第"+NUMBER+"(?:天|日)(?:白天|黑夜)?$");
    private static final Set<String> BASE_ROLES=new LinkedHashSet<>(Arrays.asList((
            "狼人,狼王,白狼王,黑狼王,狼美人,隐狼,狼巫,石像鬼,恶灵骑士,恶夜骑士,血月使徒,赤月使徒,噩梦之影,寂夜导师,蚀时狼妃,蚀日侍女,夜之贵族,寻香魅影,机械狼,狼鸦之爪,狼兄,狼弟,平民,村民,预言家,女巫,猎人,白痴,愚者,守卫,骑士,猎魔人,纯白之女,通灵师,守墓人,摄梦人,魔术师,奇迹商人,幸运儿,乌鸦,禁言长老,长老,熊,狐狸,定序王子,流光伯爵,觉醒预言家,觉醒女巫,觉醒猎人,觉醒守卫,觉醒白痴,觉醒愚者,觉醒骑士,觉醒狼美人,觉醒狼王,觉醒隐狼,魔镜少女,羊驼,白猫,子狐,河豚,炼金魔女,白昼学者,黑夜学者,丘比特,咒狐,觉醒孤独少女,孤独少女,暗恋者,野孩子,狼狗,混血儿").split(",")));
    public static final class Line {
        public String text;
        public int left, top, right, bottom, confidence;
        public Line(String text,int left,int top,int right,int bottom,int confidence) {
            this.text=text;this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.confidence=confidence;
        }
    }
    public static final class Result {
        public int seat=0,day=0,players=0,confidence=0;
        public String role="",boardId="",phase="",evidence="";
        public boolean gameScreen=false;
        public boolean ownConflict=false,boardConflict=false;
    }
    public static final class BoardSpec {
        public String id,name;
        public String[] roles;
        public int wolves;
        public BoardSpec(String id,String name,String[] roles,int wolves) {
            this.id=id;this.name=name;this.roles=roles;this.wolves=wolves;
        }
    }
    /** Combine two OCR segmentations without choosing a winner when they disagree. */
    public static Result merge(Result primary,Result fallback){
        Result out=new Result(),a=eligibleFields(primary),b=eligibleFields(fallback);
        out.gameScreen=(primary!=null&&primary.gameScreen)||(fallback!=null&&fallback.gameScreen);
        List<String> conflicts=new ArrayList<>();
        out.seat=mergeNumber(a.seat,b.seat,"座位",conflicts);
        out.role=mergeText(a.role,b.role,"身份",conflicts);
        out.boardId=mergeText(a.boardId,b.boardId,"板型",conflicts);
        out.players=mergeNumber(a.players,b.players,"人数",conflicts);
        out.day=mergeNumber(a.day,b.day,"天数",conflicts);
        out.phase=mergeText(a.phase,b.phase,"阶段",conflicts);
        out.ownConflict=conflicts.contains("座位")||conflicts.contains("身份")
                ||(primary!=null&&primary.gameScreen&&primary.ownConflict)||(fallback!=null&&fallback.gameScreen&&fallback.ownConflict);
        out.boardConflict=conflicts.contains("板型")
                ||(primary!=null&&primary.gameScreen&&primary.boardConflict)||(fallback!=null&&fallback.gameScreen&&fallback.boardConflict);
        if(out.boardConflict){out.boardId="";out.players=0;}
        if(out.ownConflict&&!conflicts.contains("座位")&&!conflicts.contains("身份"))conflicts.add("本人身份（先前分段）");
        if(out.boardConflict&&!conflicts.contains("板型")&&!conflicts.contains("人数"))conflicts.add("板型（先前分段）");
        boolean usefulA=contributes(a,out),usefulB=contributes(b,out);
        if(usefulA&&usefulB)out.confidence=Math.min(a.confidence,b.confidence);
        else if(usefulA)out.confidence=a.confidence;
        else if(usefulB)out.confidence=b.confidence;
        String conflict=conflicts.isEmpty()?"":"分段冲突："+String.join("、",conflicts)+"；";
        out.evidence=conflict+"[分段识别]"+boundedEvidence(primary)+"；[密集识别]"+boundedEvidence(fallback);
        return out;
    }
    private static Result eligibleFields(Result source){
        Result copy=new Result();if(source==null||!source.gameScreen||source.confidence<70)return copy;
        copy.confidence=Math.min(100,source.confidence);
        copy.seat=source.seat>=1&&source.seat<=20?source.seat:0;
        copy.players=source.players>=5&&source.players<=20?source.players:0;
        copy.day=source.day>=1&&source.day<=99?source.day:0;
        copy.role=source.role==null?"":source.role.trim();copy.boardId=source.boardId==null?"":source.boardId.trim();copy.phase=source.phase==null?"":source.phase.trim();
        return copy;
    }
    private static int mergeNumber(int a,int b,String name,List<String> conflicts){
        if(a>0&&b>0&&a!=b){conflicts.add(name);return 0;}return a>0?a:b;
    }
    private static String mergeText(String a,String b,String name,List<String> conflicts){
        if(!a.isEmpty()&&!b.isEmpty()&&!a.equals(b)){conflicts.add(name);return "";}return !a.isEmpty()?a:b;
    }
    private static boolean contributes(Result source,Result out){
        return (out.seat>0&&source.seat==out.seat)||(out.players>0&&source.players==out.players)||(out.day>0&&source.day==out.day)
                ||(!out.role.isEmpty()&&out.role.equals(source.role))||(!out.boardId.isEmpty()&&out.boardId.equals(source.boardId))||(!out.phase.isEmpty()&&out.phase.equals(source.phase));
    }
    private static String boundedEvidence(Result source){
        if(source==null||source.evidence==null||source.evidence.isEmpty())return "（无文字）";
        return source.evidence.length()>320?source.evidence.substring(0,320)+"…":source.evidence;
    }
    public static Result parse(List<Line> lines,int width,int height,List<BoardSpec> boards) {
        Result result=new Result();
        if(lines==null||width<=0||height<=0)return result;
        List<Line> usable=new ArrayList<>();
        Set<String> roles=new LinkedHashSet<>(BASE_ROLES);
        if(boards!=null)for(BoardSpec b:boards)if(b!=null&&b.roles!=null)for(String role:b.roles)if(role!=null)roles.add(compact(role));
        boolean anchor=false;
        for(Line line:lines){
            if(line==null||line.text==null)continue;
            String text=compact(line.text);
            // Assistant UI is a hard veto, even when a game title appears in its settings.
            if(text.contains("狼语复盘")||text.contains("话术工作台")||text.contains("设置与数据"))return result;
            if(line.confidence<70||line.right<=line.left||line.bottom<=line.top||line.left>=width||line.top>=height||line.right<0||line.bottom<0||isPublicText(text))continue;
            usable.add(line);
            if(text.length()<=32&&(text.contains("狼人杀")||text.contains("天黑请闭眼")||text.contains("天亮请睁眼")||text.contains("警长竞选")||text.contains("放逐投票")||text.contains("对局进行中")))anchor=true;
            // ScreenMonitor authenticates the foreground package before OCR. Exact private-card UI
            // also authenticates a reveal screen where the game's logo is temporarily absent.
            if(seat(text,roles)>0||!role(text,roles).isEmpty()||isOwnHeading(text))anchor=true;
        }
        // Generic role labels, public claims and arbitrary seat numbers never authenticate a screen.
        BoardSpec board=findBoard(usable,boards);
        if(!anchor&&board==null)return result;
        result.gameScreen=true;
        Set<Integer> seats=new LinkedHashSet<>();
        Set<String> ownRoles=new LinkedHashSet<>();
        List<String> evidence=new ArrayList<>();
        int confidence=100;
        for(Line line:usable){
            String text=compact(line.text);
            String value=text;
            if(isOwnHeading(text)){
                Line neighbor=nearbyValue(line,usable,width,height);
                if(neighbor!=null){value=text.replaceAll("[:：;；]+$","")+":"+compact(neighbor.text);confidence=Math.min(confidence,neighbor.confidence);}
            }
            int seat=seat(value,roles);
            String role=role(value,roles);
            if(seat>0){seats.add(seat);evidence.add(value);confidence=Math.min(confidence,line.confidence);}
            if(!role.isEmpty()){ownRoles.add(role);if(seat==0)evidence.add(value);confidence=Math.min(confidence,line.confidence);}
            Matcher d=DAY.matcher(text);
            if(d.matches()){int n=number(d.group(1));if(n>=1&&n<=99)result.day=n;}
            if(text.equals("警长竞选")||text.equals("放逐投票")||text.equals("白天发言")||text.equals("黑夜"))result.phase=text;
        }
        if(seats.size()==1)result.seat=seats.iterator().next();
        if(ownRoles.size()==1)result.role=ownRoles.iterator().next();
        result.ownConflict=seats.size()>1||ownRoles.size()>1;
        if(board!=null){
            result.boardId=board.id;result.players=board.roles.length;
            evidence.add("板子："+board.name);
            for(Line line:usable){String raw=compact(line.text);if(!correctBoardGlyph(raw,board,line.confidence).equals(raw))evidence.add("已知板名校正："+raw);}
            for(Line line:usable)if(boardTitle(compact(line.text)))confidence=Math.min(confidence,line.confidence);
        }
        if(result.seat>0||!result.role.isEmpty()||!result.boardId.isEmpty()||result.ownConflict){
            result.confidence=Math.min(100,confidence);
            result.evidence=(result.ownConflict?"画面本人身份冲突；":"")+join(evidence);
        }
        return result;
    }

    private static String compact(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("\\s+","").trim();}
    private static boolean isPublicText(String text){
        return text.contains("聊天")||text.contains("发言记录")||text.contains("话术")||text.contains("台词")||text.contains("示例")||text.contains("说:")||text.contains("说你")||text.contains("查杀")||text.contains("金水")||text.contains("?")||text.contains("？")||text.contains("“")||text.contains("\"");
    }
    private static int number(String value){
        try {return Integer.parseInt(value);}catch(NumberFormatException ignored){}
        if(value.equals("十"))return 10;
        int ten=value.indexOf('十');
        if(ten>=0){int high=ten==0?1:digit(value.charAt(0));int low=ten==value.length()-1?0:digit(value.charAt(ten+1));return high*10+low;}
        return value.length()==1?digit(value.charAt(0)):-1;
    }
    private static int digit(char c){String digits="零一二三四五六七八九";if(c=='〇')return 0;if(c=='两')return 2;return digits.indexOf(c);}
    private static int validSeat(String value){int n=number(value);return n>=1&&n<=20?n:0;}
    private static int seat(String text,Set<String> roles){
        Matcher m=OWN_SEAT.matcher(text);if(m.matches())return validSeat(m.group(1));
        m=SELF_MARK.matcher(text);if(m.matches())return validSeat(m.group(1));
        m=YOU_SEAT.matcher(text);if(m.matches()&&(m.group(2).isEmpty()||roles.contains(m.group(2))))return validSeat(m.group(1));
        return 0;
    }
    private static String role(String text,Set<String> roles){
        Matcher m=OWN_ROLE.matcher(text);String value="";
        if(m.matches())value=m.group(1);
        else if(text.startsWith("你是")){
            value=text.substring(2);Matcher seat=YOU_SEAT.matcher(text);if(seat.matches())value=seat.group(2);
        }
        return roles.contains(value)?value:"";
    }
    private static boolean isOwnHeading(String text){
        return text.matches("(?:你的身份(?:牌)?|我的身份(?:牌)?|本局身份|自己的身份牌|你的号码|你的座位|我的座位|我的号码|本局座位)[:：;；]*");
    }
    private static Line nearbyValue(Line heading,List<Line> lines,int width,int height){
        Line best=null;long bestDistance=Long.MAX_VALUE;
        int h=Math.max(16,heading.bottom-heading.top);
        for(Line line:lines){
            if(line==heading)continue;
            int dy=line.top-heading.bottom;
            boolean below=dy>=-h/4&&dy<=Math.min(height/8,h*3)&&line.left<heading.right+width/8&&line.right>heading.left-width/8;
            boolean right=Math.abs(line.top-heading.top)<=h/2&&line.left>=heading.right-h/4&&line.left-heading.right<=width/4;
            if(!below&&!right)continue;
            long distance=Math.abs((long)line.top-heading.top)+Math.abs((long)line.left-heading.left);
            if(distance<bestDistance){best=line;bestDistance=distance;}
        }
        return best;
    }
    private static boolean boardTitle(String text){
        return !text.contains("身份")&&!text.contains("号码")&&!text.contains("座位")&&!text.startsWith("你是")&&!text.contains("(我)");
    }
    private static BoardSpec findBoard(List<Line> lines,List<BoardSpec> boards){
        if(boards==null)return null;
        Set<Integer> counts=new LinkedHashSet<>();
        for(Line line:lines)if(boardTitle(compact(line.text))){Matcher m=PLAYER_COUNT.matcher(compact(line.text));while(m.find()){int n=number(m.group(1));if(n>=5&&n<=20)counts.add(n);}}
        if(counts.size()>1)return null;
        int count=counts.isEmpty()?0:counts.iterator().next();
        Set<BoardSpec> candidates=new LinkedHashSet<>();
        for(Line line:lines){
            String text=compact(line.text);if(!boardTitle(text))continue;
            text=text.replaceFirst("^(?:当前)?(?:板子|板型|玩法|模式|房间模式)[:：]?","");
            Set<BoardSpec> perLine=new LinkedHashSet<>();int longest=0;
            for(BoardSpec b:boards){
                if(b==null||b.roles==null||b.roles.length<5||b.roles.length>20||b.id==null||b.name==null)continue;
                if(count>0&&count!=b.roles.length)continue;
                String candidateText=correctBoardGlyph(text,b,line.confidence);
                for(String alias:aliases(b)){
                    if(alias.length()<2||!candidateText.contains(alias))continue;
                    // A standalone title or a clearly labelled title, never arbitrary prose.
                    String rest=candidateText.replace(alias,"").replaceAll("[0-9零〇一二两三四五六七八九十]+人(?:局|场)?","").replaceAll("[()·:：]","");
                    if(!rest.isEmpty()&&!rest.matches("标准(?:场|局)?"))continue;
                    if(alias.length()>longest){longest=alias.length();perLine.clear();}
                    if(alias.length()==longest)perLine.add(b);
                }
            }
            candidates.addAll(perLine);
        }
        return candidates.size()==1?candidates.iterator().next():null;
    }
    private static String correctBoardGlyph(String text,BoardSpec board,int confidence){
        // This substitution was observed in native Chinese OCR on Moto. Restrict it to a
        // high-confidence known title with its own matching count; do not fuzzy-match prose.
        if(confidence<80||!boardTitle(text)||isPublicText(text)||!text.contains("预女猫白")||!aliases(board).contains("预女猎白"))return text;
        Matcher count=PLAYER_COUNT.matcher(text);
        if(!count.find()||number(count.group(1))!=board.roles.length||count.find())return text;
        return text.replace("预女猫白","预女猎白");
    }
    private static Set<String> aliases(BoardSpec board){
        Set<String> aliases=new LinkedHashSet<>();
        String name=compact(board.name).replaceFirst("^[0-9零〇一二两三四五六七八九十]+人","");
        aliases.add(name);
        int p=name.indexOf('(');aliases.add(p<0?name:name.substring(0,p));
        Matcher m=Pattern.compile("\\(([^)]+)\\)").matcher(name);
        while(m.find())if(!m.group(1).equals("标准场"))aliases.add(m.group(1));
        if(board.id.equals("mirror_12"))aliases.add("镜影迷踪");
        return aliases;
    }
    private static String join(List<String> evidence){
        StringBuilder out=new StringBuilder();for(String item:evidence){if(out.length()>0)out.append("；");out.append(item);if(out.length()>400)break;}return out.toString();
    }
}
