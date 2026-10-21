"""Print a bounded blinded review batch without condition mapping."""
from __future__ import annotations

import json
import sys
from pathlib import Path

run = Path(sys.argv[1])
start = int(sys.argv[2])
end = int(sys.argv[3])
compact = len(sys.argv) > 4 and sys.argv[4] == "compact"
rows = json.loads((run / "blind-review.json").read_text(encoding="utf-8"))
for row in rows[start:end]:
    output = row["output"]
    if isinstance(output, str):
        try:
            output = json.loads(output)
        except json.JSONDecodeError:
            output = {"unparsed": output[:200]}
    output = output or {}
    print(f"{row['blindCaseId']} {row['variant']} {row['category']} failure={row['failureCode']}")
    print("  P:", str(output.get("commonPhenomenon", ""))[:180])
    print("  F:", " | ".join(str(x.get("statement", "")) for x in output.get("facts", []))[:150 if compact else 250])
    print("  H:", " | ".join(str(x.get("hypothesis", "")) for x in output.get("possibleExplanations", []))[:100 if compact else 250])
    print("  K:", ",".join(output.get("knowledgeCitations", [])))
    print("  M:", " | ".join(output.get("missingInformation", []))[:100 if compact else 180])
    print("  S:", " | ".join(output.get("checkSteps", []))[:120 if compact else 240])
