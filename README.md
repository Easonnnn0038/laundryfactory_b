# 小木棒洗衣 · 工厂后端

洗衣工厂工位系统的后端服务。它与门店后端共用业务数据库，提供到厂签收、加工、质检、打包和回店发货接口。

## 主要功能

- 设备编号与设备令牌认证
- 订单/大件到厂导入及逐件核对
- 普通衣物的洗涤、烘干、熨烫灵活记录
- 鞋类独立清洗流程
- 质检合格、返工、备注及最多 9 张照片
- 同一大件完整质检后打包
- 按门店生成回店批次并发货
- 普通/加急工期与超时预警

## 技术栈

Java 17、Spring Boot 3.2.5、Spring JDBC、MySQL 8、Knife4j。

## 本地运行

要求：JDK 17、Maven 3.8+、MySQL 8，并准备好由门店后端 Flyway 管理的 `laundry_db`。

```powershell
$env:DB_PASSWORD = '你的数据库密码'
$env:FACTORY_DEVICE_TOKENS = 'dev-device:请替换为足够长的令牌'
mvn spring-boot:run
```

默认端口为 `8081`。公开状态接口：<http://localhost:8081/api/factory/public/status>。

> 数据库迁移只由门店后端执行。工厂后端不要单独维护或重复执行 Flyway 迁移。

## 常用配置

| 环境变量 | 用途 |
| --- | --- |
| `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | MySQL 连接 |
| `FACTORY_DEVICE_TOKENS` | `设备编号:令牌` 列表 |
| `FACTORY_ALLOWED_ORIGINS` | 允许的工厂前端来源 |
| `FACTORY_QUALITY_PHOTO_DIR` | 质检照片目录 |
| `NORMAL_DEADLINE_HOURS` | 普通订单时限，默认 96 小时 |
| `URGENT_DEADLINE_HOURS` | 加急订单时限，默认 48 小时 |
| `WARNING_BEFORE_HOURS` | 提前预警时间，默认 24 小时 |

生产环境启用 `prod` Profile 后，数据库和设备安全配置必须显式提供，接口文档默认关闭。

## 验证与构建

```bash
mvn test
mvn clean package
```

## 安全说明

- 不要提交设备令牌、数据库密码、质检照片或生产环境文件。
- 不要在多个工位长期共用同一设备令牌；正式环境应按设备分配并定期轮换。
- 工厂端不展示顾客姓名、消费金额等非生产所需信息。

## 相关仓库

- [工厂前端](https://github.com/Easonnnn0038/laundryfactory_f)
- [门店后端](https://github.com/Easonnnn0038/laundry-backend)
- [门店前端](https://github.com/Easonnnn0038/lanudry-frontend)

