package com.plugin.validation;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import java.lang.reflect.InvocationTargetException;

/** Kotlin 검사 런타임과 독립적으로 실제 축소된 APK의 얼굴 모델을 검사합니다. */
public final class MediaPipeProbe extends Instrumentation {
    /** 앱 초기화를 생략하고 모델 JNI만 격리합니다. */
    @Override public Application newApplication(ClassLoader cl, String name, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return super.newApplication(cl, "android.app.Application", context);
    }

    /** 검사 진입 시 실제 검사를 시작합니다. */
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    /** CPU·GPU 각각 얼굴과 빈 화면을 검사하고 초기화 원인까지 출력합니다. */
    @Override public void onStart() {
        Bundle result = new Bundle(); StringBuilder output = new StringBuilder(); boolean success = true;
        Bitmap blank = Bitmap.createBitmap(321, 181, Bitmap.Config.ARGB_8888); blank.eraseColor(Color.GRAY);
        Bitmap portrait = null;
        try {
            if (!getTargetContext().getPackageName().endsWith(".devicecheck")) throw new IllegalStateException("전용 검사 앱만 허용합니다.");
            portrait = BitmapFactory.decodeStream(getContext().getAssets().open("portrait.jpg"));
            Class<?> type = Class.forName("com.plugin.faces.FaceDetector", true, getTargetContext().getClassLoader());
            for (String backend : new String[]{"cpu", "gpu"}) {
                Object detector = null;
                try {
                    detector = type.getConstructor(Context.class, String.class, float.class, int.class, String.class)
                        .newInstance(getTargetContext(), "mediapipe", 0.5f, 640, backend);
                    if (!(Boolean)type.getMethod("hasFace", Bitmap.class).invoke(detector, portrait)) throw new AssertionError("얼굴 미검출");
                    if ((Boolean)type.getMethod("hasFace", Bitmap.class).invoke(detector, blank)) throw new AssertionError("빈 화면 오검출");
                    output.append(backend).append(": ").append(type.getMethod("getEngine").invoke(detector)).append(" PASS\n");
                } catch (Throwable error) {
                    success = false;
                    while (error instanceof InvocationTargetException) error = error.getCause();
                    output.append(backend).append(": ").append(android.util.Log.getStackTraceString(error)).append('\n');
                } finally { if (detector != null) type.getMethod("close").invoke(detector); }
            }
        } catch (Throwable error) { success = false; output.append(android.util.Log.getStackTraceString(error)); }
        finally { blank.recycle(); if (portrait != null) portrait.recycle(); }
        result.putString("stream", output.toString()); finish(success ? -1 : 0, result);
    }
}
