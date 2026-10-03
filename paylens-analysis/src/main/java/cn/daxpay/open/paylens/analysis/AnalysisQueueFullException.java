package cn.daxpay.open.paylens.analysis;

public class AnalysisQueueFullException extends RuntimeException {
    public AnalysisQueueFullException() { super("诊断队列已满，请稍后重试"); }
}
