# CRM 前端

本目录是个金营销 NL2SQL 平台的 Vue 3 + TypeScript 工作台。界面以“智能问数”为主任务，用户登录后可以提交业务问题、补充缺失条件、确认高范围查询并查看动态结果和历史记录。

## 已实现功能

- 三类数据库演示账号登录和JWT会话恢复。
- 客户筛选、交易分析、产品持有和营销活动示例问题。
- 查询任务状态轮询和阶段进度反馈。
- 时间缺失、业务主题缺失和矛盾条件的补充界面。
- 高范围查询确认、运行中取消，以及超时和模板降级结果提示。
- 时间口径不明确时先补充确认，不自动预选答案。
- 根据后端`columns`元数据动态渲染结果表，逐项展示`charts`中的柱状图、折线图、面积图、构成图、散点图和热力图。
- 同时展示多项指标，标明单位、合计、分组均值或加权口径，不把空值补成零。
- 展示汇总指标、最高项、合计或分组均值，以及可继续追问的分析建议。
- 展示规则/DeepSeek识别来源与置信度，图表分析和明细表可切换。
- SQL受控预览、复制、空结果和错误恢复。
- 查询历史加载、复用和删除。
- 以常规PC浏览器为主要使用环境，采用克制的中行红视觉。
- 不保留尚未实现的业务导航入口。

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
    ├── conversation/ResultChart.vue            ECharts图表与尺寸适配
    └── marketing/InsightPanel.vue       提问建议与识别策略说明
```

根目录文件中，`package.json`保存依赖和命令，`vite.config.ts`配置开发代理，`tsconfig.json`启用TypeScript严格模式，`.env.example`提供后端路径模板。

v1.1需配套同版本后端。默认代理到8080；后端更换端口时，在`.env.development.local`中设置`API_PROXY_TARGET=http://127.0.0.1:18080`并重启Vite。本机因Windows保留8080所在端口段，已配置为18080。配置仅影响开发代理，不改变生产环境。完整协议见[实施说明](../../docs/v1.1实施说明与接口数据字典.md)。

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

构建结果输出到`dist/`。Vue、Element Plus、ECharts与业务代码独立分包；Element Plus仍采用完整组件注册，后续可改为按需导入优化体积。

## 配置

本地联调保留`.env.example`中的相对路径即可。如前后端不在同一地址，复制为`.env.local`并把`VITE_API_BASE_URL`改成后端完整地址。

## 设计说明

界面使用浅色中性背景和克制的银行红作为主操作色。颜色只用于主要操作、状态和风险，不以大面积高饱和红色干扰数据阅读。异步查询包含初始、运行、反问、确认、成功、空结果和失败状态，并支持减少动态效果的系统设置。

## 注意事项

- 侧栏只提供提问建议和策略说明，不展示静态伪造的经营指标；查询结果来自后端和MySQL。
- JWT保存在浏览器本地存储，适用于当前模拟数据演示；生产环境需结合统一认证和更严格的令牌策略。
- SQL仅可预览和复制，不可编辑或直接提交执行。规则SQL不展开绑定参数，模型生成SQL可能包含字面量条件。

数据字典、完整API示例及未实现项见 [v1.1实施说明](../../docs/v1.1实施说明与接口数据字典.md)。
