"""Unblind only after blind-ai-scores.json exists; no model requests."""
from __future__ import annotations

import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

run = Path(sys.argv[1])
ledger = json.loads((run / "ledger.json").read_text(encoding="utf-8"))
scores = json.loads((run / "blind-ai-scores.json").read_text(encoding="utf-8"))
if len(scores) != 80:
    raise SystemExit("Refusing to unblind an incomplete 80-output scoring sheet")
groups = defaultdict(list)
paired = defaultdict(dict)
for item in scores:
    assignment = ledger["blindKey"][item["blindCaseId"]]
    condition = ("KEYWORD" if assignment == "KEYWORD=X" else "HYBRID") if item["variant"] == "X" else (
        "HYBRID" if assignment == "KEYWORD=X" else "KEYWORD")
    groups[condition].append(item)
    paired[item["blindCaseId"]][condition] = sum(item["scores"].values())
dimensions = ("answerCoverage", "unsupportedInference", "citationSupport", "suggestionRisk")
summary = {condition: {dimension: round(statistics.mean(x["scores"][dimension] for x in items), 3)
                       for dimension in dimensions} | {
                       "totalMeanOutOf8": round(statistics.mean(sum(x["scores"].values()) for x in items), 3),
                       "flaggedWeakCitations": sum("EXTRA_KNOWLEDGE_CITATION" in x["auditFlags"] for x in items),
                       "aiInferenceOverrides": sum(x["scores"]["unsupportedInference"] < 2 for x in items)}
           for condition, items in groups.items()}
wins = sum(pair["HYBRID"] > pair["KEYWORD"] for pair in paired.values())
losses = sum(pair["HYBRID"] < pair["KEYWORD"] for pair in paired.values())
ties = len(paired) - wins - losses
report = {"disclosure": "AI 辅助评分，未经独立人工验证",
          "method": "固定四维0-2标准；条件映射在80份盲表完成评分后读取。脚本先标记显性风险，Codex逐例阅读摘要并记录27项无据推断人工复核覆盖；引用内容可能透露条件，非严格双盲。",
          "rubric": "../ai-review-rubric-v1.json",
          "scoresByCondition": summary,
          "pairedTotals": {"hybridWins": wins, "hybridLosses": losses, "ties": ties},
          "limitations": ["合成单证据样例不代表真实交易分布", "部分无匹配样例的支付统计与文件故障描述人为拼接", "AI 评阅可能受知识引用字段影响", "分数不等于诊断准确率"]}
(run / "ai-assisted-scoring-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
