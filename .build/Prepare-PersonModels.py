"""변환 도구가 배포 원본을 바꾸지 않도록 캐시 사본만 준비합니다."""

import hashlib
import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper, shape_inference
from onnxsim import simplify

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / ".build/cache/person-converted"


def prepare_detector():
    """YuNet의 좌표·랜드마크 출력을 평탄화한 가속용 사본을 만듭니다."""
    source = ROOT / "app/src/main/assets/face-sort/yunet.onnx"
    assert hashlib.sha256(source.read_bytes()).hexdigest() == "65443783acc01baa71e8d172e0f8270de261f609495d0249c9cd4fe4290d2367"
    model = onnx.load(source)
    input_name = model.graph.input[0].name
    model.graph.input[0].name = "data"
    for node in model.graph.node:
        for index, name in enumerate(node.input):
            if name == input_name:
                node.input[index] = "data"
    for dimension, size in zip(model.graph.input[0].type.tensor_type.shape.dim,[1,3,640,640]):
        dimension.ClearField("dim_param")
        dimension.dim_value = size
    rows = []
    for stride in (8,16,32):
        name = f"rows_{stride}"
        model.graph.node.append(helper.make_node("Concat",[f"cls_{stride}",f"obj_{stride}",f"bbox_{stride}",f"kps_{stride}"],[name],axis=2))
        rows.append(name)
    model.graph.node.extend([helper.make_node("Concat",rows,["all_faces"],axis=1),helper.make_node("Flatten",["all_faces"],["detections"],axis=1)])
    model.graph.ClearField("output")
    model.graph.output.append(helper.make_tensor_value_info("detections",onnx.TensorProto.FLOAT,[1,134400]))
    model, verified = simplify(model)
    assert verified
    CACHE.mkdir(parents=True,exist_ok=True)
    destination = CACHE / "yunet-person.onnx"
    onnx.save(model,destination)
    print(f"얼굴 검출 변환용 사본 준비: {destination.name}",flush=True)


def main():
    """공식 FP32 원본을 검증하고 추론 전용 BN을 등가 상수 연산으로 바꿉니다."""
    parser = argparse.ArgumentParser(description="얼굴 모델 변환 사본 준비")
    parser.add_argument("--detector",action="store_true")
    if parser.parse_args().detector:
        prepare_detector()
        return
    source = ROOT / ".build/cache/person-calibration/sface-fp32.onnx"
    assert hashlib.sha256(source.read_bytes()).hexdigest() == "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
    model = onnx.load(source)
    names = {value.name for value in model.graph.initializer}
    inputs = [value for value in model.graph.input if value.name not in names]
    model.graph.ClearField("input")
    model.graph.input.extend(inputs)
    model, verified = simplify(model)
    assert verified
    model = shape_inference.infer_shapes(model)
    dimensions = {value.name: len(value.type.tensor_type.shape.dim) for value in list(model.graph.value_info)+list(model.graph.input)+list(model.graph.output)}
    constants = {value.name: numpy_helper.to_array(value) for value in model.graph.initializer}
    nodes = []
    for node in model.graph.node:
        if node.op_type == "BatchNormalization":
            gamma, beta, mean, variance = [constants[name] for name in node.input[1:5]]
            epsilon = next((attribute.f for attribute in node.attribute if attribute.name == "epsilon"),1e-5)
            scale = gamma/np.sqrt(variance+epsilon)
            shift = beta-mean*scale
            rank = dimensions[node.input[0]]
            assert rank in (2,4)
            shape = [1,len(scale)]+[1]*(rank-2)
            scale_name, shift_name = node.name+"_fixed_scale",node.name+"_fixed_shift"
            model.graph.initializer.extend([numpy_helper.from_array(scale.reshape(shape).astype(np.float32),scale_name),
                numpy_helper.from_array(shift.reshape(shape).astype(np.float32),shift_name)])
            middle = node.name+"_scaled"
            nodes.extend([helper.make_node("Mul",[node.input[0],scale_name],[middle],name=node.name+"_scale"),
                helper.make_node("Add",[middle,shift_name],list(node.output),name=node.name+"_shift")])
        elif node.op_type == "Dropout":
            nodes.append(helper.make_node("Identity",[node.input[0]],[node.output[0]],name=node.name+"_inference"))
        else:
            nodes.append(node)
    model.graph.ClearField("node")
    model.graph.node.extend(nodes)
    model, verified = simplify(model)
    assert verified and all(node.op_type != "BatchNormalization" for node in model.graph.node)
    CACHE.mkdir(parents=True,exist_ok=True)
    destination = CACHE / "sface-fp32-builtin.onnx"
    onnx.save(model,destination)
    # 아래 입력은 변환 구조 검사에만 사용하며 인물 판별 임계값 산출에는 쓰지 않습니다.
    np.save(CACHE / "calibration_image_sample_data_20x128x128x3_float32.npy",np.random.default_rng(42).random((20,128,128,3),dtype=np.float32))
    print(f"변환용 사본 준비: {destination.name}",flush=True)


if __name__ == "__main__":
    main()
