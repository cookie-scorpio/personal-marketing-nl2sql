package com.boc.nl2sql.service.nl2sql;

import com.boc.nl2sql.domain.nl2sql.ClarificationQuestion;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 确定性口径冲突守卫：用户显式要求饼图，但问题语义是时间趋势或变化时，
 * 不依赖模型裁量，固定发起双口径澄清（与用户显式要求一致的“保持饼图”口径为推荐项）。
 * 此前该场景完全由模型判断，同一问题时而澄清时而静默猜测口径，行为不可复现。
 */
@Component
public class DisplayConflictGuard {
    private static final Pattern TREND = Pattern.compile("近[0-9一二三四五六七八九十两百]+\\s*(天|日)|按日|按天|按月|按周|每日|逐月|趋势|变化|走势");
    private static final String RECOMMENDED = "改为按分类（如交易类型/产品类别）统计区间内金额构成，保持饼图展示";

    public Optional<ClarificationQuestion> check(String queryText, String preferredDisplay) {
        if (!"PIE".equalsIgnoreCase(preferredDisplay)) return Optional.empty();
        if (queryText == null || !TREND.matcher(queryText).find()) return Optional.empty();
        var options = List.of(
                RECOMMENDED,
                "改为按时间（按日/按月）展示金额变化趋势，使用折线或面积图");
        return Optional.of(new ClarificationQuestion(UUID.randomUUID().toString(), "DISPLAY_CONFLICT",
                "您要求用饼图展示时间趋势数据，两者口径不同。请选择展示口径：", options,
                Map.of("展示要求", "饼图", "数据形态", "时间趋势"), RECOMMENDED));
    }
}
