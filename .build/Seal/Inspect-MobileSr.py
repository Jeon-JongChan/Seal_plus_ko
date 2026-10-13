"""공식 모바일 보정 모델의 정적 입출력과 연산 호환성을 확인합니다."""
import zipfile
import hashlib
from pathlib import Path
import tensorflow as tf
import numpy as np

root = Path(__file__).parent / "tmp/high-sr-model"
with zipfile.ZipFile(root / "real-esrgan-x4plus.zip") as archive:
    print(archive.read("real_esrgan_x4plus-tflite-float/metadata.json").decode())
    data = archive.read("real_esrgan_x4plus-tflite-float/real_esrgan_x4plus.tflite")
    (root / "real_esrgan_x4plus.tflite").write_bytes(data)
print("모델 SHA256:", hashlib.sha256(data).hexdigest())
interpreter = tf.lite.Interpreter(model_content=data, num_threads=1)
interpreter.allocate_tensors()
for item in interpreter.get_input_details() + interpreter.get_output_details():
    print(item["name"], item["shape"], item["dtype"], item["quantization"])
print("연산:", sorted({op["op_name"] for op in interpreter._get_ops_details()}))
details = interpreter.get_input_details()[0]
interpreter.set_tensor(details["index"], np.full(details["shape"], 0.5, np.float32))
interpreter.invoke()
output = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])
assert output.shape == (1, 512, 512, 3) and np.isfinite(output).all()
print("CPU 실제 추론 평균:", float(output.mean()))
