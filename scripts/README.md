# 开发辅助脚本

本地初始化、代码检查、契约校验等通用脚本放置在本目录。

- `mock-data/`：使用Python固定随机种子生成全量虚构营销数据，并批量写入MySQL。使用方法和表范围见[虚构数据生成说明](mock-data/README.md)。
- `start-backend-local.ps1`：创建本机 `.env`、启动 MySQL 和 Redis，并以临时 RSA 密钥启动后端。
- `start-frontend-local.ps1`：首次按锁定版本安装前端依赖并启动 Vite。

两个启动脚本只使用仓库内 `deploy/local/.env.example` 的本机开发配置，不包含个人路径、生产账号或固定 RSA 私钥。需要生产凭据时必须通过外部环境变量注入。
