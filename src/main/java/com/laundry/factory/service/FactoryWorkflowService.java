package com.laundry.factory.service;

import com.laundry.factory.dto.ConfirmProcessRequest;
import com.laundry.factory.dto.ManualImportRequest;
import com.laundry.factory.dto.ScannerImportRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FactoryWorkflowService {
    private final JdbcTemplate jdbc;

    public FactoryWorkflowService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> manualImport(ManualImportRequest request) {
        return importOrder(request.orderNo(), request.deviceCode(), "手动输入订单号整单导入");
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> scanImport(ScannerImportRequest request) {
        String scanCode = request.scanCode().trim();
        // TODO(scanner-adapter): 型号确认后在设备适配层完成回车符、前后缀和串口协议解析。
        List<String> orderNos = jdbc.query(
                "SELECT order_no FROM factory_package WHERE package_no=?",
                (rs, rowNum) -> rs.getString(1), scanCode);
        String orderNo = orderNos.isEmpty() ? scanCode : orderNos.get(0);
        return importOrder(orderNo, request.deviceCode(), "扫码导入整单");
    }

    private Map<String, Object> importOrder(String rawOrderNo, String deviceCode, String importRemark) {
        String orderNo = rawOrderNo.trim();
        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT id, order_no, total_count, urgent_flag, receive_time, status
                FROM laundry_order WHERE order_no = ? FOR UPDATE
                """, orderNo);
        if (orders.isEmpty()) throw new IllegalArgumentException("未找到订单：" + orderNo);
        Map<String, Object> order = orders.get(0);
        if (!"SENT_TO_FACTORY".equals(String.valueOf(order.get("status")))) {
            throw new IllegalArgumentException("该订单尚未由门店打包送厂，当前状态：" + order.get("status"));
        }

        List<Map<String, Object>> packages = jdbc.queryForList(
                "SELECT * FROM factory_package WHERE order_no = ? ORDER BY package_seq FOR UPDATE", orderNo);
        if (packages.isEmpty()) throw new IllegalArgumentException("订单缺少大件包装信息，请先在门店系统执行打包送厂");
        if (packages.stream().anyMatch(p -> "FROZEN".equals(String.valueOf(p.get("status"))))) {
            throw new IllegalArgumentException("该订单存在冻结的大件，请联系客服处理");
        }

        Long orderId = ((Number) order.get("id")).longValue();
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM factory_item_state WHERE order_item_id IN (SELECT id FROM order_item WHERE order_id=?)",
                Integer.class, orderId);
        if (existing != null && existing > 0) return orderDetail(orderNo);

        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT oi.id, oi.barcode, oi.category_name, oi.color, oi.brand, oi.special,
                       fpi.package_id, fpi.package_no
                FROM order_item oi
                JOIN factory_package_item fpi ON fpi.order_item_id = oi.id
                WHERE oi.order_id = ? ORDER BY oi.item_seq
                """, orderId);
        int totalCount = ((Number) order.get("total_count")).intValue();
        if (items.size() != totalCount) throw new IllegalArgumentException("大件内衣物明细不完整，不能整单导入");

        LocalDateTime receiveTime = toLocalDateTime(order.get("receive_time"));
        int hours = ((Number) order.get("urgent_flag")).intValue() == 1 ? 48 : 96;
        LocalDateTime deadline = receiveTime.plusHours(hours);
        LocalDateTime now = LocalDateTime.now();
        String device = normalizeDevice(deviceCode);

        // TODO(scanner): 扫码枪型号确认后，改为“大件码 + 每件衣物码”逐一扫描匹配；手动录入暂按整单核对通过。
        for (Map<String, Object> item : items) {
            Long itemId = ((Number) item.get("id")).longValue();
            Long packageId = ((Number) item.get("package_id")).longValue();
            String barcode = String.valueOf(item.get("barcode"));
            jdbc.update("""
                    INSERT INTO factory_item_state(order_item_id, barcode, package_id, current_process,
                        status, deadline_time, last_operate_time, create_time, update_time)
                    VALUES (?, ?, ?, 'SORT', 'WAIT_SORT', ?, ?, ?, ?)
                    """, itemId, barcode, packageId, deadline, now, now, now);
            writeRecord(itemId, barcode, packageId, "RECEIVE", "COMPLETE", "WAIT_RECEIVE",
                    "WAIT_SORT", device, "RECEIVE", importRemark);
        }
        jdbc.update("""
                UPDATE factory_package SET received_item_count=expected_item_count, status='RECEIVED',
                    received_time=?, update_time=? WHERE order_no=?
                """, now, now, orderNo);
        jdbc.update("""
                UPDATE factory_package_item SET scan_status='MATCHED', scan_time=?
                WHERE package_id IN (SELECT id FROM factory_package WHERE order_no=? )
                """, now, orderNo);
        return orderDetail(orderNo);
    }

    public Map<String, Object> orderDetail(String orderNo) {
        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT id, order_no AS orderNo, total_count AS totalCount, urgent_flag AS urgentFlag,
                       receive_time AS receiveTime, status AS storeStatus
                FROM laundry_order WHERE order_no = ?
                """, orderNo.trim());
        if (orders.isEmpty()) throw new IllegalArgumentException("未找到订单：" + orderNo);
        Map<String, Object> result = new LinkedHashMap<>(orders.get(0));
        List<Map<String, Object>> packages = jdbc.queryForList("""
                SELECT id, package_no AS packageNo, source_batch_no AS sourceBatchNo,
                       expected_item_count AS expectedItemCount, status
                FROM factory_package WHERE order_no=? ORDER BY package_seq
                """, orderNo.trim());
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT oi.id, oi.barcode, oi.category_name AS categoryName, oi.color, oi.brand,
                       oi.special, fis.current_process AS currentProcess, fis.status,
                       fis.need_dry AS needDry, fis.need_iron AS needIron,
                       fis.rework_count AS reworkCount, fis.deadline_time AS deadlineTime
                FROM order_item oi
                LEFT JOIN factory_item_state fis ON fis.order_item_id=oi.id
                WHERE oi.order_id=? ORDER BY oi.item_seq
                """, result.get("id"));
        result.put("packages", packages); result.put("items", items);
        result.put("currentProcess", aggregateProcess(items));
        return result;
    }

    public List<Map<String, Object>> waitingOrders(String process) {
        String normalized = process.toUpperCase();
        return jdbc.queryForList("""
                SELECT lo.order_no AS orderNo, lo.total_count AS totalCount, lo.urgent_flag AS urgentFlag,
                       MIN(fis.deadline_time) AS deadlineTime, COUNT(*) AS readyItemCount
                FROM factory_item_state fis
                JOIN order_item oi ON oi.id=fis.order_item_id
                JOIN laundry_order lo ON lo.id=oi.order_id
                WHERE fis.current_process=?
                GROUP BY lo.id, lo.order_no, lo.total_count, lo.urgent_flag
                HAVING COUNT(*) = lo.total_count
                ORDER BY lo.urgent_flag DESC, MIN(fis.deadline_time)
                """, normalized);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirm(ConfirmProcessRequest request) {
        String process = request.process().toUpperCase();
        if (!List.of("SORT", "WASH", "DRY", "IRON", "QUALITY", "PACK").contains(process)) {
            throw new IllegalArgumentException("不支持的工序：" + process);
        }
        Map<String, Object> detail = orderDetail(request.orderNo());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        if (items.isEmpty() || items.get(0).get("currentProcess") == null) {
            throw new IllegalArgumentException("订单尚未在到厂签收工位导入");
        }
        if (items.stream().anyMatch(item -> !process.equals(String.valueOf(item.get("currentProcess"))))) {
            throw new IllegalArgumentException("订单尚未全部进入“" + processLabel(process) + "”工序，不能越级确认");
        }

        String next = nextProcess(process, request);
        String afterStatus = "WAIT_" + next;
        String device = normalizeDevice(request.deviceCode());
        LocalDateTime now = LocalDateTime.now();
        Long sortTypeId = null;
        if ("SORT".equals(process)) {
            if (request.sortTypeCode() == null || request.sortTypeCode().isBlank()) throw new IllegalArgumentException("请选择分拣类型");
            List<Long> ids = jdbc.query("SELECT id FROM factory_sort_type WHERE type_code=? AND enabled=1",
                    (rs, rowNum) -> rs.getLong(1), request.sortTypeCode());
            if (ids.isEmpty()) throw new IllegalArgumentException("分拣类型不存在或已停用");
            sortTypeId = ids.get(0);
        }

        for (Map<String, Object> item : items) {
            long itemId = ((Number) item.get("id")).longValue();
            String barcode = String.valueOf(item.get("barcode"));
            Long packageId = jdbc.queryForObject("SELECT package_id FROM factory_item_state WHERE order_item_id=?", Long.class, itemId);
            int changed;
            if ("SORT".equals(process)) {
                changed = jdbc.update("""
                        UPDATE factory_item_state SET current_process=?, status=?, sort_type_id=?,
                            need_dry=?, need_iron=?, last_operate_time=?, update_time=?, version=version+1
                        WHERE order_item_id=? AND current_process='SORT'
                        """, next, afterStatus, sortTypeId, Boolean.TRUE.equals(request.needDry()) ? 1 : 0,
                        Boolean.TRUE.equals(request.needIron()) ? 1 : 0, now, now, itemId);
            } else if ("QUALITY".equals(process) && "REWORK".equalsIgnoreCase(request.qualityResult())) {
                changed = jdbc.update("""
                        UPDATE factory_item_state SET current_process='WASH', status='WAIT_WASH',
                            rework_count=rework_count+1, last_operate_time=?, update_time=?, version=version+1
                        WHERE order_item_id=? AND current_process='QUALITY'
                        """, now, now, itemId);
                int reworkCount = ((Number) item.get("reworkCount")).intValue() + 1;
                jdbc.update("""
                        INSERT INTO factory_quality_record(order_item_id, barcode, result, rework_count,
                            reason, device_code, operate_time) VALUES (?, ?, 'REWORK', ?, ?, ?, ?)
                        """, itemId, barcode, reworkCount, request.remark(), device, now);
            } else {
                changed = jdbc.update("""
                        UPDATE factory_item_state SET current_process=?, status=?, last_operate_time=?,
                            update_time=?, version=version+1 WHERE order_item_id=? AND current_process=?
                        """, next, afterStatus, now, now, itemId, process);
                if ("QUALITY".equals(process)) {
                    jdbc.update("""
                            INSERT INTO factory_quality_record(order_item_id, barcode, result, rework_count,
                                reason, device_code, operate_time) VALUES (?, ?, 'PASSED', ?, ?, ?, ?)
                            """, itemId, barcode, item.get("reworkCount"), request.remark(), device, now);
                }
            }
            if (changed != 1) throw new IllegalArgumentException("衣物状态已变化，请刷新后重试");
            writeRecord(itemId, barcode, packageId, process,
                    "QUALITY".equals(process) && "REWORK".equalsIgnoreCase(request.qualityResult()) ? "REWORK" : "COMPLETE",
                    String.valueOf(item.get("status")), afterStatus, device, process, request.remark());
        }

        updatePackageStatus(request.orderNo(), process, request.qualityResult(), now);
        return orderDetail(request.orderNo());
    }

    private String nextProcess(String process, ConfirmProcessRequest request) {
        return switch (process) {
            case "SORT" -> "WASH";
            case "WASH" -> {
                Map<String, Object> flags = jdbc.queryForMap("""
                        SELECT MIN(need_dry) AS need_dry, MIN(need_iron) AS need_iron FROM factory_item_state fis
                        JOIN order_item oi ON oi.id=fis.order_item_id WHERE oi.order_no=?
                        """, request.orderNo());
                yield ((Number) flags.get("need_dry")).intValue() == 1 ? "DRY" : ((Number) flags.get("need_iron")).intValue() == 1 ? "IRON" : "QUALITY";
            }
            case "DRY" -> {
                Integer needIron = jdbc.queryForObject("""
                        SELECT MIN(need_iron) FROM factory_item_state fis JOIN order_item oi ON oi.id=fis.order_item_id
                        WHERE oi.order_no=?
                        """, Integer.class, request.orderNo());
                yield needIron != null && needIron == 1 ? "IRON" : "QUALITY";
            }
            case "IRON" -> "QUALITY";
            case "QUALITY" -> "REWORK".equalsIgnoreCase(request.qualityResult()) ? "WASH" : "PACK";
            case "PACK" -> "RETURN";
            default -> throw new IllegalArgumentException("未知工序");
        };
    }

    private void updatePackageStatus(String orderNo, String process, String qualityResult, LocalDateTime now) {
        String status = switch (process) {
            case "QUALITY" -> "REWORK".equalsIgnoreCase(qualityResult) ? "PROCESSING" : "QUALITY_PASSED";
            case "PACK" -> "PACKED";
            default -> "PROCESSING";
        };
        jdbc.update("UPDATE factory_package SET status=?, packed_time=IF(?='PACK', ?, packed_time), update_time=? WHERE order_no=?",
                status, process, now, now, orderNo);
    }

    private void writeRecord(Long itemId, String barcode, Long packageId, String process, String action,
                             String before, String after, String device, String station, String reason) {
        jdbc.update("""
                INSERT INTO factory_process_record(event_id, order_item_id, barcode, package_id, process_code,
                    action_code, before_status, after_status, device_code, station_code, reason, operate_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), itemId, barcode, packageId, process, action,
                before, after, device, station, reason, LocalDateTime.now());
    }

    private String aggregateProcess(List<Map<String, Object>> items) {
        if (items.isEmpty() || items.get(0).get("currentProcess") == null) return "WAIT_IMPORT";
        String first = String.valueOf(items.get(0).get("currentProcess"));
        return items.stream().allMatch(i -> first.equals(String.valueOf(i.get("currentProcess")))) ? first : "MIXED";
    }

    private String normalizeDevice(String device) { return device == null || device.isBlank() ? "MANUAL-STATION" : device.trim(); }
    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) return localDateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        throw new IllegalArgumentException("订单收衣时间格式异常，请联系管理员处理");
    }
    private String processLabel(String process) { return Map.of("SORT","分拣","WASH","洗涤","DRY","烘干","IRON","熨烫","QUALITY","质检","PACK","打包","RETURN","回店发货").getOrDefault(process, process); }
}
