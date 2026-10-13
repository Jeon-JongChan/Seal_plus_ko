"""동일 YuNet 가중치를 정적 LiteRT 모델로 변환하고 ONNX 점수와 비교합니다."""

import argparse
import hashlib
import json
import os
from pathlib import Path

os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"
os.environ["TF_USE_LEGACY_KERAS"] = "1"

import numpy as np
import onnx
import onnxruntime as ort
import tensorflow as tf
from onnx import helper
from onnxsim import simplify
from onnx2tf import convert
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/assets/face-sort/yunet.onnx"
WORK = ROOT / ".build/Seal/tmp/yunet-models"


def convert_size(size):
    """검출 박스 대신 원래 클래스·객체 점수의 최댓값만 출력합니다."""
    model = onnx.load(SOURCE)
    assert model.opset_import[0].version < 18, "ReduceMax의 축 표현을 확인하세요."
    dims = model.graph.input[0].type.tensor_type.shape.dim
    for dim, value in zip(dims, (1, 3, size, size)):
        dim.ClearField("dim_param")
        dim.dim_value = value
    maxima = []
    for stride in (8, 16, 32):
        product, maximum = f"face_product_{stride}", f"face_max_{stride}"
        model.graph.node.extend([
            helper.make_node("Mul", [f"cls_{stride}", f"obj_{stride}"], [product]),
            helper.make_node("ReduceMax", [product], [maximum], keepdims=1),
        ])
        maxima.append(maximum)
    model.graph.node.extend([
        helper.make_node("Concat", maxima, ["face_maxima"], axis=1),
        helper.make_node("ReduceMax", ["face_maxima"], ["face_probability"], keepdims=1),
        helper.make_node("Sqrt", ["face_probability"], ["face_score"]),
    ])
    del model.graph.output[:]
    model.graph.output.append(helper.make_tensor_value_info("face_score", onnx.TensorProto.FLOAT, [1, 1, 1]))
    # 사용하지 않는 박스·키포인트 가지를 제거하고 가중치 자체는 바꾸지 않습니다.
    model, valid = simplify(model)
    assert valid
    folder = WORK / str(size)
    folder.mkdir(parents=True, exist_ok=True)
    source = folder / "yunet.onnx"
    onnx.save(model, source)
    convert(input_onnx_file_path=str(source), output_folder_path=str(folder / "converted"),
            not_use_onnxsim=True, non_verbose=True, output_signaturedefs=True,
            copy_onnx_input_output_names_to_tflite=True, optimization_for_gpu_delegate=True)
    return folder / "converted/yunet_float32.tflite"


def verify(path, size):
    """합성 입력·얼굴·빈 화면에서 원본 ONNX와 FP32 LiteRT의 점수를 비교합니다."""
    runtime = tf.lite.Interpreter(model_path=str(path))
    runtime.allocate_tensors()
    inp, out = runtime.get_input_details()[0], runtime.get_output_details()[0]
    assert inp["shape"].tolist() == [1, size, size, 3]
    reference = ort.InferenceSession(str(SOURCE), providers=["CPUExecutionProvider"])
    rng = np.random.default_rng(7)
    samples = [("합성", rng.uniform(0, 255, (size, size, 3)).astype(np.float32)),
               ("빈화면", np.full((size, size, 3), 128, np.float32))]
    portrait = Image.open(ROOT / "app/src/androidTest/assets/face-sort/portrait.jpg").convert("RGB")
    portrait.thumbnail((size, size))
    padded = np.zeros((size, size, 3), np.float32)
    padded[:portrait.height, :portrait.width] = np.asarray(portrait)[:, :, ::-1]
    samples.append(("얼굴", padded))
    records = []
    for name, values in samples:
        outputs = reference.run([f"{kind}_{stride}" for stride in (8, 16, 32) for kind in ("cls", "obj")],
                                {"input": values.transpose(2, 0, 1)[None]})
        expected = max(float(np.sqrt(np.clip(outputs[i], 0, 1) * np.clip(outputs[i + 1], 0, 1)).max())
                       for i in range(0, 6, 2))
        runtime.set_tensor(inp["index"], values[None])
        runtime.invoke()
        actual = float(runtime.get_tensor(out["index"]).max())
        assert abs(expected - actual) < 0.0002, (size, name, expected, actual)
        assert (actual >= 0.5) == (expected >= 0.5)
        records.append({"입력": name, "ONNX": expected, "LiteRT": actual})
    return {"해상도": size, "파일": str(path.relative_to(ROOT)), "바이트": path.stat().st_size,
            "SHA256": hashlib.sha256(path.read_bytes()).hexdigest(), "검사": records}


def main():
    """요청한 해상도만 변환하며 검증되지 않은 모델을 앱 자산에 복사하지 않습니다."""
    parser = argparse.ArgumentParser()
    parser.add_argument("sizes", type=int, nargs="*", default=[320, 640, 960, 1280])
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    WORK.mkdir(parents=True, exist_ok=True)
    # 변환기의 외부 보정 사진 다운로드 대신 격리된 합성 입력만 사용합니다.
    np.save(WORK / "calibration_image_sample_data_20x128x128x3_float32.npy",
            np.random.default_rng(7).random((20, 128, 128, 3), dtype=np.float32))
    os.chdir(WORK)
    results = []
    for size in args.sizes:
        assert size in (320, 640, 960, 1280)
        path = WORK / str(size) / "converted/yunet_float32.tflite" if args.verify_only else convert_size(size)
        result = verify(path, size)
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    (WORK / "verification.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.publish:
        assets = SOURCE.parent
        manifest = assets / "models.json"
        entries = json.loads(manifest.read_text(encoding="utf-8"))
        source_hash = hashlib.sha256(SOURCE.read_bytes()).hexdigest()
        for result in results:
            name = f"yunet-{result['해상도']}.tflite"
            path = ROOT / result["파일"]
            (assets / name).write_bytes(path.read_bytes())
            entries = [entry for entry in entries if entry["file"] != name]
            entries.append({"file": name, "source": "yunet.onnx", "source_sha256": source_hash,
                            "sha256": result["SHA256"], "bytes": result["바이트"],
                            "conversion": "onnx2tf 1.26.3 / TensorFlow 2.19.0 / FP32 / BGR NHWC"})
        manifest.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
