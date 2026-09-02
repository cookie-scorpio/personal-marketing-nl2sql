package com.boc.nl2sql.dao.authorization;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.boc.nl2sql.domain.authorization.UserAccountEntity;

/** 用户账号的 MyBatis-Plus 数据访问入口。 */
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {
}
