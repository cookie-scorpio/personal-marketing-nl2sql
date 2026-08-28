package com.boc.nl2sql.conversation.domain;

/** 只保存已完成查询的条件，不把模型推测和结果明细作为后续指令。 */
public record ConversationContext(String query, String customerId, String sourceTaskId) {
    public static ConversationContext empty() { return new ConversationContext("", null, null); }
}
