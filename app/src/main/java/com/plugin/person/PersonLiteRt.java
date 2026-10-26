package com.plugin.person;

import android.content.Context;
import com.google.ai.edge.litert.*;
import com.plugin.compatibility.enhancement.OptionalNpuProvider;
import java.io.File;
import java.util.*;

/** 같은 LiteRT 모델의 NPU·GPU·CPU 세션을 검증하고 버퍼를 재사용합니다. */
public final class PersonLiteRt implements AutoCloseable {
    private Environment environment;
    private CompiledModel model;
    private List<TensorBuffer> inputs = Collections.emptyList();
    private List<TensorBuffer> outputs = Collections.emptyList();
    private final int inputLength;
    private final int outputLength;

    /** 요청 가속기의 가용성·입출력 규격을 확인하고 실패한 부분 생성 자원도 반환합니다. */
    public PersonLiteRt(Context context, File file, int size, int dimensions, String accelerator) throws Exception {
        inputLength = size*size*3; outputLength = dimensions;
        try {
            Accelerator selected = Accelerator.valueOf(accelerator);
            if (selected == Accelerator.NPU) {
                OptionalNpuProvider provider = new OptionalNpuProvider(context);
                if (!provider.isDeviceSupported() || !provider.isLibraryReady())
                    throw new IllegalStateException("NPU 미지원 또는 Qualcomm 런타임 미설치");
                environment = Environment.create(context,provider);
            } else environment = Environment.create(context);
            if (selected != Accelerator.CPU && !environment.getAvailableAccelerators().contains(selected))
                throw new IllegalStateException(accelerator+" 가속기를 사용할 수 없습니다.");
            CompiledModel.Options options = new CompiledModel.Options(Collections.singleton(selected));
            options.setCpuOptions(new CompiledModel.CpuOptions(2,null,null));
            model = CompiledModel.create(file.getAbsolutePath(),options,environment);
            TensorType input = model.getInputTensorType("data", "");
            if (input.getElementType() != TensorType.ElementType.FLOAT ||
                    !input.getLayout().getDimensions().equals(Arrays.asList(1,size,size,3)))
                throw new IllegalArgumentException("얼굴 모델의 NHWC FP32 입력 규격이 다릅니다.");
            inputs = model.createInputBuffers(0); outputs = model.createOutputBuffers(0);
            if (inputs.size()!=1 || outputs.size()!=1)
                throw new IllegalArgumentException("단일 얼굴 모델 입출력이 필요합니다.");
        } catch (Exception | LinkageError failure) { close(); throw failure; }
    }

    /** 단일 작업 스레드에서 유한한 입력·출력과 출력 차원을 확인합니다. */
    public float[] run(float[] values) throws Exception {
        if (values.length != inputLength) throw new IllegalArgumentException("얼굴 모델 입력 크기가 다릅니다.");
        for (float value: values) if (!Float.isFinite(value)) throw new IllegalArgumentException("비정상 모델 입력입니다.");
        inputs.get(0).writeFloat(values); model.run(inputs,outputs,0);
        float[] result = outputs.get(0).readFloat();
        if (result.length != outputLength) throw new IllegalStateException("얼굴 모델 출력 크기가 다릅니다.");
        for (float value: result) if (!Float.isFinite(value)) throw new IllegalStateException("비정상 모델 출력입니다.");
        return result;
    }

    /** 생성·실행과 같은 스레드에서 텐서·모델·환경을 해제합니다. */
    @Override public void close() {
        for (TensorBuffer value: inputs) value.close();
        for (TensorBuffer value: outputs) value.close();
        inputs = Collections.emptyList(); outputs = Collections.emptyList();
        if (model!=null) { model.close(); model=null; }
        if (environment!=null) { environment.close(); environment=null; }
    }
}
