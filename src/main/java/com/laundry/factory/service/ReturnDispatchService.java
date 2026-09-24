package com.laundry.factory.service;

import com.laundry.factory.dto.DispatchReturnBatchRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReturnDispatchService {
    private final JdbcTemplate jdbc;

    public ReturnDispatchService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> readyPackages() {
        return jdbc.queryForList("""
                SELECT fp.id, fp.package_no AS packageNo, fp.order_no AS orderNo,
                       fp.expected_item_count AS itemCount, fp.source_batch_no AS sourceBatchNo,
                       lo.store_code AS storeCode
                FROM factory_package fp JOIN laundry_order lo ON lo.id=fp.order_id
                WHERE fp.status='PACKED'
                  AND NOT EXISTS (SELECT 1 FROM factory_return_batch_package rbp WHERE rbp.package_id=fp.id)
                  AND (EXISTS (SELECT 1 FROM supplement_attachment sa WHERE sa.package_id=fp.id)
                    OR NOT EXISTS (SELECT 1 FROM factory_package sibling
                      WHERE sibling.order_id=fp.order_id AND sibling.status<>'PACKED'))
                  AND fp.expected_item_count=(SELECT COUNT(*) FROM factory_item_state fis
                      WHERE fis.package_id=fp.id AND fis.current_process='RETURN')
                ORDER BY lo.store_code, fp.order_no, fp.package_seq
                """);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dispatch(DispatchReturnBatchRequest request) {
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(request.packageIds()));
        if (ids.isEmpty() || ids.size() != request.packageIds().size())
            throw new IllegalArgumentException("大件选择不能为空或重复");
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        List<Map<String, Object>> packages = jdbc.queryForList("""
                SELECT fp.id, fp.package_no, fp.order_id, fp.order_no, fp.source_batch_id, fp.source_batch_no,
                       fp.expected_item_count, fp.status, lo.store_code
                FROM factory_package fp JOIN laundry_order lo ON lo.id=fp.order_id
                WHERE fp.id IN (%s) ORDER BY fp.id FOR UPDATE
                """.formatted(placeholders), ids.toArray());
        if (packages.size() != ids.size()) throw new IllegalArgumentException("部分大件不存在，请刷新");
        String storeCode = String.valueOf(packages.get(0).get("store_code"));
        int itemCount = 0;
        Map<Long, Long> selectedPerOrder = packages.stream().collect(java.util.stream.Collectors.groupingBy(
                p -> ((Number) p.get("order_id")).longValue(), java.util.stream.Collectors.counting()));
        for (Map.Entry<Long, Long> entry : selectedPerOrder.entrySet()) {
            Integer supplementSelected = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM supplement_attachment sa WHERE sa.package_id IN (%s) AND sa.order_id=?
                    """.formatted(placeholders), Integer.class,
                    java.util.stream.Stream.concat(ids.stream().map(x -> (Object)x), java.util.stream.Stream.of(entry.getKey())).toArray());
            if (supplementSelected != null && supplementSelected > 0) continue;
            Integer allPackages = jdbc.queryForObject("SELECT COUNT(*) FROM factory_package WHERE order_id=?",
                    Integer.class, entry.getKey());
            if (allPackages == null || allPackages.longValue() != entry.getValue())
                throw new IllegalArgumentException("同一订单的全部大件必须在同一回店批次发出");
        }
        for (Map<String, Object> pkg : packages) {
            long packageId = ((Number) pkg.get("id")).longValue();
            if (!storeCode.equals(pkg.get("store_code"))) throw new IllegalArgumentException("一次发车只能选择同一门店的大件");
            if (!"PACKED".equals(pkg.get("status"))) throw new IllegalArgumentException("大件尚未完成打包或已发货：" + pkg.get("package_no"));
            Integer already = jdbc.queryForObject("SELECT COUNT(*) FROM factory_return_batch_package WHERE package_id=?", Integer.class, packageId);
            if (already != null && already > 0) throw new IllegalArgumentException("大件已在回店批次中：" + pkg.get("package_no"));
            Integer ready = jdbc.queryForObject("SELECT COUNT(*) FROM factory_item_state WHERE package_id=? AND current_process='RETURN'", Integer.class, packageId);
            int expected = ((Number) pkg.get("expected_item_count")).intValue();
            if (ready == null || ready != expected) throw new IllegalArgumentException("大件衣物未全部完成：" + pkg.get("package_no"));
            if (pkg.get("source_batch_id") == null || pkg.get("source_batch_no") == null)
                throw new IllegalArgumentException("大件缺少原送厂批次：" + pkg.get("package_no"));
            itemCount += expected;
        }
        LocalDateTime now = LocalDateTime.now();
        String batchNo = "RB" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        jdbc.update("""
                INSERT INTO factory_return_batch(return_batch_no, package_count, item_count, status,
                    dispatch_operator, dispatch_time) VALUES (?, ?, ?, 'DISPATCHED', ?, ?)
                """, batchNo, packages.size(), itemCount,
                request.deviceCode() == null || request.deviceCode().isBlank() ? "MANUAL-RETURN" : request.deviceCode(), now);
        Long batchId = jdbc.queryForObject("SELECT id FROM factory_return_batch WHERE return_batch_no=?", Long.class, batchNo);
        for (Map<String, Object> pkg : packages) {
            long packageId = ((Number) pkg.get("id")).longValue();
            jdbc.update("""
                    INSERT INTO factory_return_batch_package(return_batch_id, return_batch_no, package_id,
                        package_no, source_batch_id, source_batch_no, store_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, batchId, batchNo, packageId, pkg.get("package_no"),
                    pkg.get("source_batch_id"), pkg.get("source_batch_no"), storeCode);
            jdbc.update("UPDATE factory_package SET status='RETURNING', update_time=? WHERE id=?", now, packageId);
            jdbc.update("""
                    UPDATE factory_item_state SET current_process='DONE', status='WAIT_STORE',
                        last_operate_time=?, update_time=?, version=version+1
                    WHERE package_id=? AND current_process='RETURN'
                    """, now, now, packageId);
            jdbc.update("""
                    INSERT INTO factory_process_record(event_id, order_item_id, barcode, package_id,
                        process_code, action_code, before_status, after_status, device_code,
                        station_code, reason, operate_time)
                    SELECT UUID(), fis.order_item_id, fis.barcode, fis.package_id, 'RETURN', 'COMPLETE',
                        'WAIT_RETURN', 'WAIT_STORE', ?, 'RETURN', ?, ?
                    FROM factory_item_state fis WHERE fis.package_id=?
                    """, request.deviceCode() == null ? "MANUAL-RETURN" : request.deviceCode(), batchNo, now, packageId);
        }
        return Map.of("batchNo", batchNo, "storeCode", storeCode,
                "packageCount", packages.size(), "itemCount", itemCount);
    }
}
