"""Summarize a durable cloud ledger without sending any model requests."""
from __future__ import annotations

import json
import statistics
import sys
from collections import Counter
from pathlib import Path


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def percentile(values: list[int], proportion: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, int((len(ordered) - 1) * proportion)))]


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    run = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / "docs/paylens/improvement-evaluation/cloud-paired-v3"
    ledger = load(run / "ledger.json")
    calls = ledger["calls"]
    cases = load(root / "docs/paylens/improvement-evaluation/analysis-holdout-v2.json")["cases"]
    by_case = {item["id"]: item for item in cases}
    raw = {path.stem: load(path) for path in (run / "raw").glob("*.json")}
    report = {"note": "AI-assisted scoring is separate and is not independent human verification",
              "maximumCalls": 120,
              "consumedCalls": sum(row["status"] not in ("NOT_STARTED", "SKIPPED_CONTEXT_TOO_LARGE") for row in calls),
              "budgetExhausted": sum(row["status"] not in ("NOT_STARTED", "SKIPPED_CONTEXT_TOO_LARGE") for row in calls) >= 120,
              "conditions": {}, "failures": dict(Counter(row.get("failureCode") for row in calls if row["status"] == "FAILED"))}
    for condition in ("KEYWORD", "HYBRID"):
        group = [row for row in calls if row["condition"] == condition]
        first = [row for row in group if row["kind"] == "INITIAL"]
        repairs = [row for row in group if row["kind"] == "FORMAT_REPAIR"]
        success = {row["caseId"] for row in group if row["status"] == "SUCCEEDED"}
        initial_success = sum(row["status"] == "SUCCEEDED" for row in first)
        result = {"initialIssued": sum(row["status"] != "NOT_STARTED" for row in first),
                  "initialValid": initial_success,
                  "initialValidRate": initial_success / 40,
                  "repairTriggered": len(repairs),
                  "repairIssued": sum(row["status"] not in ("NOT_STARTED", "SKIPPED_CONTEXT_TOO_LARGE") for row in repairs),
                  "repairValid": sum(row["status"] == "SUCCEEDED" for row in repairs),
                  "afterRepairValid": len(success), "afterRepairValidRate": len(success) / 40,
                  "promptTokensAllCalls": sum(row.get("promptTokens") or 0 for row in group),
                  "completionTokensAllCalls": sum(row.get("completionTokens") or 0 for row in group),
                  "initialDurationP50Ms": percentile([row["elapsedMs"] for row in first if row.get("elapsedMs") is not None], .5),
                  "initialDurationP95Ms": percentile([row["elapsedMs"] for row in first if row.get("elapsedMs") is not None], .95),
                  "repairDurationP50Ms": percentile([row["elapsedMs"] for row in repairs if row.get("elapsedMs") is not None], .5),
                  "successfulReferenceScopeValid": all(
                      bool(raw[row["id"]].get("validatedResult")) for row in group if row["status"] == "SUCCEEDED")}
        report["conditions"][condition] = result
    rows = []
    for case in cases:
        pair = {condition: next((row for row in calls if row["caseId"] == case["id"] and
                                row["condition"] == condition and row["kind"] == "INITIAL"), None)
                for condition in ("KEYWORD", "HYBRID")}
        rows.append({"caseId": case["id"], "category": case["category"],
                     "keywordFirstValid": pair["KEYWORD"]["status"] == "SUCCEEDED",
                     "hybridFirstValid": pair["HYBRID"]["status"] == "SUCCEEDED",
                     "keywordFailure": pair["KEYWORD"].get("failureCode"),
                     "hybridFailure": pair["HYBRID"].get("failureCode")})
    report["pairedStructuralResults"] = rows
    report["queueTimeMs"] = "NOT_APPLICABLE_STANDALONE_EVALUATION"
    report["reviewStatus"] = "PENDING_AI_ASSISTED_REVIEW"
    (run / "paired-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # The reviewer sheet contains no condition field or mapping. The mapping stays in the ledger.
    blind = []
    for case in cases:
        assignment = ledger["blindKey"][case["id"]]
        for variant in ("X", "Y"):
            condition = ("KEYWORD" if assignment == "KEYWORD=X" else "HYBRID") if variant == "X" else (
                "HYBRID" if assignment == "KEYWORD=X" else "KEYWORD")
            identifier = case["id"] + "-" + condition + "-INITIAL"
            first = raw.get(identifier, {})
            repair = raw.get(case["id"] + "-" + condition + "-FORMAT_REPAIR", {})
            blind.append({"blindCaseId": case["id"], "variant": variant,
                          "category": case["category"], "input": case["input"],
                          "scoringPoints": case["scoringPoints"],
                          "output": repair.get("validatedResult") or first.get("validatedResult") or
                                    repair.get("content") or first.get("content"),
                          "failureCode": repair.get("failureCode") or first.get("failureCode")})
    (run / "blind-review.json").write_text(json.dumps(blind, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"consumedCalls": report["consumedCalls"], "conditions": report["conditions"],
                      "failures": report["failures"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
