package com.laundry.factory.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="factory.schema-check.enabled", havingValue="true", matchIfMissing=true)
public class StoreSchemaVersionCheck implements ApplicationRunner {
    // V25 adds the fulfillment method required to keep home deliveries out of store return batches.
    private static final int REQUIRED_VERSION = 25;
    private final JdbcTemplate jdbc;

    public StoreSchemaVersionCheck(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer version = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0)
                    FROM flyway_schema_history WHERE success=1 AND type<>'UNDO'
                    """, Integer.class);
            if (version == null || version < REQUIRED_VERSION) {
                throw new IllegalStateException("门店数据库结构版本不足：工厂服务至少需要 Flyway V"
                        + REQUIRED_VERSION + "，当前为 V" + version + "；请先部署门店后端完成迁移");
            }
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("无法读取门店数据库 Flyway 版本；请先确认数据库连接和门店迁移已完成", e);
        }
    }
}
