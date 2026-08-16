package com.mhsx.actorsticker;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * v0.8 offline identity embedding. The model is bundled in the APK and runs
 * fully on-device. It expects a 112x112 RGB face crop normalized to [-1, 1]
 * and returns a 192-D MobileFaceNet embedding which is L2-normalized here.
 */
final class V080FaceEmbedder {
    private static final String MODEL = "models/mobilefacenet.tflite";
    private static volatile V080FaceEmbedder instance;
    private final Interpreter interpreter;
    private final ByteBuffer input = ByteBuffer.allocateDirect(1 * 112 * 112 * 3 * 4).order(ByteOrder.nativeOrder());
    private final float[][] output = new float[1][192];

    private V080FaceEmbedder(Context context) throws Exception {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
        options.setUseXNNPACK(true);
        interpreter = new Interpreter(loadModel(context, MODEL), options);
        int[] in = interpreter.getInputTensor(0).shape();
        int[] out = interpreter.getOutputTensor(0).shape();
        if (in.length != 4 || in[1] != 112 || in[2] != 112 || in[3] != 3)
            throw new IllegalStateException("Unexpected MobileFaceNet input shape");
        if (out.length < 2 || out[out.length - 1] != 192)
            throw new IllegalStateException("Unexpected MobileFaceNet output shape");
    }

    static float[] embedding(Bitmap src, Rect box) {
        try {
            Context c = V080App.context();
            if (c == null || src == null || box == null) return null;
            V080FaceEmbedder x = instance;
            if (x == null) {
                synchronized (V080FaceEmbedder.class) {
                    x = instance;
                    if (x == null) instance = x = new V080FaceEmbedder(c);
                }
            }
            return x.run(src, box);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean available() {
        try {
            Context c = V080App.context();
            if (c == null) return false;
            c.getAssets().open(MODEL).close();
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private synchronized float[] run(Bitmap src, Rect box) {
        Rect r = FaceEngine.padded(box, src.getWidth(), src.getHeight(), 0.24f);
        if (r.width() < 20 || r.height() < 20) return null;
        Bitmap crop = null, square = null, scaled = null;
        try {
            crop = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height());
            int side = Math.min(crop.getWidth(), crop.getHeight());
            int left = Math.max(0, (crop.getWidth() - side) / 2);
            int top = Math.max(0, Math.round((crop.getHeight() - side) * 0.40f));
            if (top + side > crop.getHeight()) top = crop.getHeight() - side;
            square = Bitmap.createBitmap(crop, left, Math.max(0, top), side, side);
            scaled = Bitmap.createScaledBitmap(square, 112, 112, true);

            input.rewind();
            int[] pixels = new int[112 * 112];
            scaled.getPixels(pixels, 0, 112, 0, 0, 112, 112);
            for (int c : pixels) {
                input.putFloat(Color.red(c) / 127.5f - 1f);
                input.putFloat(Color.green(c) / 127.5f - 1f);
                input.putFloat(Color.blue(c) / 127.5f - 1f);
            }
            interpreter.run(input, output);
            float[] v = output[0].clone();
            FaceEngine.normalize(v);
            return v;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (scaled != null && scaled != square && !scaled.isRecycled()) scaled.recycle();
            if (square != null && square != crop && !square.isRecycled()) square.recycle();
            if (crop != null && !crop.isRecycled()) crop.recycle();
        }
    }

    private static MappedByteBuffer loadModel(Context context, String asset) throws Exception {
        AssetFileDescriptor afd = context.getAssets().openFd(asset);
        try (FileInputStream in = new FileInputStream(afd.getFileDescriptor()); FileChannel channel = in.getChannel()) {
            return channel.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength());
        } finally { afd.close(); }
    }
}
