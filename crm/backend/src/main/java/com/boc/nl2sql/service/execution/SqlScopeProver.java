package com.boc.nl2sql.service.execution;

import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.service.authorization.DataScopePolicy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权证明：以"等值连通"为核心的不动点推导。两条链相互独立——账号链只读写
 * scopeFacts/allowed/scopeProven，客户链只读写 identityFacts/bound/customerProven，
 * 共享的只有只读的等值边表，先后运行与交织运行收敛到同一结果。
 *
 * <p>账号链：种子是账号范围值，dim_customer 的范围列连通到范围值（或 customer_id 连通到
 * 已授权来源）即获证；其余物理表的关键列连通到授权来源获证。</p>
 * <p>客户链：种子是已确认客户编号（含 IN 名单证明），每个物理表的关键列连通到种子获证。</p>
 *
 * <p>外层查询块已证明的绑定可作为本块（关联子查询）的种子。CTE/派生表在其内部查询块
 * 已完成同样的证明，此处视为已证明；但其列不作为本块的证明种子——新关联的事实表
 * 仍须在本块内与已证明来源建立等值连接。OR/NOT 分支不产生事实。</p>
 */
final class SqlScopeProver {
    private final Scope scope;
    private final List<Edge> edges;
    private final CurrentUser user;
    private final Set<String> confirmedCustomers;   // null = 非客户模式
    private final Run run;

    SqlScopeProver(Scope scope, List<Edge> edges, CurrentUser user,
                   Set<String> confirmedCustomers, Run run) {
        this.scope = scope;
        this.edges = edges;
        this.user = user;
        this.confirmedCustomers = confirmedCustomers;
        this.run = run;
    }

    /** 调度：按调用模式运行两条独立证明链，最后统一判定（fail-closed）。 */
    void prove() {
        boolean accountMode = user != null;
        boolean customerMode = confirmedCustomers != null;
        if (!accountMode && !customerMode)
            return; // 纯只读安全校验，无身份要求
        DataScopePolicy.Scope account = accountMode ? DataScopePolicy.scopeOf(user) : null;
        if (accountMode)
            proveAccountScope(account);
        if (customerMode)
            proveCustomerBinding();
        judge(account, customerMode);
    }

    /** 账号链：以账号范围值为种子，沿等值边传播；dim_customer 获证后其 customer_id 成为新的授权来源。 */
    private void proveAccountScope(DataScopePolicy.Scope account) {
        if (account.value() == null || account.value().isBlank())
            SqlErrorCode.ACCOUNT_SCOPE_INVALID.fail("账号数据范围未配置");
        Set<Fact> scopeFacts = new HashSet<>();  // 可连通到账号范围值的事实
        Set<Fact> allowed = new HashSet<>();     // 已获证数据源的授权列
        scopeFacts.add(new ValueFact(account.value()));
        // 关联子查询的支撑：外层已证明的绑定把事实"送给"内层当种子
        for (Binding outerBinding : outerBindings())
            if (outerBinding.scopeProven) {
                scopeFacts.add(new ColumnFact(outerBinding, account.column()));
                allowed.add(new ColumnFact(outerBinding, SqlGuardPolicy.CUSTOMER_ID));
                allowed.add(new ColumnFact(outerBinding, SqlGuardPolicy.CAMPAIGN_ID));
            }

        boolean changed = true;
        while (changed) {
            changed = false;
            changed |= propagate(scopeFacts, edges);
            changed |= propagate(allowed, edges);
            for (Binding binding : scope.bindings.values()) {
                if (!binding.isPhysical() || binding.scopeProven)
                    continue; // CTE/派生表在内部查询块证明
                if (SqlGuardPolicy.DIM_CUSTOMER.equals(binding.baseTable)) {
                    // 范围列连通范围值，或 customer_id 连通到任一已授权来源；
                    // 获证后其 customer_id 成为新的授权来源
                    if (scopeFacts.contains(new ColumnFact(binding, account.column()))
                            || allowed.contains(new ColumnFact(binding, SqlGuardPolicy.CUSTOMER_ID))) {
                        binding.scopeProven = true;
                        allowed.add(new ColumnFact(binding, SqlGuardPolicy.CUSTOMER_ID));
                        changed = true;
                    }
                } else if (allowed.contains(new ColumnFact(binding, keyColumnOf(binding)))) {
                    binding.scopeProven = true;
                    // fct_customer_marketing 获证后，其 campaign_id 成为新的授权来源，
                    // 借此把与它按 campaign_id 关联的 dim_marketing_campaign 一并授权。
                    if (SqlGuardPolicy.FCT_CUSTOMER_MARKETING.equals(binding.baseTable))
                        allowed.add(new ColumnFact(binding, SqlGuardPolicy.CAMPAIGN_ID));
                    changed = true;
                }
            }
        }
    }

    /** 客户链：以已确认客户编号（含 IN 名单证明）为种子，每个物理表的关键列连通到种子即获证。 */
    private void proveCustomerBinding() {
        Set<Fact> identityFacts = new HashSet<>();  // 可连通到已确认客户的事实
        Set<Fact> bound = new HashSet<>();          // 已获证数据源的客户绑定列
        for (String customer : confirmedCustomers)
            identityFacts.add(new ValueFact(customer));
        identityFacts.addAll(run.customerListProven);
        for (Binding outerBinding : outerBindings())
            if (outerBinding.customerProven)
                bound.add(new ColumnFact(outerBinding, SqlGuardPolicy.CUSTOMER_ID));

        boolean changed = true;
        while (changed) {
            changed = false;
            changed |= propagate(identityFacts, edges);
            changed |= propagate(bound, edges);
            for (Binding binding : scope.bindings.values()) {
                if (!binding.isPhysical() || binding.customerProven)
                    continue;
                if (SqlGuardPolicy.DIM_CUSTOMER.equals(binding.baseTable)) {
                    // customer_id 连通到已确认客户事实（字面量、命名参数或 IN 名单证明）；
                    // 获证后其 customer_id 成为新的绑定来源
                    if (identityFacts.contains(new ColumnFact(binding, SqlGuardPolicy.CUSTOMER_ID))
                            || bound.contains(new ColumnFact(binding, SqlGuardPolicy.CUSTOMER_ID))) {
                        binding.customerProven = true;
                        bound.add(new ColumnFact(binding, SqlGuardPolicy.CUSTOMER_ID));
                        changed = true;
                    }
                } else if (identityFacts.contains(new ColumnFact(binding, keyColumnOf(binding)))
                        || bound.contains(new ColumnFact(binding, keyColumnOf(binding)))) {
                    binding.customerProven = true;
                    if (SqlGuardPolicy.FCT_CUSTOMER_MARKETING.equals(binding.baseTable))
                        bound.add(new ColumnFact(binding, SqlGuardPolicy.CAMPAIGN_ID));
                    changed = true;
                }
            }
        }
    }

    /** 判定（fail-closed）：逐绑定先账号后客户，任何未获证的数据源都构成拒绝。 */
    private void judge(DataScopePolicy.Scope account, boolean customerMode) {
        for (var entry : scope.bindings.entrySet()) {
            Binding binding = entry.getValue();
            String source = "数据源 " + (binding.isPhysical() ? binding.baseTable : "派生查询")
                    + "（别名 " + entry.getKey() + "，来源编号 " + binding.id + "）";
            if (account != null && !binding.scopeProven)
                SqlErrorCode.SCOPE_NOT_PROVEN.fail(source + "缺少可证明的账号范围限制。当前账号要求 dim_customer."
                        + account.column() + " = '" + account.value()
                        + "'；请在该查询块的WHERE中限制客户，并通过customer_id关联事实表。"
                        + "CTE或派生表的授权不会自动传递给新关联的事实表；OR/NOT中的条件不能作为授权依据。"
                        + "此SQL未执行，无需用户补充账号权限");
            if (customerMode && !binding.customerProven)
                SqlErrorCode.CUSTOMER_NOT_PROVEN.fail(source
                        + "未保留已确认的customer_id限制；所有客户来源必须限定为已确认客户，此SQL未执行");
        }
    }

    /** 单轮沿等值边传播事实，返回是否有新增；外层循环直到不动点。 */
    private static boolean propagate(Set<Fact> facts, List<Edge> edges) {
        boolean changed = false;
        for (Edge edge : edges)
            if (facts.contains(edge.from()))
                changed |= facts.add(edge.to());
        return changed;
    }

    /** 收集外层作用域链上的全部绑定，供内层（关联子查询）证明时播种。 */
    private List<Binding> outerBindings() {
        List<Binding> outer = new ArrayList<>();
        for (Scope current = scope.parent; current != null; current = current.parent)
            outer.addAll(current.bindings.values());
        return outer;
    }

    /** 物理表的授权关键列：dim_marketing_campaign 没有 customer_id，走 campaign_id。 */
    private static String keyColumnOf(Binding binding) {
        return SqlGuardPolicy.DIM_MARKETING_CAMPAIGN.equals(binding.baseTable)
                ? SqlGuardPolicy.CAMPAIGN_ID
                : SqlGuardPolicy.CUSTOMER_ID;
    }
}
