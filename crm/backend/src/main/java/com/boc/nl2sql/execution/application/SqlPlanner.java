package com.boc.nl2sql.execution.application;

import com.boc.nl2sql.authorization.application.DataScopePolicy;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import com.boc.nl2sql.nl2sql.domain.SemanticQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 根据受控槽位选择固定 SQL 结构。
 *
 * <p>用户原文永远不会拼入 SQL；所有值均通过命名参数绑定，表名和字段名只来自代码白名单。</p>
 */
@Component
public class SqlPlanner {
    private final DataScopePolicy dataScopePolicy;
    private final int maxRows;

    public SqlPlanner(DataScopePolicy dataScopePolicy,
                      @Value("${app.query.max-result-rows:100}") int maxRows) {
        this.dataScopePolicy = dataScopePolicy;
        this.maxRows = maxRows;
    }

    public PlannedQuery plan(SemanticQuery query, CurrentUser user) {
        return switch (query.intent()) {
            case CUSTOMER_FILTER -> customerQuery(query, user);
            case TRANSACTION_ANALYSIS -> transactionQuery(query, user);
            case PRODUCT_HOLDING -> holdingQuery(query, user);
            case MARKETING_ANALYSIS -> marketingQuery(query, user);
            case GENERIC_ANALYSIS, UNKNOWN -> throw new IllegalArgumentException("自由问题必须携带模型生成的SQL");
        };
    }

    private PlannedQuery customerQuery(SemanticQuery query, CurrentUser user) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        StringBuilder where = baseCustomerWhere(query, user, parameters);
        boolean detail = query.detailRequested();
        String sql = detail
                ? """
                  SELECT c.customer_id, c.customer_name_masked AS customer_name,
                         c.customer_level_code AS customer_level,
                         ROUND(c.total_asset_amount / 10000, 2) AS asset_wan,
                         ROUND(c.asset_change_3m_rate * 100, 2) AS asset_change_rate,
                         c.branch_id
                    FROM dim_customer c
                   WHERE %s
                   ORDER BY c.total_asset_amount DESC
                   LIMIT %d
                  """.formatted(where, maxRows)
                : """
                  SELECT c.branch_id,
                         COUNT(*) AS customer_count,
                         ROUND(SUM(c.total_asset_amount) / 100000000, 2) AS total_asset_yi,
                         ROUND(AVG(c.asset_change_3m_rate) * 100, 2) AS avg_asset_change_rate
                    FROM dim_customer c
                   WHERE %s
                   GROUP BY c.branch_id
                   ORDER BY customer_count DESC
                   LIMIT %d
                  """.formatted(where, maxRows);
        return new PlannedQuery(sql, parameters, detail ? "TABLE" : "SUMMARY",
                detail ? "符合条件的客户" : "客户筛选结果", query.broadRequested());
    }

    private PlannedQuery transactionQuery(SemanticQuery query, CurrentUser user) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        StringBuilder where = baseCustomerWhere(query, user, parameters);
        parameters.put("startDate", query.startDate());
        parameters.put("endDate", query.endDate());
        where.append(" AND t.transaction_date BETWEEN :startDate AND :endDate");
        String sql = """
                SELECT c.branch_id,
                       COUNT(*) AS transaction_count,
                       COUNT(DISTINCT c.customer_id) AS customer_count,
                       ROUND(SUM(t.amount_cny) / 10000, 2) AS transaction_amount_wan,
                       ROUND(AVG(t.amount_cny), 2) AS avg_transaction_amount
                  FROM fct_transaction t
                  JOIN dim_customer c ON c.customer_id = t.customer_id
                 WHERE %s AND t.status_code = 'SUCCESS'
                 GROUP BY c.branch_id
                 ORDER BY transaction_amount_wan DESC
                 LIMIT %d
                """.formatted(where, maxRows);
        return new PlannedQuery(sql, parameters, "SUMMARY", "交易分析结果", query.broadRequested());
    }

    private PlannedQuery holdingQuery(SemanticQuery query, CurrentUser user) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        StringBuilder where = baseCustomerWhere(query, user, parameters);
        if (query.productCategory() != null) {
            where.append(" AND h.product_category_code = :productCategory");
            parameters.put("productCategory", query.productCategory());
        }
        String sql = query.detailRequested()
                ? """
                  SELECT c.customer_id, c.customer_name_masked AS customer_name,
                         h.product_name, h.product_category_code,
                         ROUND(h.market_value_amount / 10000, 2) AS market_value_wan,
                         h.maturity_date, c.branch_id
                    FROM fct_product_holding h
                    JOIN dim_customer c ON c.customer_id = h.customer_id
                   WHERE %s
                   ORDER BY h.market_value_amount DESC
                   LIMIT %d
                  """.formatted(where, maxRows)
                : """
                  SELECT h.product_category_code,
                         COUNT(DISTINCT c.customer_id) AS customer_count,
                         COUNT(*) AS holding_count,
                         ROUND(SUM(h.market_value_amount) / 100000000, 2) AS market_value_yi,
                         ROUND(SUM(h.profit_amount) / 10000, 2) AS profit_wan
                    FROM fct_product_holding h
                    JOIN dim_customer c ON c.customer_id = h.customer_id
                   WHERE %s
                   GROUP BY h.product_category_code
                   ORDER BY market_value_yi DESC
                   LIMIT %d
                  """.formatted(where, maxRows);
        return new PlannedQuery(sql, parameters, query.detailRequested() ? "TABLE" : "SUMMARY",
                "产品持有分析结果", query.broadRequested());
    }

    private PlannedQuery marketingQuery(SemanticQuery query, CurrentUser user) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        StringBuilder where = baseCustomerWhere(query, user, parameters);
        parameters.put("startDate", query.startDate().atStartOfDay());
        parameters.put("endDate", query.endDate().plusDays(1).atStartOfDay());
        where.append(" AND m.contact_time >= :startDate AND m.contact_time < :endDate");
        if (query.campaignKeyword() != null) {
            where.append(" AND p.campaign_name LIKE :campaignKeyword");
            parameters.put("campaignKeyword", "%" + query.campaignKeyword() + "%");
        }
        String sql = """
                SELECT p.campaign_name,
                       COUNT(*) AS contact_count,
                       SUM(CASE WHEN m.response_flag THEN 1 ELSE 0 END) AS response_count,
                       SUM(CASE WHEN m.conversion_flag THEN 1 ELSE 0 END) AS conversion_count,
                       ROUND(100 * SUM(CASE WHEN m.conversion_flag THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS conversion_rate,
                       ROUND(SUM(m.conversion_amount) / 10000, 2) AS conversion_amount_wan
                  FROM fct_customer_marketing m
                  JOIN dim_marketing_campaign p ON p.campaign_id = m.campaign_id
                  JOIN dim_customer c ON c.customer_id = m.customer_id
                 WHERE %s
                 GROUP BY p.campaign_id, p.campaign_name
                 ORDER BY conversion_rate DESC
                 LIMIT %d
                """.formatted(where, maxRows);
        return new PlannedQuery(sql, parameters, "SUMMARY", "营销活动效果", query.broadRequested());
    }

    private StringBuilder baseCustomerWhere(SemanticQuery query, CurrentUser user,
                                            Map<String, Object> parameters) {
        StringBuilder where = new StringBuilder("c.status_code = 'ACTIVE' AND ")
                .append(dataScopePolicy.condition("c", user, parameters));
        if (query.customerLevel() != null) {
            // “高净值”同时覆盖资产达到100万元和白金等级客户，与business_term中的口径保持一致。
            where.append("PLATINUM".equals(query.customerLevel())
                    ? " AND (c.customer_level_code = :customerLevel OR c.total_asset_amount >= 1000000)"
                    : " AND c.customer_level_code = :customerLevel");
            parameters.put("customerLevel", query.customerLevel());
        }
        if (query.minAsset() != null) {
            where.append(" AND c.total_asset_amount >= :minAsset");
            parameters.put("minAsset", query.minAsset());
        }
        if (query.maxAsset() != null) {
            where.append(" AND c.total_asset_amount <= :maxAsset");
            parameters.put("maxAsset", query.maxAsset());
        }
        if (query.assetDropRate() != null) {
            where.append(" AND c.asset_change_3m_rate <= :assetDropRate");
            parameters.put("assetDropRate", query.assetDropRate().negate().movePointLeft(2));
        }
        return where;
    }
}
