package com.boc.nl2sql.nl2sql.application;

import com.boc.nl2sql.nl2sql.domain.ClarificationQuestion;
import com.boc.nl2sql.nl2sql.domain.SemanticQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** 客户快照上的时间条件不可擅自解释为开户日期。其余复杂口径仍由模型结合数据字典澄清。 */
public class TimeScopeClarifier {
    private static final Pattern TIME = Pattern.compile("近|过去|最近|本月|上月|今年|去年|本季度|上季度|\\d{4}[-年]");

    public Optional<ClarificationQuestion> clarify(String text, SemanticQuery semantic) {
        if (!text.matches("(?s).*(客户|客群|资产).*") || !TIME.matcher(text).find()
                || semantic.assetDropRate() != null) return Optional.empty();
        if (text.matches("(?s).*(开户|交易|触达|营销|到期|快照日期|不限定时间|忽略时间|不限制时间).*")
                || text.matches("(?s).*(同比|环比|资产变化|资产下降|历史资产|资产趋势).*") ) return Optional.empty();
        return Optional.of(new ClarificationQuestion(UUID.randomUUID().toString(), "TIME_BASIS",
                "这里的时间范围要按什么业务时间筛选客户？客户资产只有当前快照，不能计算期间资产的时间平均值。",
                List.of("按开户时间筛选，统计这些客户的当前资产", "按交易时间筛选有交易的客户，统计其当前资产",
                        "不限定时间，统计当前客户与当前资产"), semantic.recognizedSlots()));
    }
}
