package com.laundry.factory.controller;

import com.laundry.factory.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/factory/public")
public class PublicController {
    @Value("${factory.deadline.normal-hours}") private int normalHours;
    @Value("${factory.deadline.urgent-hours}") private int urgentHours;
    @Value("${factory.deadline.warning-before-hours}") private int warningHours;

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "laundry-factory-backend");
        data.put("version", "1.0.0-SNAPSHOT");
        data.put("time", LocalDateTime.now());
        data.put("normalDeadlineHours", normalHours);
        data.put("urgentDeadlineHours", urgentHours);
        data.put("warningBeforeHours", warningHours);
        return Result.success(data);
    }

    @GetMapping("/device-check")
    public Result<Map<String, Object>> deviceCheck() {
        return Result.success(Map.of("authorized", true));
    }
}
