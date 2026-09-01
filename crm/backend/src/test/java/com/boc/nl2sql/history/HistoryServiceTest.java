package com.boc.nl2sql.history;

import com.boc.nl2sql.history.application.HistoryService;
import com.boc.nl2sql.history.infrastructure.QueryHistoryEntity;
import com.boc.nl2sql.history.infrastructure.QueryHistoryMapper;
import com.boc.nl2sql.authorization.application.AuthorizationCenter;
import com.boc.nl2sql.authorization.application.DataScopePolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HistoryServiceTest {

    @Test
    void truncatesDatabaseBoundSummaries() {
        QueryHistoryMapper mapper = mock(QueryHistoryMapper.class);
        HistoryService service = new HistoryService(mapper, new AuthorizationCenter(new DataScopePolicy()));

        service.save("task-1", 1L, "测试查询", "TRANSACTION_ANALYSIS", "SUCCESS",
                "S".repeat(591), "R".repeat(620));

        ArgumentCaptor<QueryHistoryEntity> captor = ArgumentCaptor.forClass(QueryHistoryEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals(500, captor.getValue().getSqlSummary().length());
        assertEquals(500, captor.getValue().getResultSummary().length());
    }
}
