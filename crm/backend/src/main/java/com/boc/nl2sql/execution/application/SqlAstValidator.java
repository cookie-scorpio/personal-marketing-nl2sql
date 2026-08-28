package com.boc.nl2sql.execution.application;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.*;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.statement.select.*;
import java.util.*;

/**
 * 白名单 AST 校验。每个查询块单独解析列作用域，并证明每个物理数据源被授权条件约束。
 * 不能证明的结构直接拒绝；不把某处出现的范围字符串当成整条 SQL 的授权依据。
 */
public final class SqlAstValidator {
    private static Set<String> words(String text){return Set.of(text.split(" "));}
    private static final Map<String,Set<String>> SCHEMA=Map.of(
        "dim_customer",words("customer_id customer_name_masked gender_code age age_band_code mobile_masked customer_level_code vip_flag risk_level_code occupation_code region_code branch_id manager_id total_asset_amount asset_change_3m_rate open_date status_code snapshot_date"),
        "fct_transaction",words("transaction_id customer_id product_id transaction_time transaction_date transaction_type_code debit_credit_flag currency_code amount_cny branch_id status_code"),
        "fct_product_holding",words("holding_id customer_id product_id product_name product_category_code holding_amount market_value_amount profit_amount maturity_date risk_level_code snapshot_date"),
        "dim_marketing_campaign",words("campaign_id campaign_name campaign_type_code campaign_status_code product_id target_customer_segment_code channel_code owner_org_id owner_manager_id start_time end_time budget_amount target_count"),
        "fct_customer_marketing",words("relation_id campaign_id customer_id contact_time contact_channel_code response_flag conversion_flag conversion_amount"));
    private static final Set<String> FUNCTIONS=words("count sum avg min max round abs ceil ceiling floor coalesce ifnull nullif if concat concat_ws substring substr left right length char_length lower upper trim date date_format year month day dayofmonth quarter datediff timestampdiff date_add date_sub extract greatest least stddev_pop stddev_samp variance var_pop var_samp power sqrt mod row_number rank dense_rank lag lead first_value last_value ntile");
    private static final Set<String> BINARY=words("AndExpression OrExpression EqualsTo NotEqualsTo GreaterThan GreaterThanEquals MinorThan MinorThanEquals Addition Subtraction Multiplication Division IntegerDivision Modulo LikeExpression");
    private final CurrentUser user;private final Map<String,Object> parameters;private final String customer;
    private final int maxRows;private int nextId;private int nodes;
    public SqlAstValidator(CurrentUser user,Map<String,Object> parameters,String customer,int maxRows){this.user=user;this.parameters=parameters;this.customer=customer;this.maxRows=maxRows;}
    public void validate(String sql){
        if(sql==null||sql.isBlank()||sql.length()>30000)fail(422101,"SQL为空或超过长度限制");
        lexical(sql);
        try{
            var statements=CCJSqlParserUtil.parseStatements(sql,p->p.withTimeOut(2000));
            if(statements.size()!=1||!(statements.get(0) instanceof Select))fail(422101,"仅允许单条只读SELECT");
            Select select=(Select)statements.get(0);checkLimit(select,true);analyze(select,null,new LinkedHashMap<>(),0);
        }catch(BusinessException e){throw e;}catch(Exception e){fail(422101,"SQL语法无法解析或尚未支持");}
    }
    private static void lexical(String sql){
        boolean quoted=false,identifier=false;
        for(int i=0;i<sql.length();i++){
            char c=sql.charAt(i);
            if(quoted){if(c=='\\')fail(422101,"字符串转义方式未支持，请使用标准单引号转义");if(c=='\''){if(i+1<sql.length()&&sql.charAt(i+1)=='\'')i++;else quoted=false;}continue;}
            if(c=='`'){identifier=!identifier;continue;}if(identifier)continue;
            if(c=='\''){quoted=true;continue;}
            if(c=='@'||c==';'||c=='#'||c=='"'||(i+1<sql.length()&&((c=='-'&&sql.charAt(i+1)=='-')||(c=='/'&&sql.charAt(i+1)=='*'))))fail(422101,"不允许变量、注释、多语句或双引号歧义");
        }
        if(quoted||identifier)fail(422101,"SQL引号未闭合");
    }
    private List<String> analyze(Select select,Scope parent,Map<String,List<String>> inherited,int depth){
        if(depth>12||++nodes>3000)fail(422101,"SQL结构超过复杂度限制");
        if(select.getForMode()!=null||select.getForClause()!=null||select.getForUpdateTable()!=null||select.getIsolation()!=null||select.getFetch()!=null||select.getLimitBy()!=null||select.getPivot()!=null||select.getUnPivot()!=null)fail(422101,"不允许锁定或未支持的SELECT扩展");
        checkLimit(select,false);
        Map<String,List<String>> ctes=new LinkedHashMap<>(inherited);
        if(select.getWithItemsList()!=null)for(var item:select.getWithItemsList()){
            if(item.isRecursive()||item.getSelect()==null||item.getWithItemList()!=null&&!item.getWithItemList().isEmpty())fail(422101,"仅支持非递归SELECT CTE，列名请在SELECT中显式声明");
            String name=id(item.getAliasName());if(ctes.containsKey(name)||SCHEMA.containsKey(name))fail(422101,"CTE名称重复或遮蔽业务表");
            ctes.put(name,analyze(item.getSelect(),null,ctes,depth+1));
        }
        if(select instanceof ParenthesedSelect p)return analyze(p.getSelect(),parent,ctes,depth+1);
        if(select instanceof SetOperationList set){
            if(set.getOperations().stream().anyMatch(o->!(o instanceof UnionOp)))fail(422101,"集合运算仅支持UNION与UNION ALL");
            List<String> columns=null;for(Select branch:set.getSelects()){
                var result=analyze(branch,parent,ctes,depth+1);if(columns!=null&&columns.size()!=result.size())fail(422101,"UNION各分支列数不一致");if(columns==null)columns=result;
            }
            Scope output=new Scope(null);output.aliases.addAll(columns==null?List.of():columns);
            order(set.getOrderByElements(),output,ctes,depth);return columns;
        }
        if(!(select instanceof PlainSelect)) {fail(422101,"仅支持受控SELECT查询块");return List.of();}
        PlainSelect plain=(PlainSelect)select;
        if(plain.getWindowDefinitions()!=null&&!plain.getWindowDefinitions().isEmpty()||plain.getPreferringClause()!=null||plain.getSampleClause()!=null||plain.getKsqlWindow()!=null||plain.getBigQuerySelectQualifier()!=null||plain.getDistinct()!=null&&plain.getDistinct().getOnSelectItems()!=null||plain.getMySqlSqlCalcFoundRows())fail(422101,"不支持命名窗口、抽样、DISTINCT ON或SQL_CALC_FOUND_ROWS");
        if(plain.getIntoTables()!=null&&!plain.getIntoTables().isEmpty()||plain.getIntoTempTable()!=null||plain.getTop()!=null||plain.getSkip()!=null||plain.getFirst()!=null||plain.getOracleHierarchical()!=null||plain.getOracleHint()!=null||plain.getQualify()!=null||plain.getLateralViews()!=null&&!plain.getLateralViews().isEmpty())fail(422101,"不允许SELECT写入或未支持的查询扩展");
        Scope scope=new Scope(parent);add(plain.getFromItem(),scope,ctes,depth);
        if(plain.getJoins()!=null)for(Join join:plain.getJoins()){
            if(join.isRight()||join.isFull()||join.isNatural()||join.isCross()||join.isSimple()||join.isApply()||join.isSemi()||join.getUsingColumns()!=null&&!join.getUsingColumns().isEmpty())fail(422101,"关联请使用INNER/LEFT JOIN及明确ON条件");
            add(join.getRightItem(),scope,ctes,depth);
        }
        // 先证明WHERE及JOIN保证成立的限制，再验证子查询，支持关联子查询引用已授权外层。
        List<Edge> edges=new ArrayList<>();conditions(plain.getWhere(),scope,edges,null);
        if(plain.getJoins()!=null)for(Join join:plain.getJoins())for(Expression on:join.getOnExpressions()){
            Binding right=scope.bindings.get(alias(join.getRightItem()));conditions(on,scope,edges,join.isLeft()?right:null);
        }
        prove(scope,edges);
        expr(plain.getWhere(),scope,ctes,depth,false);
        if(plain.getJoins()!=null)for(Join join:plain.getJoins())for(Expression on:join.getOnExpressions())expr(on,scope,ctes,depth,false);
        List<String> output=new ArrayList<>();
        for(SelectItem<?> item:plain.getSelectItems()){
            expr(item.getExpression(),scope,ctes,depth,false);
            String name=item.getAlias()!=null?id(item.getAlias().getName()):item.getExpression() instanceof Column c?id(c.getColumnName()):null;
            if(name==null)name="expression_"+output.size();
            if(output.contains(name))fail(422101,"结果列名称重复，请提供唯一别名");output.add(name);
        }
        scope.aliases.addAll(output);
        if(plain.getGroupBy()!=null){
            if(plain.getGroupBy().getGroupingSets()!=null&&!plain.getGroupBy().getGroupingSets().isEmpty())fail(422101,"暂不支持GROUPING SETS");
            expr(plain.getGroupBy().getGroupByExpressionList(),scope,ctes,depth,true);
        }
        expr(plain.getHaving(),scope,ctes,depth,true);order(plain.getOrderByElements(),scope,ctes,depth);
        return output;
    }
    private void checkLimit(Select select,boolean required){
        Limit limit=select.getLimit();if(limit==null){
            if(required&&select instanceof ParenthesedSelect p){checkLimit(p.getSelect(),true);return;}
            // JSqlParser 5.2把未加括号UNION末尾的MySQL全局LIMIT附在最后一个PlainSelect上。
            if(required&&select instanceof SetOperationList set&&!set.getSelects().isEmpty()&&set.getSelects().get(set.getSelects().size()-1) instanceof PlainSelect last){checkLimit(last,true);return;}
            if(required)fail(422102,"最外层查询必须有LIMIT");return;
        }
        if(!(limit.getRowCount() instanceof LongValue))fail(422102,"LIMIT必须为常量整数");
        long value=((LongValue)limit.getRowCount()).getValue();if(value<1||value>maxRows)fail(422102,"LIMIT必须在1至"+maxRows+"之间");
        if(limit.getOffset()!=null&&(!(limit.getOffset() instanceof LongValue)||((LongValue)limit.getOffset()).getValue()<0))fail(422102,"OFFSET必须为非负整数");
        if(select.getOffset()!=null&&(!(select.getOffset().getOffset() instanceof LongValue)||((LongValue)select.getOffset().getOffset()).getValue()<0))fail(422102,"OFFSET必须为非负整数");
    }
    private void add(FromItem from,Scope scope,Map<String,List<String>> ctes,int depth){
        if(from==null)return;
        String name=alias(from);if(scope.bindings.containsKey(name))fail(422101,"同一查询块的表别名重复");
        if(from.getPivot()!=null||from.getUnPivot()!=null)fail(422101,"不支持PIVOT");
        if(from instanceof Table table){
            if(table.getSchemaName()!=null||table.getDatabase()!=null&&table.getDatabase().getDatabaseName()!=null)fail(403102,"禁止跨库或限定库名访问");
            String base=id(table.getName());
            if(ctes.containsKey(base))scope.bindings.put(name,new Binding(++nextId,null,new LinkedHashSet<>(ctes.get(base)),true,true));
            else {if(!SCHEMA.containsKey(base))fail(403102,"数据对象不在白名单中");scope.bindings.put(name,new Binding(++nextId,base,SCHEMA.get(base),false,false));}
        }else if(from instanceof ParenthesedSelect sub){
            if(sub.getAlias()==null)fail(422101,"派生表必须有别名");scope.bindings.put(name,new Binding(++nextId,null,new LinkedHashSet<>(analyze(sub.getSelect(),null,ctes,depth+1)),true,true));
        }else fail(422101,"不支持该数据源结构");
    }
    private void expr(Expression expression,Scope scope,Map<String,List<String>> ctes,int depth,boolean aliases){
        if(expression==null)return;if(++nodes>3000)fail(422101,"SQL表达式过多");
        if(expression instanceof Column c){String name=id(c.getColumnName());if((c.getTable()==null||c.getTable().getName()==null)&&Set.of("true","false").contains(name))return;if(aliases&&(c.getTable()==null||c.getTable().getName()==null)&&scope.aliases.contains(name))return;resolve(c,scope);return;}
        if(expression instanceof LongValue||expression instanceof DoubleValue||expression instanceof StringValue||expression instanceof NullValue||expression instanceof DateValue||expression instanceof TimeValue||expression instanceof TimestampValue||expression instanceof DateTimeLiteralExpression||expression instanceof JdbcNamedParameter)return;
        if(expression instanceof BinaryExpression binary){if(!BINARY.contains(binary.getClass().getSimpleName()))fail(422101,"表达式运算符未支持");expr(binary.getLeftExpression(),scope,ctes,depth,aliases);expr(binary.getRightExpression(),scope,ctes,depth,aliases);return;}
        if(expression instanceof ExpressionList<?> list){for(Object child:list)expr((Expression)child,scope,ctes,depth,aliases);return;}
        if(expression instanceof ParenthesedSelect select){analyze(select,scope,ctes,depth+1);return;}
        if(expression instanceof Function function){
            if(!FUNCTIONS.contains(id(function.getName()))||function.getAttribute()!=null||function.getKeep()!=null||function.getNamedParameters()!=null)fail(422101,"函数不在允许清单中");
            if(function.getParameters()!=null)for(Expression arg:function.getParameters()){
                if(arg instanceof AllColumns && "count".equals(id(function.getName())))continue;expr(arg,scope,ctes,depth,aliases);
            }
            order(function.getOrderByElements(),scope,ctes,depth);return;
        }
        if(expression instanceof CaseExpression c){expr(c.getSwitchExpression(),scope,ctes,depth,aliases);if(c.getWhenClauses()!=null)for(var w:c.getWhenClauses())expr(w,scope,ctes,depth,aliases);expr(c.getElseExpression(),scope,ctes,depth,aliases);return;}
        if(expression instanceof WhenClause w){expr(w.getWhenExpression(),scope,ctes,depth,aliases);expr(w.getThenExpression(),scope,ctes,depth,aliases);return;}
        if(expression instanceof Between b){expr(b.getLeftExpression(),scope,ctes,depth,aliases);expr(b.getBetweenExpressionStart(),scope,ctes,depth,aliases);expr(b.getBetweenExpressionEnd(),scope,ctes,depth,aliases);return;}
        if(expression instanceof InExpression i){expr(i.getLeftExpression(),scope,ctes,depth,aliases);expr(i.getRightExpression(),scope,ctes,depth,aliases);return;}
        if(expression instanceof ExistsExpression e){expr(e.getRightExpression(),scope,ctes,depth,aliases);return;}
        if(expression instanceof IsNullExpression n){expr(n.getLeftExpression(),scope,ctes,depth,aliases);return;}
        if(expression instanceof NotExpression n){expr(n.getExpression(),scope,ctes,depth,aliases);return;}
        if(expression instanceof SignedExpression s){expr(s.getExpression(),scope,ctes,depth,aliases);return;}
        if(expression instanceof CastExpression c){if(c.getColDataType()==null||!Set.of("DECIMAL","SIGNED","UNSIGNED","CHAR","DATE","DATETIME","TIME","INTEGER","DOUBLE").contains(c.getColDataType().getDataType().toUpperCase(Locale.ROOT)))fail(422101,"CAST类型未支持");expr(c.getLeftExpression(),scope,ctes,depth,aliases);return;}
        if(expression instanceof ExtractExpression e){expr(e.getExpression(),scope,ctes,depth,aliases);return;}
        if(expression instanceof IntervalExpression i){expr(i.getExpression(),scope,ctes,depth,aliases);return;}
        if(expression instanceof AnalyticExpression a){
            if(!FUNCTIONS.contains(id(a.getName()))||a.getWindowName()!=null||a.getKeep()!=null||a.getFilterExpression()!=null)fail(422101,"窗口函数结构未支持");
            expr(a.getExpression(),scope,ctes,depth,aliases);expr(a.getOffset(),scope,ctes,depth,aliases);expr(a.getDefaultValue(),scope,ctes,depth,aliases);expr(a.getPartitionExpressionList(),scope,ctes,depth,aliases);order(a.getOrderByElements(),scope,ctes,depth);
            if(a.getWindowElement()!=null){var w=a.getWindowElement();if(w.getOffset()!=null)expr(w.getOffset().getExpression(),scope,ctes,depth,aliases);if(w.getRange()!=null){if(w.getRange().getStart()!=null)expr(w.getRange().getStart().getExpression(),scope,ctes,depth,aliases);if(w.getRange().getEnd()!=null)expr(w.getRange().getEnd().getExpression(),scope,ctes,depth,aliases);}}return;
        }
        fail(422101,"不支持的SQL表达式："+expression.getClass().getSimpleName());
    }
    private void order(List<OrderByElement> order,Scope scope,Map<String,List<String>> ctes,int depth){if(order!=null)for(var item:order)expr(item.getExpression(),scope,ctes,depth,true);}
    private Node resolve(Column column,Scope scope){
        String name=id(column.getColumnName());String table=column.getTable()==null?null:column.getTable().getName();
        if(column.getTable()!=null&&column.getTable().getSchemaName()!=null)fail(403102,"禁止跨库字段引用");
        if(table!=null){for(Scope current=scope;current!=null;current=current.parent){Binding binding=current.bindings.get(id(table));if(binding!=null){if(!binding.columns.contains(name))fail(422104,"字段不存在或不允许查询："+name);return new Node(binding,name);}}}
        else for(Scope current=scope;current!=null;current=current.parent){var matches=current.bindings.values().stream().filter(b->b.columns.contains(name)).toList();if(matches.size()>1)fail(422104,"字段含义不明确，请使用表别名："+name);if(matches.size()==1)return new Node(matches.get(0),name);}
        fail(422104,"字段或表别名不存在："+name);return null;
    }
    private void conditions(Expression expr,Scope scope,List<Edge> edges,Binding nullable){
        if(expr==null)return;
        if(expr instanceof ParenthesedExpressionList<?> p&&p.size()==1){conditions(p.get(0),scope,edges,nullable);return;}
        if(expr instanceof AndExpression and){conditions(and.getLeftExpression(),scope,edges,nullable);conditions(and.getRightExpression(),scope,edges,nullable);return;}
        // OR/NOT中的限制不作为授权依据；允许它们存在，但必须另有独立AND范围约束。
        if(expr instanceof EqualsTo equals){
            Object left=term(equals.getLeftExpression(),scope),right=term(equals.getRightExpression(),scope);
            if(left!=null&&right!=null){if(nullable==null){edges.add(new Edge(left,right));edges.add(new Edge(right,left));}
                else {if(right instanceof Node n&&n.binding==nullable)edges.add(new Edge(left,right));if(left instanceof Node n&&n.binding==nullable)edges.add(new Edge(right,left));}}
        }
    }
    private Object term(Expression e,Scope scope){if(e instanceof Column c)return resolve(c,scope);if(e instanceof StringValue v)return "literal:"+v.getValue();if(e instanceof JdbcNamedParameter p&&parameters.containsKey(p.getName()))return "literal:"+parameters.get(p.getName());return null;}
    private void prove(Scope scope,List<Edge> edges){
        if(user==null&&customer==null)return;
        String scopeColumn=user==null?null:switch(user.role()){case CUSTOMER_MANAGER->"manager_id";case TEAM_LEAD->"branch_id";case ORG_MANAGER->"region_code";};
        String scopeValue=user==null?null:switch(user.role()){case CUSTOMER_MANAGER->user.managerId();case TEAM_LEAD->user.branchId();case ORG_MANAGER->user.regionCode();};
        if(user!=null&&(scopeValue==null||scopeValue.isBlank()))fail(403103,"账号数据范围未配置");
        Set<Object> allowed=new HashSet<>(),bound=new HashSet<>(),scopeFacts=new HashSet<>(),identityFacts=new HashSet<>();
        if(user!=null)scopeFacts.add("literal:"+scopeValue);if(customer!=null)identityFacts.add("literal:"+customer);
        for(Scope outer=scope.parent;outer!=null;outer=outer.parent)for(Binding b:outer.bindings.values()){
            if(b.authorized){if(scopeColumn!=null)scopeFacts.add(new Node(b,scopeColumn));allowed.add(new Node(b,"customer_id"));allowed.add(new Node(b,"campaign_id"));}
            if(b.bound)bound.add(new Node(b,"customer_id"));
        }
        for(int i=0;i<scope.bindings.size()*3+3;i++){
            propagate(scopeFacts,edges);propagate(identityFacts,edges);propagate(allowed,edges);propagate(bound,edges);
            for(Binding b:scope.bindings.values()){
                if(b.base==null)continue;
                if("dim_customer".equals(b.base)){
                    if(user!=null&&(scopeFacts.contains(new Node(b,scopeColumn))||allowed.contains(new Node(b,"customer_id")))){b.authorized=true;allowed.add(new Node(b,"customer_id"));}
                    if(customer!=null&&(identityFacts.contains(new Node(b,"customer_id"))||bound.contains(new Node(b,"customer_id")))){b.bound=true;bound.add(new Node(b,"customer_id"));}
                }else{
                    String key="dim_marketing_campaign".equals(b.base)?"campaign_id":"customer_id";
                    if(allowed.contains(new Node(b,key))){b.authorized=true;if("fct_customer_marketing".equals(b.base))allowed.add(new Node(b,"campaign_id"));}
                    if(bound.contains(new Node(b,key))){b.bound=true;if("fct_customer_marketing".equals(b.base))bound.add(new Node(b,"campaign_id"));}
                }
            }
        }
        for(var entry:scope.bindings.entrySet()){
            Binding b=entry.getValue();
            String source="数据源 "+b.base+"（别名 "+entry.getKey()+"，来源编号 "+b.id+"）";
            if(user!=null&&!b.authorized)fail(403104,source+"缺少可证明的账号范围限制。当前账号要求 dim_customer."+scopeColumn+" = '"+scopeValue+"'；请在该查询块的WHERE中限制客户，并通过customer_id关联事实表。CTE或派生表的授权不会自动传递给新关联的事实表；OR/NOT中的条件不能作为授权依据。此SQL未执行，无需用户补充账号权限");
            if(customer!=null&&!b.bound)fail(403105,source+"未保留已确认的customer_id限制；所有客户来源必须限定为已确认客户，此SQL未执行");
        }
    }
    private void propagate(Set<Object> set,List<Edge> edges){for(int i=0;i<edges.size()+1;i++){boolean changed=false;for(Edge e:edges)if(set.contains(e.from))changed|=set.add(e.to);if(!changed)break;}}
    private static String alias(FromItem from){if(from.getAlias()!=null)return id(from.getAlias().getName());if(from instanceof Table t)return id(t.getName());fail(422101,"数据源需要别名");return "";}
    private static String id(String name){if(name==null)return "";String result=name.replace("`","").toLowerCase(Locale.ROOT);if(!result.matches("[a-z_][a-z0-9_]*"))fail(422101,"标识符必须为英文列名或别名");return result;}
    private static void fail(int code,String message){throw new BusinessException(code,"SQL校验未通过："+message);}
    private static class Scope{final Scope parent;final Map<String,Binding> bindings=new LinkedHashMap<>();final Set<String> aliases=new HashSet<>();Scope(Scope parent){this.parent=parent;}}
    private static class Binding{final int id;final String base;final Set<String> columns;boolean authorized,bound;Binding(int id,String base,Set<String> columns,boolean authorized,boolean bound){this.id=id;this.base=base;this.columns=columns;this.authorized=authorized;this.bound=bound;}}
    private record Node(Binding binding,String column){}
    private record Edge(Object from,Object to){}
}
