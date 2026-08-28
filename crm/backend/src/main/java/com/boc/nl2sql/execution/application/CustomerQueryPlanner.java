package com.boc.nl2sql.execution.application;

import com.boc.nl2sql.authorization.application.DataScopePolicy;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import org.springframework.stereotype.Component;
import java.util.*;

/** 仅完整匹配的个人当前资产/持有问法走模板，额外条件不被忽略。 */
@Component
public class CustomerQueryPlanner {
    private final DataScopePolicy scope;
    public CustomerQueryPlanner(DataScopePolicy scope){this.scope=scope;}
    public Optional<PlannedQuery> plan(String text,String customer,CurrentUser user){
        if(customer==null)return Optional.empty();
        String simple=text.replaceFirst("^(再|继续)","").replaceAll("客户编号[：:]?\\s*C[0-9]{8}|C[0-9]{8}","已确认客户")
                .replaceAll("帮我|请|查找一下|查找|查询|查一下|查看|看看|查|一下|已确认客户|客户|的|当前|[，。？?\\s]","");
        Map<String,Object> args=new LinkedHashMap<>();String where=scope.condition("c",user,args)+" AND c.status_code='ACTIVE' AND c.customer_id=:resolvedCustomerId";args.put("resolvedCustomerId",customer);
        if(Set.of("资产信息","资产情况","总资产","资产").contains(simple))return Optional.of(new PlannedQuery(
                "SELECT c.customer_id,c.customer_name_masked AS customer_name,ROUND(c.total_asset_amount/10000,2) AS asset_wan,c.snapshot_date FROM dim_customer c WHERE "+where+" LIMIT 1",args,"METRIC","客户当前资产",false));
        if(Set.of("产品持有","产品持有情况","持有产品","持仓").contains(simple))return Optional.of(new PlannedQuery(
                "SELECT c.customer_id,c.customer_name_masked AS customer_name,h.product_name,h.product_category_code,ROUND(h.market_value_amount/10000,2) AS market_value_wan,h.snapshot_date FROM dim_customer c JOIN fct_product_holding h ON h.customer_id=c.customer_id WHERE "+where+" LIMIT 100",args,"AUTO","客户产品持有",false));
        return Optional.empty();
    }
}
