# SqlAstValidator 设计与实现说明

本文说明 [SqlAstValidator](../crm/backend/src/main/java/com/boc/nl2sql/execution/application/SqlAstValidator.java) 的设计思路与全部实现细节，供维护者阅读代码前建立整体认知，也供评审时逐条对照。文中提到的行为均以当前代码为准；白名单内容如有调整，代码是唯一事实来源。

## 0. 类结构总览

校验器按本文的三层防线拆分为一组协作类（同包 `service.execution`，方法名与各节描述一致）：

| 类 | 职责 | 对应防线 |
|---|---|---|
| `SqlAstValidator` | 入口 `validate`、解析与语句断言、查询块编排（绑定 → 窗口 → 约束收集 → 证明 → 表达式 → 分页） | 编排 |
| `SqlLexicalGate` | 词法预检：解析之前的文本形态即决 | 第一层 |
| `SqlGuardPolicy` | 白名单（表/列/函数/运算符/CAST/时间关键字）与资源限额的唯一事实来源 | 第一、二层的依据 |
| `SqlConstraintCollector` | 从 WHERE/ON 的 AND 链提取授权事实与等值边（含 IN 名单证明）、列解析 | 第三层·收集 |
| `SqlScopeProver` | 两条独立证明链（`proveAccountScope`/`proveCustomerBinding`）与统一判定 `judge` | 第三层·证明 |
| `SqlExpressionChecker` | 表达式白名单校验（一个实例对应一个查询块，含命名窗口与子查询递归） | 第二层·表达式 |
| `Scope`/`Binding`/`Run`/`SqlFacts`(Fact·Edge)/`SqlSubqueryAnalyzer` | 查询块作用域、数据源绑定、共享预算与名单、证明事实与边、子查询递归入口 | 内部模型 |

## 1. 定位与职责

SqlAstValidator 是模型生成的 SQL 进入执行层前的最后一道静态闸门。它不连接数据库、不执行 SQL、也不改写 SQL，只做一件事：**证明这条 SQL 是安全的，证明不了就拒绝**。

它不信任任何上游环节。规划器生成的 SQL、模型通过 `validate_sql` 工具自检的 SQL、用户确认过的 SQL，进入这里时一律平权对待，从头走完整套校验。调用方有三个：

| 调用方 | 场景 |
|---|---|
| `SqlSafetyValidator` | 每条 SQL 执行前的只读安全校验，不带身份 |
| `GeneratedSqlScopeValidator` | 账号范围校验、单客户校验、@客户名单批量校验 |
| `SqlPlanningTools` | 模型规划过程中调用 `validate_sql` 工具时提前自检 |

## 2. 总体思路：三层防线

校验按固定顺序经过三层，任何一层拒绝即整体拒绝，错误码与文案随层给出：

1. **词法预检**：在解析之前，把无法被后续 AST 校验可靠覆盖的文本形态直接拒掉——注释、多语句、会话变量、反斜杠转义等。
2. **结构与白名单**：解析为 AST 后，只接受受控的 SELECT 形态；表、列、函数、运算符、CAST 类型全部走白名单；**未识别的结构落入兜底分支直接拒绝**，绝不静默放行。
3. **授权证明**：这是核心，也是与前两层的本质区别。前两层回答"这条 SQL 是不是我们认识的安全形态"，第三层回答"这条 SQL 取数范围是否被账号数据范围或已确认客户约束住了"。证明方式是把 SQL 里的等值条件抽象成一张约束传播图，用不动点推导确认每个物理数据源都被约束住。

第三层的设计哲学是"**证明而非扫描**"：不在 SQL 里搜索"是否出现了 manager_id='M0001' 这样的字符串"，而是把所有等值条件建成图，从已知可信的事实（账号范围值、已确认客户编号）出发传播，每个数据源必须被传播到的事实覆盖。这样做的好处是 OR、子查询、JOIN 方向等语法陷阱天然被处理掉——它们只是不出边而已。

## 3. 词法预检（lexicalPreCheck）

解析器之前逐字符扫描，拒绝以下形态：

| 形态 | 拒绝原因 |
|---|---|
| 字符串中的反斜杠转义 | MySQL 的 `\'` 与标准 SQL 的 `''` 语义分歧是历史漏洞高发区，本项目统一只接受 `''` |
| `--`、`/* */` 注释 | 注释可以隐藏校验器与数据库看到的内容差异 |
| `;` | 拒绝多语句 |
| `@`（会话变量）、`#`（MySQL 注释）、`"`（双引号） | 变量取值无法静态核对；`#` 是注释；双引号在不同模式下是字符串或标识符，存在歧义 |
| 引号未闭合 | 解析器行为不可预测 |

反引号**整体禁止**：白名单中的表名/列名均为纯英文标识符，反引号引用不解锁任何能力——能通过校验的反引号标识符被证明与去掉反引号的形式完全等价——却引入转义（`` `a``b` ``）、大小写与引用语义等一整类"校验器与数据库理解分叉"的风险，在文本层直接拒绝、不进解析器。提示词端也已明确要求模型不要使用反引号。

### 3.1 为什么不直接依赖解析器

问题不是"解析器能不能解析"，而是"解析器理解的语句与 MySQL 实际执行的是否同一条"。后续所有白名单与授权证明都以 JSqlParser 的 AST 为对象，而它是多方言宽容解析器，目标是从宽解析而非与 MySQL 对齐。用 JSqlParser 5.2 实测（`java -cp jsqlparser-5.2.jar` 直接调用 `CCJSqlParserUtil.parse`）：

| 输入 | JSqlParser | MySQL |
|---|---|---|
| `SELECT 1 /*! 40101 */` | 解析成功，注释被**静默丢弃** | `/*!...*/` 是可执行注释，内容会被执行 |
| `SELECT 'a\b'` | 解析成功，AST 中反斜杠凭空消失 | 字符串值与解析结果不同 |
| `SELECT 'a\'b'` | 解析失败 | 合法字符串 `a'b` |
| `SELECT 1 # comment` | 解析失败 | `#` 是合法行注释 |
| `SELECT "dim_customer" ...` | 按带引号标识符解析 | 默认模式下双引号是字符串 |

第一行是决定性场景：若允许注释，`... WHERE manager_id='M0001' /*! UNION SELECT user FROM mysql.user */` 在解析器眼里是一条干净且已获授权的单表查询，而 MySQL 会执行 UNION——授权证明被整体绕过且校验侧无痕迹。这类"解析器与数据库理解分歧"的形态无法在 AST 层面拦截（AST 里根本没有那些内容），只能在进解析器之前按词法拒绝。

不选择"配置解析器去拒绝"的原因：JSqlParser 没有面向安全的开关，配置项随版本变动，把安全策略寄托在解析器配置上不可控。词法预检约 30 行、零依赖、行为完全受控，还能对合法 MySQL 但解析器不支持的形态（如 `#` 注释）给出准确报错，而不是含糊的解析异常。

需要强调：词法预检不是防线本身，它把输入归一化到"解析器与 MySQL 共识"的子集，为后续基于 AST 的校验提供可靠前提；真正的判断全部发生在解析之后。

## 4. 解析与结构约束

### 4.1 解析入口

`validate(sql)` 依次做：长度检查（空、超 30000 字符拒绝）→ 词法预检 → `CCJSqlParserUtil.parseStatements`（2 秒超时，防解析器被恶意构造的输入拖死）→ 断言恰好一条语句且是 `Select`。解析失败的报错不携带原始异常信息，避免把解析器内部细节泄露给调用方。

### 4.2 复杂度预算

`Run.nodes` 是整次校验共享的节点计数器，每个 SELECT 块和每个表达式节点各计一次，上限 3000；SELECT 嵌套深度上限 12。这两条保证校验本身在线性时间内结束，也让深层嵌套的对抗性 SQL 提前出局。

### 4.3 SELECT 特性拒绝清单

每个 SELECT 节点（无论在哪个嵌套层级）先过一遍特性拒绝：

- **锁定与事务**：`FOR UPDATE`、隔离级别、`FETCH`；
- **方言扩展**：`LIMIT BY`、PIVOT/UNPIVOT、抽样、`DISTINCT ON`、`SQL_CALC_FOUND_ROWS`、QUALIFY、Oracle 层级查询与 hint、LATERAL VIEW、TOP/SKIP/FIRST；（命名窗口自本版起支持，见表达式层的窗口函数一节）；
- **写入侧**：`SELECT ... INTO`、临时表；
- **GROUP BY 扩展**：GROUPING SETS。

清单是按"遇到了再补"的方式维护的：JSqlParser 新版本支持的任何新语法，在白名单表达式分发器里找不到对应分支时同样会被兜底拒绝，所以这个清单不是唯一防线，只是让报错文案更精确。

### 4.4 分页校验

LIMIT **可选**——执行层会统一追加分页，这里只负责"出现了就必须合法"：行数必须是常量整数且在 1 到 `maxRows`（默认 500）之间；OFFSET 必须是非负整数常量。校验作用于每一个查询块，子查询里同样不允许超大或动态 LIMIT。

### 4.5 CTE

`WITH` 子句逐个声明、逐个递归校验：

- 递归 CTE 拒绝；
- 显式列名列表（`WITH x(a,b) AS ...`）拒绝，列名必须由 SELECT 推导；
- CTE 名不得重复，也不得遮蔽业务表名（避免同名 confusion）；
- 后声明的 CTE 可以引用先声明的（声明表逐个增长）；
- CTE 体作为独立查询块校验，外层作用域对它不可见——CTE 不能写成关联形式。

校验通过后，CTE 以"输出列名列表"的身份进入后续作用域，可以被当作表引用。

### 4.6 UNION

集合运算允许 UNION、UNION ALL、INTERSECT、EXCEPT（MySQL 8.0.31 起支持后四者的全部形态；MINUS 等 Oracle 方言仍拒绝），各分支独立校验、列数必须一致。集合层的 ORDER BY 只能引用分支的输出列名——集合层没有表绑定，校验器构造一个只含输出列别名的空作用域来处理它。

## 5. 作用域与数据源绑定

### 5.1 Scope 链与列解析

每个查询块对应一个 `Scope`：自己绑定的数据源（`bindings`）+ 指向外层的 `parent` 指针 + 本块输出列别名（`aliases`）。

列解析（`resolveColumn`）沿作用域链向外查找，与 SQL 的关联子查询语义一致：

- **带表限定**（`t.customer_id`）：从当前块向外找别名对应的绑定，命中即要求该绑定含有此列，列不存在直接报错，不再向外找（与 SQL 语义一致）；
- **不带限定**（`customer_id`）：在作用域链的每一层检查有几个绑定含此列——多于一个报"字段含义不明确，请使用表别名"（SQL 歧义），恰有一个即解析成功，零个则继续向外层找；
- 任何带库名前缀的列引用（`db.table.col`）按跨库拒绝。

歧义检查是刻意的安全设计：`SELECT customer_id FROM dim_customer c JOIN dim_customer d ...` 即使业务上无害也拒绝，因为它逼迫 SQL 生成者写清楚每个列的来源，让后续的授权证明无歧义可钻。

### 5.2 Binding：物理表与派生表

每个数据源绑定分两类：

- **物理表**（`baseTable != null`）：表名必须在白名单 `SCHEMA` 内，列集合即白名单列。隐私控制就内建在这里——`dim_customer` 只有 `customer_name_masked`/`mobile_masked`，原始敏感列根本不在名单里，选了就报"字段不存在或不允许查询"。
- **派生表**（CTE 引用或子查询，`baseTable == null`）：内部查询块已经递归校验过，标记为"已证明"（`scopeProven` 与 `customerProven` 预置为 true）。但注意：**派生表的列不作为本块的证明种子**——在它所在的查询块里新关联的事实表，仍然要与本块内已证明的来源建立等值连接。错误文案里专门写了这条："CTE或派生表的授权不会自动传递给新关联的事实表"。

别名规则：有别名用别名，物理表没有别名时用表名本身；派生表必须有别名；同一查询块内别名重复拒绝。

### 5.3 FROM/JOIN 规则

JOIN 只允许 INNER 与 LEFT，且必须带显式 ON 条件。RIGHT/FULL/NATURAL/CROSS/逗号连接/APPLY/SEMI/USING 全部拒绝。拒绝的理由各不相同但指向同一个原则：这些形态要么让约束传播方向变得不可靠（RIGHT/FULL），要么完全没有 ON 可分析（CROSS、逗号），要么引入作用域错位（APPLY、LATERAL）。

派生表内部的外层作用域是 null，即 FROM 里的子查询不能引用外层表（不支持 LATERAL 语义）；而表达式里的子查询（EXISTS、标量子查询、IN 子查询）**可以**引用外层，这是合法的关联子查询，见第 7 节。

## 6. 表达式校验

### 6.1 fail-closed 分发

`checkExpression` 是一个显式的类型分发：字面量放行，`Column`、二元运算、函数、CASE、BETWEEN、IN、EXISTS、IS NULL、NOT、正负号、CAST、EXTRACT、INTERVAL 各有专属分支，**最后一个 `else` 分支直接失败并报出类名**。这个兜底分支是整层的安全基石：JSqlParser 升级后新增的任何表达式类型（新的 JSON 函数、新的运算符）都会落到这里被拒绝，而不是被静默跳过。

对比：社区常见的写法是继承 `ExpressionVisitorAdapter`，它对所有未覆盖的方法提供空实现——那意味着未知表达式静默通过。本项目第一版重写（已废弃）正是踩了这个坑。

### 6.2 各类表达式

- **函数**：函数名必须在 `FUNCTIONS` 白名单内（聚合、数学、字符串、日期、窗口函数，约 50 个）；JSqlParser 函数节点上的方言扩展位——属性（`getAttribute`）、KEEP 子句、命名参数形式（如 `TRIM(BOTH ... FROM ...)`）——出现即拒绝，这些形态的语义不适合逐字段静态核对；`COUNT(*)` 特判放行（参数中的 `AllColumns` 仅在 count 下豁免），其余位置的 `*` 与 `t.*` 一律拒绝。
- **窗口函数**：`AnalyticExpression` 是独立分支（5.x 起它不再继承 `Function`）。校验覆盖函数名白名单、拒绝 FILTER 子句；**命名窗口已支持**——`WINDOW w AS (...)` 的定义逐个校验（名称本块唯一、PARTITION BY / ORDER BY / 帧内表达式全走白名单），`OVER w` 引用必须命中本块已定义的窗口名。匿名 `OVER (...)` 则递归校验 PARTITION BY、窗口内 ORDER BY 和窗口帧边界的每一个表达式。边界：`OVER (w ORDER BY ...)`、`OVER (w)` 与链式定义（`w2 AS (w1 ...)`）JSqlParser 5.2 无法解析，会在解析层被拒（解析器能力边界，非校验器限制）。
- **二元运算**：按实现类简单名匹配白名单（And/Or/六种比较/加减乘除/整除/取模/LIKE），位运算、正则匹配、`||` 连接等不在名单内。
- **CAST**：目标类型白名单（DECIMAL/SIGNED/UNSIGNED/CHAR/DATE/DATETIME/TIME/INTEGER/DOUBLE）。
- **子查询**：表达式位置的 `ParenthesedSelect` 递归调用 `analyzeSelect`，外层作用域作为 parent 传入——这就是关联子查询的支撑点；子查询内部获得与普通查询块完全相同的全套校验，包括授权证明。

### 6.3 输出列别名

SELECT 输出列名按"显式别名 → 列名 → expression_N"推导，重复的输出列名拒绝。输出列确定后进入 `scope.aliases`，此后 **GROUP BY、HAVING、ORDER BY** 允许引用这些别名（与 MySQL 语义一致），WHERE 和 ON 不允许（MySQL 也不允许，同时避免别名先于定义使用的歧义）。ORDER BY 全部位置（包括聚合函数内部和窗口内的 ORDER BY）都走别名允许模式。

## 7. 授权证明（核心）

> 本节讲设计思路；函数级逐行走读、逐轮状态表与报错排查见 [权限校验详解](权限校验详解.md)。

### 7.1 要解决的问题

NL2SQL 场景里，SQL 由模型生成，不能假设它自觉遵守权限。以客户经理账号为例，业务要求是：**模型产出的每一条 SQL，取数范围都不得超出该经理名下客户**。靠提示词约束不可靠，靠事后执行 LIMIT 也不可靠（错误的大范围聚合仍然泄漏），所以必须在执行前从 AST 层面证明这一点。

证明的目标因调用模式而异（见第 8 节）：

- **账号模式**：证明每个物理数据源都被"账号数据范围"约束。`DataScopePolicy` 把角色映射为 dim_customer 上的一个范围条件：客户经理 `manager_id = 本人工号`、团队负责人 `branch_id = 本网点`、机构负责人 `region_code = 本区域`；
- **客户模式**：证明每个物理数据源都被"已确认客户"约束——SQL 里的客户编号必须来自服务端核验过的确认名单，不能是模型自己编的。

### 7.2 事实与等值边

证明的原料是两类"事实"（sealed 接口 `Fact`）：

- `ColumnFact(binding, column)`：某个数据源的某个列；
- `ValueFact(value)`：一个具体取值（字符串字面量，或命名参数在服务端参数表中的解析值）。

等值条件 `a = b` 在两个事实之间连一条边。事实沿边传播的含义是：**若 a 的取值被证明在某个受信集合内，b 与它恒等，b 也在该集合内**。

### 7.3 约束收集与 LEFT JOIN 方向

`collectConstraints` 只从 WHERE 和 ON 的 **AND 链**中提取等值条件。三条关键规则：

1. **OR/NOT 分支不产生边**。`WHERE c.manager_id='M0001' OR 1=1` 里前者虽然存在，但 OR 让它失去约束力，所以整条 WHERE 不出边，证明必然失败。这不是遗漏而是设计：约束必须是**保证成立**的，OR 分支只是可能成立。
2. **等值两侧必须是可核对的事实**：列（解析成功后）、字符串字面量、已知命名参数。函数调用、算术表达式一侧（`c.manager_id = CONCAT('M','0001')`）不产生边——不是判断它危险，而是无法静态断言它的取值。
3. **LEFT JOIN 的 ON 只向右表传播**。对左连接，ON 条件只约束"被补全的右表"（右表中不匹配的行以 NULL 出现，匹配的行必然满足 ON）；左表的所有行都保留，ON 约束不了它。所以 INNER JOIN 的边是双向的，LEFT JOIN 的边只从左往右。这直接封死一类逃逸：`dim_customer c LEFT JOIN fct_transaction t ON c.manager_id='M0001' AND c.customer_id=t.customer_id`——范围条件写在 LEFT JOIN 的 ON 里约束不到 c 本身，c 证明失败，整条 SQL 拒绝。

### 7.4 IN 名单证明（@客户名单批量查询）

批量场景的契约是：服务端核验客户名单后，模型必须用 `customer_id IN (名单)` 表达范围（`QueryTaskProcessor` 的提示词原话："不得增删或替换编号，每个客户来源都要保留该IN条件"）。`collectInListProof` 把这个契约变成可证明的约束：

- 条件 `customer_id IN ('C1','C2',...)` 的列必须带表限定且解析到本块绑定；
- 列表必须**全部是字符串字面量**，且整体包含于服务端确认名单。任何元素是数字、命名参数或子查询，整体不作证明——这是刻意收紧：`IN ('C1', 123)` 若按"收集到的部分成员"核对会产生假证明；
- 证明成立后，`ColumnFact(该绑定, customer_id)` 作为已确认客户事实进入种子。

### 7.5 两条独立证明链与不动点推导

授权证明拆成两条**相互独立**的链，`proveDataSources` 按调用模式调度（混合模式先后各跑一遍），每条链各有两个事实集合：

| 链 | 事实集合 | 种子 | 获证标记 |
|---|---|---|---|
| 账号链 `proveAccountScope` | `scopeFacts` | `ValueFact(账号范围值)` + 外层已证绑定的范围列 | `scopeProven` |
| | `allowed` | 外层已证明绑定的 `customer_id`、`campaign_id` 列 | |
| 客户链 `proveCustomerBinding` | `identityFacts` | 确认客户编号字面量 + IN 名单证明 | `customerProven` |
| | `bound` | 外层已证明绑定的 `customer_id` 列 | |

两条链共享只读的等值边表，事实集合互不读写——先后运行与交织运行收敛到同一结果，因此拆分是行为保持的重构。

外层播种是关联子查询的支撑：内层块把外层所有已证明绑定的列作为种子，于是 `EXISTS(SELECT ... FROM fct_transaction t WHERE t.customer_id=c.customer_id)` 中，内层的事实表经由外层已证明的 `c.customer_id` 获证。

每条链的不动点循环每轮做两件事：沿边扩展自己的两个事实集合（扫描中新增的事实会让其后的边继续生效——级联）；对块内每个物理表尝试标记获证（见 7.6）。任一集合有新增或任一标记翻转就继续下一轮，直到稳定。这是个单调递增的不动点计算：事实集合只增不减、上限是有限的（列, 集)组合数，标记每个绑定最多翻转一次，必然终止，且收敛到的最小不动点与更新顺序无关。表达式/条件的递归深度另有 200 层上限（`Run.expressionDepth`），与节点预算、解析超时一起把对抗性输入的资源消耗钉死在有界范围内。

### 7.6 三类表的获证规则

| 表 | 账号模式获证条件 | 客户模式获证条件 | 获证后新增事实 |
|---|---|---|---|
| `dim_customer` | 范围列连通 `scopeFacts`，**或** `customer_id` 连通 `allowed` | `customer_id` 连通 `identityFacts` 或 `bound` | `customer_id` 进入 `allowed`/`bound` |
| `dim_marketing_campaign` | `campaign_id` 连通 `allowed` | `campaign_id` 连通 `identityFacts` 或 `bound` | 无 |
| 其余事实表 | `customer_id` 连通 `allowed` | `customer_id` 连通 `identityFacts` 或 `bound` | `fct_customer_marketing` 额外把 `campaign_id` 放入对应集合 |

三条规则的意图：

- dim_customer 是唯一能凭"自身条件"获证的表，其余表必须通过等值连接**借用**它的证明——这就是"每个事实表必须与 dim_customer 按 customer_id 关联"的实现形式；
- 营销活动表没有 customer_id，走 campaign_id：一条客户-活动关联记录获证后，其 campaign_id 成为授权来源，关联到的活动明细随之获证；
- 事实表在客户模式下允许 `customer_id` 直连确认客户字面量获证（如 `t.customer_id = :resolvedCustomerId`），这与"限定为已确认客户"的契约语义等价，不再强制挂 dim_customer 连接。

### 7.7 判定与报错

不动点收敛后逐个绑定判定，任何一条不满足即整条 SQL 拒绝：

- 账号模式下存在未获证绑定 → **403104**，报错中写明当前账号要求的条件、别名与来源编号，并明确提示"OR/NOT中的条件不能作为授权依据""CTE或派生表的授权不会自动传递给新关联的事实表"——这些提示是给修复 SQL 的模型看的，能显著提高下一轮修复的成功率；
- 客户模式下存在未获证绑定 → **403105**；
- 账号范围本身配置缺失/非法（`DataScopePolicy.scopeOf` 内部校验，含质量管理员直接拒绝的场景）→ **401001 / 403103 / 403106**。

报错文案以"数据源 dim_customer（别名 c，来源编号 3）"开头，编号在整次校验内唯一，便于模型在多个绑定之间区分。

### 7.8 一致性说明

证明逻辑与 SQL 执行语义的对应关系经过逐条核对：

- **LEFT JOIN 方向**与 NULL 扩展语义一致（见 7.3）；
- **派生表/CTE 预置已证明**是安全的，因为它们内部的每个物理表在自己的查询块里已经通过了同样的证明，而校验配置（账号、确认名单）对内外层完全相同；
- **派生表列不作为本块种子**是刻意的保守：`JOIN (SELECT customer_id FROM dim_customer WHERE manager_id='M0001') d ON t.customer_id=d.customer_id` 这种写法里，事实表 t 不会因此获证——把范围条件埋进派生表再外连事实表，与把范围条件埋进 OR 本质相同，都不能让外层"免费"获得授权。正确写法是外层块内直接 JOIN dim_customer 并写明范围条件；
- **反引号整体禁止、标识符规范化**（转小写、限定 `[a-z_][a-z0-9_]*`）保证解析与证明处理的是同一个标识符，不存在大小写或引号形式造成的旁路。反引号在词法层整体禁止（白名单名称均为纯英文标识符，引用不解锁任何能力）——MySQL 的转义反引号形态 `` `a``b` `` 若不禁止，去引号归一会让校验器认为的名字与数据库解析的名字分叉，产生错误合并。

## 8. 四种调用模式

构造参数 `(user, parameters, confirmedCustomers, maxRows)` 组合出四种模式，证明层的开启情况不同：

| 模式 | user | confirmedCustomers | 行为 |
|---|---|---|---|
| 纯安全 | null | null | 只做词法、结构、白名单校验，不做授权证明 |
| 账号模式 | 非空 | null | 证明每个物理表被账号数据范围约束（403104） |
| 客户模式 | null | 单个客户 | 证明每个物理表被确认客户约束（403105） |
| 批量模式 | null | 多个客户 | 同客户模式，确认来源是 IN 名单证明 |
| 混合 | 非空 | 非空 | 两类证明同时执行，两个判定都要通过 |

`SqlPlanningTools.validate_sql` 可能出现混合模式（模型规划时同时有登录账号和已确认客户）。所有模式共享同一套结构与白名单校验。

## 9. 内部数据结构速查

| 结构 | 说明 |
|---|---|
| `Run` | 一次 validate 的共享状态：来源编号分配器、表达式节点预算、IN 名单证明收集。实例局部，因此校验器本身线程安全、可复用 |
| `Scope` | 查询块作用域：数据源绑定表 + 外层链 + 输出列别名集 |
| `Binding` | 数据源绑定：物理表名（派生表为 null）、列集合、两个证明标记。派生表构造时即预置已证明 |
| `ExprEnv` | 表达式校验环境：作用域、可见 CTE、深度、是否允许输出列别名 |
| `Fact`（sealed） | `ColumnFact(绑定, 列)` 与 `ValueFact(取值)`，证明图中的节点 |
| `Edge` | 有向事实边，LEFT JOIN 保证方向性 |

## 10. 典型用例走查

以下用例全部来自测试套件（`SqlSafetyValidatorTest`、`GeneratedSqlScopeValidatorTest`），可用于理解证明过程。

**账号模式，通过**——范围列等值连通账号范围值：

```sql
SELECT c.customer_id FROM dim_customer c WHERE c.manager_id = 'M0001' LIMIT 10
```
边：`c.manager_id ↔ 'M0001'`。`scopeFacts` 从字面量传播到 `c.manager_id`，dim_customer 获证。

**账号模式，拒绝**——值对了、列不对也不行：

```sql
SELECT c.customer_id FROM dim_customer c WHERE c.customer_id = 'M0001' LIMIT 10
```
事实传播到了 `c.customer_id`，但获证条件检查的是范围列 `c.manager_id`，403104。

**账号模式，拒绝**——LEFT JOIN 的 ON 约束不到左表：

```sql
SELECT c.customer_id FROM dim_customer c
LEFT JOIN fct_transaction t ON c.manager_id='M0001' AND c.customer_id=t.customer_id LIMIT 10
```
范围条件在 ON 里只向右表传播，c 无边可依，403104。

**账号模式，通过**——关联子查询借外层已证明的来源：

```sql
SELECT c.customer_id FROM dim_customer c
WHERE c.manager_id='M0001'
  AND EXISTS(SELECT t.transaction_id FROM fct_transaction t WHERE t.customer_id = c.customer_id) LIMIT 10
```
外层 c 获证后，其 `customer_id` 列作为种子进入内层，边 `t.customer_id ↔ c.customer_id` 把事实传播给 t，t 获证。

**账号模式，拒绝**——CTE 内部没写范围条件：

```sql
WITH x AS (SELECT customer_id FROM dim_customer) SELECT x.customer_id FROM x LIMIT 10
```
CTE 体作为独立查询块校验，内部 dim_customer 无约束，在校验 CTE 时即拒绝。

**批量模式，通过**——IN 名单证明加营销链：

```sql
SELECT m.campaign_name FROM fct_customer_marketing f
JOIN dim_marketing_campaign m ON f.campaign_id = m.campaign_id
WHERE f.customer_id IN ('C00000001','C00000002') LIMIT 10
```
IN 名单 ⊆ 确认名单 → f 的 `customer_id` 获证 → f 是 `fct_customer_marketing`，其 `campaign_id` 成为绑定事实 → 沿 ON 边传播 → m 按 `campaign_id` 获证。

**纯安全模式，拒绝**（抽查）：

```sql
SELECT * FROM dim_customer LIMIT 10                              -- 通配符不在表达式白名单
SELECT sleep(3) FROM dim_customer LIMIT 1                        -- 函数不在白名单
SELECT c.customer_name AS name FROM dim_customer c LIMIT 10      -- 原始敏感列不在列白名单
SELECT customer_id FROM dim_customer LIMIT 1000                  -- 超过 maxRows
SELECT (SELECT load_file('/etc/passwd')) AS x FROM dim_customer  -- 表达式子查询被递归校验，函数拒绝
WITH RECURSIVE a AS (...) SELECT ...                             -- 递归 CTE
```

## 11. 与旧版实现的行为差异

重构以旧版（`SqlAstValidatorOld`，与最后一个正常工作的提交版本逐字等价）为行为基准，除以下四点外语义一致：

1. **修复：批量名单模式的授权证明此前是死代码**。旧版证明入口在 `user == null && customer == null` 时直接返回，而批量模式（多个确认客户、无登录态）恰好两个条件都成立——整条 SQL 只受白名单约束，名单契约完全靠模型自觉。现在批量模式与单客户模式走同一套证明，确认来源是 IN 名单证明；
2. **修复：IN 名单证明的收集时机**。旧版在 WHERE 表达式遍历阶段收集 IN 证明，晚于该块的证明判定，证明永远用不上自己块里的 IN 条件。现在在约束收集阶段（证明之前）收集；
3. **修复：事实表可直连确认客户获证**。旧版事实表只能经"已获证的 dim_customer 连接"获证，`WHERE t.customer_id = :resolvedCustomerId` 这类直连反而被拒。现在直连确认客户（字面量或服务端参数）同样构成证明，与契约语义一致；规划器生成的 SQL 始终带 dim_customer 连接，不受影响；
4. **收紧：混合字面量 IN 列表不再产生局部证明**。旧版 `IN ('C1', 123)` 会按收集到的字符串部分核对，可能造成假证明；现在列表必须全部为字符串字面量。

结构上的改进：表达式分发改为 fail-closed（旧版同样是显式分发+兜底失败，但窗口函数分支因子类顺序问题不可达）；授权证明用 sealed `Fact` 建模并换成显式不动点循环；校验期状态收入 `Run`，实例线程安全；删除了旧版从未被调用过的"LIMIT 必填"分支。

## 12. 已知边界与维护注意

- **白名单是活文档**。表结构变更（加列、加表）必须同步 `SCHEMA`；放开新函数前先确认它在只读语义下无副作用（如 `get_lock`、`sleep` 一律不放）。
- **JSqlParser 升级是最大的风险点**。升级后新增的表达式类型会落入兜底分支被拒绝，表现为"以前能过的 SQL 现在报不支持的SQL表达式：Xxx"——这是设计行为，按报错里的类名决定是加白名单还是保持拒绝，不要为了兼容绕过兜底。
- **校验器不改写 SQL**。分页由执行层追加；如果未来需要自动注入范围条件（如 `DataScopePolicy.condition` 的参数化改写），那是执行层与规划层的职责，校验器仍按改写后的最终 SQL 做证明。
- **报错文案是产品的一部分**。403104/403105 的长文案直接作为模型修复 SQL 的上下文，改动措辞前先确认提示词端（`Nl2SqlPrompts`）没有依赖其中的具体表述。
- **时间函数的语义**：`NOW()`/`CURDATE()`/`CURRENT_DATE` 等已放行（MySQL 8.4 支持），但它们是非确定性的——确认过的 SQL 稍后执行时时间边界会漂移，结果可能与确认时预览的不同；需要可复现口径的场景仍建议由系统注入字面日期。
- **测试是行为规格**。`SqlSafetyValidatorTest` 覆盖结构与白名单，`GeneratedSqlScopeValidatorTest` 覆盖四种证明模式与批量名单，改动证明逻辑后两者必须全绿，并优先以新增用例表达行为变更而不是改旧断言。

## 13. 常见疑问

**问：为什么不用现成的 SQL 防火墙/换一个解析器？**

"用解析器做静态校验"是这类系统的通行实践，但最佳的是分层架构，解析器是可替换组件。选 JSqlParser 的实际理由：它已是项目依赖（MyBatis-Plus 分页在用），不引入新供应链；纯 Java 进程内，满足模型每轮 `validate_sql` 自检的低延迟要求；AST 足以表达授权证明所需的等值条件、作用域链与子查询。Druid WallFilter 是规则/黑名单式防火墙，授权证明仍需自建；Calcite/ANTLR 过重且分歧风险不减。任何解析器都有与 MySQL 理解分歧的残余风险，对策是分层兜底（词法归一化、fail-closed 白名单、受限执行账号），这套架构换解析器可原样迁移。

**问：为什么不直接配置数据库权限，而要在应用层检查？**

两层都在用，且互补：部署上 `MYSQL_USER=nl2sql_app` + `MYSQL_DATABASE=pf_nl2sql` 由官方镜像自动授权业务库，`mysql.*`、`sys.*` 等在数据库层不可达——这是对象级防线；校验器负责对象之内的行级与形态约束。GRANT 替代不了应用层证明，差在六件事：

1. 行级权限按"应用身份"判定（哪个客户经理在问），数据库只认识连接池里的统一账号，MySQL 8 无行级安全，推进数据库只有每账号一个 DB 用户或读会话变量的 INVOKER 视图，都远比现状脆弱；
2. "已确认客户"是会话状态，GRANT 语法上无法表达"customer_id IN (本会话确认名单)"；
3. 危险函数（`sleep`、`get_lock`、`load_file`）不是权限对象，GRANT 完全管不到；语句形态类约束（多语句、FOR UPDATE、LIMIT 上限）同理；
4. 模型修复回路需要执行前、结构化、带修复指引的拒绝；数据库报错发生在执行阶段（用户已确认后）且无法指导精确修复；
5. 确认流程要求范围约束在 SQL 文本中可见，藏在安全视图里的约束会让用户确认的文本不是完整真相；
6. 纵深防御：数据库权限兜住校验器漏洞的爆炸半径，校验器防止账号被过度授权后越权行级访问，两者互为失效保险。

**问：`lexicalPreCheck` 为什么自己写，不靠解析器？** 见 3.1 节。

**问：能否用 EXPLAIN 之类让 MySQL 自己判断，替代不可靠的解析器？**

EXPLAIN 能给的只有语法权威性和表/列存在性，替代不了授权证明——它回答"会碰哪些表"，证明要回答"哪些行被约束住了"；其 JSON 输出里的 `attached_condition` 是优化器改写后的文本，从它反推证明比从源 AST 证明更不可靠。列白名单方向也相反：EXPLAIN 按真实 schema 判存在性，而本白名单比真实 schema 窄（脱敏依赖它），EXPLAIN 会放行原始敏感列。更决定性的是，EXPLAIN 并非只读：官方手册写明"EXPLAIN will likely execute a subquery in the FROM clause"（派生表在优化期被求值），EXPLAIN 派生表中的用户变量赋值会真实生效，EXPLAIN ANALYZE 直接执行整条语句——把闸门建在"EXPLAIN 大概率不执行"上与建立在解析器隐含行为上是同类错误。此外每次校验一次数据库往返（模型自检与修复循环都要打库），报错也无法映射回 422/403 产品语义。EXPLAIN 的正确用法若要吸收，是在执行网关用同一受限账号对最终 SQL 做触及表清单交叉核对，作为执行边界的纵深防御；静态校验不可省略。
