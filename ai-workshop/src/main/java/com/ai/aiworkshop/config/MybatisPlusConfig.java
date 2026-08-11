package com.ai.aiworkshop.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 扫描配置：让 Mapper 接口被 Spring 托管。
 * （Mapper 接口本身也加了 @Mapper 注解，这里再统一扫描双保险。）
 */
@Configuration
@MapperScan("com.ai.aiworkshop.mapper")
public class MybatisPlusConfig {
}
