# CRM 前端

本目录是个金营销 NL2SQL 平台的 Vue 3 + TypeScript 工作台。界面以“智能问数”为主任务，用户登录后可以提交业务问题、补充缺失条件、确认高范围查询并查看动态结果和历史记录。

## 已实现功能

- 三类数据库演示账号登录和JWT会话恢复。
- 客户筛选、交易分析、产品持有和营销活动示例问题。
- 查询任务状态轮询和阶段进度反馈。
- 时间缺失、业务主题缺失和矛盾条件的补充界面。
- 高范围查询确认或取消。
- 根据后端`columns`元数据动态渲染结果表，不硬编码数据库字段。
- SQL受控预览、复制、空结果和错误恢复。
- 查询历史加载、复用和删除。
- 桌面、平板和移动端响应式布局。
- 客户洞察、营销活动、客户群管理和指标中心暂留说明页。

## 文件说明

```text
src/
├── main.ts                              Element Plus与Vue启动入口
├── App.vue                              登录后布局、导航、用户和数据范围
├── app/
│   ├── api.ts                           统一请求、JWT、幂等键和错误处理
│   ├── auth.ts                          登录状态与会话恢复
│   ├── types.ts                         后端接口类型
│   └── styles.css                       红色点缀的界面规则与响应式样式
└── features/
    ├── auth/LoginPage.vue               数据库账号登录页
    ├── conversation/ConversationWorkspace.vue  NL2SQL完整交互主链路
    └── marketing/InsightPanel.vue       MVP演示信息侧栏
```

根目录文件中，`package.json`保存依赖和命令，`vite.config.ts`配置开发代理，`tsconfig.json`启用TypeScript严格模式，`.env.example`提供后端路径模板。

## 页面调用链路

```text
LoginPage
  → POST /api/v1/auth/login
  → App保存JWT并显示用户数据范围
  → ConversationWorkspace提交问题
  → POST /api/v1/queries
  → 轮询GET /api/v1/queries/{taskId}/status
      ├── ASKING：提交补充条件
      ├── CONFIRMING：确认或取消
      ├── SUCCESS：按列元数据展示结果
      └── FAILED：展示可恢复错误
  → GET /api/v1/query-history读取历史
```

前端从不提交可覆盖权限的`userId`、`managerId`或机构范围，权限仅以后端JWT解析结果为准。

## 安装和运行

建议Node.js 20或更高版本：

```powershell
cd D:\code\boc\0824nl2sql\crm\frontend
npm install
npm run dev
```

开发地址为`http://127.0.0.1:5173`，Vite默认把`/api`代理到`http://127.0.0.1:8080`。

## 类型检查和构建

```powershell
npm run type-check
npm run build
npm run preview
```

构建结果输出到`dist/`。当前Element Plus采用完整组件注册以降低MVP配置复杂度，后续可按页面拆包优化首屏体积。

## 配置

本地联调保留`.env.example`中的相对路径即可。如前后端不在同一地址，复制为`.env.local`并把`VITE_API_BASE_URL`改成后端完整地址。

## 设计说明

界面使用浅色中性背景和克制的银行红作为主操作色。颜色只用于主要操作、状态和风险，不以大面积高饱和红色干扰数据阅读。异步查询包含初始、运行、反问、确认、成功、空结果和失败状态，并支持减少动态效果的系统设置。

## 注意事项

- 演示侧栏中的机会和活动为静态说明数据，核心查询结果来自后端和MySQL。
- JWT保存在浏览器本地存储，仅适用于MVP；生产环境需结合统一认证和更严格的令牌策略。
- 前端展示的SQL不包含实际绑定参数，不能在浏览器中直接执行SQL。
