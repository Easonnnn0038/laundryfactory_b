package com.laundry.factory.controller;

import com.laundry.factory.common.Result;
import com.laundry.factory.dto.DispatchReturnBatchRequest;
import com.laundry.factory.service.ReturnDispatchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/factory/return-dispatch")
public class ReturnDispatchController {
    private final ReturnDispatchService service;
    public ReturnDispatchController(ReturnDispatchService service) { this.service = service; }

    @GetMapping("/ready")
    public Result<List<Map<String, Object>>> ready() { return Result.success(service.readyPackages()); }

    @PostMapping("/dispatch")
    public Result<Map<String, Object>> dispatch(@Valid @RequestBody DispatchReturnBatchRequest request) {
        return Result.success(service.dispatch(request));
    }
}
