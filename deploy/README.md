# 部署

本目录保存基础依赖和应用的部署配置。目前`local/`用于在Windows开发机上通过Docker运行MySQL 8.4和Redis 7.x；`mysql/`保留后续共享数据库方案参考，当前不要启用Tailscale绑定。

本地调用关系为：Vue前端调用Spring Boot，Spring Boot访问`local/compose.yml`启动的MySQL与Redis。详细命令、数据目录和停止方式见各子目录README。

仓库中只保存不含敏感值的模板；数据库密码、模型令牌、证书和生产地址由组织批准的配置或密钥管理系统注入。
