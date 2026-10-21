"""Offline, reproducible keyword/BGE retrieval calibration. No cloud requests."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "docs/paylens/improvement-evaluation"
KNOWLEDGE = ROOT / "paylens-analysis/src/main/resources/paylens/knowledge"
MODEL = ROOT / "models/bge-small-zh-v1.5"
PREFIX = "为这个句子生成表示以用于检索相关文章："


def documents() -> list[dict]:
    result = []
    for path in sorted(KNOWLEDGE.glob("*.md")):
        raw = path.read_text(encoding="utf-8")
        _, front, body = raw.split("---", 2)
        metadata = dict(line.split(":", 1) for line in front.strip().splitlines())
        result.append({"id": metadata["id"].strip(), "title": metadata["title"].strip(),
                       "keywords": [x.strip().lower() for x in metadata["keywords"].split(",")],
                       "content": body.strip()})
    return result


def encode(texts: list[str], tokenizer: Tokenizer, session: ort.InferenceSession) -> np.ndarray:
    vectors = []
    for text in texts:
        encoding = tokenizer.encode(text[:10000])
        ids = np.asarray([encoding.ids[:512]], dtype=np.int64)
        mask = np.ones_like(ids)
        feeds = {"input_ids": ids, "attention_mask": mask}
        if "token_type_ids" in {x.name for x in session.get_inputs()}:
            feeds["token_type_ids"] = np.zeros_like(ids)
        vector = session.run(["last_hidden_state"], feeds)[0][0, 0].astype(np.float64)
        vectors.append(vector / np.linalg.norm(vector))
    return np.stack(vectors)


def rank(query: str, docs: list[dict], doc_vectors: np.ndarray, vector: np.ndarray,
         threshold: float | None) -> list[str]:
    haystack = ("INCIDENT MULTIPLE MULTIPLE " + query).lower()
    lexical = []
    for doc in docs:
        score = sum(2 if len(word) > 3 else 1 for word in doc["keywords"] if word in haystack)
        if score:
            lexical.append((doc["id"], score))
    lexical.sort(key=lambda x: (-x[1], x[0]))
    lexical = lexical[:5]
    if threshold is None:
        return [item[0] for item in lexical[:3]]
    semantic = [(doc["id"], float(doc_vectors[i] @ vector)) for i, doc in enumerate(docs)]
    semantic = [item for item in semantic if item[1] >= threshold]
    semantic.sort(key=lambda x: (-x[1], x[0]))
    semantic = semantic[:5]
    fused = {}
    for path in (lexical, semantic):
        for i, (doc_id, _) in enumerate(path):
            fused[doc_id] = fused.get(doc_id, 0.0) + 1 / (61 + i)
    return sorted(fused, key=lambda doc_id: (-fused[doc_id], doc_id))[:3]


def metrics(rows: list[dict], rankings: dict[str, list[str]]) -> dict:
    positive = [r for r in rows if r["candidateKnowledgeIds"]]
    negative = [r for r in rows if not r["candidateKnowledgeIds"]]
    hits = [next((1 / (i + 1) for i, doc in enumerate(rankings[r["id"]])
                  if doc in r["candidateKnowledgeIds"]), 0.0) for r in positive]
    return {"recallAt3": sum(bool(x) for x in hits) / len(hits),
            "mrr": sum(hits) / len(hits),
            "noMatchFalsePositiveRate": sum(bool(rankings[r["id"]]) for r in negative) / len(negative)}


def main() -> None:
    docs = documents()
    tokenizer = Tokenizer.from_file(str(MODEL / "tokenizer.json"))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(MODEL / "model.onnx"), options, providers=["CPUExecutionProvider"])
    doc_vectors = encode([doc["title"] + "\n" + doc["content"] for doc in docs], tokenizer, session)
    datasets = {name: json.loads((DATA / f"analysis-{name}-v2.json").read_text(encoding="utf-8"))
                for name in ("dev", "holdout")}
    vectors = {name: encode([PREFIX + "INCIDENT MULTIPLE MULTIPLE " +
                             " ".join(e["summary"] for e in row["input"]["evidence"])
                             for row in data["cases"]], tokenizer, session)
               for name, data in datasets.items()}
    dev = datasets["dev"]["cases"]
    candidates = []
    for step in range(25, 50):
        threshold = step / 50
        rankings = {row["id"]: rank(row["input"]["evidence"][0]["summary"], docs,
                                      doc_vectors, vectors["dev"][i], threshold)
                    for i, row in enumerate(dev)}
        score = metrics(dev, rankings)
        if score["recallAt3"] >= .9 and score["noMatchFalsePositiveRate"] <= .1:
            candidates.append((score["mrr"], threshold))
    selected = max(candidates)[1] if candidates else None
    report = {"method": "CLS + L2; query prefix; top-5 each; equal RRF k=60; top-3",
              "modelSha256": hashlib.sha256((MODEL / "model.onnx").read_bytes()).hexdigest(),
              "knowledgeIds": [x["id"] for x in docs], "selectedThreshold": selected,
              "thresholdCalibrated": selected is not None, "sets": {}}
    for name, data in datasets.items():
        rows = data["cases"]
        variants = {}
        for variant, threshold in (("keyword", None), ("hybrid", selected)):
            if variant == "hybrid" and selected is None:
                continue
            rankings = {row["id"]: rank(row["input"]["evidence"][0]["summary"], docs,
                                          doc_vectors, vectors[name][i], threshold)
                        for i, row in enumerate(rows)}
            variants[variant] = {"metrics": metrics(rows, rankings), "rankings": rankings}
        report["sets"][name] = variants
    (DATA / "retrieval-offline-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"selectedThreshold": selected,
                      "dev": {k: v["metrics"] for k, v in report["sets"]["dev"].items()},
                      "holdout": {k: v["metrics"] for k, v in report["sets"]["holdout"].items()}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
