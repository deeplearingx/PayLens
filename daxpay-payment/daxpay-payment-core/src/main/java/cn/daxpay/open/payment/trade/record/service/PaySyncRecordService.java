package cn.daxpay.open.payment.trade.record.service;

import cn.daxpay.open.payment.trade.record.dao.PaySyncRecordManager;
import cn.daxpay.open.payment.trade.record.entity.PaySyncRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/// # 支付同步记录服务
///
/// 仅提供记录保存, 查询后续按需补充
@Slf4j
@Service
@RequiredArgsConstructor
public class PaySyncRecordService {

    private final PaySyncRecordManager manager;

    /// 参与查单事务，确保返回给 PayLens 的证据与本次状态变更一起提交。
    @Transactional(rollbackFor = Exception.class)
    public PaySyncRecord saveRecord(PaySyncRecord record) {
        manager.save(record);
        return record;
    }
}
