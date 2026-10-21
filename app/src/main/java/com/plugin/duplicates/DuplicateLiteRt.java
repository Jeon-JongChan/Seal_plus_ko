package com.plugin.duplicates;

import android.content.Context;
import com.google.ai.edge.litert.*;
import com.plugin.compatibility.enhancement.OptionalNpuProvider;
import java.util.*;

/** 검증된 설치 모델의 임베딩을 같은 입력 규칙으로 NPU·GPU·CPU에서 실행합니다. */
public final class DuplicateLiteRt implements AutoCloseable {
    private Environment environment;
    private CompiledModel model;
    private List<TensorBuffer> inputs = Collections.emptyList();
    private List<TensorBuffer> outputs = Collections.emptyList();
    private final int dimensions;

    /** 가속기별 세션을 독립 생성하며 실패한 세션의 네이티브 자원도 정리합니다. */
    public DuplicateLiteRt(Context context, String size, String accelerator) throws Exception {
        if (!size.equals("small") && !size.equals("large")) throw new IllegalArgumentException("지원하지 않는 임베딩 모델입니다.");
        dimensions = size.equals("small") ? 1024 : 1280;
        try {
            Accelerator selected = Accelerator.valueOf(accelerator);
            if (selected == Accelerator.NPU) {
                OptionalNpuProvider provider = new OptionalNpuProvider(context);
                if (!provider.isDeviceSupported() || !provider.isLibraryReady())
                    throw new IllegalStateException("NPU 미지원 또는 런타임 미설치");
                environment = Environment.create(context, provider);
            } else environment = Environment.create(context);
            if (selected != Accelerator.CPU && !environment.getAvailableAccelerators().contains(selected))
                throw new IllegalStateException(accelerator + " 가속기를 사용할 수 없습니다.");
            CompiledModel.Options options = new CompiledModel.Options(Collections.singleton(selected));
            options.setCpuOptions(new CompiledModel.CpuOptions(2, null, null));
            model = CompiledModel.create(DuplicateModels.INSTANCE.requireFile(context, size).getAbsolutePath(), options, environment);
            TensorType input = model.getInputTensorType("inputs", "serving_default");
            TensorType output = model.getOutputTensorType("feature_vector", "serving_default");
            if (input.getElementType() != TensorType.ElementType.FLOAT
                    || !input.getLayout().getDimensions().equals(Arrays.asList(1, 224, 224, 3))
                    || output.getElementType() != TensorType.ElementType.FLOAT
                    || !output.getLayout().getDimensions().equals(Arrays.asList(1, dimensions)))
                throw new IllegalArgumentException("MobileNetV3 임베딩 입출력이 일치하지 않습니다.");
            inputs = model.createInputBuffers(0);
            outputs = model.createOutputBuffers(0);
            if (inputs.size() != 1 || outputs.size() != 1) throw new IllegalArgumentException("단일 임베딩 입출력이 필요합니다.");
        } catch (Exception | LinkageError failure) { close(); throw failure; }
    }

    /** 모델 메타데이터의 RGB 0~1 입력으로 유한한 비영 임베딩을 계산합니다. */
    public float[] embed(float[] values) throws Exception {
        if (values.length != 224 * 224 * 3) throw new IllegalArgumentException("224×224 RGB 입력이 필요합니다.");
        inputs.get(0).writeFloat(values);
        model.run(inputs, outputs, 0);
        float[] vector = outputs.get(0).readFloat();
        if (vector.length != dimensions) throw new IllegalStateException("임베딩 차원이 다릅니다.");
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("임베딩에 비정상 값이 있습니다.");
            norm += (double) value * value;
        }
        if (norm <= 0) throw new IllegalStateException("영벡터 임베딩입니다.");
        return vector;
    }

    /** 생성·추론과 동일한 스레드에서 버퍼와 모델·환경을 해제합니다. */
    @Override public void close() {
        for (TensorBuffer buffer : inputs) buffer.close();
        for (TensorBuffer buffer : outputs) buffer.close();
        inputs = Collections.emptyList(); outputs = Collections.emptyList();
        if (model != null) { model.close(); model = null; }
        if (environment != null) { environment.close(); environment = null; }
    }
}
