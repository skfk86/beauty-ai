package com.rosiva.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * قراءة النص من صورة على الجهاز (Google ML Kit — مجاني، بدون إنترنت).
 * يُستخدم لقراءة اسم المنتج والمكونات من صورة العبوة.
 */
@CapacitorPlugin(name = "TextScan")
public class TextScanPlugin extends Plugin {

    private TextRecognizer recognizer;

    @Override
    public void load() {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @PluginMethod
    public void recognize(PluginCall call) {
        String b64 = call.getString("image");
        if (b64 == null || b64.isEmpty()) { call.reject("Missing image", "NO_IMAGE"); return; }
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1);

        Bitmap bmp;
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            // تصغير الصور الكبيرة لتوفير الذاكرة على الأجهزة الضعيفة
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 2000) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o2);
        } catch (Exception e) {
            call.reject("Bad image", "BAD_IMAGE"); return;
        }
        if (bmp == null) { call.reject("Bad image", "BAD_IMAGE"); return; }

        int rotation = call.getInt("rotation", 0);
        recognizer.process(InputImage.fromBitmap(bmp, rotation))
            .addOnSuccessListener((Text result) -> {
                JSObject res = new JSObject();
                res.put("text", result.getText());
                JSArray lines = new JSArray();
                for (Text.TextBlock block : result.getTextBlocks())
                    for (Text.Line line : block.getLines()) lines.put(line.getText());
                res.put("lines", lines);
                call.resolve(res);
            })
            .addOnFailureListener(e -> call.reject("OCR failed: " + e.getMessage(), "OCR_FAILED"));
    }
}
