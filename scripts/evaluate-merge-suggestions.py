"""Calibrate human-confirmed merge suggestions against frozen local BGE pairs."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "docs/paylens/improvement-evaluation"
MODEL = ROOT / "models/bge-small-zh-v1.5"


def vectors(texts: list[str]) -> dict[str, np.ndarray]:
    tokenizer = Tokenizer.from_file(str(MODEL / "tokenizer.json"))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(MODEL / "model.onnx"), options, providers=["CPUExecutionProvider"])
    result = {}
    for text in dict.fromkeys(texts):
        ids = np.asarray([tokenizer.encode(text).ids[:512]], dtype=np.int64)
        feeds = {"input_ids": ids, "attention_mask": np.ones_like(ids)}
        if "token_type_ids" in {x.name for x in session.get_inputs()}:
            feeds["token_type_ids"] = np.zeros_like(ids)
        output = session.run(["last_hidden_state"], feeds)[0][0, 0].astype(np.float64)
        result[text] = output / np.linalg.norm(output)
    return result


def compatible(query: dict, item: dict) -> bool:
    fields = ("merchant", "business", "phase", "channel", "abnormalType")
    return (all(query[k] and item[k] and query[k] == item[k] for k in fields)
            and not (query["errorCode"] and item["errorCode"] and query["errorCode"] != item["errorCode"])
            and abs(query["minute"] - item["minute"]) <= 10)


def score(queries: list[dict], embeddings: dict[str, np.ndarray], threshold: float) -> dict:
    true_positive = returned = total_relevant = no_match_returned = no_match_queries = boundary_returned = 0
    rows = []
    for query in queries:
        ranked = []
        relevant = [item for item in query["candidates"] if item["relevant"]]
        total_relevant += len(relevant)
        if not relevant:
            no_match_queries += 1
        for item in query["candidates"]:
            if not compatible(query, item):
                continue
            similarity = float(embeddings[query["description"]] @ embeddings[item["description"]])
            if similarity >= threshold:
                ranked.append((item["id"], similarity, item["relevant"], item.get("boundary", False)))
        ranked.sort(key=lambda x: (-x[1], x[0]))
        chosen = ranked[:3]
        true_positive += sum(item[2] for item in chosen)
        returned += len(chosen)
        boundary_returned += sum(item[3] for item in chosen)
        no_match_returned += bool(not relevant and chosen)
        rows.append({"id": query["id"], "chosen": [{"id": x[0], "similarity": x[1], "relevant": x[2]}
                                                    for x in chosen]})
    return {"precisionAt3": true_positive / (3 * len(queries)),
            "precisionAmongShownAt3": true_positive / returned if returned else 0,
            "recallAt3": true_positive / total_relevant if total_relevant else 0,
            "noRelatedSuggestionRate": no_match_returned / no_match_queries,
            "crossBoundarySuggestions": boundary_returned,
            "returned": returned, "truePositive": true_positive, "rankings": rows}


def main() -> None:
    datasets = {name: json.loads((DATA / f"merge-{name}-v1.json").read_text(encoding="utf-8"))["queries"]
                for name in ("dev", "holdout")}
    texts = [description for queries in datasets.values() for query in queries
             for description in [query["description"]] + [c["description"] for c in query["candidates"]]]
    embeddings = vectors(texts)
    candidates = []
    for step in range(25, 50):
        threshold = step / 50
        outcome = score(datasets["dev"], embeddings, threshold)
        if outcome["precisionAt3"] >= .8 and outcome["crossBoundarySuggestions"] == 0:
            candidates.append((outcome["recallAt3"], threshold))
    selected = max(candidates)[1] if candidates else None
    diagnostic_threshold = .80
    result = {"modelSha256": hashlib.sha256((MODEL / "model.onnx").read_bytes()).hexdigest(),
              "selectedThreshold": selected, "thresholdCalibrated": selected is not None,
              "diagnosticThreshold": diagnostic_threshold,
              "method": "Known hard boundaries, one deduplicated description per event, CLS/L2 cosine, top-3",
              "development": score(datasets["dev"], embeddings, selected or diagnostic_threshold),
              "holdout": score(datasets["holdout"], embeddings, selected or diagnostic_threshold)}
    (DATA / "merge-offline-report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"threshold": selected,
                      "development": {k: v for k, v in (result["development"] or {}).items() if k != "rankings"},
                      "holdout": {k: v for k, v in (result["holdout"] or {}).items() if k != "rankings"}},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
