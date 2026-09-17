package com.laundry.factory.controller;

import com.laundry.factory.common.Result;
import com.laundry.factory.dto.ConfirmProcessRequest;
import com.laundry.factory.dto.ManualImportRequest;
import com.laundry.factory.dto.ScannerImportRequest;
import com.laundry.factory.service.FactoryWorkflowService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/factory/workflow")
public class FactoryWorkflowController {
    private final FactoryWorkflowService service;
    public FactoryWorkflowController(FactoryWorkflowService service) { this.service = service; }

    @PostMapping("/manual-import")
    public Result<Map<String, Object>> manualImport(@Valid @RequestBody ManualImportRequest request) {
        return Result.success(service.manualImport(request));
    }

    @PostMapping("/scan-import")
    public Result<Map<String, Object>> scanImport(@Valid @RequestBody ScannerImportRequest request) {
        return Result.success(service.scanImport(request));
    }

    @GetMapping("/order/{orderNo}")
    public Result<Map<String, Object>> detail(@PathVariable String orderNo) {
        return Result.success(service.orderDetail(orderNo));
    }

    @GetMapping("/waiting")
    public Result<List<Map<String, Object>>> waiting(@RequestParam String process) {
        return Result.success(service.waitingOrders(process));
    }

    @PostMapping("/confirm")
    public Result<Map<String, Object>> confirm(@Valid @RequestBody ConfirmProcessRequest request) {
        return Result.success(service.confirm(request));
    }
}
