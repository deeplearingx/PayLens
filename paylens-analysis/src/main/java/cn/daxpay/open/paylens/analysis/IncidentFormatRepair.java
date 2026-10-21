package cn.daxpay.open.paylens.analysis;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared, bounded repair context for production and paired evaluation. */
public final class IncidentFormatRepair {
    private IncidentFormatRepair() { }

    public static String prompt(String original, String invalid, String code, ObjectMapper mapper) {
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("instructions", "只修正 JSON 格式、缺失字段及字段类型，保留原证据与知识范围；不得增加事实或引用。仅输出修正后的 JSON。");
            context.put("originalPrompt", original);
            context.put("invalidOutput", invalid);
            context.put("validationError", code);
            String prompt = mapper.writeValueAsString(context);
            if (prompt.getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
                throw new DiagnosticValidationException("REPAIR_CONTEXT_TOO_LARGE", "修复上下文超过 16 KB");
            }
            return prompt;
        } catch (DiagnosticValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("修复上下文无法序列化", exception);
        }
    }
}
