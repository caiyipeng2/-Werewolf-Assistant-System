package com.langyu.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Native, offline match notebook. All game-state mutations occur on the main thread. */
public final class MainActivity extends Activity {
    public static volatile boolean visible;
    public static volatile long visibilityEpoch;
    private static final int BG = Color.rgb(10, 17, 32);
    private static final int CARD = Color.rgb(19, 30, 49);
    private static final int RAISED = Color.rgb(27, 41, 63);
    private static final int BORDER = Color.rgb(43, 58, 81);
    private static final int TEXT = Color.rgb(239, 244, 252);
    private static final int MUTED = Color.rgb(164, 181, 205);
    private static final int PURPLE = Color.rgb(157, 128, 255);
    private static final int PRIMARY = Color.rgb(102, 75, 217);
    private static final int CYAN = Color.rgb(89, 219, 211);
    private static final int RED = Color.rgb(255, 137, 150);
    private static final int PROJECTION = 91;
    private static final int NOTIFICATIONS = 92;
    private static final int EXPORT = 93;
    private static final int AUDIO_PERMISSION = 94;
    private static final String UPDATE = "com.langyu.assistant.UPDATE";
    private static final String[] TABS = {"对局", "记录", "分析", "话术", "设置"};
    private static final String[] PHASES = {"白天发言", "警上竞选", "警下发言", "放逐投票", "夜晚行动", "遗言", "复盘"};
    private static final String[] STYLES = {"清晰简练", "证据推理", "温和沟通", "重点质询", "复盘总结", "关键词提纲"};
    private Repository repository;
    private SharedPreferences settings;
    private LinearLayout shell;
    private FrameLayout content;
    private LinearLayout navigation;
    private ScrollView scroll;
    private int tab;
    private final int[] scrollPositions = new int[5];
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Button> seatButtons = new LinkedHashMap<>();
    private final Map<Integer, AnalysisViews> analysisViews = new LinkedHashMap<>();
    private TextView captureStatus, partialText, modelText, matchMeta, recordCount, sourceLabel, analysisNotice;
    private TextView boardNameText, selfSummary, screenStatusText, screenEvidenceText, screenTimeText;
    private ProgressBar audioLevel;
    private Button startButton, pauseButton, speakerButton, phaseButton, scanButton, speechIdentityButton;
    private LinearLayout recordList, seatGrid;
    private int renderedSeatCount;
    private boolean launchAfterProjection;
    private boolean startAfterUsagePermission;
    private EditText draftEditor;
    private boolean bindingDraft;
    private int dialogCount;
    private boolean receiverRegistered;
    private boolean notificationAsked;
    private boolean imeVisible;
    private int imeBottom;
    private volatile String loadingStatus = "离线语音模型准备中…";
    private volatile boolean modelLoading;
    private String pendingExport;
    private String recordSignature = "";
    private String analysisSignature = "";
    private final Runnable saveDraft = () -> saveState();
    private final Runnable revealFocusedEditor = () -> revealFocusedEditor();
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshLive();
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = Repository.get(this);
        settings = getSharedPreferences("settings", MODE_PRIVATE);
        if (savedInstanceState != null) {
            tab = savedInstanceState.getInt("tab", 0);
            launchAfterProjection = savedInstanceState.getBoolean("launchAfterProjection", false);
            startAfterUsagePermission = savedInstanceState.getBoolean("startAfterUsagePermission", false);
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        shell = vertical();
        shell.setBackgroundColor(BG);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                boolean visible = insets.isVisible(WindowInsets.Type.ime());
                boolean keyboardChanged = visible != imeVisible || ime.bottom != imeBottom;
                imeVisible = visible;
                imeBottom = ime.bottom;
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                if (navigation != null) navigation.setVisibility(visible ? View.GONE : View.VISIBLE);
                if (visible && keyboardChanged) {
                    main.removeCallbacks(revealFocusedEditor);
                    view.post(revealFocusedEditor);
                }
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(shell);
        buildHeader();
        content = new FrameLayout(this);
        shell.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        buildNavigation();
        showTab(tab);
        IntentFilter filter = new IntentFilter(UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        receiverRegistered = true;
        preloadModel();
    }

    private void buildHeader() {
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(13), dp(20), dp(13));
        IconView mark = new IconView(this, 5, CYAN);
        header.addView(mark, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout labels = vertical();
        labels.setPadding(dp(10), 0, 0, 0);
        labels.addView(text("狼语复盘", 21, TEXT, true));
        TextView sub = text("听见线索，留下依据", 11, MUTED, false);
        sub.setLetterSpacing(.08f);
        labels.addView(sub);
        header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = text("本地离线", 11, CYAN, true);
        badge.setPadding(dp(10), dp(7), dp(10), dp(7));
        badge.setBackground(shape(Color.rgb(18, 52, 57), 10, 0));
        header.addView(badge);
        shell.addView(header);
    }

    private void buildNavigation() {
        navigation = horizontal();
        navigation.setPadding(dp(6), dp(5), dp(6), dp(5));
        navigation.setBackgroundColor(CARD);
        shell.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        updateNavigation();
    }

    private void updateNavigation() {
        navigation.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int destination = i;
            boolean selected = i == tab;
            int color = selected ? CYAN : MUTED;
            LinearLayout item = vertical();
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(7), 0, dp(5));
            item.setMinimumHeight(dp(58));
            item.setBackground(ripple(selected ? Color.rgb(25, 48, 59) : CARD, 12));
            item.setContentDescription(TABS[i] + "标签" + (selected ? "，已选中" : ""));
            item.setSelected(selected);
            item.setFocusable(true);
            item.addView(new IconView(this, i, color), new LinearLayout.LayoutParams(dp(23), dp(23)));
            TextView label = text(TABS[i], 11, color, selected);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(3), 0, 0);
            item.addView(label);
            item.setOnClickListener(view -> showTab(destination));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(dp(2), 0, dp(2), 0);
            navigation.addView(item, params);
        }
    }

    private void showTab(int next) {
        if (scroll != null) scrollPositions[tab] = scroll.getScrollY();
        if (draftEditor != null) persistDraft();
        tab = next;
        captureStatus = partialText = modelText = matchMeta = recordCount = sourceLabel = analysisNotice = null;
        boardNameText = selfSummary = screenStatusText = screenEvidenceText = screenTimeText = null;
        audioLevel = null;
        startButton = pauseButton = speakerButton = null;
        phaseButton = scanButton = speechIdentityButton = null;
        seatGrid = null;
        renderedSeatCount = 0;
        draftEditor = null;
        recordList = null;
        seatButtons.clear();
        analysisViews.clear();
        content.removeAllViews();
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (Build.VERSION.SDK_INT >= 30 && imeVisible && (bottom - top != oldBottom - oldTop || right - left != oldRight - oldLeft)) {
                main.removeCallbacks(revealFocusedEditor);
                view.post(revealFocusedEditor);
            }
        });
        LinearLayout page = vertical();
        page.setPadding(dp(20), dp(8), dp(20), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        switch (tab) {
            case 0: buildSession(page); break;
            case 1: buildRecords(page); break;
            case 2: buildAnalysis(page); break;
            case 3: buildSpeech(page); break;
            default: buildSettings(page); break;
        }
        updateNavigation();
        refreshLive();
        final int position = scrollPositions[tab];
        scroll.post(() -> scroll.scrollTo(0, position));
    }

    private void buildSession(LinearLayout page) {
        title(page, "MATCH ROOM", "当前对局", "启动游戏后自动识别本人座位、身份和板子，连续核对后固定本局。");
        LinearLayout board = card(page);
        boardNameText = text(repository.state.boardName, 20, TEXT, true);
        board.addView(boardNameText);
        matchMeta = text("", 13, MUTED, false);
        gap(board, 7);
        board.addView(matchMeta);
        gap(board, 12);
        button(board, "选择板子 / 自定义", false, this::chooseBoard);
        button(board, "调整狼人数", false, this::adjustWolfCount);
        buildScreenRecognition(page);
        LinearLayout capture = card(page);
        section(capture, "实时聆听", "只捕获所选应用允许分享的内部音频");
        sourceLabel = text("", 13, CYAN, false);
        sourceLabel.setContentDescription("当前捕获来源");
        gap(capture, 10);
        capture.addView(sourceLabel);
        button(capture, "选择游戏 / 音频来源", false, this::chooseSource);
        captureStatus = text("待开始", 15, TEXT, true);
        captureStatus.setContentDescription("监听状态");
        gap(capture, 12);
        capture.addView(captureStatus);
        audioLevel = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        audioLevel.setMax(100);
        audioLevel.setProgressTintList(ColorStateList.valueOf(CYAN));
        audioLevel.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));
        audioLevel.setContentDescription("捕获音量");
        LinearLayout.LayoutParams meter = new LinearLayout.LayoutParams(-1, dp(5));
        meter.setMargins(0, dp(10), 0, dp(9));
        capture.addView(audioLevel, meter);
        partialText = text("转写将在这里实时显示。", 14, MUTED, false);
        partialText.setMinHeight(dp(48));
        partialText.setContentDescription("实时转写");
        capture.addView(partialText);
        startButton = button(capture, "开始监听", true, () -> { launchAfterProjection = false; toggleCapture(); });
        LinearLayout captureActions = horizontal();
        capture.addView(captureActions);
        pauseButton = rowButton(captureActions, "暂停", () -> serviceAction(CaptureService.ACTION_PAUSE));
        rowButton(captureActions, "提交本段", () -> {
            if (CaptureService.running) serviceAction(CaptureService.ACTION_FLUSH);
            else toast("尚未监听，可在记录页手动补充发言");
        });
        modelText = text(loadingStatus, 12, MUTED, false);
        gap(capture, 9);
        capture.addView(modelText);
        LinearLayout stage = card(page);
        section(stage, "轮次与发言", "转写不会自动识别人声或座位，请手动切席。");
        LinearLayout dayRow = horizontal();
        gap(stage, 10);
        stage.addView(dayRow);
        rowButton(dayRow, "上一天", () -> changeDay(-1));
        rowButton(dayRow, "下一天", () -> changeDay(1));
        phaseButton = button(stage, "阶段：" + repository.state.phase, false, () -> choosePhase());
        speakerButton = button(stage, "", false, () -> chooseSeat("选择当前发言席", this::selectSpeaker));
        seatGrid = vertical();
        stage.addView(seatGrid);
        rebuildSeatGrid();
        TextView hint = text("点选切换发言席 · 长按查看身份与存活", 11, MUTED, false);
        stage.addView(hint);
        LinearLayout actions = horizontal();
        stage.addView(actions);
        rowButton(actions, "下一位发言", this::nextSpeaker);
        rowButton(actions, "管理玩家", () -> chooseSeat("管理玩家", this::playerDialog));
        LinearLayout tools = card(page);
        section(tools, "对局工具", "事实与发言分开记录，认证信息需手动锁定。");
        button(tools, "补充发言", true, () -> speechDialog(null));
        LinearLayout toolRow = horizontal();
        tools.addView(toolRow);
        rowButton(toolRow, "记录事件", this::eventDialog);
        rowButton(toolRow, "归档本局", this::archiveCurrent);
    }

    private void buildScreenRecognition(LinearLayout page) {
        LinearLayout recognition = card(page);
        section(recognition, "自动建立本局", "每次监听需一次系统屏幕捕获授权。进入对局后，请至少展示一次自己的身份牌。");
        selfSummary = text("", 17, CYAN, true);
        selfSummary.setContentDescription("本人座位与身份");
        gap(recognition, 12);
        recognition.addView(selfSummary);
        screenStatusText = text("", 13, TEXT, false);
        screenStatusText.setContentDescription("屏幕识别状态");
        gap(recognition, 8);
        recognition.addView(screenStatusText);
        screenTimeText = text("", 12, MUTED, false);
        recognition.addView(screenTimeText);
        screenEvidenceText = text("", 12, MUTED, false);
        screenEvidenceText.setMaxLines(5);
        recognition.addView(screenEvidenceText);
        button(recognition, "启动游戏并自动识别", true, this::startGameAndRecognize);
        LinearLayout scanActions = horizontal();
        recognition.addView(scanActions);
        scanButton = rowButton(scanActions, "补扫一次", () -> {
            if (!settings.getBoolean("screenRecognition", true)) { toast("请先在设置中开启自动截图识别"); return; }
            serviceAction(CaptureService.ACTION_SCAN);
        });
        rowButton(scanActions, "手动纠错", this::correctSelf);
        LinearLayout matchActions = horizontal();
        recognition.addView(matchActions);
        rowButton(matchActions, "解锁重识别", () -> confirm("解锁本局识别？", "将清除本人座位与身份的识别结果，再从游戏画面重新核对。已记录的发言、事件和草稿保留。", () -> resetScreenRecognition(false)));
        rowButton(matchActions, "归档并开始下一局", () -> confirm("进入下一局？", "先完整归档当前对局，再建立下一局并重新识别。请在上一局结束后使用。", () -> resetScreenRecognition(true)));
        gap(recognition, 9);
        recognition.addView(text("建议在系统授权中选择“整个屏幕”，便于随后启动游戏。也可只选游戏应用；只处理可识别的游戏画面，截图不存盘、不上传。固定后不会被后续画面改写。", 12, MUTED, false));
        button(recognition, "允许识别游戏前台", false, () -> usagePermission(false));
    }

    private void rebuildSeatGrid() {
        if (seatGrid == null) return;
        seatGrid.removeAllViews();
        seatButtons.clear();
        renderedSeatCount = repository.state.players();
        for (int first = 1; first <= renderedSeatCount; first += 4) {
            LinearLayout row = horizontal();
            seatGrid.addView(row);
            for (int offset = 0; offset < 4; offset++) {
                final int seat = first + offset;
                if (seat > renderedSeatCount) { row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(59), 1)); continue; }
                Button seatView = rowButton(row, String.valueOf(seat), () -> selectSpeaker(seat));
                seatView.setTextSize(16);
                seatView.setOnLongClickListener(view -> { playerDialog(seat); return true; });
                seatButtons.put(seat, seatView);
            }
        }
    }

    private void buildRecords(LinearLayout page) {
        title(page, "MATCH NOTES", "发言与事件", "保留原始表述，术语修正和手动编辑都可追溯到当前记录。");
        LinearLayout controls = card(page);
        recordCount = text("", 14, CYAN, true);
        controls.addView(recordCount);
        LinearLayout row = horizontal();
        controls.addView(row);
        rowButton(row, "补充发言", () -> speechDialog(null));
        rowButton(row, "记录事件", this::eventDialog);
        LinearLayout second = horizontal();
        controls.addView(second);
        rowButton(second, "术语修正", this::normalizeSpeeches);
        rowButton(second, "历史归档", this::showArchives);
        recordList = vertical();
        page.addView(recordList);
        renderRecords();
        LinearLayout export = card(page);
        section(export, "带走这局记录", "通过系统文件选择器保存到你指定的位置。");
        LinearLayout exports = horizontal();
        export.addView(exports);
        rowButton(exports, "导出 TXT", () -> export(false));
        rowButton(exports, "导出 JSON", () -> export(true));
    }

    private void renderRecords() {
        if (recordList == null) return;
        recordList.removeAllViews();
        List<GameState.Speech> speeches = repository.state.speeches;
        if (speeches.isEmpty() && repository.state.events.isEmpty()) {
            LinearLayout empty = card(recordList);
            section(empty, "还没有记录", "开始监听或手动补充第一段发言，分析会随证据变化。");
        }
        for (int i = speeches.size() - 1; i >= 0; i--) {
            GameState.Speech speech = speeches.get(i);
            LinearLayout item = card(recordList);
            TextView meta = text("第 " + speech.day + " 天  /  " + speech.speaker + " 号发言  ·  " + time(speech.timestamp), 12, CYAN, true);
            item.addView(meta);
            gap(item, 9);
            TextView body = text(speech.text, 15, TEXT, false);
            body.setTextIsSelectable(true);
            item.addView(body);
            LinearLayout actions = horizontal();
            item.addView(actions);
            rowButton(actions, "编辑", () -> speechDialog(speech));
            Button delete = rowButton(actions, "删除", () -> confirm("删除这段发言？", "删除后将重新计算分析依据。", () -> {
                repository.state.speeches.remove(speech);
                changed();
                renderRecords();
            }));
            delete.setTextColor(RED);
        }
        if (!repository.state.events.isEmpty()) {
            TextView eventHeading = text("行动与公开事件", 18, TEXT, true);
            eventHeading.setPadding(0, dp(8), 0, dp(6));
            recordList.addView(eventHeading);
        }
        List<GameState.Event> events = repository.state.events;
        for (int i = events.size() - 1; i >= 0; i--) {
            GameState.Event event = events.get(i);
            LinearLayout item = card(recordList);
            item.addView(text("第 " + event.day + " 天 · " + event.type, 13, PURPLE, true));
            gap(item, 6);
            item.addView(text(event.actor + " 号 → " + event.target + " 号" + (event.detail.isEmpty() ? "" : "\n" + event.detail), 14, TEXT, false));
            button(item, "删除事件", false, () -> confirm("删除事件？", "仅删除这条行动记录。", () -> {
                repository.state.events.remove(event);
                changed();
                renderRecords();
            }));
        }
        recordSignature = signature();
        if (recordCount != null) recordCount.setText(speeches.size() + " 段发言  ·  " + events.size() + " 条事件");
    }

    private void buildAnalysis(LinearLayout page) {
        title(page, "EVIDENCE FIRST", "身份倾向分析", "以本局板子、发言和手动认证为依据，证据变化时自动刷新。");
        LinearLayout explanation = card(page);
        explanation.addView(text("启发式估计 · 非校准概率", 15, CYAN, true));
        gap(explanation, 7);
        explanation.addView(text("百分比表示规则计算的狼人倾向，不代表真实概率或胜率。无发言时采用板子先验；只有手动认证可锁定 0% / 100%。", 13, MUTED, false));
        analysisNotice = text("", 12, PURPLE, false);
        gap(explanation, 8);
        explanation.addView(analysisNotice);
        button(explanation, "立即刷新分析", true, this::refreshAnalysis);
        for (RuleEngine.Assessment assessment : RuleEngine.analyze(repository.state)) {
            LinearLayout result = card(page);
            LinearLayout top = horizontal();
            top.setGravity(Gravity.CENTER_VERTICAL);
            result.addView(top);
            TextView seat = text(assessment.seat + " 号", 19, TEXT, true);
            top.addView(seat, new LinearLayout.LayoutParams(0, -2, 1));
            AnalysisViews views = new AnalysisViews();
            views.value = text("", 25, TEXT, true);
            top.addView(views.value);
            gap(result, 4);
            views.label = text("", 12, MUTED, false);
            result.addView(views.label);
            ProgressBar probability = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            probability.setMax(100);
            probability.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));
            views.probability = probability;
            LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, dp(4));
            bar.setMargins(0, dp(12), 0, dp(10));
            result.addView(probability, bar);
            views.reasons = text("", 13, MUTED, false);
            result.addView(views.reasons);
            analysisViews.put(assessment.seat, views);
            button(result, "核对 " + assessment.seat + " 号信息", false, () -> playerDialog(assessment.seat));
        }
        refreshAnalysis();
    }

    /** Update existing cards only, keeping the surrounding page, scroll and focus intact. */
    private void refreshAnalysis() {
        if (analysisViews.isEmpty()) return;
        for (RuleEngine.Assessment assessment : RuleEngine.analyze(repository.state)) {
            AnalysisViews views = analysisViews.get(assessment.seat);
            if (views == null) continue;
            int color = assessment.probability >= 60 ? RED : assessment.probability <= 20 ? CYAN : PURPLE;
            setChangedText(views.value, assessment.probability + "%");
            views.value.setTextColor(color);
            setChangedText(views.label, assessment.label + (repository.state.dead.contains(assessment.seat) ? " · 已出局" : " · 存活"));
            views.label.setTextColor(color);
            views.probability.setProgress(assessment.probability, false);
            views.probability.setProgressTintList(ColorStateList.valueOf(color));
            StringBuilder reasons = new StringBuilder();
            for (String reason : assessment.reasons) {
                if (reasons.length() > 0) reasons.append('\n');
                reasons.append("· ").append(reason);
            }
            setChangedText(views.reasons, reasons.toString());
        }
        analysisSignature = signature();
        if (analysisNotice != null) analysisNotice.setText("已包含最新证据 · 自动刷新");
    }

    private void setChangedText(TextView view, String value) {
        if (!view.getText().toString().equals(value)) view.setText(value);
    }
    private void setChangedDescription(View view, String value) {
        if (!value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) view.setContentDescription(value);
    }

    private static final class AnalysisViews {
        TextView value, label, reasons;
        ProgressBar probability;
    }

    private void buildSpeech(LinearLayout page) {
        title(page, "SPEAK WITH REASONS", "话术工作台", "按身份与表达方式整理发言框架，核对事实后再使用。");
        LinearLayout options = card(page);
        section(options, "本次发言", "框架依赖当前对局，不会代替你判断或向游戏发送内容。");
        String identity = speechIdentity();
        String style = settings.getString("style", STYLES[0]);
        speechIdentityButton = button(options, "发言身份：" + identity, false, this::chooseSpeechIdentity);
        button(options, "表达方式：" + style, false, () -> chooseSetting("选择表达方式", STYLES, "style"));
        button(options, "生成发言框架", true, this::generateFramework);
        LinearLayout editor = card(page);
        section(editor, "我的草稿", "修改自动保存在本机，切页和实时转写不会覆盖输入。");
        draftEditor = edit("输入你的发言草稿…", 8, "话术草稿编辑框");
        draftEditor.setText(repository.state.draft == null ? "" : repository.state.draft);
        editor.addView(draftEditor, new LinearLayout.LayoutParams(-1, -2));
        draftEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (bindingDraft) return;
                repository.state.draft = s.toString();
                main.removeCallbacks(saveDraft);
                main.postDelayed(saveDraft, 450);
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        LinearLayout actions = horizontal();
        editor.addView(actions);
        rowButton(actions, "复制草稿", () -> {
            persistDraft();
            if (repository.state.draft.trim().isEmpty()) { toast("先输入或生成草稿"); return; }
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("狼语复盘发言草稿", repository.state.draft));
            toast("已复制草稿");
        });
        rowButton(actions, "保存草稿", () -> { if (persistDraft()) toast("草稿已保存到本机"); });
        button(editor, "清空草稿", false, () -> confirm("清空草稿？", "只清空话术草稿，不影响发言记录。", () -> {
            repository.state.draft = "";
            draftEditor.setText("");
            saveState();
        }));
        LinearLayout tip = card(page);
        section(tip, "一个可靠的发言顺序", "先明确身份与立场 → 引用具体席位和轮次 → 解释推理 → 提出可验证的问题 → 给出本轮行动。");
    }

    private void buildSettings(LinearLayout page) {
        title(page, "DEVICE & PRIVACY", "设置与数据", "语音、截图识别、记录与规则分析均在这台设备上运行。");
        LinearLayout source = card(page);
        section(source, "音频来源与权限", "捕获所选应用的内部音频，不使用麦克风。Android 仍要求录音权限。");
        sourceLabel = text("", 13, CYAN, false);
        gap(source, 10);
        source.addView(sourceLabel);
        button(source, "更改来源应用", false, this::chooseSource);
        Switch overlay = toggle("监听时显示悬浮助手", settings.getBoolean("overlay", true));
        source.addView(overlay);
        overlay.setOnCheckedChangeListener((button, checked) -> {
            settings.edit().putBoolean("overlay", checked).apply();
            if (checked && !Settings.canDrawOverlays(this)) overlayPermission();
            if (CaptureService.running) serviceAction(CaptureService.ACTION_OVERLAY);
        });
        button(source, "管理悬浮窗权限", false, this::overlayPermission);
        button(source, "管理通知权限", false, () -> {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        });
        LinearLayout screen = card(page);
        section(screen, "自动截图识别", "在游戏前台自动识别本人座位、身份和板子，连续核对一致后固定到当前对局。");
        Switch recognition = toggle("监听时自动截图识别", settings.getBoolean("screenRecognition", true));
        screen.addView(recognition);
        recognition.setOnCheckedChangeListener((button, checked) -> {
            settings.edit().putBoolean("screenRecognition", checked).apply();
            if (CaptureService.running) serviceAction(CaptureService.ACTION_SCAN);
            refreshLive();
        });
        button(screen, "允许识别游戏前台", false, () -> usagePermission(false));
        gap(screen, 9);
        screen.addView(text("需要“使用情况访问”权限确认所选游戏正在前台才截图，不读取其他应用内容。每次监听另需一次系统屏幕捕获授权；进入对局后展示一次本人身份牌即可。截图仅临时在本机分析，不存盘、不上传。", 13, MUTED, false));
        LinearLayout model = card(page);
        section(model, "离线语音模型", "中文模型随安装包提供，无需账户、密钥或联网下载。");
        modelText = text(loadingStatus, 13, CYAN, false);
        gap(model, 10);
        model.addView(modelText);
        button(model, "重新检查模型", false, this::preloadModel);
        LinearLayout data = card(page);
        section(data, "本地对局数据", "当前对局自动保存。归档保留板子、记录、认证和草稿。");
        LinearLayout archives = horizontal();
        data.addView(archives);
        rowButton(archives, "归档本局", this::archiveCurrent);
        rowButton(archives, "载入历史", this::showArchives);
        LinearLayout export = horizontal();
        data.addView(export);
        rowButton(export, "导出 JSON", () -> export(true));
        rowButton(export, "导出 TXT", () -> export(false));
        Button clear = button(data, "删除全部本地对局数据", false, () -> {
            if (!requireStopped()) return;
            confirm("删除全部本地数据？", "当前对局、全部归档、草稿和自定义板子将被删除。已导出的文件需要自行删除。此操作无法撤销。", () -> {
                repository.deleteAll();
                toast("本地对局数据已删除");
                showTab(4);
            });
        });
        clear.setTextColor(RED);
        LinearLayout privacy = card(page);
        section(privacy, "捕获边界与隐私", "每次开始都需要 Android 系统授权。只处理你指定应用 UID 的可捕获音频；应用禁录、通话、耳机路由、系统策略或设备限制可能导致无声。无法绕过其他应用的录音保护。");
        gap(privacy, 10);
        privacy.addView(text("本应用没有联网权限，也不读取麦克风。暂停时不处理音频或截图；停止监听会结束捕获前台服务，保留已固定的本局信息。悬浮助手只显示本局信息，可在通知中暂停或停止。", 13, MUTED, false));
        gap(privacy, 10);
        privacy.addView(text("使用前请征得参与者同意，并遵守所用平台规则。身份倾向是可解释的规则估计；话术为本地模板生成，请自行核实后使用。", 13, MUTED, false));
        TextView version = text("狼语复盘 1.1  ·  Android 10+", 12, MUTED, false);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, dp(12), 0, dp(8));
        page.addView(version);
    }

    /** Capture broadcasts refresh only live fragments; they never recreate the page or a dialog. */
    private void refreshLive() {
        if (isFinishing() || repository == null) return;
        GameState state = repository.state;
        if (boardNameText != null) setChangedText(boardNameText, state.boardName);
        if (matchMeta != null) setChangedText(matchMeta, state.players() + " 人局  ·  " + state.wolfCount + " 狼  ·  第 " + state.day + " 天 · " + state.phase);
        if (selfSummary != null) {
            String seat = state.mySeat > 0 ? state.mySeat + " 号" : "待识别";
            String role = state.myRole == null || state.myRole.isEmpty() ? "待展示身份牌" : state.myRole;
            setChangedText(selfSummary, "我：" + seat + " / " + role + (state.screenLocked ? " · 本局已固定" : " · 核对中"));
        }
        if (screenStatusText != null) {
            String status = CaptureService.screenStatus;
            if (!settings.getBoolean("screenRecognition", true)) status = "自动截图识别已关闭，可在设置中开启";
            else if (!usageAllowed()) status = "等待前台识别授权 · 点下方“允许识别游戏前台”";
            else if (!CaptureService.running) status = state.screenLocked ? "识别已固定 · 停止监听后仍保留本局" : "启动游戏并授权后自动识别";
            else if (status == null || status.trim().isEmpty()) status = CaptureService.screenAvailable ? "等待游戏画面与本人身份牌" : "屏幕捕获准备中…";
            setChangedText(screenStatusText, status);
        }
        if (screenTimeText != null) {
            setChangedText(screenTimeText, state.screenRecognizedAt > 0 ? "最近确认：" + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(state.screenRecognizedAt)) : "尚未固定本局");
        }
        if (screenEvidenceText != null) setChangedText(screenEvidenceText, state.screenEvidence == null || state.screenEvidence.isEmpty() ? "识别依据会显示在这里；可随时人工纠错。" : "依据：" + state.screenEvidence);
        if (seatGrid != null && renderedSeatCount != state.players()) rebuildSeatGrid();
        if (phaseButton != null) setChangedText(phaseButton, "阶段：" + state.phase);
        if (speechIdentityButton != null) setChangedText(speechIdentityButton, "发言身份：" + speechIdentity() + (settings.getBoolean("followIdentity", true) ? " · 跟随本局" : " · 手动"));
        if (scanButton != null) {
            boolean allowed = CaptureService.running && settings.getBoolean("screenRecognition", true);
            scanButton.setEnabled(allowed);
            scanButton.setAlpha(allowed ? 1f : .45f);
        }
        if (sourceLabel != null) setChangedText(sourceLabel, "来源：" + appLabel(settings.getString("targetPackage", "com.netease.werewolf")));
        if (captureStatus != null) setChangedText(captureStatus, CaptureService.status == null || CaptureService.status.trim().isEmpty()
                ? (CaptureService.running ? "正在监听" : "待开始 · 仅捕获所选应用") : CaptureService.status);
        if (partialText != null) {
            String partial = CaptureService.partial;
            setChangedText(partialText, partial == null || partial.isEmpty() ? (CaptureService.running ? "等待所选应用播放可捕获音频…" : "转写将在这里实时显示。") : partial);
        }
        if (audioLevel != null) audioLevel.setProgress(Math.max(0, Math.min(100, CaptureService.level)));
        if (modelText != null) setChangedText(modelText, CaptureService.modelReady(this) ? "中文离线模型已就绪" : loadingStatus);
        if (startButton != null) {
            setChangedText(startButton, CaptureService.running ? "停止监听" : "开始监听");
            setChangedDescription(startButton, CaptureService.running ? "停止监听" : "开始监听");
        }
        if (pauseButton != null) {
            pauseButton.setEnabled(CaptureService.running);
            pauseButton.setAlpha(CaptureService.running ? 1f : .45f);
            setChangedText(pauseButton, CaptureService.paused ? "继续" : "暂停");
            setChangedDescription(pauseButton, CaptureService.paused ? "继续监听" : "暂停监听");
        }
        if (speakerButton != null) {
            setChangedText(speakerButton, "我：" + (state.mySeat > 0 ? state.mySeat + " 号" : "待识别") + " / 当前发言：" + state.speaker + " 号");
            setChangedDescription(speakerButton, "当前发言席，" + state.speaker + " 号");
        }
        for (Map.Entry<Integer, Button> entry : seatButtons.entrySet()) {
            int seat = entry.getKey();
            boolean current = seat == state.speaker;
            boolean dead = state.dead.contains(seat);
            String lock = state.locks.get(seat);
            Button view = entry.getValue();
            setChangedText(view, seat + " 号" + (dead ? "\n出局" : lock != null ? "\n已认证" : ""));
            view.setTextColor(current ? CYAN : dead ? MUTED : TEXT);
            view.setBackground(ripple(current ? Color.rgb(24, 66, 72) : RAISED, 10));
            view.setSelected(current);
            setChangedDescription(view, seat + " 号玩家，" + (current ? "当前发言，" : "") + (dead ? "已出局" : "存活") + (lock == null ? "，未认证" : "，认证" + lock));
        }
        if (recordCount != null) setChangedText(recordCount, state.speeches.size() + " 段发言  ·  " + state.events.size() + " 条事件");
        if (tab == 1 && recordList != null && dialogCount == 0 && !recordSignature.equals(signature())) {
            int position = scroll.getScrollY();
            renderRecords();
            scroll.post(() -> scroll.scrollTo(0, position));
        }
        if (analysisNotice != null && !analysisSignature.equals(signature())) {
            if (dialogCount == 0) refreshAnalysis();
            else analysisNotice.setText("新证据已到达 · 关闭弹窗后自动刷新");
        }
        if (draftEditor != null && !draftEditor.hasFocus() && !draftEditor.getText().toString().equals(state.draft)) {
            bindingDraft = true;
            draftEditor.setText(state.draft == null ? "" : state.draft);
            bindingDraft = false;
        }
    }

    private void preloadModel() {
        if (modelLoading) { toast("离线模型正在准备中"); return; }
        modelLoading = true;
        loadingStatus = "离线语音模型准备中…";
        refreshLive();
        new Thread(() -> {
            try {
                CaptureService.model(getApplicationContext());
                loadingStatus = "中文离线模型已就绪";
            } catch (Throwable error) {
                loadingStatus = "模型未就绪：" + message(error) + "；可在设置中重试";
            } finally {
                modelLoading = false;
            }
            main.post(this::refreshLive);
        }, "langyu-model-preload").start();
    }

    private void startGameAndRecognize() {
        if (settings.getBoolean("screenRecognition", true) && !usageAllowed()) {
            usagePermission(true);
            return;
        }
        if (CaptureService.running) {
            serviceAction(CaptureService.ACTION_SCAN);
            launchSelectedGame();
        } else {
            launchAfterProjection = true;
            toggleCapture();
        }
    }

    private void launchSelectedGame() {
        String target = settings.getString("targetPackage", "com.netease.werewolf");
        if (target == null || target.trim().isEmpty() || target.equals(getPackageName())) { toast("请先选择游戏来源"); return; }
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(target.trim());
            if (launch == null) { toast("所选应用无法直接启动，请手动打开；授权监听继续运行"); return; }
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception error) { toast("启动游戏失败：" + message(error) + "；可手动打开"); }
    }

    private boolean usageAllowed() {
        try {
            AppOpsManager operations = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            return operations != null && operations.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception ignored) { return false; }
    }

    private void usagePermission(boolean continueStart) {
        if (usageAllowed()) { toast("已允许识别所选游戏前台"); if (continueStart) startGameAndRecognize(); return; }
        tracked(new AlertDialog.Builder(this).setTitle("确认游戏正在前台")
                .setMessage("请在系统中允许狼语复盘的“使用情况访问”。用于确认所选游戏正在前台才截图，不读取其他应用内容。返回后可继续启动游戏。")
                .setNegativeButton("稍后", null).setPositiveButton("去授权", (dialog, which) -> {
                    try {
                        startAfterUsagePermission = continueStart;
                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    } catch (Exception error) {
                        startAfterUsagePermission = false;
                        toast("请在系统设置中允许本应用的使用情况访问");
                    }
                }).create());
    }

    private void resetScreenRecognition(boolean newMatch) {
        if (draftEditor != null) persistDraft();
        if (CaptureService.running) {
            serviceAction(newMatch ? CaptureService.ACTION_NEW_MATCH : CaptureService.ACTION_UNLOCK);
        } else {
            try {
                new SessionBinder(this).reset(newMatch);
                refreshLive();
                toast(newMatch ? "上一局已归档，下一局等待识别" : "已解锁，开始监听后重新识别");
            } catch (Exception error) { toast("操作失败：" + message(error)); }
        }
    }

    private void correctSelf() {
        GameState state = repository.state;
        final String editingMatch = state.id;
        final String editingBoard = state.boardName;
        LinearLayout form = dialogForm();
        form.addView(text("确认后固定本人座位与身份；不会切换当前发言席。板子有误时请停止监听后手动更正板子。", 13, MUTED, false));
        EditText seat = labeledEdit(form, "我的座位", "1–" + state.players(), 1);
        seat.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (state.mySeat > 0) seat.setText(String.valueOf(state.mySeat));
        final String[] selectedRole = {state.myRole == null ? "" : state.myRole};
        LinkedHashSet<String> choices = new LinkedHashSet<>();
        for (String role : state.roles) if (role != null && !role.trim().isEmpty()) choices.add(role.trim());
        final String[] roles = choices.toArray(new String[0]);
        Button roleButton = button(form, "我的身份：" + (selectedRole[0].isEmpty() ? "点击选择" : selectedRole[0]), false, () -> { });
        roleButton.setOnClickListener(view -> tracked(new AlertDialog.Builder(this).setTitle("选择本人真实身份")
                .setItems(roles, (dialog, which) -> { selectedRole[0] = roles[which]; roleButton.setText("我的身份：" + selectedRole[0]); })
                .setNegativeButton("取消", null).create()));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("修正我的对局信息").setView(dialogScroll(form))
                .setNegativeButton("取消", null).setPositiveButton("确认并固定本局", null).create();
        tracked(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (repository.state != state || !editingMatch.equals(state.id) || !editingBoard.equals(state.boardName)) {
                dialog.dismiss();
                toast("对局已更新，请重新打开手动纠错");
                return;
            }
            int number = positiveNumber(seat, "请填写有效座位");
            if (number < 1) return;
            if (number > state.players()) { seat.setError("本局只有 " + state.players() + " 位玩家"); return; }
            if (selectedRole[0].isEmpty()) { toast("请选择本人真实身份"); return; }
            if (state.mySeat > 0 && state.mySeat != number && state.myRole != null && state.myRole.equals(state.locks.get(state.mySeat))) state.locks.remove(state.mySeat);
            state.mySeat = number;
            state.myRole = selectedRole[0];
            state.locks.put(number, selectedRole[0]);
            state.screenAutoLockSeat = 0;
            state.screenAutoLockRole = "";
            state.screenLocked = true;
            state.screenEvidence = "人工确认：我为 " + number + " 号，身份为" + selectedRole[0];
            state.screenRecognizedAt = System.currentTimeMillis();
            if (saveState()) { dialog.dismiss(); refreshLive(); toast("已修正并固定本局"); }
        });
    }

    private String speechIdentity() {
        String role = repository.state.myRole;
        return settings.getBoolean("followIdentity", true) && role != null && !role.isEmpty()
                ? role : settings.getString("identity", "好人");
    }

    private void chooseSpeechIdentity() {
        String[] manual = identityOptions(false);
        String[] choices = new String[manual.length + 1];
        choices[0] = "跟随本局识别身份";
        System.arraycopy(manual, 0, choices, 1, manual.length);
        tracked(new AlertDialog.Builder(this).setTitle("选择发言身份").setItems(choices, (dialog, which) -> {
            SharedPreferences.Editor edit = settings.edit().putBoolean("followIdentity", which == 0);
            if (which > 0) edit.putString("identity", manual[which - 1]);
            edit.apply();
            refreshLive();
        }).setNegativeButton("取消", null).create());
    }

    private void toggleCapture() {
        if (CaptureService.running) { launchAfterProjection = false; serviceAction(CaptureService.ACTION_STOP); return; }
        String configuredTarget = settings.getString("targetPackage", "com.netease.werewolf");
        String target = configuredTarget == null ? "" : configuredTarget.trim();
        if (target.isEmpty() || target.equals(getPackageName())) {
            toast("请选择要监听的游戏或音频来源应用");
            chooseSource();
            return;
        }
        try { getPackageManager().getApplicationInfo(target, 0); }
        catch (PackageManager.NameNotFoundException error) {
            toast("来源应用未安装或不可访问，请重新选择");
            chooseSource();
            return;
        }
        if (!CaptureService.modelReady(this)) { toast("中文离线模型正在准备，请稍后再开始"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            tracked(new AlertDialog.Builder(this).setTitle("授权内部音频捕获")
                    .setMessage("Android 要求授予录音权限才能捕获应用内部音频。本应用只读取所选应用允许分享的内部音频，不读取麦克风。之后还会出现系统屏幕捕获授权；为随后启动游戏，建议选“整个屏幕”。截图只在本机分析游戏信息，不存盘、不上传。")
                    .setNegativeButton("取消", (dialog, which) -> launchAfterProjection = false)
                    .setPositiveButton("继续授权", (dialog, which) -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION)).create());
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationAsked && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationAsked = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(manager.createScreenCaptureIntent(), PROJECTION);
        } catch (Exception error) { launchAfterProjection = false; toast("无法发起系统捕获授权：" + message(error)); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) toggleCapture();
            else { launchAfterProjection = false; toast("未授予录音权限，无法捕获所选应用内部音频"); }
        } else if (requestCode == NOTIFICATIONS) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) toast("通知权限未开启，可在应用内停止监听");
            requestProjection();
        }
    }

    private void serviceAction(String action) {
        if (!CaptureService.running && !CaptureService.ACTION_STOP.equals(action)) { toast("请先开始监听"); return; }
        try { startService(new Intent(this, CaptureService.class).setAction(action)); }
        catch (Exception error) { toast("操作失败：" + message(error)); }
    }

    private void selectSpeaker(int seat) {
        if (CaptureService.running) {
            try { startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_FLUSH).putExtra("speaker", seat)); }
            catch (Exception error) { toast("切换发言席失败：" + message(error)); }
        } else {
            repository.state.speaker = seat;
            changed();
        }
        refreshLive();
    }

    private void nextSpeaker() {
        if (CaptureService.running) serviceAction(CaptureService.ACTION_NEXT);
        else { repository.state.nextSpeaker(); changed(); }
        refreshLive();
    }

    private void changeDay(int difference) {
        if (CaptureService.running) { toast("请先停止监听，再更改轮次"); return; }
        repository.state.day = Math.max(1, repository.state.day + difference);
        changed();
    }

    private void choosePhase() {
        tracked(new AlertDialog.Builder(this).setTitle("选择当前阶段").setItems(PHASES, (dialog, which) -> {
            repository.state.phase = PHASES[which];
            changed();
            showTab(0);
        }).setNegativeButton("取消", null).create());
    }

    private void adjustWolfCount() {
        if (!requireStopped()) return;
        LinearLayout form = dialogForm();
        EditText count = labeledEdit(form, "当前狼人总数（含已出局）", "0–" + repository.state.players(), 1);
        count.setInputType(InputType.TYPE_CLASS_NUMBER);
        count.setText(String.valueOf(repository.state.wolfCount));
        gap(form, 10);
        form.addView(text("初始角色配方不会自动识别变阵营。请根据本局实际结果调整狼人总数，范围为 0–" + repository.state.players() + "，包含已出局的狼人。", 13, MUTED, false));
        gap(form, 8);
        form.addView(text("第三方及可变阵营角色不会仅因名称就被认证为好人。身份未核实时保持未知；实际阵营已确认后，可在玩家管理中选择“好人”或“狼人”。", 13, MUTED, false));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("调整狼人数").setView(dialogScroll(form))
                .setNegativeButton("取消", null).setPositiveButton("保存狼人数", null).create();
        tracked(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!requireStopped()) return;
            int value;
            try { value = Integer.parseInt(count.getText().toString().trim()); }
            catch (Exception error) { count.setError("请填写 0–" + repository.state.players() + " 的整数"); return; }
            if (value < 0 || value > repository.state.players()) { count.setError("范围为 0–" + repository.state.players() + "，包含已出局玩家"); return; }
            repository.state.wolfCount = value;
            boolean saved = saveState();
            refreshLive();
            dialog.dismiss();
            if (saved) toast("当前狼人总数已调整为 " + value);
        });
    }

    private void chooseBoard() {
        if (!requireStopped()) return;
        List<Repository.Board> boards = repository.boards();
        String[] names = new String[boards.size() + 1];
        for (int i = 0; i < boards.size(); i++) {
            Repository.Board board = boards.get(i);
            names[i] = board.name + " · " + board.roles.length + " 人 / " + board.wolves + " 狼";
        }
        names[names.length - 1] = "+ 自定义板子（5–20 人）";
        tracked(new AlertDialog.Builder(this).setTitle("全部板子 · " + boards.size() + " 种").setItems(names, (dialog, which) -> {
            if (which == boards.size()) customBoard();
            else boardDetails(boards.get(which));
        }).setNegativeButton("取消", null).create());
    }

    private void boardDetails(Repository.Board board) {
        String message = "角色组成：\n" + join(board.roles, "、") + "\n\n" + (board.notes == null ? "" : board.notes)
                + "\n\n组成只描述板子，不会向座位分配身份。开始新局前会归档当前有内容的对局。";
        confirm("使用“" + board.name + "”？", message, () -> {
            if (!requireStopped()) return;
            if (!repository.state.speeches.isEmpty() || !repository.state.events.isEmpty() || !repository.state.draft.isEmpty()) repository.archive();
            repository.state.configure(board.name, board.roles.clone(), board.wolves);
            changed();
            showTab(0);
            toast("已创建新对局");
        });
    }

    private void customBoard() {
        LinearLayout form = dialogForm();
        EditText name = labeledEdit(form, "板子名称", "例如：我的 12 人局", 1);
        EditText roles = labeledEdit(form, "逐角色配方（逗号分隔，每位一项）", "狼人,狼人,狼人,狼人,预言家,女巫,猎人,守卫,村民,村民,村民,村民", 4);
        EditText wolves = labeledEdit(form, "狼人数量（须与配方一致）", "4", 1);
        wolves.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText notes = labeledEdit(form, "规则备注（可选）", "记录本板特殊规则…", 2);
        form.addView(text("支持 5–20 人。角色配方描述人数构成，不代表座位身份。", 12, MUTED, false));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("自定义板子").setView(dialogScroll(form))
                .setNegativeButton("取消", null).setPositiveButton("保存并使用", null).create();
        tracked(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String boardName = name.getText().toString().trim();
            if (boardName.isEmpty()) { name.setError("请填写板子名称"); return; }
            String raw = roles.getText().toString().trim();
            String[] composition = raw.isEmpty() ? new String[0] : raw.split("[,，、\\n]+");
            for (int i = 0; i < composition.length; i++) composition[i] = composition[i].trim();
            if (composition.length < 5 || composition.length > 20) { roles.setError("请填写 5–20 个逐角色条目"); return; }
            int count = 0;
            for (String role : composition) {
                if (role.isEmpty()) { roles.setError("角色名称不能为空"); return; }
                if (RuleEngine.isWolfRole(role)) count++;
            }
            int expected;
            try { expected = Integer.parseInt(wolves.getText().toString().trim()); }
            catch (Exception error) { wolves.setError("请填写狼人数量"); return; }
            if (expected < 1 || expected >= composition.length || expected != count) {
                wolves.setError("配方识别到 " + count + " 位狼人；需与填写数量相同且有好人");
                return;
            }
            Repository.Board board = new Repository.Board();
            board.id = UUID.randomUUID().toString();
            board.name = boardName;
            board.roles = composition;
            board.wolves = expected;
            board.notes = notes.getText().toString().trim();
            try { repository.addBoard(board); }
            catch (Exception error) { toast("保存板子失败：" + message(error)); return; }
            dialog.dismiss();
            boardDetails(board);
        });
    }

    private void chooseSource() {
        if (!requireStopped()) return;
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> available = getPackageManager().queryIntentActivities(launcher, 0);
        Map<String, String> entries = new LinkedHashMap<>();
        for (ResolveInfo info : available) {
            String packageName = info.activityInfo.packageName;
            if (!packageName.equals(getPackageName())) entries.put(packageName, info.loadLabel(getPackageManager()).toString());
        }
        List<String> packages = new ArrayList<>(entries.keySet());
        Collections.sort(packages, (a, b) -> entries.get(a).compareToIgnoreCase(entries.get(b)));
        String[] labels = new String[packages.size() + 1];
        labels[0] = "手动输入应用包名";
        for (int i = 0; i < packages.size(); i++) labels[i + 1] = entries.get(packages.get(i)) + "\n" + packages.get(i);
        tracked(new AlertDialog.Builder(this).setTitle("选择内部音频来源").setItems(labels, (dialog, which) -> {
            if (which == 0) packageDialog();
            else setSource(packages.get(which - 1));
        }).setNegativeButton("取消", null).create());
    }

    private void packageDialog() {
        LinearLayout form = dialogForm();
        EditText packageName = labeledEdit(form, "来源应用包名", "例如 com.netease.werewolf", 1);
        packageName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        packageName.setText(settings.getString("targetPackage", "com.netease.werewolf"));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("指定捕获来源").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("确认来源", null).create();
        tracked(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = packageName.getText().toString().trim();
            if (!value.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") || value.equals(getPackageName())) {
                packageName.setError("请填写有效的其他应用包名"); return;
            }
            try { getPackageManager().getApplicationInfo(value, 0); }
            catch (PackageManager.NameNotFoundException error) { packageName.setError("未找到已安装来源应用"); return; }
            setSource(value);
            dialog.dismiss();
        });
    }

    private void setSource(String packageName) {
        if (!requireStopped()) return;
        settings.edit().putString("targetPackage", packageName).apply();
        refreshLive();
        toast("已选择 " + appLabel(packageName));
    }

    private void playerDialog(int seat) {
        GameState state = repository.state;
        LinearLayout form = dialogForm();
        TextView lockLabel = text("当前认证：" + (state.locks.containsKey(seat) ? state.locks.get(seat) : "未认证"), 15, CYAN, true);
        form.addView(lockLabel);
        gap(form, 8);
        form.addView(text("发言中的自报身份不等于认证。第三方及可变阵营角色应按实际阵营确认；未核实时保持未知，已核实后可选择“好人”或“狼人”。", 13, MUTED, false));
        Switch dead = toggle("已出局", state.dead.contains(seat));
        form.addView(dead);
        dead.setOnCheckedChangeListener((view, checked) -> {
            if (checked) state.dead.add(seat); else state.dead.remove(seat);
            changed();
        });
        String[] locks = identityOptions(true);
        button(form, "设置认证身份", true, () -> tracked(new AlertDialog.Builder(this).setTitle("认证 " + seat + " 号身份")
                .setItems(locks, (dialog, which) -> {
                    if (which == 0) state.locks.remove(seat); else state.locks.put(seat, locks[which]);
                    lockLabel.setText("当前认证：" + (which == 0 ? "未认证" : locks[which]));
                    changed();
                    toast(which == 0 ? "已取消认证" : "已认证 " + seat + " 号为" + locks[which]);
                }).setNegativeButton("取消", null).create()));
        button(form, "设为当前发言席", false, () -> { selectSpeaker(seat); toast("已切换至 " + seat + " 号"); });
        tracked(new AlertDialog.Builder(this).setTitle(seat + " 号玩家").setView(form).setPositiveButton("完成", null).create());
    }

    private interface SeatAction { void accept(int seat); }

    private void chooseSeat(String title, SeatAction action) {
        String[] seats = new String[repository.state.players()];
        for (int i = 0; i < seats.length; i++) seats[i] = (i + 1) + " 号" + (repository.state.dead.contains(i + 1) ? " · 已出局" : "");
        tracked(new AlertDialog.Builder(this).setTitle(title).setItems(seats, (dialog, which) -> action.accept(which + 1))
                .setNegativeButton("取消", null).create());
    }

    private void speechDialog(GameState.Speech existing) {
        LinearLayout form = dialogForm();
        EditText day = labeledEdit(form, "第几天", String.valueOf(repository.state.day), 1);
        day.setInputType(InputType.TYPE_CLASS_NUMBER);
        day.setText(String.valueOf(existing == null ? repository.state.day : existing.day));
        EditText seat = labeledEdit(form, "发言席位", String.valueOf(repository.state.speaker), 1);
        seat.setInputType(InputType.TYPE_CLASS_NUMBER);
        seat.setText(String.valueOf(existing == null ? repository.state.speaker : existing.speaker));
        EditText speech = labeledEdit(form, "发言正文", "补充听到的发言，或纠正识别内容…", 5);
        if (existing != null) speech.setText(existing.text);
        button(form, "修正当前正文术语", false, () -> {
            speech.setText(RuleEngine.normalize(speech.getText().toString()));
            speech.setSelection(speech.length());
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "补充发言" : "编辑发言")
                .setView(dialogScroll(form)).setNegativeButton("取消", null).setPositiveButton("保存发言", null).create();
        tracked(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int dayValue = positiveNumber(day, "天数需为正整数");
            int seatValue = positiveNumber(seat, "请填写有效席位");
            if (dayValue < 1 || seatValue < 1) return;
            if (seatValue > repository.state.players()) { seat.setError("本局只有 " + repository.state.players() + " 位玩家"); return; }
            String body = speech.getText().toString().trim();
            if (body.isEmpty()) { speech.setError("发言正文不能为空"); return; }
            if (existing == null) repository.state.addSpeech(dayValue, seatValue, body);
            else { existing.day = dayValue; existing.speaker = seatValue; existing.text = body; }
            changed();
            dialog.dismiss();
            if (recordList != null) renderRecords();
            toast("发言已保存");
        });
    }

    private void eventDialog() {
        String[] types = {"投票", "查验", "救人", "毒杀", "守护", "出局", "身份声明", "其他"};
        tracked(new AlertDialog.Builder(this).setTitle("选择事件类型").setItems(types, (dialog, which) -> eventDetails(types[which]))
                .setNegativeButton("取消", null).create());
    }

    private void eventDetails(String type) {
        LinearLayout form = dialogForm();
        EditText day = labeledEdit(form, "第几天", "1", 1);
        day.setInputType(InputType.TYPE_CLASS_NUMBER);
        day.setText(String.valueOf(repository.state.day));
        EditText actor = labeledEdit(form, "行动 / 声明席位", "1", 1);
        actor.setInputType(InputType.TYPE_CLASS_NUMBER);
        actor.setText(String.valueOf(repository.state.speaker));
        EditText target = labeledEdit(form, "目标席位", "1", 1);
        target.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText detail = labeledEdit(form, "详情与结果", type.equals("查验") ? "例如：声称查验 3 号为好人；尚未认证" : "记录实际发生的行动与结果…", 3);
        form.addView(text("事件不会自动锁定身份；请在玩家管理中认证已核实的信息。", 12, MUTED, false));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("记录" + type).setView(dialogScroll(form))
                .setNegativeButton("取消", null).setPositiveButton("保存事件", null).create();
        tracked(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int d = positiveNumber(day, "请填写正整数天数");
            int a = positiveNumber(actor, "请填写行动席位");
            int t = positiveNumber(target, "请填写目标席位");
            if (d < 1 || a < 1 || t < 1) return;
            if (a > repository.state.players()) { actor.setError("超出本局席位数"); return; }
            if (t > repository.state.players()) { target.setError("超出本局席位数"); return; }
            GameState.Event event = new GameState.Event();
            event.type = type;
            event.day = d;
            event.actor = a;
            event.target = t;
            event.detail = detail.getText().toString().trim();
            event.timestamp = System.currentTimeMillis();
            repository.state.events.add(event);
            if (type.equals("出局") || type.equals("毒杀")) repository.state.dead.add(t);
            changed();
            dialog.dismiss();
            if (recordList != null) renderRecords();
            toast("事件已保存");
        });
    }

    private void normalizeSpeeches() {
        if (repository.state.speeches.isEmpty()) { toast("还没有可修正的发言"); return; }
        confirm("统一修正术语？", "修正当前全部发言中的常见同音词。你仍可以逐条编辑还原表述。", () -> {
            for (GameState.Speech speech : repository.state.speeches) speech.text = RuleEngine.normalize(speech.text);
            changed();
            renderRecords();
            toast("术语修正已完成");
        });
    }

    private void chooseSetting(String title, String[] values, String key) {
        tracked(new AlertDialog.Builder(this).setTitle(title).setItems(values, (dialog, which) -> {
            settings.edit().putString(key, values[which]).apply();
            showTab(3);
        }).setNegativeButton("取消", null).create());
    }

    private void generateFramework() {
        persistDraft();
        Runnable generate = () -> {
            repository.state.draft = RuleEngine.framework(repository.state, speechIdentity(), settings.getString("style", STYLES[0]));
            bindingDraft = true;
            draftEditor.setText(repository.state.draft);
            bindingDraft = false;
            if (saveState()) toast("框架已生成，可继续编辑");
        };
        if (!repository.state.draft.trim().isEmpty()) confirm("替换现有草稿？", "生成的新框架会替换当前草稿。可以先复制保存已有文字。", generate);
        else generate.run();
    }

    private boolean persistDraft() {
        main.removeCallbacks(saveDraft);
        if (draftEditor != null) repository.state.draft = draftEditor.getText().toString();
        return saveState();
    }

    private void archiveCurrent() {
        if (draftEditor != null) persistDraft();
        try { repository.archive(); toast("本局已归档，可从历史载入"); }
        catch (Exception error) { toast("归档失败：" + message(error)); }
    }

    private void showArchives() {
        List<String[]> archives = repository.archives();
        if (archives.isEmpty()) { toast("暂无归档，先归档当前对局"); return; }
        String[] titles = new String[archives.size()];
        for (int i = 0; i < archives.size(); i++) titles[i] = archives.get(i)[1];
        tracked(new AlertDialog.Builder(this).setTitle("历史对局 · " + archives.size()).setItems(titles, (dialog, which) -> {
            if (!requireStopped()) return;
            confirm("载入此归档？", "当前有内容的对局会先归档，然后载入所选记录。", () -> {
                if (!requireStopped()) return;
                if (!repository.state.speeches.isEmpty() || !repository.state.events.isEmpty() || !repository.state.draft.isEmpty()) repository.archive();
                try {
                    repository.load(archives.get(which)[0]);
                    showTab(1);
                    toast("已载入归档");
                } catch (Exception error) { toast("载入失败：" + message(error)); }
            });
        }).setNegativeButton("取消", null).create());
    }

    private void export(boolean json) {
        try {
            if (draftEditor != null) persistDraft();
            pendingExport = json ? repository.exportJson() : repository.exportText();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(json ? "application/json" : "text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "狼语复盘_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + (json ? ".json" : ".txt"));
            startActivityForResult(intent, EXPORT);
        } catch (Exception error) { toast("无法发起导出：" + message(error)); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PROJECTION) {
            if (resultCode != RESULT_OK || data == null) { launchAfterProjection = false; toast("系统捕获授权已取消，未开始监听"); return; }
            try {
                Intent service = new Intent(this, CaptureService.class).putExtra("code", resultCode).putExtra("data", data);
                startForegroundService(service);
                boolean launch = launchAfterProjection;
                launchAfterProjection = false;
                if (launch) { launchSelectedGame(); toast(settings.getBoolean("screenRecognition", true) ? "监听已启动；进入对局后展示一次本人身份牌" : "监听已启动；自动截图识别当前已关闭"); }
                else toast("已授权；请打开所选应用进行对局");
            } catch (Exception error) { launchAfterProjection = false; toast("开始失败：" + message(error)); }
        } else if (requestCode == EXPORT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null || pendingExport == null) { pendingExport = null; return; }
            Uri destination = data.getData();
            String snapshot = pendingExport;
            pendingExport = null;
            new Thread(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new IllegalStateException("无法打开目标文件");
                    output.write(snapshot.getBytes(StandardCharsets.UTF_8));
                    main.post(() -> toast("导出完成"));
                } catch (Exception error) { main.post(() -> toast("导出失败：" + message(error))); }
            }, "langyu-export").start();
        }
    }

    private void overlayPermission() {
        try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
        catch (Exception error) { toast("请在系统设置中开启悬浮窗权限"); }
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        visibilityEpoch++;
        if (repository != null) refreshLive();
        if (startAfterUsagePermission) {
            startAfterUsagePermission = false;
            if (usageAllowed()) main.post(this::startGameAndRecognize);
            else toast("未允许游戏前台识别，可在设置中授权；音频监听仍可单独使用");
        }
    }

    @Override protected void onPause() {
        visible = false;
        visibilityEpoch++;
        if (repository != null) {
            if (draftEditor != null) persistDraft();
            else saveState();
        }
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("tab", tab);
        out.putBoolean("launchAfterProjection", launchAfterProjection);
        out.putBoolean("startAfterUsagePermission", startAfterUsagePermission);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        visible = false;
        main.removeCallbacks(saveDraft);
        main.removeCallbacks(revealFocusedEditor);
        if (receiverRegistered) unregisterReceiver(receiver);
        super.onDestroy();
    }

    /** Insets resize the viewport; reveal the existing editor without changing its input state. */
    private void revealFocusedEditor() {
        if (!imeVisible || dialogCount > 0 || scroll == null || isFinishing()) return;
        View focused = getCurrentFocus();
        if (!(focused instanceof EditText) || !focused.isShown()) return;
        android.view.ViewParent ancestor = focused.getParent();
        while (ancestor != null && ancestor != scroll) ancestor = ancestor.getParent();
        if (ancestor != scroll) return;
        Rect cursor = new Rect();
        focused.getFocusedRect(cursor);
        cursor.top = Math.max(0, cursor.top - dp(12));
        cursor.bottom += dp(12);
        focused.requestRectangleOnScreen(cursor, false);
    }

    private boolean requireStopped() {
        if (!CaptureService.running) return true;
        toast("请先停止监听，再更改来源、板子或载入数据");
        return false;
    }

    private void changed() {
        saveState();
        refreshLive();
    }

    private boolean saveState() {
        try { repository.save(); return true; }
        catch (Exception error) { toast("本地保存失败：" + message(error)); return false; }
    }

    private String[] identityOptions(boolean forLock) {
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        identities.add(forLock ? "取消认证（未知）" : "身份未知");
        identities.add("好人");
        identities.add("狼人");
        for (String role : repository.state.roles) if (role != null && !role.trim().isEmpty()) identities.add(role.trim());
        return identities.toArray(new String[0]);
    }

    private String signature() {
        GameState state = repository.state;
        StringBuilder value = new StringBuilder(state.id).append('|').append(state.day).append('|').append(state.phase).append('|').append(state.wolfCount);
        for (GameState.Speech speech : state.speeches) value.append('|').append(speech.id).append(':').append(speech.day).append(':').append(speech.speaker).append(':').append(speech.text);
        for (GameState.Event event : state.events) value.append('|').append(event.type).append(':').append(event.actor).append(':').append(event.target).append(':').append(event.detail);
        return value.append('|').append(state.locks.toString()).append('|').append(state.dead.toString())
                .append('|').append(state.mySeat).append('|').append(state.myRole).append('|').append(state.screenLocked).toString();
    }

    private void confirm(String title, String message, Runnable action) {
        tracked(new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("取消", null).setPositiveButton("确认", (dialog, which) -> runSafely(action)).create());
    }

    private void tracked(AlertDialog dialog) {
        dialogCount++;
        dialog.setOnDismissListener(ignored -> {
            dialogCount = Math.max(0, dialogCount - 1);
            refreshLive();
        });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(shape(CARD, 22, BORDER));
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (positive != null) positive.setTextColor(CYAN);
        if (negative != null) negative.setTextColor(MUTED);
    }

    private LinearLayout dialogForm() {
        LinearLayout form = vertical();
        form.setPadding(dp(22), dp(12), dp(22), dp(8));
        return form;
    }

    private ScrollView dialogScroll(LinearLayout form) {
        ScrollView container = new ScrollView(this);
        container.addView(form);
        return container;
    }

    private EditText labeledEdit(LinearLayout parent, String label, String hint, int lines) {
        TextView title = text(label, 12, MUTED, true);
        title.setPadding(0, dp(8), 0, dp(7));
        parent.addView(title);
        EditText field = edit(hint, lines, label);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private EditText edit(String hint, int lines, String description) {
        EditText field = new EditText(this);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setTextSize(15);
        field.setPadding(dp(12), dp(12), dp(12), dp(12));
        field.setBackground(shape(BG, 10, BORDER));
        field.setMinHeight(dp(50));
        field.setHint(hint);
        field.setContentDescription(description);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | (lines > 1 ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        if (lines > 1) field.setMinLines(lines);
        else field.setSingleLine(true);
        return field;
    }

    private Switch toggle(String title, boolean checked) {
        Switch control = new Switch(this);
        control.setText(title);
        control.setTextColor(TEXT);
        control.setTextSize(14);
        control.setPadding(0, dp(12), 0, dp(12));
        control.setMinimumHeight(dp(52));
        control.setThumbTintList(ColorStateList.valueOf(CYAN));
        control.setChecked(checked);
        control.setContentDescription(title);
        return control;
    }

    private int positiveNumber(EditText field, String error) {
        try {
            int number = Integer.parseInt(field.getText().toString().trim());
            if (number > 0 && number < 10000) return number;
        } catch (Exception ignored) { }
        field.setError(error);
        return -1;
    }

    private LinearLayout vertical() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout horizontal() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout result = vertical();
        result.setPadding(dp(16), dp(16), dp(16), dp(16));
        result.setBackground(shape(CARD, 18, BORDER));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(12), 0, 0);
        parent.addView(result, params);
        return result;
    }

    private void title(LinearLayout parent, String eyebrow, String heading, String description) {
        TextView overline = text(eyebrow, 10, PURPLE, true);
        overline.setLetterSpacing(.16f);
        overline.setPadding(0, dp(7), 0, dp(7));
        parent.addView(overline);
        TextView mainTitle = text(heading, 28, TEXT, true);
        mainTitle.setAccessibilityHeading(true);
        parent.addView(mainTitle);
        TextView sub = text(description, 13, MUTED, false);
        sub.setPadding(0, dp(7), 0, dp(4));
        parent.addView(sub);
    }

    private void section(LinearLayout parent, String heading, String description) {
        TextView title = text(heading, 16, TEXT, true);
        title.setAccessibilityHeading(true);
        parent.addView(title);
        if (!description.isEmpty()) {
            gap(parent, 6);
            parent.addView(text(description, 12, MUTED, false));
        }
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private Button button(LinearLayout parent, String title, boolean primary, Runnable action) {
        Button button = makeButton(title, primary, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(10), 0, 0);
        parent.addView(button, params);
        return button;
    }

    private Button rowButton(LinearLayout parent, String title, Runnable action) {
        Button button = makeButton(title, false, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(dp(3), dp(8), dp(3), 0);
        parent.addView(button, params);
        return button;
    }

    private Button makeButton(String title, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(primary ? Color.WHITE : TEXT);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), dp(9), dp(8), dp(9));
        button.setBackground(ripple(primary ? PRIMARY : RAISED, 11));
        button.setContentDescription(title);
        button.setOnClickListener(view -> runSafely(action));
        return button;
    }

    private void runSafely(Runnable action) {
        try { action.run(); }
        catch (Exception error) { toast("操作未完成：" + message(error)); }
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(color, radius, 0), shape(Color.WHITE, radius, 0));
    }

    private void gap(LinearLayout parent, int size) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size))); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private String time(long timestamp) { return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(timestamp)); }
    private String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private String join(String[] values, String delimiter) { StringBuilder result = new StringBuilder(); for (String value : values) { if (result.length() > 0) result.append(delimiter); result.append(value); } return result.toString(); }
    private String appLabel(String packageName) {
        try { ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0); return getPackageManager().getApplicationLabel(info) + " · " + packageName; }
        catch (Exception ignored) { return packageName + "（未安装或不可访问）"; }
    }

    /** Small consistent vector marks, without a font or bitmap asset dependency. */
    private static final class IconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int type;
        IconView(Context context, int type, int color) { super(context); this.type = type; paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.7f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 24f, getHeight() / 24f);
            if (type == 0) {
                canvas.drawRoundRect(3, 4, 21, 20, 3, 3, paint);
                canvas.drawLine(8, 4, 8, 20, paint); canvas.drawLine(16, 4, 16, 20, paint);
                canvas.drawLine(3, 12, 21, 12, paint);
            } else if (type == 1) {
                canvas.drawRoundRect(5, 3, 19, 21, 2, 2, paint);
                canvas.drawLine(9, 8, 15, 8, paint); canvas.drawLine(9, 12, 15, 12, paint); canvas.drawLine(9, 16, 13, 16, paint);
            } else if (type == 2) {
                canvas.drawLine(4, 20, 20, 20, paint); canvas.drawLine(6, 16, 6, 11, paint); canvas.drawLine(12, 16, 12, 5, paint); canvas.drawLine(18, 16, 18, 8, paint);
            } else if (type == 3) {
                Path p = new Path(); p.moveTo(4, 4); p.lineTo(20, 4); p.lineTo(20, 17); p.lineTo(12, 17); p.lineTo(7, 21); p.lineTo(7, 17); p.lineTo(4, 17); p.close(); canvas.drawPath(p, paint);
                canvas.drawLine(8, 9, 16, 9, paint); canvas.drawLine(8, 13, 13, 13, paint);
            } else if (type == 4) {
                canvas.drawLine(4, 6, 20, 6, paint); canvas.drawLine(4, 12, 20, 12, paint); canvas.drawLine(4, 18, 20, 18, paint);
                canvas.drawCircle(9, 6, 2, paint); canvas.drawCircle(16, 12, 2, paint); canvas.drawCircle(8, 18, 2, paint);
            } else {
                Path p = new Path(); p.moveTo(3, 3); p.lineTo(10, 7); p.lineTo(14, 7); p.lineTo(21, 3); p.lineTo(20, 15); p.lineTo(12, 22); p.lineTo(4, 15); p.close(); canvas.drawPath(p, paint);
                canvas.drawLine(7, 11, 10, 13, paint); canvas.drawLine(17, 11, 14, 13, paint); canvas.drawLine(10, 17, 14, 17, paint);
            }
            canvas.restore();
        }
    }
}
