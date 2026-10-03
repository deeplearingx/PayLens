package cn.daxpay.open.paylens.analysis;

public class DiagnosticModelException extends RuntimeException {
    private final String failureCode;

    public DiagnosticModelException(String failureCode) {
        super(failureCode);
        this.failureCode = failureCode;
    }

    public String failureCode() {
        return failureCode;
    }
}
