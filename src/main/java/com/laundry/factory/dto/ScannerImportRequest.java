package com.laundry.factory.dto;

import jakarta.validation.constraints.NotBlank;

public record ScannerImportRequest(
        @NotBlank(message = "请扫描大件码或订单号") String scanCode,
        String deviceCode
) {}
