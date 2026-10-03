package cn.daxpay.open.paylens.analysis;

public final class DiagnosticValidationException extends IllegalArgumentException {
    private final String failureCode;

    public DiagnosticValidationException(String failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    public String failureCode() { return failureCode; }
}
