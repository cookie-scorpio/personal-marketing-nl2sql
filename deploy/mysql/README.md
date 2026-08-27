# 共享 MySQL 8.4（后续预留）

> 当前 MVP 不使用本目录，也不要执行下方命令。现在只启动 `deploy/local/compose.yml`，数据库仅监听 `127.0.0.1`。只有用户之后明确提出启用 Tailscale 时，才按本页配置共享访问。

本目录用于在一台 Windows 开发电脑上运行团队共享的 MySQL 8.4。数据库只监听该电脑的 Tailscale 地址，不需要公网 IP，也不要在路由器上开放 3306 端口。

## 准备软件

主机需要安装并启动：

- Docker Desktop，使用 WSL 2 后端；
- Tailscale，并加入团队共用的 tailnet。

其他开发人员只需要安装 Tailscale 和数据库客户端。

## 配置

在 PowerShell 中执行：

```powershell
cd D:\code\boc\0824nl2sql\deploy\mysql
Copy-Item .env.example .env
tailscale ip -4
```

打开 `.env`，将 `MYSQL_BIND_ADDRESS` 改成上一条命令返回的 `100.x.x.x` 地址，并设置两个密码。`.env` 已被仓库根目录的 `.gitignore` 忽略，不要提交或发送到群聊。

## 启动

确保 Docker Desktop 已经运行，然后执行：

```powershell
docker compose up -d
docker compose ps
docker compose logs --tail 50 mysql
```

看到容器状态为 `healthy` 即启动成功。

## 团队成员连接

团队成员加入同一个 Tailscale 网络后，使用以下参数连接：

```text
主机：主机电脑的 100.x.x.x 地址
端口：3306
数据库：nl2sql
用户名：team_dev
密码：主机 `.env` 中的 MYSQL_PASSWORD
```

可以使用 MySQL Workbench、DataGrip、Navicat 或应用程序连接。连接前可在成员电脑上测试：

```powershell
tailscale ping 100.x.x.x
Test-NetConnection 100.x.x.x -Port 3306
```

## 常用命令

```powershell
# 停止数据库，但保留数据
docker compose stop

# 再次启动
docker compose start

# 查看日志
docker compose logs -f mysql

# 更新并重建容器，数据仍保留
docker compose pull
docker compose up -d
```

不要执行 `docker compose down -v`，该命令会删除数据库卷和其中的数据。

## 注意事项

- 主机电脑关机、休眠或退出 Tailscale 后，其他成员无法连接。
- Docker Desktop 和 Tailscale 应设置为开机启动。
- 只把普通账号 `team_dev` 提供给团队成员，不要提供 root 密码。
- 如果修改 `.env` 中的初始化用户名或密码，已有数据卷不会自动更新；这些变量仅在第一次创建数据卷时生效。
