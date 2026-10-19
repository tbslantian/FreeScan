package com.wibo.doc.jni;

import android.graphics.Bitmap;

/** JNI declarations for the local native image-filter adapter. */
public final class DocFilters {
    static {
        System.loadLibrary("doc_sdk_v1.6.2.1");
    }

    private DocFilters() {}

    public static native void EnhanceBrightness(Bitmap bitmap);

    public static native void EnhanceGray(Bitmap bitmap);

    public static native void EnhanceImg(Bitmap bitmap, float softening);
}
