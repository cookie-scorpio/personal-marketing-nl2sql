package com.boc.nl2sql.execution.infrastructure;

import com.boc.nl2sql.execution.QueryExecutionGateway;
import com.boc.nl2sql.execution.application.SqlSafetyValidator;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class MySqlQueryExecutionGateway implements QueryExecutionGateway {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SqlSafetyValidator safetyValidator;

    public MySqlQueryExecutionGateway(NamedParameterJdbcTemplate jdbcTemplate,
                                      SqlSafetyValidator safetyValidator) {
        this.jdbcTemplate = jdbcTemplate;
        this.safetyValidator = safetyValidator;
    }

    @Override
    public List<Map<String, Object>> execute(PlannedQuery query) {
        safetyValidator.validate(query.sql());
        return jdbcTemplate.queryForList(query.sql(), query.parameters());
    }
}
