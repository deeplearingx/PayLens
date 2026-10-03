package cn.daxpay.open.paylens.analysis;

import java.util.regex.Pattern;

/** Applies best-effort masking to free-form evidence before persistence or model submission. */
public final class SensitiveTextSanitizer {
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)[\"']?(api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|passwd|secret)[\"']?(\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;&]+)"
    );
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)(\\bauthorization\\s*[:=]\\s*)(?:bearer|basic)\\s+[^\\s,;]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern API_TOKEN = Pattern.compile("(?i)\\b(?:sk|pk)[-_](?:live|test)[-_][A-Za-z0-9_-]{6,}\\b|\\bsk-[A-Za-z0-9_-]{16,}\\b|\\bgh[pousr]_[A-Za-z0-9]{20,}\\b");

    private SensitiveTextSanitizer() {}

    public static String sanitize(String value) {
        if (value == null) return "";
        return API_TOKEN.matcher(
                BEARER.matcher(
                        AUTHORIZATION.matcher(
                                SECRET_ASSIGNMENT.matcher(
                                        PHONE.matcher(EMAIL.matcher(value).replaceAll("[邮箱已脱敏]")).replaceAll("[手机号已脱敏]")
                                ).replaceAll("$1$2[密钥已脱敏]")
                        ).replaceAll("$1[认证信息已脱敏]")
                ).replaceAll("[认证信息已脱敏]")
        ).replaceAll("[密钥已脱敏]");
    }
}
