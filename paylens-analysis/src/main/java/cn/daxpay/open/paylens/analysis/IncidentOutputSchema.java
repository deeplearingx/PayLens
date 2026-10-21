package cn.daxpay.open.paylens.analysis;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The one schema used by the prompt, production validation, and evaluation. */
public final class IncidentOutputSchema {
    private static final String RESOURCE = "/paylens/incident-output-v2.schema.json";
    private static final String CONTENT = load();

    private IncidentOutputSchema() {}

    public static String content() { return CONTENT; }

    public static void validate(JsonNode output, ObjectMapper mapper) {
        try {
            check(output, mapper.readTree(CONTENT), "$", true);
        } catch (DiagnosticValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("事件分析输出 Schema 无法读取", exception);
        }
    }

    private static void check(JsonNode value, JsonNode schema, String path, boolean required) {
        if (value == null || value.isMissingNode()) {
            if (required) invalid(path + " 缺失");
            return;
        }
        JsonNode type = schema.path("type");
        boolean matches = type.isArray() ? containsType(type, value) : matches(type.asText(), value);
        if (!matches) invalid(path + " 字段类型错误");
        if (value.isTextual() && schema.has("maxLength") && value.asText().length() > schema.path("maxLength").asInt()) {
            throw new DiagnosticValidationException("INVALID_LIMIT", path + " 长度超限");
        }
        if (value.isArray()) {
            if (schema.has("maxItems") && value.size() > schema.path("maxItems").asInt())
                throw new DiagnosticValidationException("INVALID_LIMIT", path + " 条数超限");
            JsonNode itemSchema = schema.path("items");
            for (int i = 0; i < value.size(); i++) check(value.get(i), itemSchema, path + "[" + i + "]", true);
        }
        if (value.isObject()) {
            JsonNode properties = schema.path("properties");
            for (var field = properties.properties().iterator(); field.hasNext();) {
                var entry = field.next();
                boolean isRequired = false;
                for (JsonNode name : schema.path("required")) {
                    if (entry.getKey().equals(name.asText())) { isRequired = true; break; }
                }
                check(value.get(entry.getKey()), entry.getValue(), path + "." + entry.getKey(), isRequired);
            }
        }
    }

    private static boolean containsType(JsonNode types, JsonNode value) {
        for (JsonNode candidate : types) if (matches(candidate.asText(), value)) return true;
        return false;
    }

    private static boolean matches(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private static void invalid(String reason) {
        throw new DiagnosticValidationException("INVALID_STRUCTURE", reason);
    }

    private static String load() {
        try (var input = IncidentOutputSchema.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("事件分析输出 Schema 缺失");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("事件分析输出 Schema 无法读取", exception);
        }
    }
}
