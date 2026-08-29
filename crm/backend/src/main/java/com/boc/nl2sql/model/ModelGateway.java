package com.boc.nl2sql.model;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.nl2sql.application.RuleBasedSemanticParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ModelGateway {
    private final String configuredProvider;
    private final List<ModelAdapter> adapters;
    private final RuleBasedSemanticParser ruleParser;

    public ModelGateway(@Value("${app.model.provider:mock}") String configuredProvider,
                        List<ModelAdapter> adapters,
                        RuleBasedSemanticParser ruleParser) {
        this.configuredProvider = configuredProvider;
        this.adapters = adapters;
        this.ruleParser = ruleParser;
    }

    /**
     * 高频明确场景优先走规则，只有固定模板无法完整回答时才调用模型。
     * 这既降低模型调用量，也避免简单场景受模型输出波动影响。
     */
    public QueryInterpretation interpret(String queryText, CurrentUser user) {
        return interpret(queryText, user, null);
    }

    public QueryInterpretation interpret(String queryText, CurrentUser user, java.util.function.BooleanSupplier active) {
        return interpretInternal(queryText,user,active,null);
    }
    public QueryInterpretation interpret(String text,CurrentUser user,java.util.function.BooleanSupplier active,boolean thinking){return interpretInternal(text,user,active,thinking);}
    private QueryInterpretation interpretInternal(String queryText,CurrentUser user,java.util.function.BooleanSupplier active,Boolean thinking){
        if (active != null && !active.getAsBoolean()) throw new com.boc.nl2sql.execution.QueryTerminatedException(false);
        var ruleSemantic = ruleParser.parse(queryText);
        var timeQuestion = new com.boc.nl2sql.nl2sql.application.TimeScopeClarifier().clarify(queryText, ruleSemantic);
        if (timeQuestion.isPresent()) {
            return new QueryInterpretation(ruleSemantic, "RULE", 1.0, null, null, "AUTO", timeQuestion.get());
        }
        if (ruleParser.supportsDeterministicPlan(queryText, ruleSemantic)) {
            return QueryInterpretation.rule(ruleSemantic);
        }

        ModelAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.provider().equalsIgnoreCase(configuredProvider))
                .findFirst()
                .orElseThrow(() -> new BusinessException(503101, "未找到已配置的大模型适配器"));
        if (!adapter.available() || "mock".equalsIgnoreCase(adapter.provider())) {
            throw new BusinessException(503102,
                    "该问题超出规则快速查询范围，请配置 DeepSeek V4 Flash 后重试");
        }
        return thinking!=null?adapter.interpret(queryText,user,active==null?()->true:active,thinking)
                : active == null ? adapter.interpret(queryText, user) : adapter.interpret(queryText, user, active);
    }

    public String activeProvider() {
        return configuredProvider;
    }

    /** 修复始终直达已配置模型，不再次走规则识别或无限重试。 */
    public QueryInterpretation repair(String text, CurrentUser user, String failedSql, String reason) {
        return adapters.stream().filter(adapter -> adapter.provider().equalsIgnoreCase(configuredProvider))
                .findFirst().orElseThrow(() -> new BusinessException(503101, "模型适配器不可用"))
                .repair(text, user, failedSql, reason);
    }
    public QueryInterpretation repair(String text,CurrentUser user,String sql,String reason,boolean thinking){
        return adapters.stream().filter(adapter->adapter.provider().equalsIgnoreCase(configuredProvider)).findFirst()
                .orElseThrow(()->new BusinessException(503101,"模型适配器不可用")).repair(text,user,sql,reason,thinking);
    }

    public SqlResultReview reviewResult(String text, CurrentUser user, String sql,
                                        java.util.Map<String,Object> resultSummary, boolean thinking) {
        return adapters.stream().filter(adapter->adapter.provider().equalsIgnoreCase(configuredProvider)).findFirst()
                .orElseThrow(()->new BusinessException(503101,"模型适配器不可用"))
                .reviewResult(text,user,sql,resultSummary,thinking);
    }
}
