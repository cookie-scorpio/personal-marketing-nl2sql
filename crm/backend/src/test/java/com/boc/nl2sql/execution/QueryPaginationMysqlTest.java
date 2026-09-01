package com.boc.nl2sql.execution;

import com.boc.nl2sql.execution.domain.PlannedQuery;
import com.boc.nl2sql.execution.domain.QueryPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式开启后在本地MySQL构造205行，验证数据库实际分页SQL不重不漏。 */
@SpringBootTest(properties = "app.model.provider=mock")
@ActiveProfiles("local")
@EnabledIfSystemProperty(named="v11.mysql",matches="true")
class QueryPaginationMysqlTest {
    private static final String MANAGER="MPAGE205";
    @Autowired QueryExecutionGateway execution;
    @Autowired JdbcTemplate jdbc;

    @Test
    void returnsAllTwoHundredFiveRowsAcrossThreeStablePages(){
        jdbc.update("DELETE FROM dim_customer WHERE manager_id=?",MANAGER);
        try{
            List<Object[]> batch=new ArrayList<>();
            for(int i=1;i<=205;i++)batch.add(new Object[]{String.format("P%08d",i),"分页客户*","U",30,"A26_35",
                    "900****0000","NORMAL",false,"R2","OTHER","EAST","BPAGE",MANAGER,
                    new BigDecimal(i),BigDecimal.ZERO,Date.valueOf(LocalDate.of(2026,1,1)),"ACTIVE",Date.valueOf(LocalDate.of(2026,8,31))});
            jdbc.batchUpdate("INSERT INTO dim_customer(customer_id,customer_name_masked,gender_code,age,age_band_code,mobile_masked,customer_level_code,vip_flag,risk_level_code,occupation_code,region_code,branch_id,manager_id,total_asset_amount,asset_change_3m_rate,open_date,status_code,snapshot_date) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",batch);
            var plan=new PlannedQuery("SELECT c.customer_id FROM dim_customer c WHERE c.manager_id=:manager ORDER BY c.customer_id LIMIT 100",
                    Map.of("manager",MANAGER),"TABLE","分页验收",false);
            var first=execution.execute("page-1",plan,new QueryPage(1,100,0),()->true);
            var second=execution.execute("page-2",plan,new QueryPage(2,100,100),()->true);
            var third=execution.execute("page-3",plan,new QueryPage(3,100,200),()->true);

            assertThat(List.of(first.total(),second.total(),third.total())).containsOnly(205L);
            assertThat(List.of(first.rows().size(),second.rows().size(),third.rows().size())).containsExactly(100,100,5);
            var ids=new ArrayList<String>();
            for(var page:List.of(first,second,third))for(var row:page.rows())ids.add(String.valueOf(row.get("customer_id")));
            assertThat(ids).hasSize(205).doesNotHaveDuplicates();
            assertThat(ids).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,205)
                    .mapToObj(i->String.format("P%08d",i)).toList());
            assertThat(first.hasMore()).isTrue();assertThat(second.hasMore()).isTrue();assertThat(third.hasMore()).isFalse();
        }finally{jdbc.update("DELETE FROM dim_customer WHERE manager_id=?",MANAGER);}
    }
}
