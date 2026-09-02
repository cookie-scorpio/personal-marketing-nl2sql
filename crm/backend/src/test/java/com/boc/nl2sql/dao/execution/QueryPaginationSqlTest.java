package com.boc.nl2sql.dao.execution;

import com.boc.nl2sql.domain.execution.QueryPage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPaginationSqlTest {
    private static final String LEGACY_SQL = "SELECT c.customer_id,c.total_asset_amount FROM dim_customer c "
            + "WHERE c.region_code='EAST' ORDER BY c.total_asset_amount DESC LIMIT 100";
    private static final Pattern LIMIT = Pattern.compile("(?i)\\bLIMIT\\s+(\\d+)\\s*,\\s*(\\d+)\\s*$");

    @Test
    void removesLegacyCutoffCountsAllRowsAndAddsStableTieBreaker() {
        var sql=QueryPaginationSql.build(LEGACY_SQL,new QueryPage(2,100,100));

        assertThat(sql.countSql()).contains("COUNT(*) AS total").doesNotContain("LIMIT 100","ORDER BY");
        assertThat(sql.pageSql()).contains("ORDER BY c.total_asset_amount DESC, c.customer_id ASC")
                .endsWith("LIMIT 100, 100");
    }

    @Test
    void twoHundredFiveRowsAreCompleteWithoutOverlapAcrossThreePages() {
        List<Integer> source=java.util.stream.IntStream.rangeClosed(1,205).boxed().toList();
        List<Integer> combined=new ArrayList<>();
        for(int pageNo=1;pageNo<=3;pageNo++){
            long offset=(long)(pageNo-1)*100;
            String pageSql=QueryPaginationSql.build(LEGACY_SQL,new QueryPage(pageNo,100,offset)).pageSql();
            var match=LIMIT.matcher(pageSql);
            assertThat(match.find()).isTrue();
            int sqlOffset=Integer.parseInt(match.group(1)),size=Integer.parseInt(match.group(2));
            combined.addAll(source.subList(Math.min(sqlOffset,source.size()),Math.min(sqlOffset+size,source.size())));
        }

        assertThat(combined).hasSize(205).containsExactlyElementsOf(source);
        assertThat(new HashSet<>(combined)).hasSize(205);
    }

    @Test
    void appliesOneGlobalPageToUnionResults() {
        String union="SELECT c.customer_id FROM dim_customer c UNION ALL "
                +"SELECT d.customer_id FROM dim_customer d ORDER BY customer_id LIMIT 100";
        var sql=QueryPaginationSql.build(union,new QueryPage(2,50,50));
        assertThat(sql.countSql()).doesNotContain("LIMIT 100","ORDER BY");
        assertThat(sql.pageSql()).contains("UNION ALL","ORDER BY customer_id, 1 ASC").endsWith("LIMIT 50, 50");
    }
}
