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
}
