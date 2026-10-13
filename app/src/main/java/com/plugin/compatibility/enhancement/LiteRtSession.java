package com.plugin.compatibility.enhancement;

import android.content.Context;
import com.google.ai.edge.litert.*;
import java.util.*;

/** LiteRT v2 공통 API의 실행 경로와 네이티브 자원 수명을 관리합니다. */
public final class LiteRtSession implements AutoCloseable {
    private Environment environment;
    private CompiledModel model;
    private List<TensorBuffer> inputs = Collections.emptyList();
    private List<TensorBuffer> outputs = Collections.emptyList();
    private String route;
    private int inputWidth, inputHeight, outputWidth, outputHeight;

    /** 제조사 NPU 제공자를 공통 환경에 연결하고 사용할 수 있는 NPU·GPU를 선택합니다. */
    public LiteRtSession(Context context, String path, boolean accelerated, int threads) throws Exception {
        this(context, path, accelerated, threads, true);
    }

    /** 종료된 NPU 경로를 재시도할 때는 제공자 로딩 자체를 건너뜁니다. */
    public LiteRtSession(Context context, String path, boolean accelerated, int threads, boolean allowNpu) throws Exception {
        try {
            if (accelerated && allowNpu) {
                try { environment = Environment.create(context, new OptionalNpuProvider(context)); }
                // NPU 런타임의 초기화 실패가 사용 가능한 GPU까지 막지 않게 합니다.
                catch (Exception | LinkageError unavailable) { environment = Environment.create(context); }
            } else environment = Environment.create(context);
            Set<Accelerator> selected = new LinkedHashSet<>();
            if (accelerated) {
                Set<Accelerator> available = environment.getAvailableAccelerators();
                if (allowNpu && available.contains(Accelerator.NPU)) selected.add(Accelerator.NPU);
                if (available.contains(Accelerator.GPU)) selected.add(Accelerator.GPU);
                if (selected.isEmpty()) throw new IllegalStateException("LiteRT 가속 장치를 사용할 수 없습니다.");
                route = selected.contains(Accelerator.NPU) ? "NPU·GPU 자동 경로" : "GPU 경로";
            } else { selected.add(Accelerator.CPU); route = "CPU · " + threads + " 스레드"; }
            CompiledModel.Options options = new CompiledModel.Options(selected);
            options.setCpuOptions(new CompiledModel.CpuOptions(threads, null, null));
            try { model = CompiledModel.create(path, options, environment); }
            catch (Exception | LinkageError unavailable) {
                if (!selected.contains(Accelerator.NPU) || !selected.contains(Accelerator.GPU)) throw unavailable;
                // NPU 컴파일 불가 모델은 같은 공통 API의 GPU 전용 경로로 다시 시도합니다.
                options = new CompiledModel.Options(Collections.singleton(Accelerator.GPU));
                options.setCpuOptions(new CompiledModel.CpuOptions(threads, null, null));
                model = CompiledModel.create(path, options, environment); route = "GPU 대체 경로";
            }
            TensorType input, output;
            try {
                input = model.getInputTensorType("input", ""); output = model.getOutputTensorType("output", "");
            } catch (Exception missingNames) {
                // ponytail: 내장 및 Qualcomm 공식 텐서 이름을 지원합니다. 다른 모델 이름은 공개 API 확장 시 지원합니다.
                input = model.getInputTensorType("image", ""); output = model.getOutputTensorType("upscaled_image", "");
            }
            if (!rgbTensor(input) || !rgbTensor(output)) throw new IllegalArgumentException("정적 NHWC RGB FP32 모델이 필요합니다.");
            List<Integer> inShape = input.getLayout().getDimensions(), outShape = output.getLayout().getDimensions();
            inputHeight = inShape.get(1); inputWidth = inShape.get(2);
            outputHeight = outShape.get(1); outputWidth = outShape.get(2);
            if (inputWidth < 48 || inputHeight < 48 || inputWidth > 256 || inputHeight > 256
                    || outputWidth % inputWidth != 0 || outputHeight % inputHeight != 0
                    || outputWidth / inputWidth != outputHeight / inputHeight
                    || outputWidth / inputWidth < 1 || outputWidth / inputWidth > 4)
                throw new IllegalArgumentException("48~256 크기의 정적 입력과 1~4배 출력을 지원합니다.");
            inputs = model.createInputBuffers(0); outputs = model.createOutputBuffers(0);
            if (inputs.size() != 1 || outputs.size() != 1) throw new IllegalArgumentException("단일 이미지 입출력이 필요합니다.");
        } catch (Exception | LinkageError failure) { close(); throw failure; }
    }

    /** 동적 입력·양자화 입출력·다른 채널 순서를 추론 전에 차단합니다. */
    private static boolean rgbTensor(TensorType tensor) {
        List<Integer> shape = tensor.getLayout().getDimensions();
        return tensor.getElementType() == TensorType.ElementType.FLOAT && shape.size() == 4
                && shape.get(0) == 1 && shape.get(3) == 3 && shape.get(1) > 0 && shape.get(2) > 0;
    }

    /** 모델에 기록된 타일 가로 입력 크기를 반환합니다. */
    public int inputWidth() { return inputWidth; }
    /** 모델에 기록된 타일 세로 입력 크기를 반환합니다. */
    public int inputHeight() { return inputHeight; }
    /** 모델에 기록된 타일 가로 출력 크기를 반환합니다. */
    public int outputWidth() { return outputWidth; }
    /** 모델에 기록된 타일 세로 출력 크기를 반환합니다. */
    public int outputHeight() { return outputHeight; }

    /** 선택한 경로만 보고하며 공개 API가 제공하지 않는 실제 연산 비율은 단정하지 않습니다. */
    public String route() { return route; }

    /** 입력·출력 텐서 버퍼는 요청 동안 재사용합니다. */
    public float[] infer(float[] values) throws Exception {
        inputs.get(0).writeFloat(values); model.run(inputs, outputs, 0);
        // ponytail: Kotlin API는 출력 배열을 새로 만듭니다. 실측 병목이면 네이티브 TensorBuffer 직접 읽기로 확장합니다.
        return outputs.get(0).readFloat();
    }

    /** 생성 실패·정상 종료 모두 같은 스레드에서 핸들을 닫습니다. */
    @Override public void close() {
        for (TensorBuffer buffer : inputs) buffer.close();
        for (TensorBuffer buffer : outputs) buffer.close();
        inputs = Collections.emptyList(); outputs = Collections.emptyList();
        if (model != null) { model.close(); model = null; }
        if (environment != null) { environment.close(); environment = null; }
    }
}
