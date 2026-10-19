package com.langyu.assistant;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;

/** Synthetic MediaProjection source; exists only in the independent verification APK. */
public final class ScreenFixtureActivity extends Activity {
    private final Handler updates = new Handler();
    private FixtureView fixture;
    private Button roleButton, seatButton;
    private int seat = 7;
    private String role = "预言家", board = "12人标准预女猎白", mode = "game";
    private boolean hideRole, hideSeat;
    private boolean animate=true;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (fixture != null) fixture.invalidate();
            if(animate)updates.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(20, 24, 36));
        getWindow().setNavigationBarColor(Color.rgb(20, 24, 36));
        applyIntent(getIntent());
        if (saved != null) {
            hideRole = saved.getBoolean("hideRole", hideRole);
            hideSeat = saved.getBoolean("hideSeat", hideSeat);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 24, 36));
        fixture = new FixtureView();
        root.addView(fixture, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout controls = new LinearLayout(this);
        roleButton = new Button(this);
        roleButton.setOnClickListener(v -> { hideRole = !hideRole; updateControls(); });
        seatButton = new Button(this);
        seatButton.setOnClickListener(v -> { hideSeat = !hideSeat; updateControls(); });
        controls.addView(roleButton, new LinearLayout.LayoutParams(0, -2, 1f));
        controls.addView(seatButton, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(controls);
        setContentView(root);
        updateControls();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
        updateControls();
    }

    private void applyIntent(Intent intent) {
        if (intent.hasExtra("landscape")) setRequestedOrientation(intent.getBooleanExtra("landscape", false)
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        seat = Math.max(1, Math.min(20, intent.getIntExtra("seat", 7)));
        role = value(intent, "role", "预言家");
        board = value(intent, "board", "12人标准预女猎白");
        mode = value(intent, "mode", "game");
        hideRole = intent.getBooleanExtra("hideRole", false);
        hideSeat = intent.getBooleanExtra("hideSeat", false);
        animate = intent.getBooleanExtra("animate", true);
    }

    private static String value(Intent intent, String key, String fallback) {
        String text = intent.getStringExtra(key);
        if (text == null) return fallback;
        text = text.replace('\n', ' ').replace('\r', ' ');
        return text.substring(0, Math.min(40, text.length()));
    }

    private void updateControls() {
        roleButton.setText(hideRole ? "显示身份牌" : "隐藏身份牌");
        seatButton.setText(hideSeat ? "显示座位" : "隐藏座位");
        fixture.invalidate();
    }

    @Override protected void onResume() {
        super.onResume();
        updates.removeCallbacks(refresh);
        updates.post(refresh);
    }

    @Override protected void onPause() {
        updates.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("hideRole", hideRole);
        out.putBoolean("hideSeat", hideSeat);
        super.onSaveInstanceState(out);
    }

    private final class FixtureView extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        FixtureView() {
            super(ScreenFixtureActivity.this);
            text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            setContentDescription("模拟对局识别验证，独立测试画面");
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.rgb(20, 24, 36));
            float unit = getWidth() / 1080f;
            float vertical = Math.min(unit, getHeight() / 1030f);
            draw(canvas, "模拟对局识别验证", 72 * vertical, 42 * vertical,
                    Color.rgb(180, 190, 205), unit);
            if ("chat".equals(mode)) {
                draw(canvas, "聊天测试画面", 210 * vertical, 54 * vertical, Color.WHITE, unit);
                draw(canvas, "聊天：我的座位：9号", 350 * vertical, 50 * vertical, Color.WHITE, unit);
                draw(canvas, "聊天：我的身份：女巫", 490 * vertical, 50 * vertical, Color.WHITE, unit);
                draw(canvas, "9号说：昨天我投了7号", 630 * vertical, 50 * vertical, Color.WHITE, unit);
                draw(canvas, "这些文字并非本人的身份牌", 770 * vertical, 42 * vertical,
                        Color.rgb(180, 190, 205), unit);
            } else {
                int gold = Color.rgb(255, 224, 145);
                draw(canvas, "狼人杀", 220 * vertical, 64 * vertical, gold, unit);
                draw(canvas, board, 380 * vertical, 54 * vertical, gold, unit);
                if (!hideSeat) draw(canvas, "我的座位：" + seat + "号", 540 * vertical,
                        58 * vertical, gold, unit);
                if (!hideRole) draw(canvas, "我的身份：" + role, 700 * vertical,
                        58 * vertical, gold, unit);
                draw(canvas, "第1天 白天", 860 * vertical, 54 * vertical, gold, unit);
            }
            draw(canvas, "刷新帧 " + (SystemClock.elapsedRealtime() / 1000 % 10000),
                    getHeight() - 28 * unit, 28 * unit, Color.rgb(150, 165, 185), unit);
        }

        private void draw(Canvas canvas, String value, float baseline, float size,
                          int color, float unit) {
            text.setColor(color);
            text.setTextSize(size);
            canvas.drawText(value, 64 * unit, baseline, text);
        }
    }
}
