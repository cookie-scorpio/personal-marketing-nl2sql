package com.boc.nl2sql.service.conversation;

import com.boc.nl2sql.service.authorization.DataScopePolicy;
import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.authorization.RoleCode;
import com.boc.nl2sql.service.conversation.CustomerResolver;
import com.boc.nl2sql.domain.conversation.QueryTaskEntity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** v1.5 回归：提及（mention）提取不应被“姓名和电话/姓名和手机号”等业务词组合劫持。 */
class MentionHijackTest {
    private final CustomerResolver resolver = new CustomerResolver(mock(NamedParameterJdbcTemplate.class), new DataScopePolicy(), new tools.jackson.databind.json.JsonMapper());
    private final CurrentUser user = new CurrentUser(1L, "manager01", "演示经理", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");

    private QueryTaskEntity task(String text) {
        QueryTaskEntity task = new QueryTaskEntity();
        task.setTaskId("t"); task.setMergedQueryText(text);
        return task;
    }

    @Test
    void nameAndAssetIsNotHijacked() {
        var task = task("按总资产从高到低列出前10名客户，用表格展示他们的姓名和资产");
        assertTrue(resolver.inspect(task, user).isEmpty(), "姓名和资产不应触发客户定位");
    }

    @Test
    void nameAndPhoneIsNotHijacked() {
        var task = task("按总资产从高到低列出前10名客户，用表格展示他们的姓名和电话");
        var question = resolver.inspect(task, user);
        assertTrue(question.isEmpty() || !"CUSTOMER_NOT_FOUND".equals(question.get().type()), "姓名和电话不应被劫持为未找到");
    }

    @Test
    void nameAndMobileIsNotHijacked() {
        var task = task("按总资产从高到低列出前10名客户，用表格展示他们的姓名和手机号");
        assertTrue(resolver.inspect(task, user).isEmpty());
    }

    @Test
    void displayRedactDoesNotProduceConjunctionMask() {
        String redacted = resolver.redact("按总资产从高到低列出前10名客户，用表格展示他们的姓名和电话");
        assertFalse(redacted.contains("和**"), "不应出现连词开头的错乱掩码");
    }

    @Test
    void realFullNameStillResolvable() {
        assertEquals("李", resolver.mention("帮我查找一下李先生的资产信息").name());
    }
    @Test
    void maskingKeepsFirstAndLastChar() {
        assertEquals("王*明", com.boc.nl2sql.common.privacy.CustomerMasking.name("王小明"));
        assertEquals("欧**泽", com.boc.nl2sql.common.privacy.CustomerMasking.name("欧阳佳泽"));
        assertEquals("李*", com.boc.nl2sql.common.privacy.CustomerMasking.name("李明"));
        assertEquals("王**", com.boc.nl2sql.common.privacy.CustomerMasking.name("王**"), "已脱敏文本应保持稳定");
    }
}
