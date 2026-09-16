-- 小木棒洗衣工厂端基础表（增量脚本，不删除门店端现有数据）
-- 执行库：laundry_db；执行前请先备份数据库。
USE `laundry_db`;

CREATE TABLE IF NOT EXISTS `factory_device` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `device_code` VARCHAR(40) NOT NULL COMMENT '设备唯一编号',
    `device_name` VARCHAR(100) NOT NULL,
    `station_code` VARCHAR(40) NOT NULL COMMENT '工位编号',
    `station_type` VARCHAR(30) NOT NULL COMMENT 'RECEIVE/SORT/WASH/DRY/IRON/QUALITY/PACK/RETURN',
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `last_online_time` DATETIME DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_factory_device_code` (`device_code`),
    KEY `idx_factory_device_station` (`station_code`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工厂设备及工位绑定';

CREATE TABLE IF NOT EXISTS `factory_sort_type` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `type_code` VARCHAR(30) NOT NULL,
    `type_name` VARCHAR(50) NOT NULL,
    `sort_order` INT NOT NULL DEFAULT 0,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_factory_sort_type_code` (`type_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可扩展分拣类型配置';

INSERT INTO `factory_sort_type` (`type_code`, `type_name`, `sort_order`)
VALUES ('WATER_WASH', '水洗', 10), ('DRY_CLEAN', '干洗', 20),
       ('IRON_ONLY', '单烫', 30), ('SPECIAL', '特殊处理', 40)
ON DUPLICATE KEY UPDATE `type_name` = VALUES(`type_name`), `sort_order` = VALUES(`sort_order`);

CREATE TABLE IF NOT EXISTS `factory_package` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `package_no` VARCHAR(40) NOT NULL COMMENT '大件包装码',
    `order_id` BIGINT NOT NULL,
    `order_no` VARCHAR(20) NOT NULL,
    `source_batch_id` BIGINT DEFAULT NULL COMMENT '原送厂批次ID',
    `source_batch_no` VARCHAR(30) DEFAULT NULL,
    `package_seq` INT NOT NULL DEFAULT 1 COMMENT '同一订单拆包序号',
    `expected_item_count` INT NOT NULL,
    `received_item_count` INT NOT NULL DEFAULT 0,
    `status` VARCHAR(30) NOT NULL DEFAULT 'WAIT_FACTORY' COMMENT 'WAIT_FACTORY/CHECKING/RECEIVED/PROCESSING/QUALITY_PASSED/PACKED/RETURNING/BACK_TO_STORE/FROZEN',
    `frozen_reason` VARCHAR(500) DEFAULT NULL,
    `received_time` DATETIME DEFAULT NULL,
    `packed_time` DATETIME DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_factory_package_no` (`package_no`),
    UNIQUE KEY `uk_factory_package_order_seq` (`order_id`, `package_seq`),
    KEY `idx_factory_package_order` (`order_id`),
    KEY `idx_factory_package_source_batch` (`source_batch_id`),
    KEY `idx_factory_package_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单大件包装；超过5件允许管理员拆包';

CREATE TABLE IF NOT EXISTS `factory_package_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `package_id` BIGINT NOT NULL,
    `package_no` VARCHAR(40) NOT NULL,
    `order_item_id` BIGINT NOT NULL,
    `barcode` VARCHAR(20) NOT NULL,
    `scan_status` VARCHAR(20) NOT NULL DEFAULT 'WAIT_SCAN' COMMENT 'WAIT_SCAN/MATCHED/EXCEPTION',
    `scan_time` DATETIME DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_factory_package_item` (`package_id`, `order_item_id`),
    UNIQUE KEY `uk_factory_package_barcode` (`package_id`, `barcode`),
    KEY `idx_factory_package_item_barcode` (`barcode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大件与单件衣物关系及到厂核对状态';

CREATE TABLE IF NOT EXISTS `factory_item_state` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `order_item_id` BIGINT NOT NULL,
    `barcode` VARCHAR(20) NOT NULL,
    `package_id` BIGINT NOT NULL,
    `current_process` VARCHAR(30) NOT NULL DEFAULT 'RECEIVE',
    `status` VARCHAR(30) NOT NULL DEFAULT 'WAIT_RECEIVE',
    `sort_type_id` BIGINT DEFAULT NULL,
    `need_dry` TINYINT NOT NULL DEFAULT 0,
    `need_iron` TINYINT NOT NULL DEFAULT 0,
    `rework_count` INT NOT NULL DEFAULT 0,
    `version` INT NOT NULL DEFAULT 0,
    `deadline_time` DATETIME DEFAULT NULL,
    `last_operate_time` DATETIME DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_factory_item_state_item` (`order_item_id`),
    UNIQUE KEY `uk_factory_item_state_barcode` (`barcode`),
    KEY `idx_factory_item_state_process` (`current_process`, `status`),
    KEY `idx_factory_item_state_deadline` (`deadline_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单件衣物当前工厂生产状态';

CREATE TABLE IF NOT EXISTS `factory_process_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `event_id` VARCHAR(64) NOT NULL COMMENT '客户端生成的幂等事件ID',
    `order_item_id` BIGINT NOT NULL,
    `barcode` VARCHAR(20) NOT NULL,
    `package_id` BIGINT NOT NULL,
    `process_code` VARCHAR(30) NOT NULL,
    `action_code` VARCHAR(30) NOT NULL COMMENT 'START/COMPLETE/REWORK/ADMIN_ADJUST',
    `before_status` VARCHAR(30) DEFAULT NULL,
    `after_status` VARCHAR(30) NOT NULL,
    `device_code` VARCHAR(40) NOT NULL,
    `station_code` VARCHAR(40) NOT NULL,
    `admin_user_id` BIGINT DEFAULT NULL,
    `reason` VARCHAR(500) DEFAULT NULL,
    `operate_time` DATETIME NOT NULL,
    `server_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_factory_process_event` (`event_id`),
    KEY `idx_factory_process_item_time` (`order_item_id`, `operate_time`),
    KEY `idx_factory_process_device` (`device_code`, `operate_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单件工序流转不可变事件记录';

CREATE TABLE IF NOT EXISTS `factory_quality_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `order_item_id` BIGINT NOT NULL,
    `barcode` VARCHAR(20) NOT NULL,
    `result` VARCHAR(20) NOT NULL COMMENT 'PASSED/REWORK',
    `rework_count` INT NOT NULL DEFAULT 0,
    `reason` VARCHAR(500) DEFAULT NULL,
    `device_code` VARCHAR(40) NOT NULL,
    `operate_time` DATETIME NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_factory_quality_item` (`order_item_id`, `operate_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检与返工记录';

CREATE TABLE IF NOT EXISTS `factory_exception` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `exception_no` VARCHAR(40) NOT NULL,
    `package_id` BIGINT NOT NULL,
    `order_item_id` BIGINT DEFAULT NULL,
    `exception_type` VARCHAR(30) NOT NULL COMMENT 'MISSING/EXTRA/WRONG_STORE/BROKEN_BARCODE/OTHER',
    `description` VARCHAR(500) DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/RESOLVED/CLOSED',
    `resolved_by` BIGINT DEFAULT NULL,
    `resolved_time` DATETIME DEFAULT NULL,
    `resolution` VARCHAR(500) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_factory_exception_no` (`exception_no`),
    KEY `idx_factory_exception_package_status` (`package_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常冻结记录，后续与客服系统对接';

CREATE TABLE IF NOT EXISTS `factory_return_batch` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `return_batch_no` VARCHAR(40) NOT NULL,
    `package_count` INT NOT NULL DEFAULT 0,
    `item_count` INT NOT NULL DEFAULT 0,
    `status` VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/DISPATCHED/PART_RECEIVED/RECEIVED',
    `dispatch_operator` VARCHAR(50) DEFAULT NULL,
    `dispatch_time` DATETIME DEFAULT NULL,
    `remark` VARCHAR(500) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_factory_return_batch_no` (`return_batch_no`),
    KEY `idx_factory_return_batch_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工厂回店发货批次';

CREATE TABLE IF NOT EXISTS `factory_return_batch_package` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `return_batch_id` BIGINT NOT NULL,
    `return_batch_no` VARCHAR(40) NOT NULL,
    `package_id` BIGINT NOT NULL,
    `package_no` VARCHAR(40) NOT NULL,
    `source_batch_id` BIGINT NOT NULL,
    `source_batch_no` VARCHAR(30) NOT NULL,
    `store_code` VARCHAR(10) NOT NULL,
    `store_receive_status` VARCHAR(20) NOT NULL DEFAULT 'WAIT_SCAN',
    `store_receive_time` DATETIME DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_factory_return_package` (`return_batch_id`, `package_id`),
    KEY `idx_factory_return_store` (`store_code`, `store_receive_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回店批次与大件、原送厂批次关系';

CREATE TABLE IF NOT EXISTS `factory_change_request` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `request_no` VARCHAR(40) NOT NULL,
    `order_id` BIGINT NOT NULL,
    `order_item_id` BIGINT DEFAULT NULL,
    `field_name` VARCHAR(50) NOT NULL,
    `before_value` TEXT DEFAULT NULL,
    `requested_value` TEXT NOT NULL,
    `reason` VARCHAR(500) NOT NULL,
    `status` VARCHAR(30) NOT NULL DEFAULT 'WAIT_FACTORY_ADMIN' COMMENT 'WAIT_FACTORY_ADMIN/WAIT_STORE/APPROVED/REJECTED',
    `device_code` VARCHAR(40) NOT NULL,
    `factory_admin_id` BIGINT DEFAULT NULL,
    `store_operator_id` BIGINT DEFAULT NULL,
    `review_remark` VARCHAR(500) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_factory_change_request_no` (`request_no`),
    KEY `idx_factory_change_request_status` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工人申请、工厂管理员审核、门店确认的信息修改流程';

CREATE TABLE IF NOT EXISTS `factory_sync_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `event_id` VARCHAR(64) NOT NULL,
    `device_code` VARCHAR(40) NOT NULL,
    `event_type` VARCHAR(40) NOT NULL,
    `aggregate_type` VARCHAR(30) NOT NULL,
    `aggregate_id` VARCHAR(64) NOT NULL,
    `payload` JSON NOT NULL,
    `client_time` DATETIME NOT NULL,
    `sync_status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SYNCED/CONFLICT/FAILED',
    `retry_count` INT NOT NULL DEFAULT 0,
    `error_message` VARCHAR(500) DEFAULT NULL,
    `synced_time` DATETIME DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_factory_sync_event_id` (`event_id`),
    KEY `idx_factory_sync_status` (`sync_status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线操作同步和冲突处理队列';

CREATE TABLE IF NOT EXISTS `factory_audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `operator_id` BIGINT DEFAULT NULL,
    `operator_name` VARCHAR(50) DEFAULT NULL,
    `device_code` VARCHAR(40) DEFAULT NULL,
    `action` VARCHAR(50) NOT NULL,
    `target_type` VARCHAR(30) NOT NULL,
    `target_id` VARCHAR(64) NOT NULL,
    `reason` VARCHAR(500) DEFAULT NULL,
    `detail` JSON DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_factory_audit_target` (`target_type`, `target_id`),
    KEY `idx_factory_audit_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工厂敏感操作审计日志';

CREATE TABLE IF NOT EXISTS `factory_system_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `config_key` VARCHAR(80) NOT NULL,
    `config_value` VARCHAR(500) NOT NULL,
    `description` VARCHAR(500) DEFAULT NULL,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_factory_system_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工厂可配置业务参数';

INSERT INTO `factory_system_config` (`config_key`, `config_value`, `description`)
VALUES ('NORMAL_DEADLINE_HOURS', '96', '普通订单总时限'),
       ('URGENT_DEADLINE_HOURS', '48', '加急订单总时限'),
       ('WARNING_BEFORE_HOURS', '24', '预计超时提前预警小时数'),
       ('PACKAGE_SPLIT_THRESHOLD', '5', '超过该件数允许管理员拆分大件')
ON DUPLICATE KEY UPDATE `config_value` = VALUES(`config_value`), `description` = VALUES(`description`);

