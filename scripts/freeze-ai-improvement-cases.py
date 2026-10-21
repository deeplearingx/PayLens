"""Freeze disjoint, authored synthetic event cases. Labels never enter model input."""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

GROUPS = {
    "routine": (
        "lost-callback",
        [
            "通道流水显示已付款，平台等待回调后仍停留在处理中。",
            "付款渠道回执成功，本地通知入口没有对应投递记录。",
            "交易查询证实付款完成，回调接收表缺少这次通知。",
            "本地订单处理中，通道已经完成付款但通知尚未入库。",
            "通道端确认支付成功，平台回调处理链路未留下记录。",
            "渠道账单已有成功交易，平台回调接收记录为空。",
            "支付结果查询为成功，应用没有收到关联支付通知。",
            "外部流水标明成功，平台仅能查到处理中订单。",
            "渠道发送支付完成结果后，本地通知审计没有对应项。",
            "主动查询显示成功，本地回调入口没有相应处理痕迹。",
            "付款已由通道确认，平台仍等候异步通知更新状态。",
            "支付网关已完成交易，本地系统未记录回调到达。",
            "渠道侧支付终态明确成功，平台通知日志缺失。",
            "通道对账记录为支付成功，本地订单还在处理中。",
            "交易结果查询成功，但异步支付通知尚未写入接收表。",
        ],
    ),
    "insufficient": (
        "query-timeout",
        [
            "查单超时，未收到通道支付状态，平台订单保持处理中。",
            "通道状态查询没有响应，本地无法确认付款是否完成。",
            "查询接口等待到超时上限，外部终态依旧未知。",
            "支付结果请求被读超时中断，没有可用通道流水。",
            "查询请求已发出却未拿到状态，当前付款结果不明。",
            "通道查单连接在读取响应时超时，未知交易最终状态。",
            "支付状态查询长时间无返回，不能推断成功或失败。",
            "远端查单响应缺失，平台只保留处理中记录。",
            "支付结果接口响应时限已过，外部状态尚未取得。",
            "通道查询超时且没有流水佐证，交易仍待人工核查。",
            "查单调用没有取得有效响应，支付终态无法确认。",
            "查询客户端报告读取超时，平台没有确认的渠道结果。",
            "订单状态查询返回未知，缺少进一步的通道凭据。",
            "查单链路在等待阶段中止，当前只知道本地处理中。",
            "通道侧查询未返回终态，不能根据超时认定扣款结果。",
        ],
    ),
    "conflict": (
        "terminal-conflict",
        [
            "本地订单已关单，随后收到通道成功支付结果。",
            "平台记录交易关闭，外部流水却标记付款完成。",
            "关闭终态已落库，稍后到达的回调声明支付成功。",
            "本地关单记录与通道成功查询结果相互矛盾。",
            "交易关闭动作完成后收到渠道成功通知，状态出现冲突。",
            "平台终态为关闭，渠道交易终态显示成功。",
            "已经关闭的支付单被回调告知通道付款成功。",
            "订单在本地结束为关闭，外部查单却返回成功。",
            "通道成功流水对应平台已关单交易，双方终态不一致。",
            "本地关闭状态存在，迟到通知表示支付已完成。",
            "渠道确认收款，但同一交易的平台状态仍为关闭。",
            "关单时间早于成功回调到达时间，需核对时序。",
            "平台拒绝修改已关闭订单，渠道回执却说明付款成功。",
            "交易被本地关单后，远端仍报告成功支付。",
            "关闭记录与渠道支付完成通知同属一笔交易。",
        ],
    ),
    "no_match": (
        None,
        [
            "日志归档校验发现压缩包头部格式与预期不一致。",
            "报表导出任务生成的文件扩展名和实际编码不符。",
            "离线归档程序读取配置文件时发现字符集标记错误。",
            "夜间数据备份校验发现文件末尾缺少结束标志。",
            "批次报表目录权限变化导致归档文件无法移动。",
            "文件压缩步骤报告字典损坏，待人工检查归档产物。",
            "历史报表索引重建时发现排序规则版本不一致。",
            "静态附件校验中发现文档页数与登记值不同。",
            "归档文件读取器发现一段无效的文本编码序列。",
            "离线报告任务写文件时磁盘空间不足。",
            "旧版导出模板缺失表头字段，报表无法打开。",
            "备份校验器发现两个文件的摘要值不一致。",
            "文件系统目录扫描时遇到失效的软链接。",
            "本地归档任务不能识别新增的压缩格式标识。",
            "离线文档生成任务缺少字体文件导致渲染失败。",
        ],
    ),
}


def case(index: int, group: str, summary: str, knowledge: str | None) -> dict:
    timestamp = datetime(2026, 9, 28, 10, tzinfo=timezone.utc) + timedelta(minutes=index)
    iso = timestamp.isoformat().replace("+00:00", "Z")
    return {
        "id": f"AI-{index:03d}",
        "input": {
            "statistics": {"observationCount": 1, "distinctTradeCount": 1, "firstObservedAt": iso,
                           "lastObservedAt": iso, "typeDistribution": {"QUERY": 1},
                           "ordersAmountByCurrency": {"CNY": 1000}},
            "evidence": [{"id": f"E-{index:03d}", "type": "QUERY", "occurredAt": iso, "summary": summary}],
            "omittedEvidenceCount": 0,
        },
        "category": group,
        "candidateKnowledgeIds": [knowledge] if knowledge else [],
        "scoringPoints": ["仅把证据写成事实", "将解释标为待验证假设", "不建议直接改变资金状态"],
    }


def main() -> None:
    root = Path(__file__).resolve().parents[1] / "docs/paylens/improvement-evaluation"
    root.mkdir(parents=True, exist_ok=True)
    dev, holdout = [], []
    index = 0
    for group, (knowledge, summaries) in GROUPS.items():
        assert len(summaries) == 15
        for pos, summary in enumerate(summaries):
            index += 1
            (dev if pos < 5 else holdout).append(case(index, group, summary, knowledge))
    assert len(dev) == 20 and len(holdout) == 40
    for name, rows in (("analysis-dev-v2.json", dev), ("analysis-holdout-v2.json", holdout)):
        output = {"datasetVersion": name.removesuffix(".json"), "cases": rows}
        (root / name).write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Frozen {len(dev)} development and {len(holdout)} holdout cases in {root}")


if __name__ == "__main__":
    main()
