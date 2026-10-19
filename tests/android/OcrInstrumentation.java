package com.langyu.assistant;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Test-only runner: native Chinese OCR over clearly synthetic drawn UI, no device capture. */
public final class OcrInstrumentation extends Instrumentation {
    private boolean compare;
    private boolean compareDark;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        compare = arguments != null && "true".equals(arguments.getString("compare"));
        compareDark = arguments != null && "true".equals(arguments.getString("compareDark"));
        start();
    }

    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            if (compare || compareDark) {
                String report = compareModels(getTargetContext(), compareDark);
                output.putString("result", "DIAGNOSTIC");
                output.putString("stream", "\nOCR model/PSM diagnostic (no board PASS implied)\n" + report + "\n");
                finish(Activity.RESULT_OK, output);
                return;
            }
            String report = verify(getTargetContext());
            output.putString("ocr", report);
            output.putString("result", "PASS");
            output.putString("stream", "\nPASS native own-card OCR and persistence; board status is reported separately below\n" + report + "\n");
            finish(Activity.RESULT_OK, output);
        } catch (Throwable error) {
            output.putString("result", "FAIL");
            output.putString("error", android.util.Log.getStackTraceString(error));
            output.putString("stream", "\nFAIL offline OCR validation\n" + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, output);
        }
    }

    public static String verify(Context context) throws Exception {
        if (!OfflineOcr.ready(context)) throw new AssertionError("Bundled OCR assets unavailable");
        StringBuilder report = new StringBuilder("Synthetic native Chinese OCR; ");
        List<ScreenRecognition.BoardSpec> boards = new ArrayList<>();
        for (Repository.Board board : Repository.get(context).boards()) {
            boards.add(new ScreenRecognition.BoardSpec(board.id, board.name, board.roles, board.wolves));
        }
        try (OfflineOcr ocr = new OfflineOcr(context)) {
            checkFixture(ocr, false, 1080, boards, report);
            checkFixture(ocr, true, 540, boards, report);
            checkChat(ocr, boards, report);
        }
        checkPersistence(report);
        return report.toString();
    }

    private static void checkFixture(OfflineOcr ocr, boolean dark, int width,
                                     List<ScreenRecognition.BoardSpec> boards, StringBuilder report) throws Exception {
        int height = width * 5 / 6;
        float unit = width / 1080f;
        Bitmap source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(source);
            canvas.drawColor(dark ? Color.rgb(20, 24, 36) : Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(54 * unit);
            paint.setColor(dark ? Color.rgb(255, 224, 145) : Color.BLACK);
            String[] rows = {"狼人杀", "12人标准预女猎白", "我的座位：7号", "我的身份：预言家", "第1天 白天"};
            for (int i = 0; i < rows.length; i++) {
                canvas.drawText(rows[i], 70 * unit, (130 + i * 145) * unit, paint);
            }
            List<ScreenRecognition.Line> lines = ocr.recognize(source);
            boolean seat = false, role = false;
            int confident = 0;
            for (ScreenRecognition.Line line : lines) {
                if (line.left < 0 || line.top < 0 || line.right > width || line.bottom > height
                        || line.right <= line.left || line.bottom <= line.top) {
                    throw new AssertionError("Invalid native line bounding box");
                }
                if (line.confidence > 0) confident++;
                String text = line.text.replaceAll("\\s+", "");
                if (text.contains("7") && line.top > 300 * unit && line.bottom < 470 * unit) seat = true;
                if (text.contains("预言家") && line.top > 470 * unit && line.bottom < 620 * unit) role = true;
                report.append('[').append(dark ? "dark" : "light").append(':')
                        .append(line.text).append('@').append(line.left).append(',')
                        .append(line.top).append(',').append(line.right).append(',')
                        .append(line.bottom).append('/').append(line.confidence).append(']');
            }
            if (!seat || !role || confident < 3 || lines.size() < 3) {
                throw new AssertionError("Chinese multiline OCR did not identify fixture labels: " + report);
            }
            ScreenRecognition.Result parsed = ScreenRecognition.parse(lines, width, height, boards);
            if (!parsed.gameScreen || parsed.seat != 7 || !"预言家".equals(parsed.role)
                    || parsed.confidence < 70) {
                throw new AssertionError("Actual native OCR did not parse own 7/seer labels: seat="
                        + parsed.seat + "; role=" + parsed.role + "; board=" + parsed.boardId
                        + "; confidence=" + parsed.confidence + "; lines=" + report);
            }
            report.append(" PASS actual own-card OCR -> parse: 7/预言家, confidence=")
                    .append(parsed.confidence).append(';');
            if (parsed.boardId.isEmpty()) {
                report.append(" UNRESOLVED board OCR; board recognition has NOT passed;");
            } else {
                require("classic_12".equals(parsed.boardId) && parsed.players == 12,
                        "Board OCR must never be accepted as an incorrect board");
                report.append(" PASS actual board OCR -> classic_12/12;");
            }
            if (source.isRecycled()) throw new AssertionError("OCR recycled caller-owned screenshot");
        } finally {
            source.recycle();
        }
    }

    private static void checkChat(OfflineOcr ocr, List<ScreenRecognition.BoardSpec> boards,
                                  StringBuilder report) throws Exception {
        Bitmap source = Bitmap.createBitmap(1080, 900, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(source);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(54);
            paint.setColor(Color.BLACK);
            String[] rows = {"聊天测试画面", "聊天：我的座位：9号", "聊天：我的身份：女巫", "9号说：昨天我投了7号"};
            for (int i = 0; i < rows.length; i++) canvas.drawText(rows[i], 70, 130 + i * 145, paint);
            List<ScreenRecognition.Line> lines = ocr.recognize(source);
            ScreenRecognition.Result parsed = ScreenRecognition.parse(lines, 1080, 900, boards);
            if (parsed.gameScreen || parsed.seat != 0 || !parsed.role.isEmpty() || !parsed.boardId.isEmpty()) {
                throw new AssertionError("Unrelated public chat was interpreted as a private match card");
            }
            if (lines.isEmpty()) throw new AssertionError("Chat negative OCR fixture was unreadable");
            report.append(" PASS actual unrelated-chat OCR rejected;");
        } finally {
            source.recycle();
        }
    }

    /** No Repository.state assignment, save, load, archive or settings mutation. */
    private static void checkPersistence(StringBuilder report) throws Exception {
        GameState source = new GameState();
        source.mySeat = 7;
        source.myRole = "预言家";
        source.screenLocked = true;
        source.screenEvidence = "我的座位：7号；我的身份：预言家";
        source.screenRecognizedAt = 1792396800123L;
        source.screenBoardId = "classic_12";
        source.screenAutoLockSeat = 7;
        source.screenAutoLockRole = "预言家";
        source.speaker = 5;
        source.locks.put(7, "预言家");
        JSONObject encoded = Repository.encode(source);
        GameState decoded = Repository.decode(new JSONObject(encoded.toString()));
        require(decoded.mySeat == 7 && decoded.speaker == 5, "Own seat must persist independently of active speaker");
        require("预言家".equals(decoded.myRole) && decoded.screenLocked, "Own role and lock must survive roundtrip");
        require(source.screenEvidence.equals(decoded.screenEvidence)
                && source.screenRecognizedAt == decoded.screenRecognizedAt
                && source.screenBoardId.equals(decoded.screenBoardId), "Screen evidence/time/board must survive roundtrip");
        require(decoded.screenAutoLockSeat == 7 && "预言家".equals(decoded.screenAutoLockRole)
                && "预言家".equals(decoded.locks.get(7)), "Automatic identity lock ownership must survive roundtrip");
        require(encoded.toString().equals(Repository.encode(decoded).toString()), "New snapshot must roundtrip exactly");

        JSONObject old = new JSONObject(encoded.toString());
        for (String key : new String[]{"mySeat", "myRole", "screenLocked", "screenEvidence", "screenRecognizedAt",
                "screenBoardId", "screenAutoLockSeat", "screenAutoLockRole"}) old.remove(key);
        old.put("version", 1);
        GameState migrated = Repository.decode(old);
        require(migrated.mySeat == 0 && migrated.myRole.isEmpty() && !migrated.screenLocked,
                "v1 has no fabricated own identity or screen lock");
        require(migrated.screenEvidence.isEmpty() && migrated.screenRecognizedAt == 0
                && migrated.screenBoardId.isEmpty() && migrated.screenAutoLockSeat == 0
                && migrated.screenAutoLockRole.isEmpty(), "v1 screen defaults must be empty");
        require(source.id.equals(migrated.id) && migrated.speaker == 5
                && migrated.roles.length == source.roles.length, "v1 migration must retain existing match data");
        report.append(" PASS pure-memory v2 field roundtrip and v1 empty defaults; live repository untouched;");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Compare unchanged synthetic input across languages, segmentation and glyph scales. */
    private static String compareModels(Context context, boolean dark) throws Exception {
        // Production constructor verifies and extracts the exact bundled models first.
        try (OfflineOcr ignored = new OfflineOcr(context)) { }
        StringBuilder report = new StringBuilder();
        int width = dark ? 540 : 1080;
        int height = dark ? 450 : 900;
        float unit = width / 1080f;
        Bitmap original = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(original);
            canvas.drawColor(dark ? Color.rgb(20, 24, 36) : Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(54 * unit);
            paint.setColor(dark ? Color.rgb(255, 224, 145) : Color.BLACK);
            String[] rows = {"狼人杀", "12人标准预女猎白", "我的座位：7号", "我的身份：预言家", "第1天 白天"};
            for (int i = 0; i < rows.length; i++) canvas.drawText(rows[i], 70 * unit, (130 + i * 145) * unit, paint);
            for (String languages : dark ? new String[]{"chi_sim"} : new String[]{"chi_sim+eng", "chi_sim"}) {
                TessBaseAPI nativeApi = new TessBaseAPI();
                try {
                    require(nativeApi.init(new java.io.File(context.getFilesDir(), "ocr-fast-87416418").getAbsolutePath(),
                            languages, TessBaseAPI.OEM_LSTM_ONLY), "Diagnostic model init failed");
                    for (int mode : dark ? new int[]{11, 6} : new int[]{11, 6, 3}) {
                        nativeApi.setPageSegMode(mode);
                        for (float scale : dark ? new float[]{1.3333333f, 2f} : new float[]{1f, 0.65f}) {
                          for (String transform : dark ? new String[]{"rgb", "invert", "grayInvert"} : new String[]{"rgb"}) {
                            Bitmap input = Bitmap.createBitmap(Math.round(width * scale),
                                    Math.round(height * scale), Bitmap.Config.ARGB_8888);
                            Paint converted = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                            if (!"rgb".equals(transform)) {
                                ColorMatrix matrix = new ColorMatrix(new float[]{
                                        -1,0,0,0,255, 0,-1,0,0,255, 0,0,-1,0,255, 0,0,0,1,0});
                                if ("grayInvert".equals(transform)) {
                                    ColorMatrix gray = new ColorMatrix();
                                    gray.setSaturation(0);
                                    matrix.postConcat(gray);
                                }
                                converted.setColorFilter(new ColorMatrixColorFilter(matrix));
                            }
                            new Canvas(input).drawBitmap(original, null,
                                    new android.graphics.Rect(0, 0, input.getWidth(), input.getHeight()), converted);
                            try {
                                nativeApi.setImage(input);
                                nativeApi.getUTF8Text();
                                report.append("\nMODEL=").append(languages).append(" PSM=").append(mode)
                                        .append(" SCALE=").append(scale).append(" TRANSFORM=").append(transform).append(' ');
                                ResultIterator iterator = nativeApi.getResultIterator();
                                if (iterator != null) try {
                                    iterator.begin();
                                    do {
                                        String line = iterator.getUTF8Text(2);
                                        report.append('[').append(line == null ? "" : line.replace('\n', ' ').trim())
                                                .append('/').append(Math.round(iterator.confidence(2))).append(']');
                                    } while (iterator.next(2));
                                } finally { iterator.delete(); }
                            } finally {
                                nativeApi.clear();
                                input.recycle();
                            }
                          }
                        }
                    }
                } finally { nativeApi.recycle(); }
            }
        } finally { original.recycle(); }
        return report.toString();
    }
}
