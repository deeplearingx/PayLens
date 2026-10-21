package cn.daxpay.open.paylens.demo;

import cn.daxpay.open.paylens.analysis.AnalysisQueueFullException;
import cn.daxpay.open.paylens.analysis.DiagnosticEvidence;
import cn.daxpay.open.paylens.analysis.IncidentAnalysisInput;
import cn.daxpay.open.paylens.analysis.IncidentAnalysisService;
import cn.daxpay.open.paylens.analysis.IncidentAnalysisTask;
import cn.daxpay.open.paylens.analysis.ReanalysisRequiredException;
import cn.daxpay.open.paylens.incidents.domain.Observation;
import cn.daxpay.open.platform.capability.auth.util.SecurityUtil;
import cn.daxpay.open.platform.core.annotation.PermCode;
import cn.daxpay.open.platform.core.code.CommonCode;
import cn.daxpay.open.platform.core.code.PermCodes;
import cn.daxpay.open.platform.core.rest.Res;
import cn.daxpay.open.platform.core.rest.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@PermCode(menuCode = PermCodes.Trade.AbnormalOrder.MENU)
@ConditionalOnProperty(prefix = "paylens.incidents", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/paylens/api")
@RequiredArgsConstructor
public class PayLensIncidentController {
    private final PayLensIncidentBatchService batches;
    private final PayLensIncidentAdjustmentService adjustments;
    private final PayLensMergeSuggestionService suggestions;
    private final ObjectProvider<IncidentAnalysisService> analysisProvider;
    @Value("${paylens.demo.mch-no:}")
    private String demoMchNo;

    @PostMapping("/incident-batches")
    @PermCode(code = PermCodes.Action.MANAGE)
    public ResponseEntity<Result<PayLensIncidentBatchService.BatchView>> createBatch(@RequestBody RangeRequest request) {
        try {
            var batch = batches.submit(request.from(), request.to(), request.idempotencyKey());
            return ResponseEntity.accepted().body(Res.ok(batch));
        } catch (AnalysisQueueFullException exception) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Res.response(CommonCode.FAIL_CODE, "SCAN_QUEUE_FULL"));
        }
    }

    @GetMapping("/incident-batches/{batchId}")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<PayLensIncidentBatchService.BatchView> batch(@PathVariable String batchId) {
        return Res.ok(batches.get(batchId));
    }

    @GetMapping("/incident-batches/{batchId}/incidents")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<PayLensIncidentBatchService.IncidentPage> incidents(
            @PathVariable String batchId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Res.ok(batches.incidents(batchId, page, pageSize));
    }

    @GetMapping("/incidents/{incidentId}")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<PayLensIncidentBatchService.IncidentDetail> incident(@PathVariable String incidentId) {
        return Res.ok(batches.detail(incidentId));
    }

    @GetMapping("/incidents/{incidentId}/merge-suggestions")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<PayLensMergeSuggestionService.Suggestions> mergeSuggestions(@PathVariable String incidentId) {
        return Res.ok(suggestions.forIncident(incidentId));
    }

    @PostMapping("/incidents/{incidentId}/split")
    @PermCode(code = PermCodes.Action.MANAGE)
    public ResponseEntity<Result<PayLensIncidentAdjustmentService.SplitResult>> split(
            @PathVariable String incidentId, @RequestBody SplitRequest request) {
        try {
            return ResponseEntity.ok(Res.ok(adjustments.split(incidentId, request.expectedVersion(), request.groups(),
                    request.remark(), String.valueOf(SecurityUtil.getUserId()))));
        } catch (IncidentVersionConflictException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Res.response(CommonCode.FAIL_CODE, "INCIDENT_VERSION_CONFLICT"));
        }
    }

    @PostMapping("/incidents/merge")
    @PermCode(code = PermCodes.Action.MANAGE)
    public ResponseEntity<Result<PayLensIncidentAdjustmentService.MergeResult>> merge(
            @RequestBody MergeRequest request) {
        try {
            return ResponseEntity.ok(Res.ok(adjustments.merge(request.incidents(), request.remark(),
                    String.valueOf(SecurityUtil.getUserId()))));
        } catch (IncidentVersionConflictException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Res.response(CommonCode.FAIL_CODE, "INCIDENT_VERSION_CONFLICT"));
        }
    }

    @PostMapping("/incidents/{incidentId}/refresh")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<PayLensIncidentAdjustmentService.Progress> refresh(@PathVariable String incidentId) {
        batches.detail(incidentId);
        return Res.ok(adjustments.refresh(incidentId));
    }

    @PostMapping("/incidents/{incidentId}/analyses")
    @PermCode(code = PermCodes.Action.MANAGE)
    public ResponseEntity<Result<IncidentAnalysisTask>> analyze(@PathVariable String incidentId) {
        var incident = batches.detail(incidentId);
        try {
            return ResponseEntity.accepted().body(Res.ok(analysisService().submit(demoMchNo,
                    incident.id(), incident.version(), toAnalysisInput(incident))));
        } catch (AnalysisQueueFullException exception) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Res.response(CommonCode.FAIL_CODE, "PAYLENS_QUEUE_FULL"));
        }
    }

    @GetMapping("/incidents/{incidentId}/analyses")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<IncidentAnalysisService.IncidentAnalysisPage> analysisHistory(
            @PathVariable String incidentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        var incident = batches.detail(incidentId);
        return Res.ok(analysisService().history(demoMchNo, incident.id(), page, pageSize));
    }

    @GetMapping("/incidents/{incidentId}/analyses/{analysisId}")
    @PermCode(code = PermCodes.Action.VIEW)
    public Result<IncidentAnalysisTask> analysis(@PathVariable String incidentId, @PathVariable String analysisId) {
        var incident = batches.detail(incidentId);
        IncidentAnalysisTask task = analysisService().get(demoMchNo, analysisId);
        if (!incidentId.equals(task.incidentId())) throw new IllegalArgumentException("分析任务不属于当前事件");
        return Res.ok(task);
    }

    @PostMapping("/incidents/{incidentId}/analyses/{analysisId}/retry")
    @PermCode(code = PermCodes.Action.MANAGE)
    public ResponseEntity<Result<IncidentAnalysisTask>> retry(@PathVariable String incidentId,
                                                                @PathVariable String analysisId) {
        var incident = batches.detail(incidentId);
        IncidentAnalysisTask old = analysisService().get(demoMchNo, analysisId);
        if (!incidentId.equals(old.incidentId())) throw new IllegalArgumentException("分析任务不属于当前事件");
        try {
            return ResponseEntity.accepted().body(Res.ok(analysisService().retry(demoMchNo, analysisId)));
        } catch (AnalysisQueueFullException exception) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Res.response(CommonCode.FAIL_CODE, "PAYLENS_QUEUE_FULL"));
        } catch (ReanalysisRequiredException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Res.response(CommonCode.FAIL_CODE, "REANALYSIS_REQUIRED"));
        }
    }

    @GetMapping(value = "/incidents/{incidentId}/analyses/{analysisId}/report", produces = "text/markdown;charset=UTF-8")
    @PermCode(code = PermCodes.Action.VIEW)
    public ResponseEntity<String> report(@PathVariable String incidentId, @PathVariable String analysisId) {
        IncidentAnalysisTask task = analysis(incidentId, analysisId).getData();
        if (!"SUCCEEDED".equals(task.status()) || task.result() == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.TEXT_PLAIN)
                    .body("事件分析尚未成功，当前状态：" + task.status());
        }
        var result = task.result();
        StringBuilder report = new StringBuilder("# 支付异常事件分析\n\n")
                .append("- 事件：").append(markdown(incidentId)).append("\n")
                .append("- 事件版本：").append(task.incidentVersion()).append("\n")
                .append("- 任务状态：").append(task.status()).append("\n")
                .append("- 分析耗时：").append(task.durationMs()).append(" ms\n");
        if (task.execution() != null) {
            report.append("- 证据摘要：").append(markdown(task.execution().snapshotHash())).append("\n")
                    .append("- 模型：").append(markdown(task.execution().modelId())).append("\n")
                    .append("- 提示词版本：").append(markdown(task.execution().promptVersion())).append("\n")
                    .append("- 知识库版本：").append(markdown(task.execution().knowledgeVersion())).append("\n");
        }
        report.append("\n## 共同现象\n\n").append(markdown(result.commonPhenomenon())).append("\n\n## 已确认事实\n");
        result.facts().forEach(fact -> report.append("- ").append(markdown(fact.statement()))
                .append("（证据：").append(String.join(", ", fact.evidenceIds())).append("）\n"));
        report.append("\n## 组内差异\n");
        result.differences().forEach(difference -> report.append("- ").append(markdown(difference.description()))
                .append("（证据：").append(String.join(", ", difference.evidenceIds())).append("）\n"));
        report.append("\n## 待验证解释\n");
        result.possibleExplanations().forEach(item -> report.append("- ").append(markdown(item.hypothesis()))
                .append("；核查：").append(markdown(item.verification())).append("（证据：")
                .append(String.join(", ", item.evidenceIds())).append("）\n"));
        report.append("\n## 核查步骤\n");
        result.checkSteps().forEach(step -> report.append("- ").append(markdown(step)).append("\n"));
        report.append("\n## 缺失信息\n");
        result.missingInformation().forEach(item -> report.append("- ").append(markdown(item)).append("\n"));
        report.append("\n## 知识使用\n\n").append(result.knowledgeUseStatus()).append("\n");
        if (result.knowledgeOmissionReason() != null) report.append("模型声明未采用：").append(markdown(result.knowledgeOmissionReason())).append("\n");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(report.toString());
    }

    private IncidentAnalysisInput toAnalysisInput(PayLensIncidentBatchService.IncidentDetail incident) {
        List<Observation> sorted = incident.members().stream().sorted(Comparator.comparing(Observation::occurredAt)
                .thenComparing(Observation::id)).toList();
        Map<String, Observation> selected = new LinkedHashMap<>();
        sorted.forEach(observation -> {
            String category = value(observation.phase()) + "|" + value(observation.abnormalType()) + "|" + value(observation.errorCode());
            if (selected.size() < 15) selected.putIfAbsent(category, observation);
        });
        if (!sorted.isEmpty()) {
            selected.putIfAbsent("EARLIEST", sorted.getFirst());
            selected.putIfAbsent("LATEST", sorted.getLast());
        }
        for (Observation observation : sorted) {
            if (selected.size() >= 20) break;
            selected.putIfAbsent("OBS-" + observation.id(), observation);
        }
        List<DiagnosticEvidence> evidence = selected.values().stream().limit(20)
                .map(item -> new DiagnosticEvidence("E-" + item.id(), item.phase(),
                        item.occurredAt().atOffset(ZoneOffset.UTC), item.normalizedDescription())).toList();
        return new IncidentAnalysisInput(incident.statistics(), evidence, Math.max(0, sorted.size() - evidence.size()));
    }

    private IncidentAnalysisService analysisService() {
        return analysisProvider.getIfAvailable(() -> {
            throw new IllegalStateException("事件 AI 分析未启用，请设置 paylens.analysis.enabled=true");
        });
    }

    private String value(String value) { return value == null ? "UNKNOWN" : value; }
    private String markdown(String value) {
        if (value == null) return "";
        return value.replace("\r", " ").replace("\n", " ").replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("`", "\\`")
                .replace("*", "\\*").replace("_", "\\_").replace("[", "\\[").replace("]", "\\]");
    }

    public record RangeRequest(Instant from, Instant to, String idempotencyKey) {}
    public record SplitRequest(int expectedVersion, List<List<String>> groups, String remark) {}
    public record MergeRequest(List<PayLensIncidentAdjustmentService.VersionRef> incidents, String remark) {}
}
