package com.langyu.assistant;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone framework Instrumentation: no JUnit, network, microphone or UI automation. */
public final class SmokeInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private int checks;
    private Context target;
    private Repository repository;
    private SharedPreferences preferences;
    private String originalState;
    private Map<String, Object> originalSettings;
    private File testArchive;
    private String recognition = "";
    private long modelMs, recognitionMs;
    private String mode = "";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) mode = arguments.getString("mode", "");
        start();
    }

    @Override public void onStart() {
        super.onStart();
        if ("setup-capture".equals(mode)) { setupCapture(); return; }
        long started = SystemClock.elapsedRealtime();
        Throwable failure = null;
        try {
            target = getTargetContext();
            line("DEVICE Android=" + Build.VERSION.SDK_INT + " ABI=" + Arrays.toString(Build.SUPPORTED_ABIS));
            main(() -> { snapshot(); testRepository(); testPresets(); });
            testModel();
        } catch (Throwable error) {
            failure = error;
        } finally {
            try { main(this::restore); }
            catch (Throwable restoreError) {
                if (failure == null) failure = restoreError;
                else failure.addSuppressed(restoreError);
            }
        }
        Bundle result = new Bundle();
        result.putBoolean("passed", failure == null);
        result.putInt("checks", checks);
        result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started);
        result.putLong("model_ms", modelMs);
        result.putLong("recognition_ms", recognitionMs);
        result.putString("recognized_text", recognition);
        if (failure == null) line("PASS " + checks + " assertions; original match/settings restored");
        else {
            line("FAIL " + Log.getStackTraceString(failure));
            result.putString("failure", Log.getStackTraceString(failure));
        }
        result.putString("stream", "\n" + report);
        finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    /** Explicit setup mode intentionally persists only the capture source/overlay settings. */
    private void setupCapture() {
        Bundle result = new Bundle();
        Throwable failure = null;
        try {
            main(() -> {
                target = getTargetContext();
                repository = Repository.get(target);
                String previous = repository.exportJson();
                preferences = target.getSharedPreferences("settings", Context.MODE_PRIVATE);
                require(preferences.edit().putString("targetPackage", "com.langyu.assistant.tests")
                        .putBoolean("overlay", true).commit(), "capture setup settings persisted");
                repository.save();
                require(previous.equals(repository.exportJson()), "capture setup preserves the existing match");
                require(preferences.getString("targetPackage", "").equals("com.langyu.assistant.tests")
                        && preferences.getBoolean("overlay", false), "capture test source and overlay enabled");
            });
            line("SETUP capture source=com.langyu.assistant.tests overlay=true; settings intentionally persisted; match preserved");
        } catch (Throwable error) {
            failure = error;
            line("FAIL setup-capture " + Log.getStackTraceString(error));
            result.putString("failure", Log.getStackTraceString(error));
        }
        result.putString("mode", "setup-capture");
        result.putBoolean("passed", failure == null);
        result.putInt("checks", checks);
        result.putString("targetPackage", "com.langyu.assistant.tests");
        result.putBoolean("overlay", true);
        result.putString("stream", "\n" + report);
        finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private interface MainAction { void run() throws Exception; }
    private void main(MainAction action) {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { thrown.set(error); }
        });
        if (thrown.get() != null) throw new IllegalStateException("Main-thread verification failed", thrown.get());
    }

    private void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private void line(String text) { report.append(text).append('\n'); Log.i("LangyuSmoke", text); }

    private void snapshot() {
        repository = Repository.get(target);
        originalState = repository.exportJson();
        preferences = target.getSharedPreferences("settings", Context.MODE_PRIVATE);
        originalSettings = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set<?>) value = new HashSet<>((Set<?>) value);
            originalSettings.put(entry.getKey(), value);
        }
        line("SNAPSHOT current match and " + originalSettings.size() + " setting keys");
    }

    private void testRepository() throws Exception {
        GameState game = new GameState();
        game.configure("自动验证 · 六人板", new String[]{"狼人", "狼人", "预言家", "猎人", "平民", "平民"}, 2);
        game.id = "smoke_" + UUID.randomUUID().toString();
        testArchive = new File(new File(target.getFilesDir(), "sessions"), game.id + ".json");
        require(!testArchive.exists(), "test archive must have a fresh UUID");
        game.phase = "夜间复盘";
        game.day = 2;
        game.speaker = 3;
        GameState.Speech speech = game.addSpeech(2, 3, "我是预言家，二号查沙，四号津水，警会流先五后六。");
        GameState.Event vote = new GameState.Event();
        vote.type = "投票"; vote.detail = "复盘投票记录"; vote.day = 2; vote.actor = 3; vote.target = 2;
        vote.timestamp = System.currentTimeMillis(); game.events.add(vote);
        game.locks.put(1, "good"); game.locks.put(2, "wolf"); game.dead.add(6);
        game.draft = "手动话术草稿：先回应，再给依据。\n保留中文和换行。";
        repository.state = game;
        String encoded = Repository.encode(game).toString();
        GameState decoded = Repository.decode(new JSONObject(encoded));
        require(decoded.id.equals(game.id) && decoded.players() == 6, "configuration and id round trip");
        require(decoded.day == 2 && decoded.speaker == 3 && decoded.phase.equals(game.phase), "round and speaker round trip");
        require(decoded.speeches.size() == 1 && decoded.speeches.get(0).id.equals(speech.id), "speech id round trip");
        require(decoded.speeches.get(0).text.contains("2号查杀") && decoded.speeches.get(0).text.contains("4号金水"), "real Android ASR normalization");
        require(decoded.speeches.get(0).timestamp == speech.timestamp, "speech timestamp round trip");
        require(decoded.events.size() == 1 && decoded.events.get(0).type.equals("投票") && decoded.events.get(0).target == 2, "event round trip");
        require(decoded.events.get(0).detail.equals(vote.detail) && decoded.events.get(0).timestamp == vote.timestamp, "event metadata round trip");
        require(decoded.locks.get(1).equals("good") && decoded.locks.get(2).equals("wolf") && decoded.dead.contains(6), "lock and death round trip");
        require(decoded.draft.equals(game.draft), "editable Chinese multiline draft round trip");
        repository.save();
        File current = new File(target.getFilesDir(), "current.json");
        require(current.isFile() && current.length() > 0, "atomic current match persisted");
        JSONObject disk = new JSONObject(new String(read(new java.io.FileInputStream(current)), StandardCharsets.UTF_8));
        require(Repository.decode(disk).draft.equals(game.draft), "saved draft is readable from real disk");
        repository.archive();
        require(testArchive.isFile(), "archive stored on disk");
        boolean listed = false;
        for (String[] entry : repository.archives()) if (entry[0].equals(game.id)) listed = true;
        require(listed, "archive appears in repository listing");
        String gameId = game.id;
        repository.state = new GameState();
        repository.load(gameId);
        require(repository.state.id.equals(gameId) && repository.state.draft.equals(game.draft), "archive reload restores editable state");
        require(repository.state.events.size() == 1 && repository.state.speeches.size() == 1 && repository.state.locks.size() == 2, "archive reload restores all evidence");
        JSONObject exported = new JSONObject(repository.exportJson());
        require(exported.getString("id").equals(gameId) && exported.getJSONArray("speeches").length() == 1, "JSON export contains the match");
        String plain = repository.exportText();
        require(plain.contains("自动验证") && plain.contains("2号查杀") && plain.contains("话术草稿") && plain.contains("投票"), "readable text export includes evidence and draft");
        List<RuleEngine.Assessment> analysis = RuleEngine.analyze(repository.state);
        require(analysis.size() == 6 && analysis.get(0).probability == 0 && analysis.get(1).probability == 100, "real Android rules honor explicit camp locks");
        int total = 0;
        for (RuleEngine.Assessment item : analysis) {
            total += item.probability;
            require(item.reasons.size() > 0 && item.reasons.size() <= 3, "Android rule explanations remain bounded");
        }
        require(total == 200, "remaining wolf estimate respects six-player recipe");
        String framework = RuleEngine.framework(repository.state, "预言家", "证据推理");
        require(framework.contains("回应") && framework.contains("待核实") && framework.contains("警徽流"), "speech editor receives a complete reasoning framework");
        Set<String> temporarySet = new HashSet<>(Arrays.asList("甲", "乙"));
        require(preferences.edit().putBoolean("__smoke_boolean", true).putInt("__smoke_int", 7)
                .putLong("__smoke_long", 2147483648L).putFloat("__smoke_float", 1.25f)
                .putString("__smoke_string", "中文设置").putStringSet("__smoke_set", temporarySet).commit(), "settings support atomic save");
        require(preferences.getInt("__smoke_int", 0) == 7 && preferences.getString("__smoke_string", "").equals("中文设置"), "saved settings readable");
        line("PASS repository/configuration/speech/events/locks/draft/disk/archive/load/export/settings");
    }

    private void testPresets() throws Exception {
        JSONArray bundled = new JSONArray(new String(read(target.getAssets().open("boards.json")), StandardCharsets.UTF_8));
        require(bundled.length() == 30, "thirty board presets are actually embedded");
        List<Repository.Board> boards = repository.boards();
        require(boards.size() >= bundled.length(), "repository exposes all embedded presets");
        Map<String, Repository.Board> available = new HashMap<>();
        for (Repository.Board board : boards) available.put(board.id, board);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < bundled.length(); index++) {
            JSONObject asset = bundled.getJSONObject(index);
            String id = asset.getString("id");
            require(seen.add(id), "preset ids are unique: " + id);
            Repository.Board board = available.get(id);
            require(board != null && board.name != null && !board.name.trim().isEmpty(), "preset reachable: " + id);
            require(board.roles.length >= 5 && board.roles.length <= 20 && board.roles.length == asset.getInt("players"), "seat count valid: " + id);
            require(board.wolves > 0 && board.wolves < board.roles.length, "wolf count valid: " + id);
            int classifiedWolves = 0;
            for (String role : board.roles) {
                require(role != null && !role.trim().isEmpty(), "nonempty role in " + id);
                if (RuleEngine.isWolfRole(role)) classifiedWolves++;
            }
            require(classifiedWolves == board.wolves, "all special wolf roles classified for " + id);
            GameState configured = new GameState();
            configured.configure(board.name, board.roles, board.wolves);
            int estimatedWolves = 0;
            for (RuleEngine.Assessment item : RuleEngine.analyze(configured)) {
                require(item.probability > 0 && item.probability < 100 && item.label.contains("估计"), "recipe never becomes a seat identity: " + id);
                estimatedWolves += item.probability;
            }
            require(estimatedWolves == board.wolves * 100, "neutral total follows preset: " + id);
            require(!RuleEngine.framework(configured, board.roles[board.roles.length - 1], "关键词提纲").isEmpty(), "preset has a speech framework: " + id);
        }
        line("PASS 30 embedded board recipes, all role counts, wolf classifications, neutral priors and frameworks");
    }

    private void testModel() throws Exception {
        long started = SystemClock.elapsedRealtime();
        Model model = CaptureService.model(target);
        modelMs = SystemClock.elapsedRealtime() - started;
        require(model != null && CaptureService.modelReady(target), "embedded model loads through the production loader");
        byte[] silence = new byte[6400]; // 200 ms, PCM signed 16-bit little endian, mono, 16 kHz.
        try (Recognizer recognizer = new Recognizer(model, 16000f)) {
            for (int block = 0; block < 10; block++) {
                if (recognizer.acceptWaveForm(silence, silence.length))
                    require(resultText(recognizer.getResult()).isEmpty(), "silence endpoint must not fabricate words");
                require(new JSONObject(recognizer.getPartialResult()).optString("partial", "").trim().isEmpty(), "silence partial must not fabricate words");
            }
            require(resultText(recognizer.getFinalResult()).isEmpty(), "two seconds of silence produces no speech");
        }
        line("PASS real native Vosk Model + Recognizer, 16000 Hz silence; model_ms=" + modelMs);
        byte[] wav;
        try { wav = read(getContext().getAssets().open("chinese.wav")); }
        catch (FileNotFoundException absent) { line("SKIP optional chinese.wav (not packaged in this test APK)"); return; }
        Wav sample = parseWav(wav);
        StringBuilder recognized = new StringBuilder();
        started = SystemClock.elapsedRealtime();
        try (Recognizer recognizer = new Recognizer(model, 16000f)) {
            recognizer.setWords(true);
            for (int cursor = sample.offset; cursor < sample.offset + sample.length; cursor += 4000) {
                int length = Math.min(4000, sample.offset + sample.length - cursor);
                byte[] block = Arrays.copyOfRange(wav, cursor, cursor + length);
                if (recognizer.acceptWaveForm(block, length)) appendText(recognized, resultText(recognizer.getResult()));
            }
            appendText(recognized, resultText(recognizer.getFinalResult()));
        }
        recognitionMs = SystemClock.elapsedRealtime() - started;
        recognition = recognized.toString().trim();
        require(!recognition.isEmpty(), "the real Chinese audio sample must produce nonempty recognition");
        line("PASS real Chinese WAV; audio_ms=" + (sample.length * 1000L / 32000L) + "; recognition_ms=" + recognitionMs + "; text=" + recognition);
    }

    private static String resultText(String json) throws Exception { return new JSONObject(json).optString("text", "").trim(); }
    private static void appendText(StringBuilder text, String words) {
        if (words.isEmpty()) return;
        if (text.length() > 0) text.append(' ');
        text.append(words);
    }
    private static final class Wav { final int offset, length; Wav(int offset, int length) { this.offset = offset; this.length = length; } }
    private Wav parseWav(byte[] bytes) {
        require(bytes.length >= 44 && ascii(bytes, 0).equals("RIFF") && ascii(bytes, 8).equals("WAVE"), "sample is a RIFF WAVE file");
        boolean validFormat = false;
        Wav data = null;
        for (int cursor = 12; cursor + 8 <= bytes.length;) {
            String tag = ascii(bytes, cursor);
            long length = u32(bytes, cursor + 4);
            require(length <= Integer.MAX_VALUE && cursor + 8L + length <= bytes.length, "WAV chunks are in bounds");
            int start = cursor + 8;
            if (tag.equals("fmt ")) {
                require(length >= 16, "WAV format chunk is complete");
                validFormat = u16(bytes, start) == 1 && u16(bytes, start + 2) == 1 && u32(bytes, start + 4) == 16000 && u16(bytes, start + 14) == 16;
            } else if (tag.equals("data")) data = new Wav(start, (int) length);
            long next = start + length + (length & 1);
            if (next > bytes.length) break;
            cursor = (int) next;
        }
        require(validFormat, "Chinese sample must be PCM16 mono 16000 Hz");
        require(data != null && data.length > 0 && (data.length & 1) == 0, "Chinese sample contains whole PCM16 samples");
        return data;
    }
    private static String ascii(byte[] bytes, int offset) { return new String(bytes, offset, 4, StandardCharsets.US_ASCII); }
    private static int u16(byte[] bytes, int offset) { return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8); }
    private static long u32(byte[] bytes, int offset) { return u16(bytes, offset) | ((long) u16(bytes, offset + 2) << 16); }
    private static byte[] read(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int length;
            while ((length = in.read(buffer)) != -1) output.write(buffer, 0, length);
            return output.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private void restore() throws Exception {
        List<Throwable> errors = new ArrayList<>();
        if (originalState != null && repository != null) {
            try {
                repository.state = Repository.decode(new JSONObject(originalState));
                repository.save();
                require(repository.exportJson().equals(originalState), "original match content restored exactly");
            } catch (Throwable error) { errors.add(error); }
        }
        if (preferences != null && originalSettings != null) {
            try {
                SharedPreferences.Editor editor = preferences.edit().clear();
                for (Map.Entry<String, Object> entry : originalSettings.entrySet()) {
                    String key = entry.getKey(); Object value = entry.getValue();
                    if (value instanceof String) editor.putString(key, (String) value);
                    else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                    else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                    else if (value instanceof Long) editor.putLong(key, (Long) value);
                    else if (value instanceof Float) editor.putFloat(key, (Float) value);
                    else if (value instanceof Set<?>) editor.putStringSet(key, new HashSet<>((Set<String>) value));
                    else throw new IllegalStateException("Unsupported saved setting type for " + key);
                }
                require(editor.commit(), "original settings persisted during cleanup");
                require(preferences.getAll().equals(originalSettings), "original settings restored exactly");
            } catch (Throwable error) { errors.add(error); }
        }
        if (testArchive != null) {
            try {
                File sessions = new File(target.getFilesDir(), "sessions").getCanonicalFile();
                require(testArchive.getCanonicalFile().getParentFile().equals(sessions) && testArchive.getName().startsWith("smoke_"), "cleanup path is restricted to this test archive");
                for (String suffix : new String[]{"", ".bak", ".new"}) {
                    File generated = new File(testArchive.getPath() + suffix);
                    require(!generated.exists() || generated.delete(), "temporary test archive removed: " + generated.getName());
                }
            } catch (Throwable error) { errors.add(error); }
        }
        if (!errors.isEmpty()) {
            IllegalStateException cleanup = new IllegalStateException("State restoration failed", errors.get(0));
            for (int i = 1; i < errors.size(); i++) cleanup.addSuppressed(errors.get(i));
            throw cleanup;
        }
        line("RESTORE original state/settings complete; test archive removed");
    }
}
