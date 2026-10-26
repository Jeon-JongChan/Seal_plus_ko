"""공개 LFW 검증 쌍으로 모델별 5단계 임계값을 재현 가능하게 산출합니다."""

import hashlib
import argparse
import json
import tarfile
import urllib.request
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / ".build/cache/person-calibration"
ASSETS = ROOT / "app/src/main/assets"
FILES = {
    "lfw-funneled.tgz": ("5976015", "b47c8422c8cded889dc5a13418c4bc2abbda121092b3533a83306f90d900100a"),
    "pairsDevTrain.txt": ("5976012", "1d454dada7dfeca0e7eab6f65dc4e97a6312d44cf142207be28d688be92aabfa"),
    "pairsDevTest.txt": ("5976009", "7cb06600ea8b2814ac26e946201cdb304296262aad67d046a16a7ec85d0ff87c"),
}
TEMPLATE = np.array([[38.2946,51.6963],[73.5318,51.5014],[56.0252,71.7366],[41.5493,92.3655],[70.7299,92.2041]], dtype=np.float32)


def download():
    """검증용 얼굴 자료는 무결성을 확인하고 빌드 캐시에만 둡니다."""
    CACHE.mkdir(parents=True, exist_ok=True)
    for name, (file_id, sha) in FILES.items():
        path = CACHE / name
        if not path.exists():
            print(f"다운로드: {name}", flush=True)
            urllib.request.urlretrieve(f"https://ndownloader.figshare.com/files/{file_id}", path)
        assert hashlib.sha256(path.read_bytes()).hexdigest() == sha, f"검증 자료 해시 불일치: {name}"
    if not (CACHE / "lfw_funneled").is_dir():
        with tarfile.open(CACHE / "lfw-funneled.tgz") as archive:
            archive.extractall(CACHE, filter="data")


def pairs(name):
    """LFW의 동일인물·타인 비교 명세를 실제 이미지 경로로 변환합니다."""
    result = []
    for line in (CACHE / name).read_text().splitlines()[1:]:
        parts = line.split()
        if len(parts) == 3:
            a, i, j = parts
            b, same = a, True
        else:
            a, i, b, j = parts
            same = False
        result.append((f"{a}/{a}_{int(i):04d}.jpg", f"{b}/{b}_{int(j):04d}.jpg", same))
    return result


def alignment(points):
    """앱과 같은 반사 없는 5점 최소제곱 유사변환을 계산합니다."""
    source_mean, target_mean = points.mean(axis=0), TEMPLATE.mean(axis=0)
    source, target = points-source_mean, TEMPLATE-target_mean
    denominator = (source*source).sum()
    assert denominator > 1e-8
    a = (source*target).sum()/denominator
    b = (source[:,0]*target[:,1]-source[:,1]*target[:,0]).sum()/denominator
    matrix = np.array([[a,-b,0],[b,a,0]], dtype=np.float64)
    matrix[:,2] = target_mean-matrix[:,:2]@source_mean
    return matrix


def embedding(path, detector, infer):
    """검증 자료의 중앙 얼굴을 앱과 같은 YuNet·정렬·RGB 규칙으로 분석합니다."""
    image = cv2.imread(str(CACHE / "lfw_funneled" / path))
    assert image is not None
    height, width = image.shape[:2]
    detector.setInputSize((width,height))
    _, faces = detector.detect(image)
    if faces is None:
        return None
    # LFW는 중앙 인물이 라벨 대상인 데이터셋입니다. 앱의 사용자 얼굴 선택을 대체하지 않습니다.
    face = min(faces, key=lambda f: ((f[0]+f[2]/2-width/2)**2+(f[1]+f[3]/2-height/2)**2))
    aligned = cv2.warpAffine(image,alignment(face[4:14].reshape(5,2)),(112,112),flags=cv2.INTER_LINEAR)
    values = aligned[:,:,::-1].astype(np.float32)[None]
    vector = infer(values).reshape(-1)
    norm = np.linalg.norm(vector)
    assert np.isfinite(vector).all() and norm > 0 and vector.size == 128
    return vector/norm


def main():
    """학습 쌍으로만 임계값을 정하고 별도 검증 쌍의 FAR·FRR·보류율을 기록합니다."""
    parser = argparse.ArgumentParser(description="얼굴 모델 검증 임계값 산출")
    parser.add_argument("--model")
    args = parser.parse_args()
    download()
    cv2.setNumThreads(1)
    train, test = pairs("pairsDevTrain.txt"), pairs("pairsDevTest.txt")
    all_paths = sorted({p for pair in train+test for p in pair[:2]})
    manifest = json.loads((ASSETS / "person-ai/models.json").read_text(encoding="utf-8"))
    for model in manifest["models"]:
        if args.model and model["id"] != args.model:
            continue
        model_path = ASSETS / "person-ai" / model["file"]
        if not model_path.exists():
            model_path = CACHE / model["file"]
        if not model_path.exists():
            continue
        assert hashlib.sha256(model_path.read_bytes()).hexdigest() == model["sha256"]
        if model_path.suffix == ".tflite":
            import tensorflow as tf
            session = tf.lite.Interpreter(model_path=str(model_path),num_threads=2)
            session.allocate_tensors()
            input_index = session.get_input_details()[0]["index"]
            output_index = session.get_output_details()[0]["index"]

            def infer(values):
                """앱과 같은 NHWC 입력으로 변환 모델 자체의 점수를 계산합니다."""
                session.set_tensor(input_index,values)
                session.invoke()
                return session.get_tensor(output_index)
        else:
            options = ort.SessionOptions()
            options.log_severity_level = 3
            options.intra_op_num_threads = 2
            session = ort.InferenceSession(str(model_path),options,providers=["CPUExecutionProvider"])

            def infer(values):
                """원본 ONNX 모델에는 동일 픽셀의 NCHW 입력을 전달합니다."""
                return session.run(None,{session.get_inputs()[0].name:np.transpose(values,(0,3,1,2))})[0]
        detector = cv2.FaceDetectorYN.create(str(ASSETS / "face-sort/yunet.onnx"),"",(250,250),.7,.3,5000)
        vectors = {}
        for index, path in enumerate(all_paths):
            vectors[path] = embedding(path,detector,infer)
            if index % 250 == 0:
                print(f"{model['id']}: {index}/{len(all_paths)}",flush=True)

        def scores(data):
            """검출 실패 쌍은 보류로 집계하며 정상 쌍의 라벨과 점수를 반환합니다."""
            valid = [(float(vectors[a]@vectors[b]),same) for a,b,same in data if vectors[a] is not None and vectors[b] is not None]
            return np.array([s for s,_ in valid]), np.array([same for _,same in valid]), len(data)-len(valid)

        training, labels, train_missing = scores(train)
        testing, test_labels, test_missing = scores(test)
        # 얼굴 이미지·임베딩을 저장하지 않고 재현용 라벨·점수만 빌드 캐시에 기록합니다.
        np.savez_compressed(CACHE / f"{model['id']}-scores.npz",training=training,labels=labels,
            testing=testing,test_labels=test_labels,train_missing=train_missing,test_missing=test_missing)
        positives, negatives = training[labels], training[~labels]
        assert len(positives) >= 500 and len(negatives) >= 500
        levels = []
        rejection = float(np.quantile(positives,.01,method="lower"))
        for target_far in [.1,.03,.01,.003,.001]:
            # 관측 타인 점수보다 한 단계 높은 값을 사용하여 동률 오승인을 방지합니다.
            accept = float(np.nextafter(np.quantile(negatives,1-target_far,method="higher"),np.inf))
            # 승인 기준 근처는 관측 타인 분포의 더 낮은 분위수까지 보류합니다.
            reject = min(rejection,float(np.quantile(negatives,1-2*target_far,method="lower")))
            assert reject < accept
            levels.append({"accept":accept,"reject":reject,"target_far":target_far,
                "far":float(np.mean(testing[~test_labels]>=accept)),
                "frr":float(np.mean(testing[test_labels]<reject)),
                "tar":float(np.mean(testing[test_labels]>=accept)),
                "hold":float((np.sum((testing>=reject)&(testing<accept))+test_missing)/len(test))})
        output = {"model_sha256":model["sha256"],"preprocessing":model["preprocessing"],
            "dataset":"LFW funneled development train/test · YuNet 0.7 · 5-point similarity",
            "source":"https://github.com/scikit-learn/scikit-learn/blob/main/sklearn/datasets/_lfw.py",
            "train_pairs":len(train),"test_pairs":len(test),"train_missing":train_missing,"test_missing":test_missing,
            "train_positive":len(positives),"train_negative":len(negatives),"levels":levels,
            "limits":"공개 벤치마크의 개발 검증 결과입니다. 실제 사용 환경의 정확도·극저 FAR·기기 간 수치 일치를 보장하지 않습니다."}
        destination = ASSETS / "person-ai" / f"{model['id']}-calibration.json"
        destination.write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding="utf-8")
        print(f"검증 완료: {destination.name}",flush=True)


if __name__ == "__main__":
    main()
