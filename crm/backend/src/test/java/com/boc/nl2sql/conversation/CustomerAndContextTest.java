package com.boc.nl2sql.conversation;

import com.boc.nl2sql.conversation.application.*;
import com.boc.nl2sql.conversation.domain.ConversationContext;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CustomerAndContextTest {
    private final CustomerResolver customers=new CustomerResolver(null,null,new tools.jackson.databind.json.JsonMapper());
    private final FollowupResolver followups=new FollowupResolver();
    @Test void recognizesCourtesyNamesAndFullNamesWithoutConfusingGroups(){
        var mention=customers.mention("帮我查找一下李先生的资产信息");assertThat(mention).isNotNull();assertThat(mention.name()).isEqualTo("李");assertThat(mention.surname()).isTrue();
        assertThat(customers.mention("查询李明的资产").name()).isEqualTo("李明");
        assertThat(customers.mention("查李姓客户的资产分布")).isNull();
        assertThat(customers.mention("统计近30天各机构客户交易金额")).isNull();
        assertThat(customers.mention("帮我查找一下李先生的资产信息，客户定位信息：李明").name()).isEqualTo("李明");
        assertThat(customers.redact("查询李明的资产")).isEqualTo("查询李*的资产");
        assertThat(customers.explicitIdentity("再查C00000002的资产")).isTrue();
    }
    @Test void followupsReplaceTimeAndGroupingButKeepOtherConditions(){
        var context=new ConversationContext("统计近30天各机构高净值客户交易金额","C00000001","previous");
        assertThat(followups.merge("改成近三个月",context)).contains("高净值","近三个月").doesNotContain("近30天");
        assertThat(followups.merge("再按年龄分组",context)).contains("近30天","高净值","按年龄分组").doesNotContain("各机构");
        assertThat(followups.merge("看看他的产品持有",context)).isEqualTo("看看已确认客户的产品持有");
        assertThat(followups.followup("查询本季度各渠道营销效果")).isFalse();
        assertThat(followups.followup("查询其他客户的资产")).isFalse();
    }
}
