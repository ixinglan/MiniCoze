package com.ai.aiworkshop.mapper;

import com.ai.aiworkshop.entity.RateLimitCountDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * rate_limit_count 表 Mapper（阶段 9 限流）。
 * increment 用 INSERT ... ON DUPLICATE KEY UPDATE 原子递增（并发不超发）。
 */
@Mapper
public interface RateLimitMapper extends BaseMapper<RateLimitCountDO> {

    @Insert("""
            INSERT INTO rate_limit_count (scope_type, scope_value, action, day, count)
            VALUES (#{scopeType}, #{scopeValue}, #{action}, #{day}, 1)
            ON DUPLICATE KEY UPDATE count = count + 1
            """)
    void increment(@Param("scopeType") String scopeType,
                   @Param("scopeValue") String scopeValue,
                   @Param("action") String action,
                   @Param("day") LocalDate day);
}
