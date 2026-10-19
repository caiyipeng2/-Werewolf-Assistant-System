package com.langyu.assistant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explainable, bounded board-aware heuristics. Values are estimates, not calibrated probabilities. */
public final class RuleEngine {
    private RuleEngine() { }

    public static final class Assessment {
        public int seat, probability;
        public String label = "";
        public List<String> reasons = new ArrayList<>();
    }

    private static final Set<String> WOLF_ROLES = words("狼人,狼王,白狼王,黑狼王,狼美人,隐狼,狼巫,石像鬼,恶灵骑士,恶夜骑士,血月使徒,赤月使徒,噩梦之影,寂夜导师,蚀时狼妃,蚀日侍女,夜之贵族,寻香魅影,机械狼,狼鸦之爪,狼兄,狼弟,狼枪,狼妃,狼鸦,小狼,大灰狼,白狼,黑狼");
    private static final Set<String> GOOD_ROLES = words("好人,平民,村民,预言家,女巫,猎人,白痴,愚者,守卫,骑士,猎魔人,纯白之女,通灵师,守墓人,摄梦人,魔术师,奇迹商人,幸运儿,乌鸦,禁言长老,长老,熊,狐狸,定序王子,流光伯爵,觉醒预言家,觉醒女巫,觉醒猎人,觉醒守卫,觉醒白痴,觉醒愚者,觉醒骑士,魔镜少女,羊驼,白猫,子狐,河豚,猎狼人,占卜师,天眼,炼金魔女,驯熊师,守夜人,追猎者,圣骑士,牧师,小女孩,潜行者,咒狐猎人,白昼学者,黑夜学者");
    private static final Set<String> VARIABLE_ROLES = words("丘比特,咒狐,觉醒孤独少女,孤独少女,暗恋者,野孩子,狼狗,混血儿,盗贼");
    private static final Pattern CHINESE_SEAT = Pattern.compile("([零〇一二两三四五六七八九十幺壹贰叁肆伍陆柒捌玖拾]{1,3})\\s*[号號]");
    private static final Pattern CHECK_RESULT = Pattern.compile("([0-9]{1,2})\\s*号\\s*(?:是|为|给|发|报|我给的|我的|验出是|查验为|查验是|查出是)?\\s*(金水|查杀|好人|狼人)");
    private static final Pattern OWN_ROLE = Pattern.compile("(?:我这里是|我这张牌是|我的底牌是|我的身份是|我身份是|我是|我为|我跳|我起跳|起跳)([\\p{IsHan}]{1,8})");
    private static final String[][] CORRECTIONS = {
            {"预言加", "预言家"}, {"预言佳", "预言家"}, {"寓言家", "预言家"},
            {"查沙", "查杀"}, {"茶杀", "查杀"}, {"叉杀", "查杀"}, {"查啥", "查杀"},
            {"津水", "金水"}, {"金谁", "金水"}, {"进水", "金水"},
            {"汗跳", "悍跳"}, {"旱跳", "悍跳"}, {"悍调", "悍跳"},
            {"警会流", "警徽流"}, {"警灰流", "警徽流"}, {"警徽留", "警徽流"},
            {"战边", "站边"}, {"占边", "站边"}, {"倒勾", "倒钩"}, {"道钩", "倒钩"},
            {"冲峰", "冲锋"}, {"退税", "退水"}, {"狼吭", "狼坑"}
    };

    private static Set<String> words(String text) { return new LinkedHashSet<>(Arrays.asList(text.split(","))); }

    public static boolean isWolfRole(String role) {
        if (role == null) return false;
        String r = role.trim();
        if (r.equalsIgnoreCase("wolf")) return true;
        if (GOOD_ROLES.contains(r) || r.startsWith("猎") || r.contains("好人") || r.contains("不是")) return false;
        if (r.startsWith("觉醒")) return isWolfRole(r.substring(2));
        // Transforming roles do not have a fixed camp before the user verifies the transformation.
        if (VARIABLE_ROLES.contains(r)) return false;
        return WOLF_ROLES.contains(r) || (r.length() <= 8 && (r.startsWith("狼") || r.endsWith("狼") || r.endsWith("狼王") || r.endsWith("狼妃")));
    }

    public static String normalize(String original) {
        if (original == null) return "";
        String text = Normalizer.normalize(original, Normalizer.Form.NFKC).trim();
        text = text.replaceAll("(?<=[\\p{IsHan}])\\s+(?=[\\p{IsHan}])", "");
        for (String[] correction : CORRECTIONS) text = text.replace(correction[0], correction[1]);
        Matcher seats = CHINESE_SEAT.matcher(text);
        StringBuffer converted = new StringBuffer();
        while (seats.find()) {
            int number = chineseNumber(seats.group(1));
            seats.appendReplacement(converted, number >= 1 && number <= 20 ? number + "号" : seats.group());
        }
        seats.appendTail(converted);
        return converted.toString().replaceAll("([0-9]{1,2})\\s*[号號]", "$1号");
    }

    private static int chineseNumber(String numeral) {
        String digits = numeral.replace('幺','一').replace('两','二').replace('壹','一').replace('贰','二')
                .replace('叁','三').replace('肆','四').replace('伍','五').replace('陆','六').replace('柒','七')
                .replace('捌','八').replace('玖','九').replace('拾','十').replace('〇','零');
        int ten = digits.indexOf('十');
        if (ten >= 0) {
            int prefix = ten == 0 ? 1 : "零一二三四五六七八九".indexOf(digits.charAt(0));
            int suffix = ten == digits.length() - 1 ? 0 : "零一二三四五六七八九".indexOf(digits.charAt(ten + 1));
            return prefix < 0 || suffix < 0 ? -1 : prefix * 10 + suffix;
        }
        return digits.length() == 1 ? "零一二三四五六七八九".indexOf(digits.charAt(0)) : -1;
    }

    private static String canonicalRole(String role) {
        if (role == null) return "";
        return role.replace("愚者", "白痴").replace("村民", "平民").replace("赤月使徒", "血月使徒").replace("恶夜骑士", "恶灵骑士").trim();
    }

    /** -1 good, 1 wolf, 0 unrecognized. Only explicit user locks call this. */
    private static int lockedCamp(String value) {
        if (value == null) return 0;
        String role = value.trim().replaceAll("\\s+", "");
        if (role.contains("不是") || role.contains("未知") || role.contains("未确定") || role.contains("怀疑")) return 0;
        role = role.replaceFirst("^(已锁定|已确认|确认|锁定|真)", "");
        if (VARIABLE_ROLES.contains(role)) return 0;
        if (role.equalsIgnoreCase("good") || role.equals("好") || role.equals("神") || role.equals("民")
                || role.equals("神职") || role.equals("金水") || GOOD_ROLES.contains(role)) return -1;
        if (role.equals("查杀") || isWolfRole(role)) return 1;
        return 0;
    }

    private static final class Evidence {
        final String text; final double strength;
        Evidence(String text, double strength) { this.text = text; this.strength = strength; }
    }
    private static final class SeatEvidence {
        double score;
        final Map<String, Evidence> details = new LinkedHashMap<>();
        void add(String key, double change, String text) {
            if (details.containsKey(key)) return;
            details.put(key, new Evidence(text, Math.abs(change)));
            score += change;
        }
    }

    public static List<Assessment> analyze(GameState state) {
        List<Assessment> answer = new ArrayList<>();
        if (state == null || state.players() == 0) return answer;
        int count = state.players(), verifiedWolves = 0, verifiedGood = 0;
        int[] camps = new int[count + 1];
        SeatEvidence[] evidence = new SeatEvidence[count + 1];
        for (int seat = 1; seat <= count; seat++) {
            evidence[seat] = new SeatEvidence();
            camps[seat] = lockedCamp(state.locks.get(seat));
            if (camps[seat] == 1) verifiedWolves++;
            if (camps[seat] == -1) verifiedGood++;
            if (state.locks.containsKey(seat) && camps[seat] == 0) {
                String lock = state.locks.get(seat);
                boolean special = lock != null && VARIABLE_ROLES.contains(lock.replaceFirst("^(已锁定|已确认|确认|锁定|真)", "").trim());
                evidence[seat].add("unknown-lock", 0, special
                        ? "该角色涉及关系、转阵营或第三方；仅锁角色不确认实际阵营，请核实后选择好人/狼人并调整当前狼数"
                        : "锁定内容未识别为明确身份，暂按未知；请改选好人、狼人或具体身份");
            }
        }
        Map<String, Integer> recipe = new LinkedHashMap<>();
        for (String role : state.roles) {
            String canonical = canonicalRole(role);
            recipe.put(canonical, recipe.containsKey(canonical) ? recipe.get(canonical) + 1 : 1);
        }
        Map<Integer, Set<String>> ownClaims = new HashMap<>();
        Map<String, Set<Integer>> roleClaimants = new LinkedHashMap<>();
        Map<String, Integer> verifiedRoles = new LinkedHashMap<>();
        for (int seat = 1; seat <= count; seat++) {
            if (camps[seat] == 0) continue;
            String role = canonicalRole(state.locks.get(seat).trim().replaceFirst("^(已锁定|已确认|确认|锁定|真)", ""));
            // Camp-only locks carry no specific-role quantity information.
            if (role.equals("狼人") || role.equalsIgnoreCase("wolf") || role.equalsIgnoreCase("good")
                    || words("好人,好,神,民,神职,金水,查杀").contains(role)) continue;
            verifiedRoles.put(role, verifiedRoles.containsKey(role) ? verifiedRoles.get(role) + 1 : 1);
            roleClaimants.computeIfAbsent(role, k -> new LinkedHashSet<>()).add(seat);
        }
        boolean roleConflict = false;
        for (Map.Entry<String, Integer> entry : verifiedRoles.entrySet())
            if (!recipe.containsKey(entry.getKey()) || entry.getValue() > recipe.get(entry.getKey())) roleConflict = true;
        Map<String, Set<Integer>> results = new LinkedHashMap<>();
        Set<String> seenSpeeches = new HashSet<>(), seenResults = new HashSet<>();
        double[] publicScores = new double[count + 1];
        List<String>[] publicReasons = makeReasonArray(count + 1);
        for (GameState.Speech speech : state.speeches) {
            if (speech == null || speech.speaker < 1 || speech.speaker > count) continue;
            int actor = speech.speaker;
            String text = normalize(speech.text);
            String fingerprint = actor + ":" + text.replaceAll("[\\s\\p{Punct}，。！？；：、]", "");
            if (!seenSpeeches.add(fingerprint)) continue;
            Matcher claim = OWN_ROLE.matcher(text);
            while (claim.find()) {
                if (negatedBefore(text, claim.start()) || quotedBefore(text, claim.start())) continue;
                String role = roleAtStart(claim.group(1), recipe.keySet());
                if (role.isEmpty()) continue;
                ownClaims.computeIfAbsent(actor, k -> new LinkedHashSet<>()).add(role);
                roleClaimants.computeIfAbsent(role, k -> new LinkedHashSet<>()).add(actor);
            }
            Matcher result = CHECK_RESULT.matcher(text);
            while (result.find()) {
                int target = Integer.parseInt(result.group(1));
                if (target < 1 || target > count || target == actor || negatedBefore(text, result.start())) continue;
                String outcome = result.group(2);
                int camp = outcome.equals("查杀") || outcome.equals("狼人") ? 1 : -1;
                String prior = text.substring(Math.max(0, result.start() - 12), result.start());
                boolean checkClaim = outcome.equals("金水") || outcome.equals("查杀") || prior.matches(".*[查验].*");
                String key = actor + ":" + target + ":" + camp;
                if (!seenResults.add(key)) continue;
                if (checkClaim) results.computeIfAbsent(actor + ":" + target, k -> new LinkedHashSet<>()).add(camp);
                publicScores[target] += camp * (checkClaim ? 2.5 : 1.5);
                publicReasons[target].add(actor + "号" + (checkClaim ? "声称" : "指认") + target + "号为" + outcome + "，属于未核实证词");
            }
        }
        for (Map.Entry<Integer, Set<String>> entry : ownClaims.entrySet()) {
            int seat = entry.getKey();
            if (entry.getValue().size() > 1)
                evidence[seat].add("changing-role", 10, "本人先后声明多个身份（" + String.join("、", entry.getValue()) + "），需核对退水或转写误差");
        }
        for (Map.Entry<String, Set<Integer>> entry : roleClaimants.entrySet()) {
            String role = entry.getKey();
            if (!recipe.containsKey(role) || recipe.get(role) != 1 || entry.getValue().size() < 2) continue;
            String rivals = seatsText(entry.getValue());
            for (int seat : entry.getValue())
                evidence[seat].add("counterclaim:" + role, 10, rivals + "对跳" + role + "，仅提示核对发言与技能结果，尚不能判定真假");
        }
        for (Map.Entry<String, Set<Integer>> entry : results.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            String[] pair = entry.getKey().split(":");
            int actor = Integer.parseInt(pair[0]), target = Integer.parseInt(pair[1]);
            evidence[actor].add("contradiction:" + target, 14, "对" + target + "号的查验声称前后矛盾（金水/查杀），请先排除转写与更正");
        }
        for (int seat = 1; seat <= count; seat++) {
            if (!publicReasons[seat].isEmpty()) {
                double capped = clamp(publicScores[seat], -6, 6);
                String detail = publicReasons[seat].get(0);
                if (publicReasons[seat].size() > 1) detail += "；多条公开证词合并且限权";
                evidence[seat].add("public", capped, detail);
            }
        }
        Set<String> seenEvents = new HashSet<>();
        double[] voteScores = new double[count + 1];
        for (GameState.Event event : state.events) {
            if (event == null || event.actor < 1 || event.actor > count || event.target < 1 || event.target > count) continue;
            if (!"投票".equals(event.type) && !"vote".equalsIgnoreCase(event.type)) continue;
            if (!seenEvents.add(event.actor + ":" + event.target) || camps[event.target] == 0) continue;
            voteScores[event.actor] += camps[event.target] == 1 ? -3 : 2;
        }
        for (int seat = 1; seat <= count; seat++) if (voteScores[seat] != 0)
            evidence[seat].add("votes", clamp(voteScores[seat], -4, 4), "已记录投票与手动锁定阵营存在关联；投票行为仅作弱证据");

        int unknown = count - verifiedWolves - verifiedGood;
        int remaining = state.wolfCount - verifiedWolves;
        boolean conflict = roleConflict || state.wolfCount < 0 || state.wolfCount > count || remaining < 0 || remaining > unknown;
        boolean boundary = unknown > 0 && (remaining <= 0 || remaining >= unknown);
        int targetSum = unknown == 0 ? 0 : Math.max(unknown, Math.min(unknown * 99, remaining * 100));
        int[] estimates = allocateEstimates(evidence, camps, count, targetSum, unknown);
        String prior = "配方共" + count + "人、" + state.wolfCount + "狼；扣除手动锁定后，按剩余狼数分配先验";
        for (int seat = 1; seat <= count; seat++) {
            Assessment result = new Assessment();
            result.seat = seat;
            if (camps[seat] != 0) {
                result.probability = camps[seat] == 1 ? 100 : 0;
                result.label = "已锁定 · " + displayLock(state.locks.get(seat), camps[seat]);
                boolean screenOwn = state.screenAutoLockSeat == seat && state.screenAutoLockRole != null
                        && state.screenAutoLockRole.equals(state.locks.get(seat));
                result.reasons.add((screenOwn ? "屏幕已读本人身份：" : "用户手动确认：")
                        + displayLock(state.locks.get(seat), camps[seat]) + "；引擎不自动覆盖此信息");
                if (conflict) result.reasons.add("手动锁定与配方角色数量或狼数冲突；请核对板子或锁定，当前仍保留手动信息");
            } else {
                result.probability = estimates[seat];
                double base = unknown == 0 ? 0 : (double) remaining * 100 / unknown;
                String leaning = result.probability >= base + 9 ? "偏狼" : result.probability <= base - 8 ? "偏好人" : "不确定";
                result.label = conflict ? "估计 · 待核对配置" : "估计 · " + leaning;
                if (conflict) result.reasons.add("手动锁定与配方角色数量或狼数冲突；请先核对配置，数值仅作临时估计");
                else if (boundary) result.reasons.add("配方狼数已达边界；未手动确认的座位保留1%–99%估计，不作身份确认");
                List<Evidence> ranked = new ArrayList<>(evidence[seat].details.values());
                Collections.sort(ranked, (a, b) -> Double.compare(b.strength, a.strength));
                for (Evidence detail : ranked) {
                    if (result.reasons.size() >= 2) break;
                    result.reasons.add(detail.text);
                }
                if (result.reasons.size() < 3) result.reasons.add(prior);
            }
            answer.add(result);
        }
        return answer;
    }

    @SuppressWarnings("unchecked")
    private static List<String>[] makeReasonArray(int size) {
        List<String>[] array = (List<String>[]) new List<?>[size];
        for (int i = 0; i < size; i++) array[i] = new ArrayList<>();
        return array;
    }

    private static String displayLock(String text, int camp) {
        if (text == null) return camp == 1 ? "狼人" : "好人";
        if (text.equalsIgnoreCase("wolf")) return "狼人";
        if (text.equalsIgnoreCase("good")) return "好人";
        return text;
    }

    private static int[] allocateEstimates(SeatEvidence[] evidence, int[] camps, int count, int target, int unknown) {
        int[] allocated = new int[count + 1];
        if (unknown == 0) return allocated;
        double base = (double) target / unknown;
        double low = -300, high = 300;
        for (int iteration = 0; iteration < 80; iteration++) {
            double shift = (low + high) / 2, total = 0;
            for (int seat = 1; seat <= count; seat++) if (camps[seat] == 0)
                total += clamp(base + clamp(evidence[seat].score, -12, 24) + shift, 1, 99);
            if (total < target) low = shift; else high = shift;
        }
        final double[] fractions = new double[count + 1];
        List<Integer> seats = new ArrayList<>();
        int assigned = 0;
        for (int seat = 1; seat <= count; seat++) if (camps[seat] == 0) {
            double exact = clamp(base + clamp(evidence[seat].score, -12, 24) + (low + high) / 2, 1, 99);
            allocated[seat] = (int) Math.floor(exact + 1e-9);
            assigned += allocated[seat];
            fractions[seat] = exact - allocated[seat];
            seats.add(seat);
        }
        Collections.sort(seats, (a, b) -> {
            int fraction = Double.compare(fractions[b], fractions[a]);
            return fraction != 0 ? fraction : Integer.compare(a, b);
        });
        for (int seat : seats) if (assigned < target && allocated[seat] < 99) { allocated[seat]++; assigned++; }
        return allocated;
    }

    private static boolean negatedBefore(String text, int position) {
        String prefix = text.substring(Math.max(0, position - 8), position);
        return prefix.matches(".*(?:不认为|不觉得|并非|不是|没有|没说|别说|假如|如果|假设)$");
    }

    private static boolean quotedBefore(String text, int position) {
        String prefix = text.substring(Math.max(0, position - 12), position);
        return prefix.matches(".*(?:说|声称|原话是|发言是|转述|提到)[：:\"“‘]*$");
    }

    private static String roleAtStart(String text, Set<String> recipe) {
        List<String> candidates = new ArrayList<>(recipe);
        candidates.addAll(GOOD_ROLES); candidates.addAll(WOLF_ROLES);
        Collections.sort(candidates, (a, b) -> Integer.compare(b.length(), a.length()));
        for (String role : candidates) if (text.startsWith(role)) return canonicalRole(role);
        return "";
    }

    private static String seatsText(Set<Integer> seats) {
        StringBuilder text = new StringBuilder();
        for (int seat : seats) { if (text.length() > 0) text.append("、"); text.append(seat).append("号"); }
        return text.toString();
    }

    private static double clamp(double value, double low, double high) { return Math.max(low, Math.min(high, value)); }

    public static String framework(GameState state, String identity, String style) {
        GameState match = state == null ? new GameState() : state;
        String role = identity == null || identity.trim().isEmpty() ? "身份待填写" : identity.trim();
        String tone = style == null || style.trim().isEmpty() ? "稳健" : style.trim();
        List<Assessment> focus = analyze(match);
        Collections.sort(focus, (a, b) -> Integer.compare(b.probability, a.probability));
        StringBuilder targets = new StringBuilder();
        for (Assessment a : focus) {
            if (a.label.startsWith("已锁定")) continue;
            if (targets.length() > 0) targets.append("、");
            targets.append(a.seat).append("号（估计").append(a.probability).append("%）");
            if (targets.toString().split("、").length >= 2) break;
        }
        String skills = skillPrompts(match, role);
        String keywords = "查杀 / 金水 / 悍跳 / 警徽流 / 站边 / 倒钩 / 冲锋 / 退水";
        if (tone.contains("关键词"))
            return "关键词提纲 · 第" + match.day + "天 · " + role + "\n身份与技能结果 → 回应质疑 → 站边及依据 → 待核实对象 → 验证计划\n"
                    + "焦点：" + (targets.length() == 0 ? "先补充发言记录" : targets) + "\n" + keywords + "\n" + skills;
        if (tone.contains("简") || tone.contains("短"))
            return "简练发言框架 · 第" + match.day + "天 · " + role + "\n"
                    + "1. 身份：填写本人技能结果；区分确认、声称、推测。\n"
                    + "2. 回应：引用具体席位和原话，回应核心质疑。\n"
                    + "3. 站边：一句依据、一条反证；预言家补充警徽流。\n"
                    + "4. 待核实：" + (targets.length() == 0 ? "核对手动锁定" : targets) + "，估计不等于身份。\n"
                    + "5. 验证：提出一个问题和后续查验/投票观察计划。\n"
                    + "技能与板规：" + skills + "\n关键词：" + keywords;
        StringBuilder text = new StringBuilder("发言框架 · 第").append(match.day).append("天 · ").append(match.boardName)
                .append("\n身份：").append(role).append("；风格：").append(tone).append("\n\n")
                .append("1. 身份与信息：填写本人身份、亲自获得的查验或技能结果；把手动确认、他人声称与个人推测分别说明。\n")
                .append("2. 回应焦点：先回应针对自己的具体质疑，引用第几天、几号的哪条发言，留空处由你补充。\n")
                .append("3. 站边与依据：比较对跳者的查验、警徽流和前后说法；写出支持依据及反证，不因发言重复就认定身份。\n")
                .append("4. 待核实对象：").append(targets.length() == 0 ? "当前缺少未确认座位，请核对手动锁定。" : targets)
                .append("。估计只决定复盘关注顺序；请各补一条具体依据，也记录能解释该行为的好人视角。\n")
                .append("5. 下一步：列出1–2个希望回应的问题、后续查验/投票观察计划，以及什么新信息会使你调整判断。\n\n")
                .append("技能与板规：").append(skills).append("\n关键词：").append(keywords).append("\n");
        if (tone.contains("温和")) text.append("表达节奏：先复述对方观点，再说明自己的理解；使用开放问题，给对方更正转写和补充依据的机会。\n");
        else if (tone.contains("质询")) text.append("表达节奏：一次围绕一条原话提问，明确席位、天数与技能结果；先听回答，再检验前后是否一致。\n");
        else if (tone.contains("强") || tone.contains("直接")) text.append("表达节奏：先给关注对象，再给具体依据，保留待核实条件，避免把估计说成定论。\n");
        else if (tone.contains("复盘")) text.append("表达节奏：按天数回看原话、投票和技能记录，标记当时可知与事后才知的信息。\n");
        else text.append("表达节奏：事实 → 解释 → 反证 → 待核实问题，依据不足时明确保留判断。\n");
        return text.toString();
    }

    private static String skillPrompts(GameState state, String identity) {
        Set<String> present = new LinkedHashSet<>();
        for (String role : state.roles) present.add(canonicalRole(role));
        List<String> notes = new ArrayList<>();
        String role = canonicalRole(identity);
        if (role.contains("预言家")) notes.add("预言家：填写本人查验结果、查验心路与警徽流");
        if (role.equals("女巫") || role.equals("炼金魔女")) notes.add("女巫：核对用药与银水来源；银水不等于已确认金水");
        if (role.contains("猎人") || role.equals("猎魔人")) notes.add(role + "：核对开枪或夜猎反馈及技能失效条件");
        if (role.equals("平民") || role.equals("村民")) notes.add("平民：以原话、投票和可核实时间线支持判断");
        if (isWolfRole(role)) notes.add(role + "：区分身份视角与公开证据，复盘阵营信息差");
        if (present.contains("守卫")) notes.add("守卫：守护对象、连守限制及同守同救按板规核对");
        if (present.contains("纯白之女") || present.contains("狼巫")) notes.add("纯白之女/狼巫：核对验人类型、技能先后与相互作用，按所选板规确认");
        if (present.contains("通灵师") || present.contains("机械狼")) notes.add("通灵师/机械狼：区分具体身份查验、学习技能与后续反馈，按板规核对");
        if (present.contains("石像鬼")) notes.add("石像鬼：区分查验身份与狼队信息，勿套用普通狼人视角");
        if (present.contains("摄梦人")) notes.add("摄梦人：核对摄梦对象与连续摄梦、出局关联");
        if (present.contains("魔术师")) notes.add("魔术师：核对交换座位与实际技能落点");
        if (present.contains("狼美人")) notes.add("狼美人：核对魅惑与出局关联");
        if (present.contains("白狼王") || present.contains("狼王")) notes.add("狼王系：核对自爆、出局带人与技能触发条件");
        if (present.contains("骑士")) notes.add("骑士：核对决斗对象与公开结算");
        if (present.contains("白痴")) notes.add("白痴/愚者：核对翻牌、放逐与投票资格");
        if (present.contains("守墓人")) notes.add("守墓人：核对放逐阵营信息的天数与来源");
        if (present.contains("奇迹商人") || present.contains("幸运儿")) notes.add("奇迹商人/幸运儿：核对授予技能、使用时机与反馈");
        if (present.contains("乌鸦")) notes.add("乌鸦：核对诅咒对象与额外票数");
        if (present.contains("噩梦之影") || present.contains("寂夜导师") || present.contains("蚀时狼妃") || present.contains("狼鸦之爪"))
            notes.add("特殊狼技能：核对封锁、强化、保护或额外行动的对象和持续时间，按当前板规解释异常反馈");
        if (present.contains("恶灵骑士") || present.contains("血月使徒")) notes.add("恶灵骑士/血月使徒：核对反伤、技能限制与结算条件");
        if (present.contains("熊") || present.contains("驯熊师") || present.contains("狐狸")) notes.add("邻座查验：核对当时存活邻座与结果覆盖范围");
        boolean specialCamp = VARIABLE_ROLES.contains(role);
        for (String member : present) if (VARIABLE_ROLES.contains(member)) specialCamp = true;
        if (specialCamp) notes.add("关系/转阵营/第三方角色：具体身份不等于确定好人；核实实际阵营后再手动认证，并按转换结果调整当前狼数设置；情侣与第三方胜利目标需另记");
        if (notes.isEmpty()) notes.add("核对当前角色的技能对象、使用时机与公开结算");
        return String.join("；", notes) + "。各版本技能细则以本局板规为准。";
    }
}
