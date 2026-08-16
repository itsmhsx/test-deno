package com.mhsx.actorsticker;

import android.graphics.Bitmap;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Fast pass detector: no landmarks/contours/classification. */
final class V070QuickFaceDetector implements AutoCloseable {
    private final FaceDetector detector;

    V070QuickFaceDetector(float minFaceSize) {
        float min = Math.max(0.035f, Math.min(0.24f, minFaceSize));
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(min)
                .build();
        detector = FaceDetection.getClient(options);
    }

    List<Face> detect(Bitmap bitmap) throws Exception {
        return Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 12, TimeUnit.SECONDS);
    }

    @Override public void close() {
        try { detector.close(); } catch (Throwable ignored) {}
    }
}
