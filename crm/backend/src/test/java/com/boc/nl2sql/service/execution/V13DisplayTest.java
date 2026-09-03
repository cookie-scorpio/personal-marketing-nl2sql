package com.boc.nl2sql.service.execution;

import com.boc.nl2sql.service.execution.ResultAssembler;
import com.boc.nl2sql.domain.execution.PlannedQuery;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class V13DisplayTest {
    @Test void explicitPieUsesLabelBesideUniqueNumericSortColumn(){
        var hints=List.of(new com.boc.nl2sql.domain.execution.ResultColumnHint("bucket_idx","序号","DIMENSION","","NONE",null),
                new com.boc.nl2sql.domain.execution.ResultColumnHint("ratio_pct","比例","MEASURE","%","SUM",null));
        List<Map<String,Object>> rows=List.of(Map.of("bucket_idx",0,"band","零到一百万","customer_count",2,"ratio_pct",40),
                Map.of("bucket_idx",1,"band","一百万到两百万","customer_count",3,"ratio_pct",60));
        var result=new ResultAssembler(null).assemble(new PlannedQuery("",Map.of(),"PIE","构成",com.boc.nl2sql.domain.execution.QueryRisk.low(),hints),rows,"DEEPSEEK",.95);
        assertThat(result.charts()).anyMatch(c->c.type().equals("PIE") && c.dimensionKey().equals("band"));
        assertThat(result.charts()).filteredOn(c->c.type().equals("PIE")).allMatch(c->c.series().stream().allMatch(s->s.key().equals("customer_count")));
        assertThat(result.columns()).hasSize(4);
    }
    @Test void pieDoesNotDiscardAnIndependentCategory(){
        List<Map<String,Object>> rows=List.of(Map.of("branch_id","B1","category","存款","customer_count",2),
                Map.of("branch_id","B2","category","存款","customer_count",3));
        var result=new ResultAssembler(null).assemble(new PlannedQuery("",Map.of(),"PIE","两维",false),rows,"DEEPSEEK",.95);
        assertThat(result.charts()).noneMatch(c->c.type().equals("PIE"));
    }
    @Test void explicitPieSupportsArbitraryGroupingBeyondEight(){
        for(int n:List.of(10,12,20)){
            List<Map<String,Object>> rows=new ArrayList<>();for(int i=0;i<n;i++)rows.add(Map.of("bucket","组"+i,"customer_count",i));
            var result=new ResultAssembler(null).assemble(new PlannedQuery("",Map.of(),"PIE","动态区间",false),rows,"DEEPSEEK",.95);
            assertThat(result.charts()).hasSize(1);assertThat(result.charts().get(0).type()).isEqualTo("PIE");
        }
    }
    @Test void invalidPieExplainsReasonAndRetainsActualData(){
        List<Map<String,Object>> rows=List.of(Map.of("bucket","a","customer_count",-1),Map.of("bucket","b","customer_count",2));
        var result=new ResultAssembler(null).assemble(new PlannedQuery("",Map.of(),"PIE","不适合构成图",false),rows,"DEEPSEEK",.95);
        assertThat(result.charts()).noneMatch(c->c.type().equals("PIE"));assertThat(result.rows()).hasSize(2);
        assertThat(result.analysis().insights()).anyMatch(s->s.contains("未绘制"));
    }
    @Test void numericColumnWithTimeWordIsStillAMeasure(){
        // 列名包含 month/year/daily 等时间词但值是数字的列，不应被识别为 TIME 维度。
        // 否则月度趋势这类查询两个列都被打成 TIME，无 MEASURE，最终不出图。
        var hints=List.of(new com.boc.nl2sql.domain.execution.ResultColumnHint("transaction_month","月份","TIME","","NONE",null),
                new com.boc.nl2sql.domain.execution.ResultColumnHint("monthly_amount_wan","交易金额","MEASURE","万元","SUM",null));
        List<Map<String,Object>> rows=List.of(
                Map.of("transaction_month","2026-01","monthly_amount_wan",2727.74),
                Map.of("transaction_month","2026-02","monthly_amount_wan",2323.29),
                Map.of("transaction_month","2026-03","monthly_amount_wan",2813.03));
        var result=new ResultAssembler(null).assemble(new PlannedQuery("",Map.of(),"LINE","月度趋势",false),rows,"DEEPSEEK",.95);
        assertThat(result.columns().stream().filter(c->c.key().equals("monthly_amount_wan")).findFirst().orElseThrow().role())
                .isEqualTo("MEASURE");
        assertThat(result.charts()).anyMatch(c->c.type().equals("LINE"));
        assertThat(result.analysis().insights()).noneMatch(s->s.contains("明细为主"));
    }
}
