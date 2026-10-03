package cn.daxpay.open.paylens.analysis;

public final class ReanalysisRequiredException extends RuntimeException {
    public ReanalysisRequiredException() {
        super("REANALYSIS_REQUIRED: 执行配置或知识版本已变化，请新建分析任务");
    }
}
