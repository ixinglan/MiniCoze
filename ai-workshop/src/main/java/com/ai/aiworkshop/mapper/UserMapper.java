package com.ai.aiworkshop.mapper;

import com.ai.aiworkshop.entity.UserDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * users 表 Mapper（阶段 9 用户体系）。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserDO> {
}
