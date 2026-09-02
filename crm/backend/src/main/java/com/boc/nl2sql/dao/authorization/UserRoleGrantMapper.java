package com.boc.nl2sql.dao.authorization;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.boc.nl2sql.domain.authorization.UserRoleGrantEntity;

/** 多角色授权记录的数据访问入口。 */
@Mapper
public interface UserRoleGrantMapper extends BaseMapper<UserRoleGrantEntity> {
}
