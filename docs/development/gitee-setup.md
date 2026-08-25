# Gitee 仓库创建与团队配置

## 1. 创建仓库

建议由组织或企业管理员创建仓库，不要放在某位开发人员的个人账号下。

在 Gitee 页面中新建仓库时填写：

| 项目 | 建议值 |
|---|---|
| 仓库名称 | `personal-marketing-nl2sql` |
| 路径 | `personal-marketing-nl2sql` |
| 可见性 | 私有 |
| 初始化 README | 不勾选，避免与本地文件产生首次合并冲突 |
| 默认分支 | `main` |

银行项目应先确认使用的是 Gitee 公有云企业版还是行内私有化部署。仓库地址、成员权限和安全策略以本组织实际环境为准。

## 2. 初始化并推送当前骨架

当前工作区已经执行过 `git init -b main`，因此只需将 `<组织路径>` 替换为 Gitee 中的实际组织路径并执行：

```powershell
git add .
git commit -m "chore(repo): 初始化项目仓库结构"
git remote add origin git@gitee.com:<组织路径>/personal-marketing-nl2sql.git
git push -u origin main
```

如果把这套目录复制到了一个尚未初始化 Git 的新目录，应先执行：

```powershell
git init -b main
```

如果当前 Git 版本不支持上述命令，可使用：

```powershell
git init
git branch -M main
```

不建议同时在 Gitee 勾选初始化 README。如果已经初始化了远端仓库，应先拉取并处理两个仓库的初始提交关系，不能使用强制推送覆盖团队仓库。

## 3. 配置 SSH

未配置 SSH 的成员可执行：

```powershell
ssh-keygen -t ed25519 -C "姓名或工作邮箱"
Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub
ssh -T git@gitee.com
```

把公钥内容添加到个人 Gitee 账号的 SSH 公钥设置中。私有化部署时，将 `gitee.com` 替换为行内部署域名。

## 4. 添加成员和角色

建议按最小权限分配：

| 人员 | 建议角色 |
|---|---|
| TL、仓库管理员 | 管理员，至少两人互为备份 |
| 各模块负责人 | 开发者；承担对应目录评审 |
| 普通开发成员 | 开发者 |
| 测试、审计或只需查看人员 | 观察者或组织内等效只读角色 |

不要把所有成员设为管理员。仓库转移、删除、权限策略和保护分支设置仅由少数负责人维护。

## 5. 设置保护分支

在仓库管理的分支设置中至少保护：

- `main`：禁止直接推送，必须通过 Pull Request；至少 1 名维护人评审，跨系统接口建议 2 名。
- `release/*`：禁止普通成员直接推送，仅允许发布负责人合并。
- `hotfix/*`：允许创建，但合并到 `main` 前仍需评审和自动检查。

同时禁止强制推送和随意删除保护分支。若 Gitee 版本支持状态检查，要求构建、测试、静态检查通过后才能合并。

## 6. 建立团队分组和代码责任人

建议创建以下团队或评审组：

- `crm-maintainers`
- `data-maintainers`
- `llm-maintainers`
- `platform-maintainers`

确定实际 Gitee 用户名后，在仓库根目录添加 `CODEOWNERS`。示例：

```text
/crm/             @crm负责人
/data-product/    @大数据负责人
/llm-service/     @大模型负责人
/contracts/       @项目TL @crm负责人 @大数据负责人 @大模型负责人
/deploy/          @项目TL @运维负责人
```

如果当前 Gitee 版本或套餐不支持自动按 CODEOWNERS 请求评审，也应把这份文件作为责任归属清单，并在 Pull Request 中手动选择对应评审人。

## 7. 开发成员首次使用

```powershell
git clone git@gitee.com:<组织路径>/personal-marketing-nl2sql.git
Set-Location personal-marketing-nl2sql
git switch -c feat/crm-customer-search
```

完成修改后：

```powershell
git add crm
git commit -m "feat(crm): 增加客户关系查询"
git push -u origin feat/crm-customer-search
```

随后在 Gitee 创建 Pull Request，选择模块负责人评审。禁止为了图省事使用 `git add .` 提交不相关配置、日志或其他成员的文件。

## 8. 上线前检查

- 仓库为私有，成员和离职回收流程已确认。
- `main`、`release/*` 保护规则已生效。
- 真实密码和密钥由部署平台注入，仓库内只有示例配置。
- 构建、测试、安全扫描和制品留存已接入组织现有工具。
- 客户数据、日志、评测集和提示词已完成脱敏审查。
- 备份、审计、版本标签和生产回退责任人已明确。

## 9. Gitee 官方参考

- [创建第一个仓库](https://gitee.com/help/articles/4120)
- [生成和添加 SSH 公钥](https://gitee.com/help/articles/4181)
- [设置保护分支](https://gitee.com/help/articles/4239)
- [使用 CodeOwners](https://gitee.com/help/articles/4379)
- [企业仓库成员权限](https://gitee.com/help/articles/4159)
