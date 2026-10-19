package com.langyu.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Local match state. The role array is a board recipe, never a seat assignment. */
public final class GameState {
    public String id, boardName, phase, draft;
    public int day, speaker, wolfCount;
    /** Own private card is independent of the player whose speech is being recorded. */
    public int mySeat, screenAutoLockSeat;
    public String myRole="", screenEvidence="", screenBoardId="", screenAutoLockRole="";
    public boolean screenLocked;
    public long screenRecognizedAt;
    public String[] roles;
    public List<Speech> speeches = new ArrayList<>();
    public List<Event> events = new ArrayList<>();
    public Map<Integer, String> locks = new LinkedHashMap<>();
    public Set<Integer> dead = new LinkedHashSet<>();

    public GameState() {
        configure("预女猎白 · 12人", new String[]{"狼人", "狼人", "狼人", "狼人",
                "预言家", "女巫", "猎人", "白痴", "平民", "平民", "平民", "平民"}, 4);
    }

    public int players() { return roles == null ? 0 : roles.length; }

    /** Validate before changing anything so an invalid custom board cannot erase a match. */
    public void configure(String name, String[] recipe, int wolves) {
        if (recipe == null || recipe.length < 5 || recipe.length > 20)
            throw new IllegalArgumentException("板子人数须为5–20人");
        if (wolves < 0 || wolves > recipe.length)
            throw new IllegalArgumentException("狼人数须在0与总人数之间");
        String[] copy = recipe.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null || copy[i].trim().isEmpty())
                throw new IllegalArgumentException("每张角色牌都须填写身份");
            copy[i] = copy[i].trim();
        }
        id = UUID.randomUUID().toString();
        boardName = name == null || name.trim().isEmpty() ? "自定义板子" : name.trim();
        roles = copy;
        wolfCount = wolves;
        phase = "白天";
        draft = "";
        day = 1;
        speaker = 1;
        mySeat = screenAutoLockSeat = 0;
        myRole = screenEvidence = screenBoardId = screenAutoLockRole = "";
        screenLocked = false;
        screenRecognizedAt = 0;
        speeches.clear();
        events.clear();
        locks.clear();
        dead.clear();
    }

    public Speech addSpeech(int speechDay, int seat, String text) {
        if (seat < 1 || seat > players()) throw new IllegalArgumentException("发言座位超出当前板子");
        if (speechDay < 1) throw new IllegalArgumentException("天数须大于0");
        Speech speech = new Speech();
        speech.id = UUID.randomUUID().toString();
        speech.day = speechDay;
        speech.speaker = seat;
        speech.text = RuleEngine.normalize(text);
        speech.timestamp = System.currentTimeMillis();
        speeches.add(speech);
        return speech;
    }

    /** Seat attribution is manual; wrapping the seats never silently advances the day. */
    public void nextSpeaker() {
        int count = players();
        if (count == 0) return;
        int start = speaker >= 1 && speaker <= count ? speaker : count;
        for (int step = 1; step <= count; step++) {
            int candidate = (start + step - 1) % count + 1;
            if (!dead.contains(candidate)) { speaker = candidate; return; }
        }
        speaker = start;
    }

    public static final class Speech {
        public String id = "", text = "";
        public int day, speaker;
        public long timestamp;
    }

    public static final class Event {
        public String type = "", detail = "";
        public int day, actor, target;
        public long timestamp;
    }
}
