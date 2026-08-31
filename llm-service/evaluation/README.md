# 效果评测

保存脱敏标准问题、查询要素期望值、预期结果及评测代码。应覆盖正常、缺失、矛盾、歧义、越权和攻击输入场景。

当前 v1.5 可运行评测位于 [scripts/evaluation](../../scripts/evaluation/README.md)，调用实际 Java 后端，提供隔离 MySQL 标准答案比对及有次数保护的真实模型评测，不需要单独启动 Python 模型服务。
