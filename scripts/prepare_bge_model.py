"""Export the pinned Chinese BGE checkpoint to the local files consumed by Java."""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

from optimum.exporters.onnx import main_export
import numpy as np
import onnxruntime as ort
import torch
from transformers import AutoModel, AutoTokenizer
import onnx


MODEL_ID = "BAAI/bge-small-zh-v1.5"
MODEL_REVISION = "7999e1d3359715c523056ef9478215996d62a620"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_export(output: Path) -> dict[str, object]:
    texts = [
        "支付查单时通道长时间没有响应，超过了读取等待时限。",
        "发起支付查询后一直等不到通道答复，最终触发读取超时。",
        "支付回调的签名校验未通过，证书或密钥与通道配置不一致。",
        "退款查询期间通道没有及时返回，客户端等待超时。",
    ]
    tokenizer = AutoTokenizer.from_pretrained(output, local_files_only=True)
    encoded = tokenizer(texts, padding=True, truncation=True, max_length=512, return_tensors="np")
    model = AutoModel.from_pretrained(MODEL_ID, revision=MODEL_REVISION).eval()
    with torch.inference_mode():
        inputs = {key: torch.from_numpy(value.astype(np.int64)) for key, value in encoded.items()
                  if key in model.forward.__code__.co_varnames}
        reference = model(**inputs).last_hidden_state[:, 0, :].cpu().numpy()
    reference = reference / np.linalg.norm(reference, axis=1, keepdims=True)

    session_options = ort.SessionOptions()
    session_options.intra_op_num_threads = 2
    session_options.inter_op_num_threads = 1
    session_options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    session = ort.InferenceSession(str(output / "model.onnx"), sess_options=session_options,
                                   providers=["CPUExecutionProvider"])
    input_names = {item.name for item in session.get_inputs()}
    onnx_inputs = {key: value.astype(np.int64) for key, value in encoded.items() if key in input_names}
    output_names = {item.name for item in session.get_outputs()}
    if "last_hidden_state" not in output_names:
        raise SystemExit(f"ONNX 输出不含 last_hidden_state：{sorted(output_names)}")
    inferred = session.run(["last_hidden_state"], onnx_inputs)[0][:, 0, :]
    inferred = inferred / np.linalg.norm(inferred, axis=1, keepdims=True)
    if inferred.shape != reference.shape or inferred.shape[1] != 512:
        raise SystemExit(f"ONNX 向量维度异常：{inferred.shape} / reference={reference.shape}")
    np.testing.assert_allclose(inferred, reference, rtol=2e-4, atol=2e-4,
                               err_msg="ONNX CLS 向量与 Transformers CPU 参考结果不一致")
    ref_similarity = reference @ reference.T
    onnx_similarity = inferred @ inferred.T
    np.testing.assert_allclose(onnx_similarity, ref_similarity, rtol=2e-4, atol=2e-4,
                               err_msg="ONNX 成对相似度与参考结果不一致")
    if not ref_similarity[0, 1] > ref_similarity[0, 2]:
        raise SystemExit("参考样例未形成预期的同现象/异现象相似度顺序")
    report = {
        "model": MODEL_ID,
        "revision": MODEL_REVISION,
        "pooling": "CLS",
        "normalization": "L2",
        "dimensions": 512,
        "comparison": "Transformers CPU vs ONNX Runtime CPU",
        "maxVectorAbsoluteDifference": float(np.max(np.abs(inferred - reference))),
        "samePhenomenonSimilarity": float(onnx_similarity[0, 1]),
        "differentPhenomenonSimilarity": float(onnx_similarity[0, 2]),
        "pairwiseRankingValidated": True,
    }
    (output / "reference-validation.json").write_text(
        __import__("json").dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def write_manifest(output: Path, report: dict[str, object]) -> None:
    model_path = output / "model.onnx"
    tokenizer_path = output / "tokenizer.json"
    manifest = output / "model-manifest.properties"
    manifest.write_text(
        "\n".join(
            [
                f"model={MODEL_ID}",
                f"revision={MODEL_REVISION}",
                f"model.onnx.sha256={sha256(model_path)}",
                f"tokenizer.json.sha256={sha256(tokenizer_path)}",
                "pooling=CLS",
                "normalization=L2",
                "embedding.dimensions=512",
                "export.task=feature-extraction",
                "export.dtype=fp32",
                "export.onnx_output=last_hidden_state",
                f"reference.max-vector-absolute-difference={report['maxVectorAbsoluteDifference']}",
                f"reference.pairwise-ranking-validated={str(report['pairwiseRankingValidated']).lower()}",
                "",
            ]
        ),
        encoding="utf-8",
    )


def main() -> None:
    if sys.version_info[:2] not in {(3, 11), (3, 12), (3, 13)}:
        raise SystemExit("模型导出要求 Python 3.11、3.12 或 3.13")
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("models/bge-small-zh-v1.5"))
    parser.add_argument("--validate-only", action="store_true",
                        help="Compare an already exported ONNX model with its pinned Transformers reference.")
    args = parser.parse_args()
    output = args.output.resolve()
    if args.validate_only:
        report = validate_export(output)
        write_manifest(output, report)
        print(__import__("json").dumps(report, ensure_ascii=False, indent=2))
        return
    output.mkdir(parents=True, exist_ok=True)

    main_export(
        MODEL_ID,
        output=str(output),
        task="feature-extraction",
        revision=MODEL_REVISION,
        library_name="transformers",
        device="cpu",
        dtype="fp32",
    )
    AutoTokenizer.from_pretrained(MODEL_ID, revision=MODEL_REVISION).save_pretrained(output)

    model_path = output / "model.onnx"
    tokenizer_path = output / "tokenizer.json"
    if not model_path.is_file() or not tokenizer_path.is_file():
        raise SystemExit("导出结果缺少 model.onnx 或 tokenizer.json")
    graph = onnx.load(str(model_path), load_external_data=False)
    output_names = {item.name for item in graph.graph.output}
    if "last_hidden_state" not in output_names:
        raise SystemExit(f"ONNX 输出不含 last_hidden_state：{sorted(output_names)}")
    reference_report = validate_export(output)

    write_manifest(output, reference_report)
    print(f"本地模型已准备：{output}")
    print(f"revision={MODEL_REVISION}")
    print(f"model.onnx.sha256={sha256(model_path)}")
    print(f"tokenizer.json.sha256={sha256(tokenizer_path)}")
    print(f"reference.maxVectorAbsoluteDifference={reference_report['maxVectorAbsoluteDifference']}")
    print(f"reference.pairwiseRankingValidated={reference_report['pairwiseRankingValidated']}")


if __name__ == "__main__":
    main()
