# 本地基础服务

本目录通过 Docker Compose 启动 MVP 所需的 MySQL 8.4 和 Redis 7.x。两个端口都只绑定 `127.0.0.1`，当前不启用 Tailscale，也不允许其他机器访问。

## 文件说明

- `compose.yml`：MySQL、Redis、数据目录和健康检查配置。
- `.env.example`：本地端口、密码和数据目录模板；复制后的 `.env` 不提交 Git。

## 使用方法

Windows 首次启用 WSL2 后必须重启一次，Windows Hypervisor 才会加载。重启后先确认 `wsl --status` 不再提示虚拟化组件未运行，并等待 Docker Desktop 显示引擎已启动；本项目不会自动重启系统。

首次使用时复制配置并填写密码：

```powershell
cd D:\code\boc\0824nl2sql\deploy\local
Copy-Item .env.example .env
docker compose up -d
docker compose ps
```

MySQL 数据默认保存在 `D:\tools\nl2sql-mysql\data`，Redis 数据默认保存在 `D:\tools\nl2sql-redis\data`。Spring Boot 启动时由 Flyway 自动创建业务表，之后再运行 `scripts/mock-data/generate_data.py` 写入演示数据。

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
