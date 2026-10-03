package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.paylens.analysis.AnalysisQueueFullException;
import cn.daxpay.open.paylens.analysis.AnalysisTask;
import cn.daxpay.open.paylens.analysis.DiagnosticAnalysisService;
import cn.daxpay.open.paylens.analysis.ReanalysisRequiredException;
import cn.daxpay.open.payment.admin.service.trade.AbnormalOrderAdminService;
import cn.daxpay.open.payment.common.context.MerchantContextLoader;
import cn.daxpay.open.payment.common.context.PaymentContext;
import cn.daxpay.open.payment.trade.abnormal.dao.AbnormalOrderManager;
import cn.daxpay.open.payment.trade.abnormal.entity.AbnormalOrder;
import cn.daxpay.open.payment.trade.abnormal.enums.AbnormalHandleStatusEnum;
import cn.daxpay.open.payment.trade.enums.PayFundStatusEnum;
import cn.daxpay.open.payment.trade.order.dao.PayTradeManager;
import cn.daxpay.open.payment.trade.runtime.service.sync.PaySyncService;
import cn.daxpay.open.platform.core.annotation.PermCode;
import cn.daxpay.open.platform.core.code.CommonCode;
import cn.daxpay.open.platform.core.code.PermCodes;
import cn.daxpay.open.platform.core.rest.Res;
import cn.daxpay.open.platform.core.rest.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@PermCode(menuCode = PermCodes.Trade.AbnormalOrder.MENU)
@ConditionalOnProperty(prefix = "paylens.demo", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/paylens/api")
@RequiredArgsConstructor
public class PayLensController {
    private final PayLensAnomalyFacade facade;
    private final ObjectProvider<DiagnosticAnalysisService> analysisServiceProvider;
    private final AbnormalOrderManager abnormalOrderManager;
    private final AbnormalOrderAdminService abnormalOrderAdminService;
    private final PayTradeManager payTradeManager;
    private final PaySyncService paySyncService;
    private final PaymentContext paymentContext;
    private final MerchantContextLoader merchantContextLoader;
    @Value("${paylens.demo.mch-no:}")
    private String demoMchNo;

    @GetMapping("/anomalies")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<List<AnomalyRow>> anomalies() {
        requireDemoMerchant();
        return Res.ok(inDemoMerchantContext(() -> abnormalOrderManager.lambdaQuery()
                .eq(AbnormalOrder::getMchNo, demoMchNo)
                .eq(AbnormalOrder::getHandleStatus, AbnormalHandleStatusEnum.PENDING.getCode())
                .orderByDesc(AbnormalOrder::getId).last("limit 100").list().stream()
                .map(a -> new AnomalyRow(a.getId(), a.getTradeNo(), a.getTitle(), a.getAmount(), a.getCurrency(),
                        a.getTradeStatus(), a.getChannelStatus(), a.getCreateTime()))
                .toList()));
    }

    @GetMapping("/trades")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<List<PayLensAnomalyFacade.DemoTradeRow>> demoTrades() {
        return Res.ok(facade.demoTrades());
    }

    @GetMapping("/trades/{tradeNo}/timeline")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<PayLensAnomalyFacade.AnomalyDetail> tradeTimeline(@PathVariable String tradeNo) {
        return Res.ok(facade.detailForDemoTrade(tradeNo));
    }

    @PostMapping("/trades/{tradeNo}/sync")
    @PermCode(code = PermCodes.Action.MANAGE)
    public Result<Void> syncDemoTrade(@PathVariable String tradeNo) {
        PayLensAnomalyFacade.AnomalyDetail detail = facade.detailForDemoTrade(tradeNo);
        var trade = payTradeManager.findByTradeNoNotTenant(detail.tradeNo())
                .filter(item -> demoMchNo.equals(item.getMchNo()))
                .orElseThrow(() -> new IllegalArgumentException("交易不存在"));
        syncWithinMerchantContext(trade);
        return Res.ok();
    }

    @PostMapping("/trades/{tradeNo}/analyses")
    @PermCode(code = PermCodes.Action.MANAGE)
    public ResponseEntity<Result<AnalysisTask>> analyzeTrade(@PathVariable String tradeNo) {
        PayLensAnomalyFacade.AnomalyDetail detail = facade.detailForDemoTrade(tradeNo);
        try {
            return ResponseEntity.accepted().body(Res.ok(analysisService().submit(detail.mchNo(), facade.toInput(detail))));
        } catch (AnalysisQueueFullException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(queueFull(e));
        }
    }

    @GetMapping("/trades/{tradeNo}/analyses")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<DiagnosticAnalysisService.AnalysisHistory> analysisHistory(
            @PathVariable String tradeNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PayLensAnomalyFacade.AnomalyDetail detail = facade.detailForDemoTrade(tradeNo);
        return Res.ok(analysisService().history(detail.mchNo(), detail.tradeNo(), facade.toInput(detail), page, pageSize));
    }

    @GetMapping("/trades/{tradeNo}/analyses/{taskId}")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<AnalysisTask> tradeAnalysis(@PathVariable String tradeNo, @PathVariable String taskId) {
        PayLensAnomalyFacade.AnomalyDetail detail = facade.detailForDemoTrade(tradeNo);
        AnalysisTask task = analysisService().get(detail.mchNo(), taskId);
        if (!tradeNo.equals(task.tradeNo())) throw new IllegalArgumentException("分析任务不属于当前交易");
        return Res.ok(task);
    }

    @PostMapping("/trades/{tradeNo}/analyses/{taskId}/retry")
    @PermCode(code = PermCodes.Action.MANAGE)
    public ResponseEntity<Result<AnalysisTask>> retryTradeAnalysis(@PathVariable String tradeNo, @PathVariable String taskId) {
        PayLensAnomalyFacade.AnomalyDetail detail = facade.detailForDemoTrade(tradeNo);
        AnalysisTask oldTask = analysisService().get(detail.mchNo(), taskId);
        if (!tradeNo.equals(oldTask.tradeNo())) throw new IllegalArgumentException("分析任务不属于当前交易");
        try {
            return ResponseEntity.accepted().body(Res.ok(analysisService().retry(detail.mchNo(), taskId)));
        } catch (AnalysisQueueFullException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(queueFull(e));
        } catch (ReanalysisRequiredException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Res.response(CommonCode.FAIL_CODE, e.getMessage()));
        }
    }

    @GetMapping("/anomalies/{abnormalId}")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<PayLensAnomalyFacade.AnomalyDetail> detail(@PathVariable Long abnormalId) {
        return Res.ok(facade.detail(abnormalId));
    }

    @PostMapping("/anomalies/{abnormalId}/analyses")
    @PermCode(code = PermCodes.Action.MANAGE)
    public ResponseEntity<Result<AnalysisTask>> analyze(@PathVariable Long abnormalId) {
        PayLensAnomalyFacade.AnomalyDetail detail = facade.detail(abnormalId);
        try {
            return ResponseEntity.accepted().body(Res.ok(analysisService().submit(detail.mchNo(), facade.toInput(detail))));
        } catch (AnalysisQueueFullException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(queueFull(e));
        }
    }

    @GetMapping("/anomalies/{abnormalId}/analyses/{taskId}")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<AnalysisTask> analysis(@PathVariable Long abnormalId, @PathVariable String taskId) {
        PayLensAnomalyFacade.AnomalyDetail detail = facade.detail(abnormalId);
        AnalysisTask task = analysisService().get(detail.mchNo(), taskId);
        if (!detail.tradeNo().equals(task.tradeNo())) throw new IllegalArgumentException("分析任务不属于当前异常订单");
        return Res.ok(task);
    }

    @PostMapping("/anomalies/{abnormalId}/analyses/{taskId}/retry")
    @PermCode(code = PermCodes.Action.MANAGE)
    public ResponseEntity<Result<AnalysisTask>> retry(@PathVariable Long abnormalId, @PathVariable String taskId) {
        PayLensAnomalyFacade.AnomalyDetail detail = facade.detail(abnormalId);
        AnalysisTask oldTask = analysisService().get(detail.mchNo(), taskId);
        if (!detail.tradeNo().equals(oldTask.tradeNo())) throw new IllegalArgumentException("分析任务不属于当前异常订单");
        try {
            return ResponseEntity.accepted().body(Res.ok(analysisService().retry(detail.mchNo(), taskId)));
        } catch (AnalysisQueueFullException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(queueFull(e));
        } catch (ReanalysisRequiredException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Res.response(CommonCode.FAIL_CODE, e.getMessage()));
        }
    }

    @PostMapping("/anomalies/{abnormalId}/sync")
    @PermCode(code = PermCodes.Action.MANAGE)
    public Result<Void> sync(@PathVariable Long abnormalId) {
        PayLensAnomalyFacade.AnomalyDetail detail = facade.detail(abnormalId);
        var trade = payTradeManager.findByTradeNoNotTenant(detail.tradeNo())
                .filter(item -> demoMchNo.equals(item.getMchNo()))
                .orElseThrow(() -> new IllegalArgumentException("交易不存在"));
        syncWithinMerchantContext(trade);
        return Res.ok();
    }

    @PostMapping("/anomalies/{abnormalId}/confirm-success")
    @PermCode(code = PermCodes.Action.MANAGE)
    public Result<Void> confirmSuccess(@PathVariable Long abnormalId, @RequestBody ActionRequest request) {
        PayLensAnomalyFacade.AnomalyDetail detail = facade.detail(abnormalId);
        String remark = requiredRemark(request, "核实通道成功后请填写处置备注");
        var trade = payTradeManager.findByTradeNoNotTenant(detail.tradeNo())
                .filter(item -> demoMchNo.equals(item.getMchNo()))
                .orElseThrow(() -> new IllegalArgumentException("交易不存在"));
        PaySyncService.SyncEvidence syncEvidence = syncWithinMerchantContext(trade);
        if (!detail.tradeNo().equals(syncEvidence.tradeNo()) ||
                !PayFundStatusEnum.SUCCESS.getCode().equals(syncEvidence.channelStatus())) {
            throw new IllegalStateException("重新查单未取得通道明确成功证据，异常单保持待处理");
        }
        inDemoMerchantContext(() -> abnormalOrderAdminService.confirmSuccess(abnormalId, remark));
        return Res.ok();
    }

    @PostMapping("/anomalies/{abnormalId}/ignore")
    @PermCode(code = PermCodes.Action.MANAGE)
    public Result<Void> ignore(@PathVariable Long abnormalId, @RequestBody ActionRequest request) {
        facade.detail(abnormalId);
        String remark = requiredRemark(request, "忽略异常单必须填写核实说明");
        inDemoMerchantContext(() -> abnormalOrderAdminService.ignore(abnormalId, remark));
        return Res.ok();
    }

    @GetMapping(value = "/anomalies/{abnormalId}/analyses/{taskId}/report", produces = "text/markdown;charset=UTF-8")
    @PermCode(code = PermCodes.Action.VIEW)
    public ResponseEntity<String> report(@PathVariable Long abnormalId, @PathVariable String taskId) {
        PayLensAnomalyFacade.AnomalyDetail detail = facade.detail(abnormalId);
        AnalysisTask task = analysis(abnormalId, taskId).getData();
        return markdownReport(task, analysisService().snapshotHash(facade.toInput(detail)));
    }

    @GetMapping(value = "/trades/{tradeNo}/analyses/{taskId}/report", produces = "text/markdown;charset=UTF-8")
    @PermCode(code = PermCodes.Action.VIEW)
    public ResponseEntity<String> tradeReport(@PathVariable String tradeNo, @PathVariable String taskId) {
        PayLensAnomalyFacade.AnomalyDetail detail = facade.detailForDemoTrade(tradeNo);
        AnalysisTask task = tradeAnalysis(tradeNo, taskId).getData();
        return markdownReport(task, analysisService().snapshotHash(facade.toInput(detail)));
    }

    private ResponseEntity<String> markdownReport(AnalysisTask task, String currentSnapshotHash) {
        if (!"SUCCEEDED".equals(task.status()) || task.result() == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.TEXT_PLAIN).body("分析尚未成功，当前状态：" + task.status());
        }
        var result = task.result();
        StringBuilder report = new StringBuilder("# 支付异常分析报告\n\n")
                .append("- 交易号：").append(markdownText(task.tradeNo())).append("\n")
                .append("- 任务状态：").append(task.status()).append("\n")
                .append("- 分析耗时：").append(task.durationMs()).append(" ms\n");
        if (task.execution() != null) {
            var metadata = task.execution();
            report.append("- 证据快照时间：").append(metadata.snapshotAt() == null ? "未知" : metadata.snapshotAt()).append("\n")
                    .append("- 当前证据对比：").append(metadata.legacy() ? "历史版本，无法自动核对" :
                            metadata.snapshotHash().equals(currentSnapshotHash) ? "与当前证据一致" : "基于历史证据").append("\n")
                    .append("- 证据摘要：").append(markdownText(metadata.snapshotHash() == null ? "LEGACY_UNKNOWN" : metadata.snapshotHash())).append("\n")
                    .append("- 模型：").append(markdownText(metadata.modelId() == null ? "LEGACY_UNKNOWN" : metadata.modelId())).append("\n")
                    .append("- 模型配置摘要：").append(markdownText(metadata.modelConfigurationId() == null ? "LEGACY_UNKNOWN" : metadata.modelConfigurationId())).append("\n")
                    .append("- 提示词版本：").append(markdownText(metadata.promptVersion() == null ? "LEGACY_UNKNOWN" : metadata.promptVersion())).append("\n")
                    .append("- 输出结构版本：").append(markdownText(metadata.outputSchemaVersion() == null ? "LEGACY_UNKNOWN" : metadata.outputSchemaVersion())).append("\n")
                    .append("- 知识库版本：").append(markdownText(metadata.knowledgeVersion() == null ? "LEGACY_UNKNOWN" : metadata.knowledgeVersion())).append("\n\n");
        } else {
            report.append("- 版本信息：历史版本，无法自动核对\n\n");
        }
        report.append("## 摘要\n\n").append(markdownText(result.summary())).append("\n\n## 已确认事实\n");
        result.facts().forEach(f -> report.append("- ").append(markdownText(f.statement())).append("（证据：")
                .append(String.join(", ", f.evidenceIds())).append("）\n"));
        report.append("\n## 待验证原因\n");
        result.possibleCauses().forEach(c -> report.append("- ").append(markdownText(c.hypothesis())).append("；核查：")
                .append(markdownText(c.verification())).append("\n"));
        report.append("\n## 排查步骤\n");
        result.checkSteps().forEach(s -> report.append("- ").append(markdownText(s)).append("\n"));
        report.append("\n## 缺少的信息\n");
        result.missingInformation().forEach(s -> report.append("- ").append(markdownText(s)).append("\n"));
        report.append("\n## 知识引用\n");
        if (result.knowledgeCitations().isEmpty()) {
            report.append("\n本次分析未返回知识库引用。\n");
            if (result.knowledgeOmissionReason() != null && !result.knowledgeOmissionReason().isBlank()) {
                report.append("模型声明的未采用原因：").append(markdownText(result.knowledgeOmissionReason())).append("\n");
            }
        } else {
            result.knowledgeCitations().forEach(id -> {
                var chunk = task.execution() == null ? null : task.execution().retrievedKnowledge().stream()
                        .filter(item -> item.id().equals(id)).findFirst().orElse(null);
                report.append("- ").append(markdownText(id));
                if (chunk != null) report.append(" · ").append(markdownText(chunk.title()))
                        .append("\n\n  ").append(markdownText(chunk.content()));
                report.append("\n");
            });
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(report.toString());
    }

    private String markdownText(String value) {
        if (value == null) return "";
        return value.replace("\r", " ").replace("\n", " ").replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\\", "\\\\")
                .replace("`", "\\`").replace("*", "\\*").replace("_", "\\_")
                .replace("[", "\\[").replace("]", "\\]").replace("(", "\\(")
                .replace(")", "\\)").replace("#", "\\#").replace("|", "\\|");
    }

    private String requiredRemark(ActionRequest request, String message) {
        if (request == null || request.remark() == null || request.remark().isBlank()) throw new IllegalArgumentException(message);
        return request.remark().substring(0, Math.min(300, request.remark().length()));
    }

    private <T> Result<T> queueFull(AnalysisQueueFullException exception) {
        return Res.response(CommonCode.FAIL_CODE, "PAYLENS_QUEUE_FULL: " + exception.getMessage());
    }

    private DiagnosticAnalysisService analysisService() {
        return analysisServiceProvider.getIfAvailable(() -> {
            throw new IllegalStateException("PayLens 分析组件未启用，请设置 paylens.analysis.enabled=true");
        });
    }

    private void requireDemoMerchant() {
        if (demoMchNo == null || demoMchNo.isBlank()) {
            throw new IllegalStateException("未配置专用 PayLens 演示商户");
        }
    }

    private PaySyncService.SyncEvidence syncWithinMerchantContext(cn.daxpay.open.payment.trade.order.entity.PayTrade trade) {
        return inDemoMerchantContext(() -> paySyncService.syncPayOrderWithEvidence(trade));
    }

    private void inDemoMerchantContext(Runnable action) {
        inDemoMerchantContext(() -> {
            action.run();
            return null;
        });
    }

    private <T> T inDemoMerchantContext(Supplier<T> action) {
        requireDemoMerchant();
        return paymentContext.runAs(() -> {
            merchantContextLoader.initMch(demoMchNo);
            if (!Objects.equals(demoMchNo, paymentContext.getMchNo())) {
                throw new IllegalStateException("当前客户端商户范围与 PayLens 演示商户不一致");
            }
            return action.get();
        });
    }

    public record AnomalyRow(Long id, String tradeNo, String title, Long amount, String currency,
                             String localStatus, String channelStatus, OffsetDateTime createdAt) {}
    public record ActionRequest(String remark) {}
}
