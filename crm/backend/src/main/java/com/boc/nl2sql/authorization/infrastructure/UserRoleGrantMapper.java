package com.boc.nl2sql.authorization.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 多角色授权记录的数据访问入口。 */
@Mapper
public interface UserRoleGrantMapper extends BaseMapper<UserRoleGrantEntity> {
}
