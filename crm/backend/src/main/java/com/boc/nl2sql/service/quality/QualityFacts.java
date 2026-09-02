package com.boc.nl2sql.service.quality;

import com.boc.nl2sql.domain.quality.QualityFact;

/**
 * 其他职责模块向 F 提交事实的唯一写入边界。
 *
 * <p>调用方只描述已经发生的事情，不通过该接口查询状态、获得业务决定或控制查询流程。
 * 事件编号、发生时间、事务提交后派发、异步入库和失败补偿均由 F 内部完成。</p>
 */
public interface QualityFacts {
    /**
     * 提交一条事实草稿。
     *
     * <p>正常实现不会因为质量数据库或补偿文件故障而中断业务请求；如果调用时存在事务，
     * 事实只会在该事务成功提交后派发。</p>
     *
     * @param fact 业务模块构造的事实草稿，必须包含事件类型和来源模块
     */
    void publish(QualityFact fact);
}
