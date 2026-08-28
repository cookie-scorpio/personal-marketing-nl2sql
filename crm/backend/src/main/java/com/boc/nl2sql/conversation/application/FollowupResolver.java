package com.boc.nl2sql.conversation.application;

import com.boc.nl2sql.conversation.domain.ConversationContext;
import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

@Component
public class FollowupResolver {
    private static final Pattern FOLLOWUP = Pattern.compile("^(再|改成|改为|换成|换为|继续|那么|那就)|(?<!其)[他她](?:的|呢|[，。？?]|$)|这些客户|这批客户|上述客户|上面|刚才");
    private static final Pattern TIME = Pattern.compile("近[0-9一二三四五六七八九十两]+(?:天|个月|月|年)|过去[0-9一二三四五六七八九十两]+(?:天|个月|月|年)|本季度|上季度|本月|上月|今年以来|今年|去年");
    public boolean followup(String text) { return FOLLOWUP.matcher(text).find(); }
    public String merge(String text, ConversationContext previous) {
        if (!followup(text) || previous == null || previous.query() == null || previous.query().isBlank()) return text;
        String prior = previous.query();
        var time = TIME.matcher(text);
        if (time.find()) {
            String replacement = time.group();
            prior = TIME.matcher(prior).replaceAll("");
            if (text.matches("^(改成|改为|换成|换为).*(天|月|年|季度)[。？?！!]*$")) return prior + "，时间范围：" + replacement;
        }
        if (text.matches(".*[他她]的.*(持有|持仓|产品|交易|资产).*")) {
            // 单个客户切换业务主题，不把上一条的资产聚合方式带入产品查询。
            return text.replaceAll("[他她]", "已确认客户");
        }
        if (text.matches(".*(?:再按|改按|改为按|换成按).*")) {
            String dimension = text.replaceFirst("^.*?(?:再按|改按|改为按|换成按)", "")
                    .replaceAll("分组|统计|展示|看看|看|[。？?]", "").trim();
            if (!dimension.isBlank() && dimension.length() <= 20) {
                prior = prior.replaceAll("按[^，。；]{1,20}?(?:分组|统计|展示)", "")
                        .replaceAll("各年龄段|各机构|各网点|各性别|各渠道", "");
                return prior + "，按" + dimension + "分组";
            }
        }
        // 复杂追问交给模型解释，明确标记前后关系，不假装确定性重写已覆盖所有表达。
        return "上一条已完成查询条件：" + prior + "\n本次追问（明确更正优先，其他适用条件保留）：" + text;
    }
}
