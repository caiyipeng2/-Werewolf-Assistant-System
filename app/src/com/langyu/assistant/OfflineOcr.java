package com.langyu.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Offline, bounded screenshot OCR. Construct, recognize and close on the same worker. */
public final class OfflineOcr implements AutoCloseable {
    private static final String[] LANGUAGES = {"chi_sim", "eng"};
    private static final long[] MODEL_LENGTHS = {2469156L, 4113088L};
    private static final String[] MODEL_HASHES = {
        "a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730",
        "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
    };
    private static final Object EXTRACTION_LOCK = new Object();
    private final Thread owner;
    private TessBaseAPI api;

    public OfflineOcr(Context context) throws Exception {
        owner = Thread.currentThread();
        File root = new File(context.getFilesDir(), "ocr-fast-87416418");
        synchronized (EXTRACTION_LOCK) {
            File data = new File(root, "tessdata");
            if (!data.isDirectory() && !data.mkdirs()) {
                throw new IOException("无法创建离线截图识别目录");
            }
            for (int i = 0; i < LANGUAGES.length; i++) {
                extract(context, data, i);
            }
        }
        TessBaseAPI created = new TessBaseAPI();
        try {
            // tessdata_fast contains only LSTM models, not the legacy engine.
            // The Chinese model already recognizes Arabic seat numbers. The English model
            // competed with Chinese UI labels in native device checks, so keep it bundled
            // for compatibility without enabling it for the Chinese game screen pass.
            if (!created.init(root.getAbsolutePath(), "chi_sim", TessBaseAPI.OEM_LSTM_ONLY)) {
                throw new IOException("无法初始化离线中英文截图识别");
            }
            created.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
            created.setVariable("tessedit_do_invert", "1");
            api = created;
        } finally {
            if (api == null) created.recycle();
        }
    }

    /** Bundled data availability; native initialization happens only on the OCR worker. */
    public static boolean ready(Context context) {
        for (String language : LANGUAGES) {
            try (InputStream input = context.getAssets().open("ocr/" + language + ".traineddata")) {
                if (input.read() < 0) return false;
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    /** Returns actual native text-line boxes and confidence, mapped to the input bitmap. */
    public List<ScreenRecognition.Line> recognize(Bitmap bitmap) throws Exception {
        checkThread();
        if (api == null) throw new IllegalStateException("离线截图识别已经关闭");
        if (bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("截图无效");
        Bitmap prepared = prepare(bitmap);
        ResultIterator iterator = null;
        ArrayList<ScreenRecognition.Line> result = new ArrayList<>();
        try {
            api.setImage(prepared);
            api.getUTF8Text(); // Execute recognition before asking for the result iterator.
            iterator = api.getResultIterator();
            if (iterator == null) return result;
            int level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE;
            double scaleX = (double) bitmap.getWidth() / prepared.getWidth();
            double scaleY = (double) bitmap.getHeight() / prepared.getHeight();
            iterator.begin();
            do {
                if (Thread.currentThread().isInterrupted()) break;
                String text = iterator.getUTF8Text(level);
                int[] box = iterator.getBoundingBox(level);
                if (text == null || box == null || box.length < 4) continue;
                text = text.replace('\n', ' ').replace('\r', ' ').trim();
                if (text.isEmpty()) continue;
                int left = clamp((int) Math.floor(box[0] * scaleX), 0, bitmap.getWidth());
                int top = clamp((int) Math.floor(box[1] * scaleY), 0, bitmap.getHeight());
                int right = clamp((int) Math.ceil(box[2] * scaleX), 0, bitmap.getWidth());
                int bottom = clamp((int) Math.ceil(box[3] * scaleY), 0, bitmap.getHeight());
                if (right <= left || bottom <= top) continue;
                int confidence = clamp(Math.round(iterator.confidence(level)), 0, 100);
                result.add(new ScreenRecognition.Line(text, left, top, right, bottom, confidence));
                // A screenshot should contain only a few dozen useful labels.
                if (result.size() >= 300) break;
            } while (iterator.next(level));
            return result;
        } finally {
            if (iterator != null) iterator.delete();
            api.clear();
            prepared.recycle();
        }
    }

    /** Optional second pass for dense card layouts; uses the same single-threaded engine. */
    public List<ScreenRecognition.Line> recognizeDense(Bitmap bitmap) throws Exception {
        checkThread();
        if (api == null) throw new IllegalStateException("离线截图识别已经关闭");
        try {
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK);
            return recognize(bitmap);
        } finally {
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
        }
    }

    @Override public void close() {
        checkThread();
        if (api != null) {
            api.recycle();
            api = null;
        }
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("离线截图识别必须在同一个工作线程使用和关闭");
        }
    }

    private static Bitmap prepare(Bitmap source) {
        // Preserve RGB and native/default DPI. Device comparisons showed that slightly
        // changing the glyph scale can split a readable Chinese identity into low-quality
        // fragments. Use screen width for portrait/near-square images and screen height
        // for wide landscape images, matching the verified 720px UI-width baseline.
        int reference = source.getWidth() > source.getHeight() * 1.5
                ? source.getHeight() : source.getWidth();
        double scale = Math.min(2.0, Math.max(1.0,
                720.0 / reference));
        scale = Math.min(scale, 2560.0 / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        Bitmap target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(target);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, null, new Rect(0, 0, width, height), paint);
        return target;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void extract(Context context, File directory, int index) throws Exception {
        String filename = LANGUAGES[index] + ".traineddata";
        File destination = new File(directory, filename);
        if (validModel(destination, index)) return;
        File temporary = new File(directory, filename + ".copying");
        try {
            try (InputStream input = context.getAssets().open("ocr/" + filename);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            }
            if (!validModel(temporary, index)) throw new IOException("离线截图识别模型校验失败");
            if (destination.exists() && !destination.delete()) throw new IOException("无法更新截图识别模型");
            if (!temporary.renameTo(destination)) throw new IOException("无法保存截图识别模型");
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static boolean validModel(File file, int index) throws Exception {
        if (!file.isFile() || file.length() != MODEL_LENGTHS[index]) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return MODEL_HASHES[index].equals(hex.toString());
    }
}
