package com.boc.nl2sql.conversation.application;

import com.boc.nl2sql.conversation.domain.ConversationContext;
import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

/** 追问识别、上下文合并与面向用户的展示摘要。合并文本只交给模型，展示摘要面向业务人员。 */
@Component
public class FollowupResolver {
    // 指代词后接“的/呢/标点/时间词/方位词”都视为追问，覆盖“他近30天”“她今年”等高频省略表达。
    private static final Pattern FOLLOWUP = Pattern.compile("^(再|改成|改为|换成|换为|继续|接着|那么|那就)|(?<!其)[他她它](?:的|呢|[，。？?]|$|(?=[近这上去今昨本之]))|这些客户|这批客户|上述客户|上面|刚才");
    private static final Pattern TIME = Pattern.compile("近[0-9一二三四五六七八九十两]+(?:天|个月|月|年)|过去[0-9一二三四五六七八九十两]+(?:天|个月|月|年)|本季度|上季度|本月|上月|今年以来|今年|去年");
    // 用户显式把统计对象扩大到“全量”时，上一条的单客约束必须让位，否则形成无法解除的死循环。
    private static final Pattern EXPANDS_SCOPE = Pattern.compile("全行|全行客户|全部客户|所有客户|全体客户|全公司|全部(机构|区域|网点|客户)|所有(机构|区域|网点)|各(?:机构|区域|网点)(?:的)?(?:客户)?(交易|资产|人数|统计)?");
    public boolean followup(String text) { return FOLLOWUP.matcher(text).find(); }

    /** 命中表示用户要的是全量统计，不应继承上一条已确认客户的限制。 */
    public boolean expandsScope(String text) {
        return text != null && EXPANDS_SCOPE.matcher(text).find();
    }

    /** 追问本身已含业务对象和指标时无需追问上下文；“改成近7天”这类残缺表达才需要提示补全。 */
    public boolean selfContained(String text) {
        return text != null && text.matches("(?s).*(?:资产|交易|人数|金额|客户|持有|持仓|产品|活动|转化|余额|排名|名单|统计|分布|趋势).*");
    }

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

    /** 面向消息列表的当前有效问题：永远是用户自己的原话，不暴露内部合并模板。 */
    public String displayText(String currentUserText, ConversationContext previous, boolean inherited) {
        String text = currentUserText == null ? "" : currentUserText.trim();
        if (!inherited || previous == null || previous.query() == null || previous.query().isBlank()) return text;
        String prior = previous.query();
        String brief = prior.length() > 24 ? prior.substring(0, 24) + "…" : prior;
        return "（承接上文“" + brief + "”）" + text;
    }
}
