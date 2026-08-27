package com.boc.nl2sql.execution.application;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/** 仅已知SQL表达错误可修复；连接、权限、取消和超时不会重复调用模型。绝不发送数据库原始报错/数据值。 */
public final class SqlFailureClassifier {
    private static final Map<Integer, String> REPAIRABLE = Map.of(
            1054, "字段或别名不存在，请核对数据字典", 1064, "MySQL语法不正确",
            1055, "分组列与聚合表达式不一致", 1111, "聚合函数使用位置错误",
            1140, "聚合与非聚合字段缺少正确分组", 1241, "表达式返回了多列",
            1242, "标量表达式返回了多行", 1305, "函数不存在，请使用MySQL支持的函数");
    private SqlFailureClassifier() { }
    public static Optional<String> repairReason(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && REPAIRABLE.containsKey(sql.getErrorCode())) {
                return Optional.of(REPAIRABLE.get(sql.getErrorCode()) + "（" + sql.getErrorCode() + "）");
            }
        }
        return Optional.empty();
    }
}
