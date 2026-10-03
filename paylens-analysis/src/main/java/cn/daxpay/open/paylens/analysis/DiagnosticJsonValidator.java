package cn.daxpay.open.paylens.analysis;

import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "paylens.analysis", name = "enabled", havingValue = "true")
public class DiagnosticJsonValidator {
    private final ObjectMapper mapper;
    public DiagnosticJsonValidator(ObjectMapper mapper) { this.mapper = mapper; }

    public DiagnosticResult validate(String json, DiagnosticInput input, Set<String> knowledgeIds) {
        DiagnosticResult result;
        try {
            result = mapper.readValue(json, DiagnosticResult.class);
        } catch (Exception e) {
            throw new DiagnosticValidationException("INVALID_JSON", "模型未返回有效 JSON");
        }

        if (result == null || result.summary() == null || result.summary().isBlank() || result.summary().length() > 1200 ||
                result.facts() == null || result.possibleCauses() == null || result.checkSteps() == null ||
                result.missingInformation() == null || result.knowledgeCitations() == null) {
            throw new DiagnosticValidationException("INVALID_STRUCTURE", "诊断结果缺少必填字段或摘要无效");
        }
        if (result.possibleCauses().size() > 3 || result.checkSteps().size() > 8 ||
                result.facts().size() > 12 || result.missingInformation().size() > 8) {
            throw new DiagnosticValidationException("INVALID_STRUCTURE", "诊断结果超过允许条数");
        }
        if (result.facts().stream().anyMatch(f -> f.statement() == null || f.statement().isBlank() || f.evidenceIds().isEmpty()) ||
                result.possibleCauses().stream().anyMatch(c -> c.hypothesis() == null || c.hypothesis().isBlank() ||
                        c.evidenceIds().isEmpty() || c.verification() == null || c.verification().isBlank())) {
            throw new DiagnosticValidationException("INVALID_STRUCTURE", "事实和待验证原因必须有证据引用，原因还必须有核查建议");
        }
        Set<String> evidenceIds = input.evidence().stream().map(DiagnosticEvidence::id).collect(Collectors.toSet());
        boolean badEvidence = result.facts().stream().flatMap(f -> f.evidenceIds().stream()).anyMatch(id -> !evidenceIds.contains(id)) ||
                result.possibleCauses().stream().flatMap(c -> c.evidenceIds().stream()).anyMatch(id -> !evidenceIds.contains(id));
        if (badEvidence) throw new DiagnosticValidationException("INVALID_EVIDENCE_REFERENCE", "模型引用了本次证据之外的 ID");
        if (result.knowledgeCitations().stream().anyMatch(id -> !knowledgeIds.contains(id))) {
            throw new DiagnosticValidationException("INVALID_KNOWLEDGE_CITATION", "模型引用了本次检索结果之外的知识 ID");
        }
        if (!knowledgeIds.isEmpty() && result.knowledgeCitations().isEmpty() &&
                (result.knowledgeOmissionReason() == null || result.knowledgeOmissionReason().isBlank())) {
            throw new DiagnosticValidationException("MISSING_KNOWLEDGE_DECISION", "检索到知识时须引用知识或说明未采用原因");
        }
        if (result.knowledgeOmissionReason() != null && result.knowledgeOmissionReason().length() > 500) {
            throw new DiagnosticValidationException("INVALID_STRUCTURE", "知识未采用说明过长");
        }
        String usage = !knowledgeIds.isEmpty() && result.knowledgeCitations().isEmpty() ? "NOT_USED" :
                !result.knowledgeCitations().isEmpty() ? "CITED" : "NO_MATCH";
        return result.withKnowledgeUse(usage,
                "NOT_USED".equals(usage) ? result.knowledgeOmissionReason() : null);
    }
}
