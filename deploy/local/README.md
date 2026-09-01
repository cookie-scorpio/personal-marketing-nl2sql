# 本地基础服务

本目录通过 Docker Compose 启动 MVP 所需的 MySQL 8.4 和 Redis 7.x。两个端口都只绑定 `127.0.0.1`，当前不启用 Tailscale，也不允许其他机器访问。

## 文件说明

- `compose.yml`：MySQL、Redis、数据目录和健康检查配置。
- `.env.example`：可直接使用的本机端口、开发密码和相对数据目录；复制后的 `.env` 不提交 Git。

## 使用方法

Windows 首次启用 WSL2 后必须重启一次，Windows Hypervisor 才会加载。重启后先确认 `wsl --status` 不再提示虚拟化组件未运行，并等待 Docker Desktop 显示引擎已启动；本项目不会自动重启系统。

首次使用时可直接启动，无需修改配置：

```powershell
cd D:\code\boc\personal-marketing-nl2sql\deploy\local
Copy-Item .env.example .env
docker compose up -d --wait
docker compose ps
```

MySQL 数据默认保存在 `deploy/local/data/mysql`，Redis 数据默认保存在 `deploy/local/data/redis`，两者均已被 Git 忽略。Spring Boot 启动时由 Flyway 自动创建业务表，之后再运行 `scripts/mock-data/generate_data.py` 写入演示数据。

在 VS Code 中打开仓库后，更推荐运行“任务: 运行任务”中的“启动后端（本地开发）”。该任务会自动创建 `.env`、启动并等待 MySQL 和 Redis 就绪，再启动后端；无需先执行本节的命令。

停止服务但保留数据：

```powershell
docker compose stop
```

## 调用链路

```text
Vue 前端 → Spring Boot → MySQL（账号、任务、历史、营销数据）
                         → Redis（短期会话索引）
```

不要执行 `docker compose down -v`，也不要手工删除 `D:\tools\nl2sql-mysql\data`。远程共享将在明确提出 Tailscale 需求后单独配置。
