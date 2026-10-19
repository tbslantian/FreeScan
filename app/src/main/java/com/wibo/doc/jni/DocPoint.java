package com.wibo.doc.jni;

/** Minimal JNI-compatible point carrier for the local scanner comparison build. */
public final class DocPoint {
    // These field names match the class layout expected by the bundled JNI library.
    public float f17546x;
    public float f17547y;

    public DocPoint(float x, float y) {
        this.f17546x = x;
        this.f17547y = y;
    }

    public float getX() {
        return f17546x;
    }

    public float getY() {
        return f17547y;
    }

    public void setX(float x) {
        f17546x = x;
    }

    public void setY(float y) {
        f17547y = y;
    }
}
