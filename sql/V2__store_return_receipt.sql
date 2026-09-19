-- 回店验收增量迁移；先备份 laundry_db，再执行一次。不可重新执行 receive_clothes.sql。
USE `laundry_db`;

-- 一个大件只能属于一次回店发货；执行前确认旧数据没有重复 package_id。
ALTER TABLE `factory_return_batch_package`
    ADD UNIQUE KEY `uk_return_package_once` (`package_id`),
    ADD COLUMN `store_receive_operator_id` BIGINT DEFAULT NULL,
    ADD COLUMN `exception_reason` VARCHAR(500) DEFAULT NULL,
    ADD COLUMN `exception_time` DATETIME DEFAULT NULL,
    ADD COLUMN `exception_operator_id` BIGINT DEFAULT NULL;

CREATE TABLE IF NOT EXISTS `factory_store_receive_scan` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `return_batch_package_id` BIGINT NOT NULL,
    `package_id` BIGINT NOT NULL,
    `order_item_id` BIGINT NOT NULL,
    `barcode` VARCHAR(20) NOT NULL,
    `operator_id` BIGINT NOT NULL,
    `scan_time` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_store_scan_package_barcode` (`return_batch_package_id`, `barcode`),
    KEY `idx_store_scan_package` (`package_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店逐件核对记录；整大件确认前不改变订单状态';
