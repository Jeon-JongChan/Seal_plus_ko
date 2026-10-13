"""공식 QuickSRNet 두 모델을 검증하고 공유 내장 자산으로 준비합니다."""
import hashlib
import json
import zipfile
from pathlib import Path
import numpy as np
import tensorflow as tf

root = Path(__file__).resolve().parents[2]
work = root / ".build/Seal/tmp/quicksr"
assets = root / "app/src/main/assets/image-ai"
records = []
for name in ("Small", "Medium"):
    archive = work / f"{name}.zip"
    with zipfile.ZipFile(archive) as package:
        entries = [item for item in package.namelist() if item.endswith(".tflite")]
        assert len(entries) == 1
        data = package.read(entries[0])
    path = work / f"quicksrnet-{name.lower()}.tflite"
    path.write_bytes(data)
    runtime = tf.lite.Interpreter(model_path=str(path), num_threads=1)
    runtime.allocate_tensors()
    source, target = runtime.get_input_details()[0], runtime.get_output_details()[0]
    assert source["shape"].tolist() == [1, 128, 128, 3]
    print(name, source["shape"].tolist(), target["shape"].tolist())
    shape = target["shape"].tolist()
    assert shape[0] == 1 and shape[3] == 3 and shape[1] == shape[2] and shape[1] in (256, 384, 512)
    assert source["dtype"] == np.float32 and target["dtype"] == np.float32
    runtime.set_tensor(source["index"], np.full(source["shape"], 0.5, np.float32))
    runtime.invoke()
    output = runtime.get_tensor(target["index"])
    assert np.isfinite(output).all()
    print(name, source["name"], target["name"], len(data), float(output.mean()))
    record = {"model": name, "file": path.name, "bytes": len(data),
              "sha256": hashlib.sha256(data).hexdigest(), "archive_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
              "url": json.loads((work / f"{name}-assets.json").read_text())["precisions"]["float"]["universal_assets"]["tflite"]["download_url"],
              "input": source["name"], "output": target["name"], "mean": float(output.mean()), "scale": shape[1] // 128}
    records.append(record)
    assets.mkdir(parents=True, exist_ok=True)
    (assets / path.name).write_bytes(data)
(assets / "quicksrnet-models.json").write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
