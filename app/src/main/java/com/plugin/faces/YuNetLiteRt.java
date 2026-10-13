package com.plugin.faces;

import android.content.Context;
import com.google.ai.edge.litert.*;
import java.util.*;

/** 기존 LiteRT 런타임의 GPU 전용 세션으로 YuNet 최댓값 점수를 계산합니다. */
public final class YuNetLiteRt implements AutoCloseable {
    private Environment environment;
    private CompiledModel model;
    private List<TensorBuffer> inputs = Collections.emptyList();
    private List<TensorBuffer> outputs = Collections.emptyList();

    /** 정적 입력을 쓰는 검증된 내장 모델만 로드하며 CPU 대체는 호출자가 담당합니다. */
    public YuNetLiteRt(Context context, int size) throws Exception {
        this(context, size, true);
    }

    /** GPU 없는 검사 환경에서도 같은 Android 텐서·모델 연결을 CPU로 검증합니다. */
    public YuNetLiteRt(Context context, int size, boolean gpu) throws Exception {
        try {
            environment = Environment.create(context);
            if (gpu && !environment.getAvailableAccelerators().contains(Accelerator.GPU))
                throw new IllegalStateException("LiteRT GPU를 사용할 수 없습니다.");
            CompiledModel.Options options = new CompiledModel.Options(Collections.singleton(gpu ? Accelerator.GPU : Accelerator.CPU));
            options.setCpuOptions(new CompiledModel.CpuOptions(1, null, null));
            model = CompiledModel.create(context.getAssets(), "face-sort/yunet-" + size + ".tflite", options, environment);
            if (!model.getInputTensorType("input", "").getLayout().getDimensions().equals(Arrays.asList(1, size, size, 3))
                    || model.getInputTensorType("input", "").getElementType() != TensorType.ElementType.FLOAT)
                throw new IllegalArgumentException("YuNet BGR NHWC FP32 모델이 필요합니다.");
            inputs = model.createInputBuffers(0);
            outputs = model.createOutputBuffers(0);
            if (inputs.size() != 1 || outputs.size() != 1)
                throw new IllegalArgumentException("YuNet 단일 점수 출력이 필요합니다.");
        } catch (Exception | LinkageError failure) { close(); throw failure; }
    }

    /** 스캔의 동일 전용 스레드에서 GPU 버퍼와 단일 점수 출력을 재사용합니다. */
    public float score(float[] values) throws Exception {
        inputs.get(0).writeFloat(values);
        model.run(inputs, outputs, 0);
        float[] result = outputs.get(0).readFloat();
        if (result.length != 1 || !Float.isFinite(result[0]) || result[0] < 0 || result[0] > 1)
            throw new IllegalStateException("YuNet GPU 점수가 유효하지 않습니다.");
        return result[0];
    }

    /** 초기화 실패와 정상 종료에서 생성된 네이티브 자원을 모두 해제합니다. */
    @Override public void close() {
        for (TensorBuffer buffer : inputs) buffer.close();
        for (TensorBuffer buffer : outputs) buffer.close();
        inputs = Collections.emptyList(); outputs = Collections.emptyList();
        if (model != null) { model.close(); model = null; }
        if (environment != null) { environment.close(); environment = null; }
    }
}
