package com.mhsx.actorsticker;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;

import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceLandmark;

/** Landmark-based normalization before the local identity descriptor. */
final class V060AlignedDescriptor {
    private V060AlignedDescriptor() {}

    static float[] descriptor(FaceEngine engine, Bitmap frame, Face face) {
        if (engine == null || frame == null || face == null) return null;
        Rect box = face.getBoundingBox();
        if (box == null) return null;
        FaceLandmark l = face.getLandmark(FaceLandmark.LEFT_EYE);
        FaceLandmark r = face.getLandmark(FaceLandmark.RIGHT_EYE);
        if (l == null || r == null || l.getPosition() == null || r.getPosition() == null) return engine.descriptor(frame, box);
        PointF a = l.getPosition(), b = r.getPosition();
        float angle = (float)Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x));
        if (Math.abs(angle) < 2.2f || Math.abs(angle) > 28f) return engine.descriptor(frame, box);

        Rect padded = FaceEngine.padded(box, frame.getWidth(), frame.getHeight(), 0.22f);
        Bitmap crop = null, rotated = null;
        try {
            crop = Bitmap.createBitmap(frame, padded.left, padded.top, padded.width(), padded.height());
            Matrix m = new Matrix();
            m.postRotate(-angle, crop.getWidth()/2f, crop.getHeight()/2f);
            rotated = Bitmap.createBitmap(crop, 0, 0, crop.getWidth(), crop.getHeight(), m, true);
            Rect all = new Rect(0, 0, rotated.getWidth(), rotated.getHeight());
            return engine.descriptor(rotated, all);
        } catch (Throwable ignored) {
            return engine.descriptor(frame, box);
        } finally {
            if (rotated != null && rotated != crop && !rotated.isRecycled()) rotated.recycle();
            if (crop != null && !crop.isRecycled()) crop.recycle();
        }
    }
}
