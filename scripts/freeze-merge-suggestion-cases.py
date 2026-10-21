"""Create disjoint synthetic suggestion pairs; labels are evaluation-only."""
from __future__ import annotations

import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "docs/paylens/improvement-evaluation"
PHENOMENA = {
    "callback": ("渠道付款成功但平台没收到通知", "交易已完成而异步回调尚未入库",
                 "支付成功消息没有进入本地回调表", "本地订单处理中且通道确认收款"),
    "timeout": ("通道查单读取超时，支付终态未知", "查询支付结果没有获得远端响应",
                "查单接口超时，不能确认是否扣款", "平台等待渠道结果直到请求过期"),
    "closed": ("订单本地已关闭，通道随后通知成功", "交易关单后出现外部付款成功流水",
               "平台关单状态与渠道支付完成结果冲突", "已关闭支付单收到迟到成功回调"),
    "duplicate": ("同一支付回调重复投递多次", "渠道通知重发而本地已经处理",
                  "相同付款事件出现重复的异步通知", "一笔交易收到多条相同成功回调"),
    "amount": ("本地订单金额与通道实付金额不一致", "渠道账单金额和平台下单金额不同",
               "交易金额核对时发现应付与实付有差异", "支付流水金额与订单记录存在差额"),
}
HOLDOUT = {
    "callback": ("外部支付流水已经成功，本地没有通知接收痕迹", "主动查得渠道成功，平台异步入口无对应记录",
                 "网关给出收款完成结果，商户通知链路仍为空", "渠道账单显示已付款，系统尚在等支付通知"),
    "timeout": ("远端交易查询未返回状态，订单仍待确认", "支付状态请求等待后断开，缺少终态凭据",
                "连接支付网关查单失败，当前结果不可判定", "结果接口没有应答，不能将未知状态当成失败"),
    "closed": ("关单记录已经写入，又收到渠道已收款的回执", "本地关闭终态与远端成功流水对不上",
               "先结束支付单，后到的通知声明交易完成", "支付已在平台关闭，但渠道对账确认成功"),
    "duplicate": ("通知入口再次收到已处理过的付款消息", "同笔交易的成功通知被渠道重新发送",
                  "回调接收记录出现重复的支付完成事件", "支付通知重试命中已完成的处理记录"),
    "amount": ("对账发现渠道收款数额与订单应付不符", "平台下单金额和外部实际支付值冲突",
               "核验付款流水时看到金额差额", "订单金额字段与渠道交易金额不相等"),
}


def build(split: str, seed: int) -> dict:
    rng = random.Random(seed)
    kinds = list(PHENOMENA)
    phrases = PHENOMENA if split == "dev" else HOLDOUT
    queries = []
    for index in range(100):
        kind = kinds[index % len(kinds)]
        phrase = phrases[kind][(index // 5) % 4]
        suffix = (f"，发生于演示批次 {index + 1}" if split == "dev" else
                  f"；核查第 {index + 1} 组事件记录")
        query = phrase + suffix
        candidates = []
        has_positive = index % 5 != 0
        for offset in range(3 if has_positive else 0):
            candidate_phrase = phrases[kind][(offset + index + 1) % 4]
            candidates.append({"id": f"P{offset}", "description": candidate_phrase + suffix,
                               "merchant": "M1", "business": "PAY", "phase": "QUERY",
                               "channel": "SIM", "abnormalType": "TRADE_EXCEPTION",
                               "errorCode": "", "minute": 2 + offset, "relevant": True})
        other_kinds = [x for x in kinds if x != kind]
        for offset in range(10 - len(candidates) - 2):
            other = other_kinds[(offset + index) % len(other_kinds)]
            candidates.append({"id": f"N{offset}",
                               "description": phrases[other][(index + offset) % 4] + suffix,
                               "merchant": "M1", "business": "PAY", "phase": "QUERY",
                               "channel": "SIM", "abnormalType": "TRADE_EXCEPTION",
                               "errorCode": "", "minute": 2 + offset, "relevant": False})
        for offset in range(2):
            candidates.append({"id": f"B{offset}", "description": phrase + suffix,
                               "merchant": "M2" if offset == 0 else "M1", "business": "PAY",
                               "phase": "QUERY", "channel": "SIM" if offset == 0 else "OTHER",
                               "abnormalType": "TRADE_EXCEPTION", "errorCode": "",
                               "minute": 3, "relevant": False, "boundary": True})
        rng.shuffle(candidates)
        assert len(candidates) == 10
        queries.append({"id": f"{split.upper()}-{index:03d}", "description": query,
                        "merchant": "M1", "business": "PAY", "phase": "QUERY",
                        "channel": "SIM", "abnormalType": "TRADE_EXCEPTION",
                        "errorCode": "", "minute": 0, "candidates": candidates})
    return {"datasetVersion": "merge-suggestions-v1-" + split,
            "seed": seed, "queries": queries}


def main() -> None:
    ROOT.mkdir(parents=True, exist_ok=True)
    for split, seed in (("dev", 26301), ("holdout", 26302)):
        path = ROOT / f"merge-{split}-v1.json"
        path.write_text(json.dumps(build(split, seed), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(path)


if __name__ == "__main__":
    main()
