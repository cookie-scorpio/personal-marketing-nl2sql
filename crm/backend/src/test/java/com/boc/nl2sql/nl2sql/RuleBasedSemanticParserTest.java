package com.boc.nl2sql.nl2sql;

import com.boc.nl2sql.nl2sql.application.CompletenessValidator;
import com.boc.nl2sql.nl2sql.application.RuleBasedSemanticParser;
import com.boc.nl2sql.nl2sql.domain.IntentType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedSemanticParserTest {
    private final RuleBasedSemanticParser parser = new RuleBasedSemanticParser();
    private final CompletenessValidator validator = new CompletenessValidator();

    @Test
    void parsesCustomerFilterWithAssetAndLevel() {
        var query = parser.parse("找出资产超过50万元的高净值客户名单");

        assertThat(query.intent()).isEqualTo(IntentType.CUSTOMER_FILTER);
        assertThat(query.customerLevel()).isEqualTo("PLATINUM");
        assertThat(query.minAsset()).isEqualByComparingTo(new BigDecimal("500000"));
        assertThat(query.detailRequested()).isTrue();
        assertThat(validator.validate(query)).isEmpty();
    }

    @Test
    void asksForMissingTransactionTimeRange() {
        var query = parser.parse("统计各机构客户交易金额");

        assertThat(query.intent()).isEqualTo(IntentType.TRANSACTION_ANALYSIS);
        assertThat(validator.validate(query)).get().extracting("type").isEqualTo("MISSING_TIME_RANGE");
    }

    @Test
    void detectsContradictoryTimeRangesAndAllowsFinalOverride() {
        var conflicted = parser.parse("统计近30天和近半年客户交易金额");
        assertThat(conflicted.conflicts()).isNotEmpty();

        var resolved = parser.parse("统计近30天和近半年客户交易金额，最终条件为：近30天");
        assertThat(resolved.conflicts()).isEmpty();
        assertThat(resolved.startDate()).isNotNull();
    }

    @Test
    void routesUnsupportedCustomerDimensionToModel() {
        var query = parser.parse("分析各年龄段客户数量分布");

        assertThat(query.intent()).isEqualTo(IntentType.CUSTOMER_FILTER);
        assertThat(parser.supportsDeterministicPlan("分析各年龄段客户数量分布", query)).isFalse();
    }

    @Test
    void routesUnconsumedFiltersAndUnsupportedTemplateShapesToModel() {
        for (String text : java.util.List.of("找出南京客户名单", "统计近30天交易金额超过5万元的客户",
                "统计近30天各机构理财交易金额", "列出近30天客户交易明细", "统计上个月各机构客户交易金额",
                "分析各机构持有理财产品的客户和持有规模", "找出近半年开户的高净值客户名单")) {
            assertThat(parser.supportsDeterministicPlan(text, parser.parse(text))).as(text).isFalse();
        }
    }

    @Test
    void keepsVerifiedDemoQuestionsOnRulePath() {
        for (String text : java.util.List.of("找出资产超过50万元的高净值客户名单", "统计近30天各机构客户交易金额",
                "分析持有理财产品的客户和持有规模", "分析本季度营销活动的触达和转化效果",
                "找出所有客户名单", "统计各机构客户交易金额，补充条件：近30天",
                "统计近30天和近半年客户交易金额，最终条件为：近30天")) {
            assertThat(parser.supportsDeterministicPlan(text, parser.parse(text))).as(text).isTrue();
        }
    }
}
