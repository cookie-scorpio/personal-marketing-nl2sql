package com.boc.nl2sql.execution;

import com.boc.nl2sql.execution.application.ResultAssembler;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class V13DisplayTest {
    @Test void explicitPieUsesLabelBesideUniqueNumericSortColumn(){
        var hints=List.of(new com.boc.nl2sql.execution.domain.ResultColumnHint("bucket_idx","序号","DIMENSION","","NONE",null),
                new com.boc.nl2sql.execution.domain.ResultColumnHint("ratio_pct","比例","MEASURE","%","SUM",null));
        List<Map<String,Object>> rows=List.of(Map.of("bucket_idx",0,"band","零到一百万","customer_count",2,"ratio_pct",40),
                Map.of("bucket_idx",1,"band","一百万到两百万","customer_count",3,"ratio_pct",60));
        var result=new ResultAssembler().assemble(new PlannedQuery("",Map.of(),"PIE","构成",com.boc.nl2sql.execution.domain.QueryRisk.low(),hints),rows,"DEEPSEEK",.95);
        assertThat(result.charts()).anyMatch(c->c.type().equals("PIE") && c.dimensionKey().equals("band"));
        assertThat(result.charts()).filteredOn(c->c.type().equals("PIE")).allMatch(c->c.series().stream().allMatch(s->s.key().equals("customer_count")));
        assertThat(result.columns()).hasSize(4);
    }
    @Test void pieDoesNotDiscardAnIndependentCategory(){
        List<Map<String,Object>> rows=List.of(Map.of("branch_id","B1","category","存款","customer_count",2),
                Map.of("branch_id","B2","category","存款","customer_count",3));
        var result=new ResultAssembler().assemble(new PlannedQuery("",Map.of(),"PIE","两维",false),rows,"DEEPSEEK",.95);
        assertThat(result.charts()).noneMatch(c->c.type().equals("PIE"));
    }
    @Test void explicitPieSupportsArbitraryGroupingBeyondEight(){
        for(int n:List.of(10,12,20)){
            List<Map<String,Object>> rows=new ArrayList<>();for(int i=0;i<n;i++)rows.add(Map.of("bucket","组"+i,"customer_count",i));
            var result=new ResultAssembler().assemble(new PlannedQuery("",Map.of(),"PIE","动态区间",false),rows,"DEEPSEEK",.95);
            assertThat(result.charts()).hasSize(1);assertThat(result.charts().get(0).type()).isEqualTo("PIE");
        }
    }
    @Test void invalidPieExplainsReasonAndRetainsActualData(){
        List<Map<String,Object>> rows=List.of(Map.of("bucket","a","customer_count",-1),Map.of("bucket","b","customer_count",2));
        var result=new ResultAssembler().assemble(new PlannedQuery("",Map.of(),"PIE","不适合构成图",false),rows,"DEEPSEEK",.95);
        assertThat(result.charts()).noneMatch(c->c.type().equals("PIE"));assertThat(result.rows()).hasSize(2);
        assertThat(result.analysis().insights()).anyMatch(s->s.contains("未绘制"));
    }
}
