# 小木棒洗衣工厂后端

基于 JDK 17、Spring Boot 3.2.5、MyBatis-Plus、MySQL 和 Redis。

## 本地启动

1. 先执行 `sql/V1__factory_foundation.sql`。
2. 确保 MySQL `laundry_db` 和 Redis 已启动。
3. 执行 `mvn spring-boot:run`。
4. 状态接口：`http://localhost:8081/api/factory/public/status`。

所有密码都支持环境变量覆盖。生产环境必须设置 `DB_PASSWORD`、`REDIS_PASSWORD` 和 `JWT_SECRET`。

