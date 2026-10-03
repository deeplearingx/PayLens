package cn.daxpay.open.paylens.analysis;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "paylens.analysis", name = "enabled", havingValue = "true")
public class IncidentAnalysisJsonValidator {
    private final ObjectMapper mapper;
    public IncidentAnalysisJsonValidator(ObjectMapper mapper) { this.mapper = mapper; }

    public IncidentAnalysisResult validate(String json, IncidentAnalysisInput input, Set<String> knowledgeIds) {
        Object parsed;
        try { parsed = mapper.readValue(json, Object.class); }
        catch (Exception exception) { throw new DiagnosticValidationException("INVALID_JSON", "事件分析未返回有效 JSON"); }
        if (!(parsed instanceof java.util.Map<?, ?>)) {
            throw new DiagnosticValidationException("INVALID_STRUCTURE", "事件分析 JSON 根节点必须是对象");
        }
        IncidentAnalysisResult result;
        try { result = mapper.readValue(json, IncidentAnalysisResult.class); }
        catch (Exception exception) { throw new DiagnosticValidationException("INVALID_STRUCTURE", "事件分析 JSON 字段类型与输出契约不匹配"); }
        if (result == null || blank(result.commonPhenomenon()) || result.commonPhenomenon().length() > 1200 ||
                result.facts() == null || result.differences() == null || result.possibleExplanations() == null ||
                result.checkSteps() == null || result.missingInformation() == null || result.knowledgeCitations() == null) {
            throw new DiagnosticValidationException("INVALID_STRUCTURE", "事件分析缺少必填字段或共同现象无效");
        }
        if (result.facts().size() > 12 || result.differences().size() > 12 || result.possibleExplanations().size() > 3 ||
                result.checkSteps().size() > 8 || result.missingInformation().size() > 8) {
            throw new DiagnosticValidationException("INVALID_STRUCTURE", "事件分析结果超过允许条数");
        }
        Set<String> allowedEvidence = input.evidence().stream().map(DiagnosticEvidence::id).collect(Collectors.toSet());
        Set<String> citedEvidence = new HashSet<>();
        result.facts().forEach(fact -> {
            if (blank(fact.statement()) || fact.evidenceIds().isEmpty()) throw invalidFact();
            citedEvidence.addAll(fact.evidenceIds());
        });
        result.differences().forEach(difference -> {
            if (blank(difference.description()) || difference.evidenceIds().isEmpty()) throw invalidFact();
            citedEvidence.addAll(difference.evidenceIds());
        });
        result.possibleExplanations().forEach(explanation -> {
            if (blank(explanation.hypothesis()) || explanation.evidenceIds().isEmpty() || blank(explanation.verification())) {
                throw invalidFact();
            }
            citedEvidence.addAll(explanation.evidenceIds());
        });
        if (!allowedEvidence.containsAll(citedEvidence)) {
            throw new DiagnosticValidationException("INVALID_EVIDENCE_REFERENCE", "事件分析引用了本次快照之外的证据");
        }
        if (result.knowledgeCitations().stream().anyMatch(id -> !knowledgeIds.contains(id))) {
            throw new DiagnosticValidationException("INVALID_KNOWLEDGE_CITATION", "事件分析引用了本次检索结果之外的知识 ID");
        }
        if (!knowledgeIds.isEmpty() && result.knowledgeCitations().isEmpty() && blank(result.knowledgeOmissionReason())) {
            throw new DiagnosticValidationException("MISSING_KNOWLEDGE_DECISION", "检索到知识时须引用知识或说明未采用原因");
        }
        if (result.knowledgeOmissionReason() != null && result.knowledgeOmissionReason().length() > 500) {
            throw new DiagnosticValidationException("INVALID_STRUCTURE", "知识未采用说明过长");
        }
        String usage = knowledgeIds.isEmpty() ? "NO_MATCH" : result.knowledgeCitations().isEmpty() ? "NOT_USED" : "CITED";
        return new IncidentAnalysisResult(result.commonPhenomenon(), result.facts(), result.differences(),
                result.possibleExplanations(), result.checkSteps(), result.missingInformation(),
                result.knowledgeCitations(), "NOT_USED".equals(usage) ? result.knowledgeOmissionReason() : null, usage);
    }

    private DiagnosticValidationException invalidFact() {
        return new DiagnosticValidationException("INVALID_STRUCTURE", "事实、差异和待验证解释必须带有效证据引用");
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
