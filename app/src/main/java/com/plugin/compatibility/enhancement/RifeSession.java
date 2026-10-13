package com.plugin.compatibility.enhancement;

import android.content.Context;
import android.graphics.Bitmap;
import java.nio.ByteBuffer;

/** LiteRT와 독립적인 ncnn Vulkan GPU 세션을 한 동영상에서 재사용합니다. */
public final class RifeSession implements AutoCloseable {
    static { System.loadLibrary("seal_rife"); }
    private long handle;
    private ByteBuffer first, second, output;
    private int[] pixels;

    /** 실제 장치에서는 소프트웨어 Vulkan을 GPU로 표시하거나 사용하지 않습니다. */
    public RifeSession(Context context, String modelPath) { this(context, modelPath, false); }

    /** CPU 기준 추론은 별도 검사 패키지의 모델 계약 검사에 한정합니다. */
    public RifeSession(Context context, String modelPath, boolean referenceTest) {
        if (referenceTest && !context.getPackageName().endsWith(".devicecheck")) throw new IllegalArgumentException("전용 검사 앱만 허용합니다.");
        java.io.File directory = new java.io.File(modelPath);
        if (modelPath.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 200 || !directory.isDirectory()
                || new java.io.File(directory, "flownet.param").length() != 16532
                || new java.io.File(directory, "flownet.bin").length() != 10614320)
            throw new IllegalArgumentException("RIFE 4.6 모델 경로·파일이 올바르지 않습니다.");
        handle = nativeCreate(modelPath, referenceTest);
    }

    /** 두 원본 사이 시각의 RGB 보간을 실행하며 호출자의 비트맵은 변경하지 않습니다. */
    public Bitmap interpolate(Bitmap a, Bitmap b, float position) {
        int width = a.getWidth(), height = a.getHeight();
        long bytes = (long)width * height * 3;
        if (handle == 0 || a.isRecycled() || b.isRecycled() || width != b.getWidth() || height != b.getHeight()
                || bytes > 50331648 || !(position > 0 && position < 1)) throw new IllegalArgumentException("잘못된 프레임 보간 입력입니다.");
        if (first == null || first.capacity() != bytes) {
            first = ByteBuffer.allocateDirect((int)bytes); second = ByteBuffer.allocateDirect((int)bytes);
            output = ByteBuffer.allocateDirect((int)bytes); pixels = new int[width * height];
        }
        pack(a, first); pack(b, second);
        nativeRun(handle, first, second, output, width, height, position);
        output.rewind();
        for (int i = 0; i < pixels.length; ++i) {
            int r = output.get() & 255, g = output.get() & 255, blue = output.get() & 255;
            pixels[i] = 0xff000000 | r << 16 | g << 8 | blue;
        }
        Bitmap image = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        image.setHasAlpha(false); return image;
    }

    /** 미디어 프레임의 ARGB를 모델의 RGB 순서로 변환합니다. */
    private void pack(Bitmap image, ByteBuffer buffer) {
        image.getPixels(pixels, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight()); buffer.rewind();
        for (int pixel : pixels) { buffer.put((byte)(pixel >>> 16)); buffer.put((byte)(pixel >>> 8)); buffer.put((byte)pixel); }
        buffer.rewind();
    }

    /** 네이티브 모델을 먼저 닫고 CPU 프레임 버퍼 참조를 반환합니다. */
    @Override public void close() { if (handle != 0) { nativeClose(handle); handle = 0; } first = second = output = null; pixels = null; }

    /** 검증된 모델 폴더에서 GPU 세션을 생성합니다. */
    private static native long nativeCreate(String path, boolean referenceTest);
    /** 직접 RGB 버퍼를 Vulkan 모델로 처리합니다. */
    private static native void nativeRun(long handle, ByteBuffer a, ByteBuffer b, ByteBuffer output, int width, int height, float position);
    /** 세션이 소유한 모델을 해제합니다. */
    private static native void nativeClose(long handle);
}
