# 效果评测

保存脱敏标准问题、查询要素期望值、预期结果及评测代码。应覆盖正常、缺失、矛盾、歧义、越权和攻击输入场景。

当前v1.3可运行评测位于[scripts/evaluation](../../scripts/evaluation/README.md)，调用实际Java后端，提供隔离MySQL标准答案比对及有次数保护的真实模型评测，不需要单独Python模型服务。
