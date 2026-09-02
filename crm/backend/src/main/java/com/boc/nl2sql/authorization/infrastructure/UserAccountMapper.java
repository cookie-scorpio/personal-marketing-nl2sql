package com.boc.nl2sql.authorization.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 用户账号的 MyBatis-Plus 数据访问入口。 */
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {
}
