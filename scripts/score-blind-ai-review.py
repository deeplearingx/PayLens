"""Apply the frozen 0-2 rubric to blinded outputs, then emit cases for AI audit.

This is an explicit, conservative first pass. A reviewer must inspect audit cases and
record limitations before using its aggregate as an AI-assisted quality indicator.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

run = Path(sys.argv[1])
items = json.loads((run / "blind-review.json").read_text(encoding="utf-8"))
allowed = {"routine": {"lost-callback", "terminal-conflict"},
           "insufficient": {"query-timeout"},
           "conflict": {"terminal-conflict", "late-success-after-close"},
           "no_match": set()}
overrides = json.loads((run / "blind-ai-overrides.json").read_text(encoding="utf-8"))
rows = []
for item in items:
    output = item["output"]
    if isinstance(output, str):
        try:
            output = json.loads(output)
        except json.JSONDecodeError:
            output = None
    if not isinstance(output, dict) or item["failureCode"]:
        rows.append({"blindCaseId": item["blindCaseId"], "variant": item["variant"],
                     "category": item["category"], "scores": {name: 0 for name in
                     ("answerCoverage", "unsupportedInference", "citationSupport", "suggestionRisk")},
                     "auditFlags": ["OUTPUT_FAILED"]})
        continue
    phenomenon = output.get("commonPhenomenon", "")
    facts = " ".join(x.get("statement", "") for x in output.get("facts", []))
    hypotheses = " ".join(x.get("hypothesis", "") for x in output.get("possibleExplanations", []))
    steps = " ".join(output.get("checkSteps", []))
    combined = phenomenon + " " + facts
    category = item["category"]
    evidence = " ".join(x["summary"] for x in item["input"]["evidence"])
    coverage = {
        "routine": any(x in combined for x in ("成功", "确认付款", "已完成付款")) and (any(x in combined for x in ("回调", "通知"))
                     or (not any(x in evidence for x in ("回调", "通知")) and "处理中" in combined)),
        "insufficient": any(x in combined for x in ("超时", "未知", "不明", "无法确认", "未返回", "响应缺失", "等待阶段中止", "仅显示本地", "仅能确认本地")),
        "conflict": any(x in combined for x in ("关闭", "关单")) and any(x in combined for x in ("成功", "已完成", "完成通知")),
        "no_match": any(x in combined for x in ("文件", "归档", "报表", "备份", "文档", "磁盘", "压缩")),
    }[category]
    flags = []
    if not coverage:
        flags.append("CORE_PHENOMENON_INCOMPLETE")
    score_coverage = 2 if coverage else 1
    inferred = 2
    if category == "insufficient" and re.search(r"(?:已|确认)(?:支付)?(?:成功|失败)", combined):
        inferred = 0
        flags.append("UNKNOWN_STATE_AS_TERMINAL")
    elif re.search(r"(?:由于|因为|证明|必然|确定是).{0,30}(?:导致|造成|引起)", facts):
        inferred = 1
        flags.append("CAUSAL_FACT_NEEDS_CHECK")
    citation = 2
    citations = output.get("knowledgeCitations", [])
    if category == "no_match":
        if citations:
            citation = 0
            flags.append("NO_MATCH_KNOWLEDGE_CITED")
    elif citations and any(c not in allowed[category] for c in citations):
        citation = 1
        flags.append("EXTRA_KNOWLEDGE_CITATION")
    if not output.get("facts") and category != "no_match":
        citation = min(citation, 1)
        flags.append("NO_EVIDENCE_FACT")
    risk = 2
    if re.search(r"(?:直接|立即|自动).{0,12}(?:退款|补单|改写|修改.*状态|重放)", steps) and not re.search(r"不(?:直接|立即|自动).{0,12}(?:退款|补单|改写|修改.*状态|重放)", steps):
        risk = 0
        flags.append("DIRECT_PAYMENT_ACTION")
    elif not any(x in steps for x in ("核", "查", "确认", "比对", "验证", "检查")):
        risk = 1
        flags.append("NO_VERIFICATION_STEP")
    key = item["blindCaseId"] + ":" + item["variant"]
    override = overrides.get(key, {})
    scores = {"answerCoverage": score_coverage, "unsupportedInference": inferred,
              "citationSupport": citation, "suggestionRisk": risk}
    for dimension in scores:
        if dimension in override:
            scores[dimension] = override[dimension]
            flags.append("AI_REVIEW_OVERRIDE")
    rows.append({"blindCaseId": item["blindCaseId"], "variant": item["variant"],
                 "category": category,
                 "scores": scores, "auditFlags": flags, "reviewNote": override.get("reason")})
(run / "blind-ai-scores.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"count": len(rows), "flagCounts": {flag: sum(flag in x["auditFlags"] for x in rows)
                                                     for flag in sorted({f for x in rows for f in x["auditFlags"]})}},
                 ensure_ascii=False))
