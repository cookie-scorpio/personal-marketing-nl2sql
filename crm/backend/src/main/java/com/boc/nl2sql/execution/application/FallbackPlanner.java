package com.boc.nl2sql.execution.application;

import com.boc.nl2sql.authorization.application.DataScopePolicy;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import com.boc.nl2sql.nl2sql.application.RuleBasedSemanticParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Optional;

/** 降级仅允许完整匹配的固定模板；不删除无法理解的条件，不把失败SQL再送模型。 */
@Component
public class FallbackPlanner {
    private final RuleBasedSemanticParser parser;
    private final SqlPlanner planner;
    private final DataScopePolicy scope;
    private final int maxRows;
    public FallbackPlanner(RuleBasedSemanticParser parser, SqlPlanner planner, DataScopePolicy scope,
                           @Value("${app.query.max-result-rows:100}") int maxRows) {
        this.parser = parser; this.planner = planner; this.scope = scope; this.maxRows = maxRows;
    }
    public Optional<Template> plan(String text, CurrentUser user) {
        var semantic = parser.parse(text);
        if (!semantic.conflicts().isEmpty()) return Optional.empty();
        if (parser.supportsDeterministicPlan(text, semantic)) {
            return Optional.of(new Template("RULE_" + semantic.intent(), planner.plan(semantic, user)));
        }
        boolean age = text.contains("年龄段");
        boolean gender = text.contains("性别");
        if (age == gender || !text.contains("客户") || (!text.contains("数量") && !text.contains("客户数")
                && !text.contains("平均资产"))) return Optional.empty();
        boolean noTime = text.contains("不限定时间") || text.contains("不限制时间");
        boolean opened = text.contains("开户");
        if (semantic.startDate() != null && !opened && !noTime) return Optional.empty();
        // 消耗完整句子的白名单表达，剩余任何条件都不允许静默省略。
        String remainder = text.replaceAll("近\\s*[0-9一二三四五六七八九十半]+\\s*(天|日|个?月|年)|本季度|本月|今年以来|今年", "")
                .replaceAll("按开户时间筛选|按开户日期筛选|不限定时间|不限制时间|时间口径|补充条件", "")
                .replaceAll("平均资产|客户数量|客户数|年龄段|性别|当前资产|当前客户|新开户|开户|客户|这些", "")
                .replaceAll("分析|统计|查询|比较|查看|分组|分布|各|按|的|和|与|及", "")
                .replaceAll("[\\s，。！？、：,.:!?]", "");
        if (!remainder.isBlank()) return Optional.empty();
        String dimension = age ? "age_band_code" : "gender_code";
        var parameters = new LinkedHashMap<String, Object>();
        String where = "c.status_code = 'ACTIVE' AND " + scope.condition("c", user, parameters);
        if (semantic.startDate() != null && !noTime) {
            where += " AND c.open_date >= :startDate AND c.open_date < :endExclusive";
            parameters.put("startDate", semantic.startDate());
            parameters.put("endExclusive", semantic.endDate().plusDays(1));
        }
        String sql = "SELECT c." + dimension + ", COUNT(DISTINCT c.customer_id) AS customer_count, "
                + "ROUND(AVG(c.total_asset_amount) / 10000, 2) AS avg_asset_wan FROM dim_customer c WHERE "
                + where + " GROUP BY c." + dimension + " ORDER BY c." + dimension + " LIMIT " + maxRows;
        return Optional.of(new Template(age ? "CUSTOMER_AGE_ASSETS" : "CUSTOMER_GENDER_ASSETS",
                new PlannedQuery(sql, parameters, "AUTO", (age ? "年龄段" : "性别") + "客户数量与当前平均资产", false)));
    }
    public record Template(String id, PlannedQuery query) { }
}
