# JSqlParser 数据结构速查（SqlAstValidator 视角）

本文解释 [SqlAstValidator](../crm/backend/src/main/java/com/boc/nl2sql/service/execution/SqlAstValidator.java) 里出现的每一个 JSqlParser 类型：它对应 SQL 的哪个片段、有哪些字段、校验器为什么这样用它。所有签名均以项目实际使用的 **JSqlParser 5.2** 为准（经 `javap` 核对）。阅读前建议先看 [SqlAstValidator设计说明](SqlAstValidator设计说明.md) 了解校验器的整体思路。

## 1. 一棵树看懂：SQL 文本如何变成 AST

以这条被校验器放行的 SQL 为例：

```sql
SELECT c.customer_id, SUM(t.amount_cny) AS amount
FROM dim_customer c
LEFT JOIN fct_transaction t ON t.customer_id = c.customer_id
WHERE c.manager_id = 'M0001' AND t.txn_date > '2026-01-01'
GROUP BY c.customer_id
HAVING SUM(t.amount_cny) > 0
ORDER BY amount DESC
LIMIT 100
```

解析后的树（只画校验器关心的部分，括号内是 Java 类型）：

```
Statements (extends ArrayList<Statement>)          ← parseStatements 的返回值，就是 List<Statement>
└─ [0] PlainSelect (extends Select)                ← 本例无 CTE/UNION/外层括号，所以是一条 PlainSelect
   ├─ selectItems: List<SelectItem<?>>
   │   ├─ [0] SelectItem  expression=Column(c.customer_id)          无别名
   │   └─ [1] SelectItem  expression=Function(SUM)  alias=Alias("amount")
   │            └─ parameters: ExpressionList[ Column(t.amount_cny) ]
   ├─ fromItem: Table("dim_customer")  alias=Alias("c")
   ├─ joins: List<Join>
   │   └─ [0] Join  left=true  rightItem=Table("fct_transaction") alias=Alias("t")
   │            └─ onExpressions: [ EqualsTo( t.customer_id = c.customer_id ) ]
   ├─ where: AndExpression
   │   ├─ left:  EqualsTo( Column(c.manager_id) = StringValue("M0001") )
   │   └─ right: GreaterThan( Column(t.txn_date) = StringValue("2026-01-01") )
   ├─ groupBy: GroupByElement  groupByExpressionList=[ Column(c.customer_id) ]
   ├─ having: GreaterThan( Function(SUM) > LongValue(0) )
   ├─ orderByElements: [ OrderByElement( Column("amount") , asc=false ) ]
   └─ limit: Limit  rowCount=LongValue(100)  offset=null
```

校验器的三层就是沿这棵树从上往下走：`validate` 拿 Statements → `analyzeSelect`/`analyzePlainSelect` 走查询块层 → `checkExpression`/`proveDataSources` 走表达式层。

以下三个例子覆盖校验器实际处理的主要复杂形态，树形图都经过 5.2 实际解析验证（非手绘示意）。

### 例 2：CTE + UNION ALL + 集合层排序分页

```sql
WITH scoped AS (SELECT c.customer_id, c.age_band_code FROM dim_customer c WHERE c.manager_id='M0001')
SELECT s.age_band_code, COUNT(*) AS cnt FROM scoped s GROUP BY s.age_band_code
UNION ALL
SELECT c.customer_level_code, COUNT(*) FROM dim_customer c WHERE c.manager_id='M0001'
GROUP BY c.customer_level_code
ORDER BY 1 LIMIT 100
```

```
Statements
└─ [0] SetOperationList                        ← WITH、UNION、LIMIT 全挂在这一层（根 Select）
   ├─ withItemsList: [ WithItem("scoped") ]    ← CTE 声明在根上，不在分支里
   │    └─ select: PlainSelect                 ← CTE 体是完整查询块
   │         fromItem=Table("dim_customer") alias="c"
   │         where=EqualsTo(c.manager_id = 'M0001')
   │         → 递归校验后输出列 [customer_id, age_band_code] 登记进 ctes
   ├─ selects: List<Select>
   │    ├─ [0] PlainSelect  fromItem=Table("scoped") alias="s"
   │    │                  selectItems=[Column(s.age_band_code), Function(COUNT) alias=cnt]
   │    │                  groupBy=GroupByElement[c.age_band_code]
   │    └─ [1] PlainSelect  fromItem=Table("dim_customer") alias="c"
   │                   where=EqualsTo(c.manager_id='M0001')  groupBy=GroupByElement[...]
   ├─ operations: [ UnionOp(isAll=true) ]      ← 相邻分支之间一个运算符；IntersectOp/ExceptOp 同级
   ├─ orderByElements: [ OrderByElement(LongValue(1)) ]    ← ORDER BY 1 是位置引用
   └─ limit: Limit(rowCount=LongValue(100))    ← 实测：本形态下 LIMIT 挂在根上，最后分支 limit=null
```

校验器关注点：CTE 体以 null 外层作用域递归校验，输出列名成为 `ctes["scoped"]`，外层 `FROM scoped s` 绑定的就是这份列清单；每个分支作为独立查询块走含授权证明的完整校验。**LIMIT 落点有怪癖**：5.2 里有的形态挂根、有的形态挂最后一个分支（`QueryPaginationSql.stripOuterPagination` 因此两处都清）——校验器因为"每个 Select 节点都过 `checkPagination`"而天然两处都覆盖。

### 例 3：标量子查询 + CASE + IN 列表 + EXISTS 关联

```sql
SELECT c.customer_id,
       (SELECT COUNT(*) FROM fct_transaction t WHERE t.customer_id=c.customer_id) AS txn_count,
       CASE WHEN c.total_asset_amount BETWEEN 1000000 AND 5000000 THEN 'MID' ELSE 'BASE' END AS tier
FROM dim_customer c
WHERE c.customer_id IN ('C00000001','C00000002')
  AND EXISTS (SELECT 1 FROM fct_product_holding h WHERE h.customer_id=c.customer_id)
LIMIT 10
```

```
PlainSelect
├─ selectItems:
│   ├─ [0] SelectItem( Column(c.customer_id) )
│   ├─ [1] SelectItem( ParenthesedSelect, alias="txn_count" )      ← 标量子查询是表达式
│   │         └─ getSelect(): PlainSelect
│   │              fromItem=Table("fct_transaction") alias="t"
│   │              where=EqualsTo(t.customer_id = c.customer_id)   ← 引用外层别名 c：关联子查询
│   │              selectItems=[ Function(COUNT, parameters=[AllColumns]) ]  ← COUNT(*)
│   └─ [2] SelectItem( CaseExpression, alias="tier" )
│            ├─ whenClauses: [ WhenClause(
│            │      whenExpression = Between(c.total_asset_amount, LongValue(1000000), LongValue(5000000)),
│            │      thenExpression = StringValue("MID") ) ]
│            └─ elseExpression = StringValue("BASE")
├─ fromItem: Table("dim_customer") alias="c"
└─ where: AndExpression
    ├─ left:  InExpression( left=Column(c.customer_id),
    │            right=ParenthesedExpressionList[ StringValue("C00000001"), StringValue("C00000002") ] )
    └─ right: ExistsExpression( right=ParenthesedSelect → PlainSelect(...) )
```

校验器关注点：`IN ('a','b')` 的列表实际是 **`ParenthesedExpressionList`**（带括号的列表，继承 `ExpressionList`），所以名单证明和表达式校验都按 ExpressionList 统一处理；两个 `ParenthesedSelect` 身份不同——`selectItems` 里的是标量子查询、`EXISTS` 里的是谓词子查询，校验器都以**当前作用域为外层**递归 `analyzeSelect`，内层的 `t.customer_id = c.customer_id` 因此能解析到外层别名 `c`（授权事实由此跨块传播）；`BETWEEN` 是独立类型，不是两个比较运算的语法糖。

### 例 4：窗口帧 + CAST 精度 + 复合 ON + 命名参数

```sql
SELECT c.customer_id, CAST(c.total_asset_amount AS DECIMAL(12,2)) AS assets,
       ROW_NUMBER() OVER (PARTITION BY c.branch_id ORDER BY c.total_asset_amount DESC
                          ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS rn
FROM dim_customer c LEFT JOIN fct_transaction t
  ON t.customer_id = c.customer_id AND t.txn_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
WHERE c.manager_id = :scopeManagerId
LIMIT 500
```

```
PlainSelect
├─ selectItems:
│   ├─ [0] Column(c.customer_id)
│   ├─ [1] SelectItem( CastExpression, alias="assets" )
│   │         └─ colDataType = ColDataType( dataType="DECIMAL (12, 2)" )  ← 5.2 把精度并进类型名
│   └─ [2] SelectItem( AnalyticExpression, alias="rn" )
│            ├─ name = "row_number"（5.x 里它不继承 Function）
│            ├─ partitionExpressionList = [ Column(c.branch_id) ]
│            ├─ orderByElements = [ OrderByElement(c.total_asset_amount, asc=false) ]
│            └─ windowElement = WindowElement
│                 └─ range = WindowRange( start=WindowRangeItem(expression=null),   ← UNBOUNDED PRECEDING
│                                          end  =WindowRangeItem(expression=null) ) ← CURRENT ROW
├─ fromItem: Table("dim_customer") alias="c"
├─ joins: [ Join( left=true, rightItem=Table("fct_transaction") alias="t",
│                 onExpressions: [ AndExpression(                        ← 复合 ON 是一个 AndExpression
│                     EqualsTo(t.customer_id = c.customer_id),
│                     GreaterThanEquals(t.txn_date, Function(DATE_SUB)) ) ] ) ]
│                              └─ DATE_SUB 参数 = [ Function(CURDATE), IntervalExpression(1 DAY) ]
└─ where: EqualsTo( Column(c.manager_id) = JdbcNamedParameter(name="scopeManagerId") )
                                                    └─ getName() 不带冒号，校验器拿它查服务端参数表
```

校验器关注点：复合 `ON` 整体是一个 `AndExpression`，`collectConstraints` 拆出两条等值对——其中 `t.txn_date >= DATE_SUB(...)` 右侧是函数，`factOf` 返回 null，**不出边**（不构成授权事实）；LEFT JOIN 的等值边只从 `c.customer_id` 指向 `t.customer_id`；窗口帧边界 `UNBOUNDED PRECEDING`/`CURRENT ROW` 的表达式是 **null**，`checkExpression(null)` 直接返回——判空不可省；`CAST` 的 `getDataType()` 带精度后缀，须截取括号前的纯类型名再匹配白名单（这里曾有一个真实 bug：`DECIMAL(12,2)` 被误拒，已修复）。

## 2. 入口与语句层

### `CCJSqlParserUtil.parseStatements(sql, Consumer<CCJSqlParser>)` → `Statements`

- 解析入口。`Statements extends ArrayList<Statement>`——**它本身就是 List\<Statement\>**，所以能直接 `size()`、`get(0)`。
- 第二个参数是给解析器的配置钩子，校验器用它设置超时：`parser -> parser.withTimeOut(2000)`，防构造输入把解析器拖死。
- **必须用复数版**：单数版 `parse()` 遇到 `SELECT 1; SELECT 2` 不报错、静默返回第一条，多语句注入会被它藏起来；复数版会全部解析出来，校验器再显式断言 `size()==1`。

### `Statement`

所有可执行语句的父类型：`Select`、`Insert`、`Update`、`Delete`、`CreateTable`、`Grant`、`Commit`……解析成功不代表语句合法，所以校验器用 `statements.get(0) instanceof Select` 做**类型级**过滤——DELETE 进来解析成功，但在 instanceof 处被拒。

### `Select`（抽象类）

校验器里 `Select` 类型的变量实际是三个子类之一，用 `instanceof` 区分：

| 子类 | 对应 SQL 形态 | 校验器入口 |
|---|---|---|
| `PlainSelect` | 普通 `SELECT ... FROM ...` 查询块 | `analyzePlainSelect` |
| `SetOperationList` | `a UNION b`、`a INTERSECT b` | `analyzeSetOperation` |
| `ParenthesedSelect` | `(SELECT ...)`——括号包裹的子查询 | 递归 `analyzeSelect` 解括号 |

`Select` 同时实现三个接口，这解释了"子查询为什么能到处出现"：

- 实现 `Statement`：能当一条语句；
- 实现 **`Expression`**：能出现在表达式位置（标量子查询、`EXISTS(...)`、`IN (...)`）；
- 实现 **`FromItem`**：能出现在 FROM 后当派生表。

校验器用到的 `Select` 基类字段（三个子类共有）：

| 字段 | 对应 SQL | 校验器用法 |
|---|---|---|
| `withItemsList: List<WithItem>` | `WITH x AS (...)` | `declareCtes` 逐个声明 CTE |
| `limit: Limit`、`offset: Offset` | `LIMIT n OFFSET m` | `checkPagination` |
| `forMode/forClause/forUpdateTable/isolation` | `FOR UPDATE`、锁、隔离级别 | 出现即拒绝 |
| `fetch: Fetch`、`limitBy: LimitBy` | `FETCH FIRST`、`LIMIT n BY x` | 出现即拒绝 |
| `pivot/unPivot` | PIVOT 语法 | 出现即拒绝 |

### `PlainSelect`

标准查询块，字段与 SQL 子句一一对应：

| 字段 | 对应 SQL | 校验器用法 |
|---|---|---|
| `selectItems: List<SelectItem<?>>` | `SELECT` 后的列/表达式 | 逐项校验并推导输出列名 |
| `fromItem: FromItem` | `FROM` 后的数据源 | `bindDataSource` 登记绑定 |
| `joins: List<Join>` | 每个 `JOIN ...` 一个元素 | JOIN 类型限制 + 右表绑定 |
| `where: Expression` | `WHERE`（可能为 null） | 约束收集 + 表达式校验 |
| `groupBy: GroupByElement` | `GROUP BY`（可能为 null） | 拒绝 GROUPING SETS、校验分组列 |
| `having: Expression` | `HAVING`（可能为 null） | 表达式校验 |
| `orderByElements: List<OrderByElement>` | `ORDER BY` | 表达式校验（允许引用输出列别名） |
| `distinct: Distinct` | `DISTINCT` | 仅当 `getOnSelectItems() != null`（即 `DISTINCT ON (...)`）时拒绝 |
| `mySqlSqlCalcFoundRows: boolean` | `SQL_CALC_FOUND_ROWS` | 出现即拒绝 |
| `windowDefinitions` | `WINDOW w AS (...)` 命名窗口 | 定义逐个校验（名称本块唯一、表达式白名单），名称登记到本块作用域供 `OVER w` 引用检查 |
| `intoTables/intoTempTable` | `SELECT ... INTO` | 出现即拒绝 |
| `top/skip/first` | SQL Server / Firebird 方言 | 出现即拒绝 |
| `oracleHint/oracleHierarchical` | Oracle hint / `START WITH` 层级查询 | 出现即拒绝 |
| `qualify` | Snowflake `QUALIFY` | 出现即拒绝 |
| `lateralViews` | Hive `LATERAL VIEW` | 出现即拒绝 |
| `sampleClause/preferringClause/ksqlWindow/bigQuerySelectQualifier` | 各方言扩展 | 出现即拒绝 |

**读法要点**：这些字段全部可能为 `null`——AST 用 null 表示"该子句没写"，所以校验器每个分支都先判空。

### `SetOperationList`

`a UNION ALL b INTERSECT c` 解析成一个列表：`selects: List<Select>`（各分支）+ `operations: List<SetOperation>`（分支之间的运算符，每相邻两个分支之间一个）。`UnionOp.isAll()` 区分 UNION 和 UNION ALL；`IntersectOp`/`ExceptOp`/`MinusOp` 是同级的其它运算符——校验器放行 `UnionOp`/`IntersectOp`/`ExceptOp`（MySQL 8.0.31+ 支持），`MinusOp` 等 Oracle 方言拒绝。集合层自己的 `orderByElements` 只能引用分支输出列（校验器为它构造一个只有别名的空作用域）。

### `ParenthesedSelect`

`(SELECT ...)` 的专用类型：`getSelect()` 解开括号拿到内部的 Select，`getAlias()` 是派生表别名，自身还能带 `LIMIT`（`(SELECT ...) LIMIT 10`）。它在两个身份之间复用：表达式位置的子查询（标量/EXISTS/IN 内部）和 FROM 位置的派生表——校验器在两个位置分别处理，且 FROM 位置传入的外层作用域是 null（不支持 LATERAL），表达式位置传入当前作用域（支持关联）。

### `WithItem`（CTE）

`WITH scoped AS (SELECT ...), other AS (...)` 里每个 `AS` 一项是一个 `WithItem`：`getAliasName()` 是 CTE 名，`getWithItemList()` 是 `WITH x(a,b)` 的显式列名列表（校验器拒绝它，列名必须由 SELECT 推导），`isRecursive()` 标记 `WITH RECURSIVE`，`getSelect()` 是 CTE 体。

## 3. FROM / JOIN 层

### `FromItem`（接口）

"能出现在 FROM 后面的东西"的公共类型：`Table`、`ParenthesedSelect`（派生表）都实现它。校验器的 `bindDataSource(FromItem, ...)` 用 instanceof 区分两者，其它实现（表函数、VALUES 子句）落入拒绝分支。

### `Table`（`net.sf.jsqlparser.schema.Table`）

物理表引用：`getName()` 表名、`getAlias()` 别名、`getSchemaName()` 是 `db.table` 里的库名限定、`getDatabase()` 是 5.x 的库对象（`db.table` 的另一种表达）。校验器对这两处限定**一律拒绝**——业务 SQL 不允许跨库。

### `Alias`（`expression.Alias`）

别名的统一载体，`FROM dim_customer c` 的 `c`、`SUM(x) AS amount` 的 `amount` 都是它。`getName()` 取名字。注意它同时挂在 `Table`、`SelectItem`、`WithItem`、`ParenthesedSelect` 上——"取别名"这个动作在 SQL 里到处都是，JSqlParser 用一个类统一表达。

### `Join`

一个 `JOIN` 子句一个对象。**没有"左表"字段**——左表就是 `PlainSelect.fromItem`（或上一个 Join 的右表），`Join` 只持有自己的右表：`getRightItem(): FromItem`。标志位是一组 boolean：

| 方法 | 对应 SQL | 校验器态度 |
|---|---|---|
| `isLeft()` | `LEFT JOIN` | 允许；决定 ON 约束只向右表传播 |
| `isInner()`（默认形态） | `JOIN` / `INNER JOIN` | 允许 |
| `isRight() / isFull() / isNatural() / isCross()` | RIGHT/FULL/NATURAL/CROSS | 拒绝 |
| `isSimple()` | 逗号连接 `FROM a, b`（解析成 fromItem + simple join） | 拒绝 |
| `isApply() / isSemi()` | `CROSS/OUTER APPLY`、`SEMI JOIN` | 拒绝 |

`getOnExpressions(): Collection<Expression>` 是 ON 条件（集合类型是为了兼容个别方言一表多 ON 的写法，MySQL 下通常一个元素）。`getUsingColumns() != null` 即 `USING (col)` 写法——被拒绝，因为它等价于 ON 但让"列来自哪张表"变模糊。

## 4. 表达式层

`Expression` 是接口，校验器的 `checkExpression` 用 `instanceof` 链做分发。**分发顺序就是下面的讲解顺序**；最后一个兜底分支 `fail("不支持的SQL表达式：" + 类名)` 是安全基石——任何没显式接受的类型（包括 JSqlParser 升级新增的）都被拒绝。

### 字面量家族（校验器直接放行）

| 类型 | SQL 写法 |
|---|---|
| `LongValue` / `DoubleValue` | `100` / `1.5` |
| `StringValue` | `'abc'`（`getValue()` 已去掉两侧引号） |
| `NullValue` | `NULL` |
| `DateValue` / `TimeValue` / `TimestampValue` | `{d '2026-01-01'}` 等 JDBC 转义形态；MySQL 的 `DATE('...')` 是函数不是它 |
| `DateTimeLiteralExpression` | 标准字面量 `TIMESTAMP '...'` |
| `JdbcNamedParameter` | `:name`——`getName()` 返回不带冒号的名字，校验器拿它去服务端参数表里核对值 |

### `Column`（`schema.Column`）——最常用也最容易误解

`getColumnName()` 列名；**`getTable()` 是"限定名"，不是真实表**：`c.customer_id` 里它返回一个名字为 `c` 的 Table 对象，未限定（`customer_id`）时返回 null。校验器拿这个限定名去作用域里查**别名绑定**（`resolveColumn`），而不是当表名用——别名可能根本不对应任何物理表（CTE、派生表）。这就是"限定名命中即检查、列不在直接拒绝"的原因。

### `BinaryExpression`（抽象类）及其子类

所有二元运算的公共父类：`getLeftExpression()` + `getRightExpression()`。**每个运算符是具体子类**，`getClass().getSimpleName()` 就是运算符名——校验器的 `BINARY_OPERATORS` 白名单按类名匹配（`AndExpression`、`EqualsTo`、`GreaterThan`、`Addition`、`IntegerDivision`（MySQL 的 `DIV`）、`LikeExpression`……）。位运算、正则匹配（`RegExpMatchOperator`）、`<=>` 等 不在名单内 → 兜底拒绝。

### `ExpressionList<T>` 与 `ParenthesedExpressionList<T>`

两个"表达式列表"类，**都继承 `ArrayList<T>`**——所以代码里能直接 for-each。它们是"一组表达式"的通用容器，在 SQL 里的身份由位置决定：`IN (a,b,c)` 的列表、函数参数、`GROUP BY` 的列、窗口的 `PARTITION BY` 都是它。`ParenthesedExpressionList` 额外包一层括号：`IN ('a','b')` 的列表、以及 `(a AND b)` 这种括号包裹的条件。校验器里 `collectConstraints` 对"括号里恰好一个条件"的形态做了解包（`size()==1` 取第 0 个继续递归），否则 AND 链收集会漏。

### `InExpression`

`x IN (...)`：`getLeftExpression()` 是左值，`getRightExpression()` 是**两种形态之一**——字面量列表（`ExpressionList`/`ParenthesedExpressionList`，内含 `StringValue` 等）或 `ParenthesedSelect`（`IN (SELECT ...)` 子查询）。校验器对两种形态分别处理：表达式校验两种都递归；IN 名单证明只认"全部是 `StringValue` 的列表"。

### 谓词与控制流类

| 类型 | SQL 形态 | 校验器递归的字段 |
|---|---|---|
| `ExistsExpression` | `EXISTS (SELECT ...)` | 只递归 `rightExpression`（即那个子查询） |
| `IsNullExpression` | `x IS [NOT] NULL` | `leftExpression` |
| `NotExpression` | `NOT (...)` | 内部表达式 |
| `SignedExpression` | `-x`、`+x`（正负号是一元运算） | 内部表达式 |
| `Between` | `x BETWEEN a AND b` | 左值、`getBetweenExpressionStart()`、`getBetweenExpressionEnd()` |
| `CaseExpression` | `CASE [x] WHEN ... THEN ... [ELSE ...] END` | `switchExpression`、`whenClauses: List<WhenClause>`、`elseExpression` |
| `WhenClause` | `WHEN ... THEN ...` | `whenExpression`、`thenExpression` |
| `ExtractExpression` | `EXTRACT(YEAR FROM x)` | `expression` |
| `IntervalExpression` | `INTERVAL 1 DAY`（出现在 `DATE_ADD(x, INTERVAL 1 DAY)` 第二参） | `expression` |

### `CastExpression`

`CAST(x AS CHAR)`：`getLeftExpression()` 是被转换的表达式；`getColDataType()` 返回 `ColDataType`（来自 `create.table` 包——建表语句里的列类型复用了同一个类），`getDataType()` 是类型名字符串——**注意 5.2 会把精度并进去**（`DECIMAL(12,2)` 返回 `"DECIMAL (12, 2)"`），校验器截取括号前的纯类型名再按 9 种类型白名单匹配。

### `Function`

`SUM(x)`、`DATE_FORMAT(x,'%Y')` 等。字段：`getName()` 函数名（校验器对它做白名单）；`getParameters(): ExpressionList<?>` 圆括号内的参数；`isDistinct()` 是 `COUNT(DISTINCT x)` 的标志（放行）；**三个"方言扩展位"出现即拒绝**——`getNamedParameters()`（`TRIM(BOTH ' ' FROM x)` 的 BOTH/FROM 结构）、`getKeep()`（Oracle 聚合的 KEEP）、`getAttribute()`（属性访问类变体）。参数里的 `AllColumns`（`*`）只在 `COUNT(*)` 下豁免——`SELECT *` 和 `count(t.*)`（`AllTableColumns` 类型）都被拒。

### `AnalyticExpression`（窗口函数）

`ROW_NUMBER() OVER (PARTITION BY x ORDER BY y)`。**5.x 起它不再继承 `Function`**（4.x 时代继承），是独立的 Expression 实现，所以校验器里它和 `Function` 是两个并列分支。结构：`getExpression()` 窗口内聚合的值、`getPartitionExpressionList()` PARTITION BY 列表、`getOrderByElements()` 窗口内排序、`getWindowElement()` 窗口帧（`ROWS BETWEEN ... AND ...`：`getOffset()` 与 `getRange().getStart()/getEnd()` 各自还有表达式）、`getWindowName()`（`OVER w` 引用命名窗口——必须命中本块 WINDOW 子句已定义的名称）、`getFilterExpression()`（`FILTER (WHERE ...)`——拒绝）。校验器把这些位置的表达式**全部**递归校验。

### `TimeKeyExpression`

`CURRENT_DATE`、`CURRENT_TIMESTAMP` 这类"无括号时间关键字"解析为它——不是 `Function` 也不是字面量，而是独立的 Expression 实现（`getStringValue()` 取关键字名）。MySQL 里 `CURRENT_DATE` 与 `CURRENT_DATE()` 两种写法都合法：前者是 `TimeKeyExpression`，后者是 `Function`——所以校验器要放开时间函数必须同时处理两条路径：`FUNCTIONS` 白名单加函数形态，再加一个 `TimeKeyExpression` 分支按 `TIME_KEYWORDS` 白名单匹配。

## 5. 分页、排序、分组

### `Limit` / `Offset`

`LIMIT 100` → `Limit.getRowCount()`；`LIMIT 100 OFFSET 5` → `Limit.getOffset()`。**两个字段都是 `Expression` 而不是数值**——`LIMIT` 里可以写别的东西（变量、`ALL`），所以校验器必须 `instanceof LongValue` 确认是常量整数再取值。独立的 `OFFSET` 子句（`statement.select.Offset`）同理，`getOffset()` 也是 `Expression`。这就是 `checkPagination` 里那两处强转的来历。

### `OrderByElement`

一个排序键一个对象：`getExpression()`（可以是列、别名、位置数字 `ORDER BY 1`）+ `isAsc()/isAscDescPresent()`。校验器只校验表达式本身，不关心方向。

### `GroupByElement`

`GROUP BY a, b` → `getGroupByExpressionList(): ExpressionList`；`GROUPING SETS (...)` → `getGroupingSets()`（拒绝）；MySQL 的 `WITH ROLLUP` 解析为 `usingRollUp` 标志（当前校验器未拦截，实际放行）。

## 6. 列解析相关的机制细节

- **原始文本保留**：`getName()/getColumnName()` 返回的是**源码里的原文**（含原始大小写；反引号引用会把引号也带进名字）。校验器的 `normalizeIdentifier`（转小写、正则限制；反引号已在词法层整体禁止）就是为了让"证明时比较的标识符"和"数据库实际比较的标识符"是同一个。
- **`toString()` 往返**：每个 AST 节点都能 `toString()` 回 SQL 文本（`QueryPaginationSql` 靠这个把改写后的树序列化成分页 SQL）。校验器是纯读，不用这个能力，但它解释了为什么改写类方案在 JSqlParser 上可行。
- **Visitor 模式被放弃的原因**：JSqlParser 官方遍历方式是 `expr.accept(visitor)` + `ExpressionVisitor` 接口（约 90 个 `visit(Xxx)` 方法）；其适配器基类 `ExpressionVisitorAdapter` 对未覆盖的方法给**空实现**——意味着没写到的表达式类型静默通过。校验器改用显式 `instanceof` 分发 + 兜底拒绝，把"未知类型"从静默放行变成显式失败。

## 7. 校验器代码片段 ↔ 数据结构对照表

| 校验器代码 | 涉及类型 | 在做什么 |
|---|---|---|
| `parseStatements(sql, p -> p.withTimeOut(...))` | `Statements`、`CCJSqlParser` | 解析成语句列表 + 限时 |
| `statements.get(0) instanceof Select` | `Statement` | 类型级只读过滤 |
| `select instanceof PlainSelect / SetOperationList / ParenthesedSelect` | `Select` 三实现 | 结构分发 |
| `item.getWithItemList() != null && !isEmpty()` | `WithItem` | 拒绝 CTE 显式列名 |
| `select.getForMode() != null ...` | `Select` 扩展字段 | 方言特性拒绝 |
| `limit.getRowCount() instanceof LongValue` | `Limit` | LIMIT 必须是常量整数 |
| `join.isLeft() ? rightSide : null` | `Join` | LEFT JOIN 约束只向右表传播 |
| `join.getOnExpressions()` | `Join` | 逐个收集 ON 等值 |
| `join.isSimple() \|\| join.isApply() ...` | `Join` | JOIN 类型白名单 |
| `column.getTable().getName()` | `Column` | 限定名 → 别名查找 |
| `binary.getClass().getSimpleName()` | `BinaryExpression` 子类 | 运算符白名单 |
| `argument instanceof AllColumns && "count".equals(name)` | `Function`、`AllColumns` | 只豁免 `COUNT(*)` |
| `cast.getColDataType().getDataType()` | `CastExpression`、`ColDataType` | CAST 类型白名单 |
| `analytic.getWindowElement()...getExpression()` | `AnalyticExpression`、`WindowElement` | 窗口帧内表达式全量校验 |
| `in.getRightExpression()` | `InExpression` | 字面量列表（名单证明）或子查询（递归） |

## 8. 版本注意（4.x → 5.x）

项目钉在 **5.2**（随 `mybatis-plus-jsqlparser`）。从 4.x 到 5.x 的破坏性变化里，和本项目直接相关的：

- `SubSelect` 类被移除，统一为 `ParenthesedSelect`；
- `AnalyticExpression` 不再继承 `Function`（4.x 继承）——判断顺序依赖这个事实的代码要小心；
- `Limit.getOffset()` 从 `Offset` 对象变为 `Expression`；
- `ItemsList` 体系被移除，统一为 `ExpressionList`/`ParenthesedExpressionList`；
- `WithItem` 泛型化（`WithItem<K extends ParenthesedStatement>`）。

升级 JSqlParser 时的两个固定动作：跑 `SqlSafetyValidatorTest` + `GeneratedSqlScopeValidatorTest` 全绿；新出现的表达式类型会落进校验器兜底分支被拒绝——按报错里的类名决定加白名单还是保持拒绝，不要为了兼容绕过兜底。

## 9. 同一套结构在"写"模式下的样子

[QueryPaginationSql](../crm/backend/src/main/java/com/boc/nl2sql/dao/execution/QueryPaginationSql.java) 用的是同一批类，但方向相反——**构造与修改**而不是检查：`new Limit()` + `setRowCount(new LongValue(n))` 注入服务端分页、`setOrderByElements(...)` 追加稳定排序键、`setLimit(null)` 剥掉原分页（5.2 会把无括号 UNION 的 LIMIT 挂在最后一个分支上，所以它清了两个位置），最后 `toString()` 序列化。校验器（读）与分页改写（写）合起来就是本项目对 JSqlParser 的完整用法。
