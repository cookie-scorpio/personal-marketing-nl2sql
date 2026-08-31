package com.boc.nl2sql.conversation;

import com.boc.nl2sql.authorization.application.DataScopePolicy;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.authorization.domain.RoleCode;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.conversation.application.CustomerResolver;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerSearchBehaviorTest {
    private final CurrentUser user=new CurrentUser(1L,"manager01","经理", RoleCode.CUSTOMER_MANAGER,"EAST","B001","M0001");
    private final JsonMapper json=new JsonMapper();

    @Test
    void mobileSuffixStaysFixedWhileNameFragmentOnlyNarrowsItsResultSet(){
        NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Integer.class))).thenReturn(2);
        when(jdbc.query(anyString(),anyMap(),any(RowMapper.class))).thenReturn(List.of());
        var resolver=new CustomerResolver(jdbc,new DataScopePolicy(),json);
        var scope=new CustomerResolver.SearchScope("",false,"","0697",Set.of(CustomerResolver.FILTER_NAME,CustomerResolver.FILTER_ID));

        resolver.search(user,scope,"小明",1,20);

        @SuppressWarnings("unchecked") var params=org.mockito.ArgumentCaptor.forClass(Map.class);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(),params.capture(),eq(Integer.class));
        assertThat(sql.getValue()).contains("RIGHT(c.mobile_masked,4)=:baseMobileSuffix","c.customer_name LIKE :extra");
        assertThat(params.getValue()).containsEntry("baseMobileSuffix","0697").containsEntry("extra","%小明%");
    }

    @Test
    void exactNameScopeRejectsAnotherNameButAllowsMobileSuffix(){
        var resolver=new CustomerResolver(mock(NamedParameterJdbcTemplate.class),new DataScopePolicy(),json);
        var scope=new CustomerResolver.SearchScope("王小明",false,"","",Set.of(CustomerResolver.FILTER_ID,CustomerResolver.FILTER_MOBILE_SUFFIX));

        assertThatThrownBy(()->resolver.search(user,scope,"小明",1,20))
                .isInstanceOf(BusinessException.class).hasMessageContaining("原查询条件重复");
    }

    @Test
    void surnameScopeKeepsNameFragmentAsAnAllowedFilter(){
        var resolver=spy(new CustomerResolver(null,null,json));
        doAnswer(invocation->{
            CustomerResolver.Mention mention=invocation.getArgument(2);
            if("王".equals(mention.name()))return List.of(candidate("C00000697","王*明"),candidate("C00000721","王*明"));
            return List.of(candidate("C00000241","李*红"));
        }).when(resolver).find(eq(user),isNull(),any(CustomerResolver.Mention.class),isNull());
        QueryTaskEntity task=task("对比王先生和李先生的资产谁更多");

        var question=resolver.inspect(task,user).orElseThrow();

        assertThat(question.recognizedSlots())
                .containsEntry("固定姓名","王")
                .containsEntry("姓名匹配方式","SURNAME")
                .containsEntry("当前确认对象","王先生");
        assertThat(question.prompt()).contains("正在确认王先生","第1位","共2位");
        assertThat(question.recognizedSlots().get("筛选类型"))
                .contains(CustomerResolver.FILTER_NAME,CustomerResolver.FILTER_ID,CustomerResolver.FILTER_MOBILE_SUFFIX);
        assertThat(question.recognizedSlots()).doesNotContainKey("检索词");
    }

    @Test
    void uniqueOriginalMatchContinuesWithoutClarification(){
        var resolver=spy(new CustomerResolver(null,null,json));
        doReturn(List.of(candidate("C00000697","王*明"))).when(resolver).find(eq(user),isNull(),any(CustomerResolver.Mention.class),isNull());
        QueryTaskEntity task=task("手机号后四位为0697的客户资产是多少");
        doReturn(List.of(candidate("C00000697","王*明"))).when(resolver).find(eq(user),isNull(),isNull(),eq("0697"));

        assertThat(resolver.inspect(task,user)).isEmpty();
        assertThat(task.getResolvedCustomerId()).isEqualTo("C00000697");
    }

    @Test
    void zeroOriginalMatchesFailsInsteadOfOpeningAnEditableSearch(){
        var resolver=spy(new CustomerResolver(null,null,json));
        doReturn(List.of()).when(resolver).find(eq(user),isNull(),isNull(),eq("0697"));
        QueryTaskEntity task=task("手机号后四位为0697的客户资产是多少");

        assertThatThrownBy(()->resolver.inspect(task,user)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("未找到符合条件的客户");
    }

    @Test
    void multipleMobileMatchesExplainTheFixedSuffixAndOpenTheList(){
        var resolver=spy(new CustomerResolver(null,null,json));
        doReturn(List.of(candidate("C00000697","王*明"),candidate("C00009361","陈*满")))
                .when(resolver).find(eq(user),isNull(),isNull(),eq("0697"));
        QueryTaskEntity task=task("手机号后四位为0697的客户资产是多少");

        var question=resolver.inspect(task,user).orElseThrow();

        assertThat(question.prompt()).contains("手机号后四位 0697","对应多位客户","请选择具体客户");
        assertThat(question.recognizedSlots()).containsEntry("固定手机号后四位","0697");
    }

    @Test
    void twoUniqueCourtesyNamesAreBothResolvedWithoutASelectionPanel(){
        var resolver=spy(new CustomerResolver(null,null,json));
        doAnswer(invocation->{
            CustomerResolver.Mention mention=invocation.getArgument(2);
            return List.of(candidate("王".equals(mention.name())?"C00000697":"C00000241",mention.name()+"*"));
        }).when(resolver).find(eq(user),isNull(),any(CustomerResolver.Mention.class),isNull());
        QueryTaskEntity task=task("对比王先生和李先生的资产谁更多");

        assertThat(resolver.inspect(task,user)).isEmpty();
        assertThat(json.readValue(task.getCustomerIdsJson(),String[].class)).containsExactly("C00000697","C00000241");
    }

    private QueryTaskEntity task(String query){
        var task=new QueryTaskEntity();task.setMergedQueryText(query);task.setQueryText(query);return task;
    }
    private CustomerResolver.Candidate candidate(String id,String name){return new CustomerResolver.Candidate(id,name,"B001","138****0697");}
}
