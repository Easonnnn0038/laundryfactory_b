package com.laundry.factory;

import com.laundry.factory.dto.ConfirmProcessRequest;
import com.laundry.factory.dto.DispatchReturnBatchRequest;
import com.laundry.factory.dto.ManualImportRequest;
import com.laundry.factory.service.FactoryWorkflowService;
import com.laundry.factory.service.QualityPhotoService;
import com.laundry.factory.service.ReturnDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FactoryApplicationTests {
    private static final String TOKEN = "test-token-at-least-24-characters";

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired FactoryWorkflowService workflow;
    @Autowired ReturnDispatchService returns;
    @Autowired QualityPhotoService photos;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        for (String table : List.of("factory_return_batch_package", "factory_return_batch",
                "factory_quality_record", "factory_process_record", "factory_item_state",
                "factory_package_item", "supplement_attachment", "factory_package",
                "order_item", "laundry_order")) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void deviceAuthenticationProtectsFactoryApis() throws Exception {
        mvc.perform(get("/api/factory/workflow/waiting").param("process", "PACK"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/factory/workflow/waiting").param("process", "PACK")
                        .header("X-Factory-Device", "test-device")
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/factory/workflow/waiting").param("process", "PACK")
                        .header("X-Factory-Device", "test-device")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void importIsIdempotentAndSeparatesShoesFromClothes() {
        Fixture f = fixture("00120261013001", "001", "CLOTHES", "SHOES");
        workflow.manualImport(new ManualImportRequest(f.orderNo(), "station-1"));
        workflow.manualImport(new ManualImportRequest(f.orderNo(), "station-1"));
        assertEquals(2, count("factory_item_state"));
        assertEquals("PROCESSING", process(f.itemIds().get(0)));
        assertEquals("SHOE_WASH", process(f.itemIds().get(1)));
        assertEquals(2, count("factory_process_record"));
    }

    @Test
    void clothesAreFlexibleButShoesCannotUseClothesStations() {
        Fixture f = fixture("00120261013002", "001", "CLOTHES", "SHOES");
        workflow.manualImport(new ManualImportRequest(f.orderNo(), "station-1"));
        workflow.confirm(confirm(f.orderNo(), "DRY", null));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM factory_process_record WHERE process_code='DRY'", Integer.class));
        jdbc.update("UPDATE factory_item_state SET current_process='SHOE_WASH',status='WAIT_SHOE_WASH' WHERE order_item_id=?", f.itemIds().get(0));
        assertThrows(IllegalArgumentException.class, () -> workflow.confirm(confirm(f.orderNo(), "IRON", null)));
    }

    @Test
    void qualityReworkReturnsEachCategoryToItsOwnStation() {
        Fixture f = fixture("00120261013003", "001", "CLOTHES", "SHOES");
        workflow.manualImport(new ManualImportRequest(f.orderNo(), "station-1"));
        workflow.confirm(confirm(f.orderNo(), "SHOE_WASH", null));
        workflow.confirm(confirm(f.orderNo(), "QUALITY", "REWORK"));
        assertEquals("PROCESSING", process(f.itemIds().get(0)));
        assertEquals("SHOE_WASH", process(f.itemIds().get(1)));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM factory_quality_record WHERE result='REWORK'", Integer.class));
    }

    @Test
    void packageCannotBePackedUntilEveryItemPassesQuality() {
        Fixture f = fixture("00120261013004", "001", "CLOTHES", "CLOTHES");
        workflow.manualImport(new ManualImportRequest(f.orderNo(), "station-1"));
        jdbc.update("UPDATE factory_item_state SET current_process='PACK',status='WAIT_PACK' WHERE order_item_id=?", f.itemIds().get(0));
        assertThrows(IllegalArgumentException.class, () -> workflow.confirm(confirm(f.orderNo(), "PACK", null)));
        jdbc.update("UPDATE factory_item_state SET current_process='PACK',status='WAIT_PACK' WHERE order_item_id=?", f.itemIds().get(1));
        workflow.confirm(confirm(f.orderNo(), "PACK", null));
        assertEquals("PACKED", jdbc.queryForObject("SELECT status FROM factory_package WHERE id=?", String.class, f.packageId()));
        assertThrows(IllegalArgumentException.class, () -> workflow.confirm(confirm(f.orderNo(), "PACK", null)));
    }

    @Test
    void multipleOrdersCanShareOneReturnBatchAndCannotShipTwice() {
        Fixture first = readyToReturn("00120261013005", "001");
        Fixture second = readyToReturn("00120261013006", "001");
        Map<String, Object> result = returns.dispatch(new DispatchReturnBatchRequest(
                List.of(first.packageId(), second.packageId()), "return-station"));
        assertEquals(2, result.get("packageCount"));
        assertEquals(1, count("factory_return_batch"));
        assertThrows(IllegalArgumentException.class, () -> returns.dispatch(new DispatchReturnBatchRequest(
                List.of(first.packageId()), "return-station")));
    }

    @Test
    void invalidImageIsRejectedWithoutWritingAFile() {
        var fake = new MockMultipartFile("file", "fake.png", "image/png", "not an image".getBytes());
        assertThrows(IllegalArgumentException.class, () -> photos.save(fake));
    }

    @Test
    void concurrentQualityAdvanceOnlySucceedsOnce() throws Exception {
        Fixture f = fixture("00120261013007", "001", "CLOTHES");
        workflow.manualImport(new ManualImportRequest(f.orderNo(), "station-1"));
        var request = confirm(f.orderNo(), "QUALITY", "PASSED");
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> runAfter(start, request));
            var second = executor.submit(() -> runAfter(start, request));
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("factory_quality_record"));
        assertEquals("PACK", process(f.itemIds().get(0)));
    }

    @Test
    void concurrentPackingOnlySucceedsOnce() throws Exception {
        Fixture f = fixture("00120261013008", "001", "CLOTHES");
        workflow.manualImport(new ManualImportRequest(f.orderNo(), "station-1"));
        workflow.confirm(confirm(f.orderNo(), "QUALITY", "PASSED"));
        var request = confirm(f.orderNo(), "PACK", null);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> runAfter(start, request));
            var second = executor.submit(() -> runAfter(start, request));
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        } finally { executor.shutdownNow(); }
        assertEquals("PACKED", jdbc.queryForObject("SELECT status FROM factory_package WHERE id=?", String.class, f.packageId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM factory_process_record WHERE process_code='PACK'", Integer.class));
    }

    @Test
    void orphanCleanupKeepsReferencedPhotos(@TempDir Path temp) throws Exception {
        Fixture f = fixture("00120261013009", "001", "CLOTHES");
        Path day = Files.createDirectory(temp.resolve("20261013"));
        Path orphan = Files.writeString(day.resolve("orphan.jpg"), "orphan");
        Path used = Files.writeString(day.resolve("used.jpg"), "used");
        FileTime old = FileTime.from(Instant.now().minusSeconds(7200));
        Files.setLastModifiedTime(orphan, old);
        Files.setLastModifiedTime(used, old);
        jdbc.update("""
                INSERT INTO factory_quality_record(order_item_id,barcode,result,rework_count,photo_paths,device_code,operate_time)
                VALUES (?, 'test', 'PASSED', 0, ?, 'test-device', NOW())
                """, f.itemIds().get(0), "[\"20261013/used.jpg\"]");
        var service = new QualityPhotoService(temp.toString(), 1, jdbc);
        service.cleanOrphans();
        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(used));
        assertThrows(IllegalArgumentException.class, () -> service.delete("20261013", "used.jpg"));
    }

    private boolean runAfter(CountDownLatch start, ConfirmProcessRequest request) {
        try { start.await(); workflow.confirm(request); return true; }
        catch (Exception e) { return false; }
    }

    private Fixture readyToReturn(String orderNo, String storeCode) {
        Fixture f = fixture(orderNo, storeCode, "CLOTHES");
        workflow.manualImport(new ManualImportRequest(orderNo, "station-1"));
        jdbc.update("UPDATE factory_item_state SET current_process='RETURN',status='WAIT_RETURN' WHERE package_id=?", f.packageId());
        jdbc.update("UPDATE factory_package SET status='PACKED' WHERE id=?", f.packageId());
        return f;
    }

    private Fixture fixture(String orderNo, String storeCode, String... groups) {
        jdbc.update("""
                INSERT INTO laundry_order(order_no,customer_id,customer_name,customer_phone,store_code,total_count,
                    payment_method,operator_id,operator_name,status,receive_time)
                VALUES (?,1,'test','13800000000',?,?,'CASH',1,'tester','SENT_TO_FACTORY',NOW())
                """, orderNo, storeCode, groups.length);
        long orderId = jdbc.queryForObject("SELECT id FROM laundry_order WHERE order_no=?", Long.class, orderNo);
        jdbc.update("""
                INSERT INTO factory_package(package_no,order_id,order_no,source_batch_id,source_batch_no,
                    package_seq,expected_item_count,status) VALUES (?,?,?,1,'PC-TEST',1,?,'WAIT_FACTORY')
                """, "PK-" + orderNo, orderId, orderNo, groups.length);
        long packageId = jdbc.queryForObject("SELECT id FROM factory_package WHERE order_id=?", Long.class, orderId);
        var itemIds = new java.util.ArrayList<Long>();
        for (int i = 0; i < groups.length; i++) {
            String barcode = orderNo.substring(orderNo.length() - 8) + String.format("%02d", i + 1);
            jdbc.update("""
                    INSERT INTO order_item(order_id,order_no,item_seq,barcode,category_id,category_group,
                        category_name,unit_price,subtotal) VALUES (?,?,?,?,1,?,'test',10,10)
                    """, orderId, orderNo, i + 1, barcode, groups[i]);
            long itemId = jdbc.queryForObject("SELECT id FROM order_item WHERE barcode=?", Long.class, barcode);
            itemIds.add(itemId);
            jdbc.update("INSERT INTO factory_package_item(package_id,package_no,order_item_id,barcode) VALUES (?,?,?,?)",
                    packageId, "PK-" + orderNo, itemId, barcode);
        }
        return new Fixture(orderNo, packageId, itemIds);
    }

    private ConfirmProcessRequest confirm(String orderNo, String process, String qualityResult) {
        return new ConfirmProcessRequest(orderNo, process, "station-1", null, null, null,
                qualityResult, "test", List.of());
    }

    private String process(long itemId) {
        return jdbc.queryForObject("SELECT current_process FROM factory_item_state WHERE order_item_id=?", String.class, itemId);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private record Fixture(String orderNo, long packageId, List<Long> itemIds) {}
}

