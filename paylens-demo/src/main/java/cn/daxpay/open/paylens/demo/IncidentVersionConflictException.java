package cn.daxpay.open.paylens.demo;

public final class IncidentVersionConflictException extends RuntimeException {
    public IncidentVersionConflictException() {
        super("事件已被其他操作修改，请刷新后重试");
    }
}
