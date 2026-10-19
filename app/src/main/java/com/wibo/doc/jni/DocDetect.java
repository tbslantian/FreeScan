package com.wibo.doc.jni;

/** JNI declarations required to exercise the local document detector and rectifier. */
public final class DocDetect {
    static {
        System.loadLibrary("doc_sdk_v1.6.2.1");
    }

    private DocDetect() {}

    public static native boolean initDocDetectModel(
            byte[] edgeModel,
            int engineMode,
            boolean useGpu,
            String preprocessCachePath,
            String detectorCachePath,
            int modelId,
            byte[] detectorModel
    );

    public static native Object[] runDocDetect(Object bitmap, Object[] points, int modelId);

    public static native Object runDocRectify(Object bitmap, Object[] points, Object bitmapConfig);

    public static native void releaseDocModel(int modelId);
}
