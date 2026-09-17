package com.laundry.factory.dto;

import jakarta.validation.constraints.NotBlank;

public record ManualImportRequest(
        @NotBlank(message = "请输入订单号") String orderNo,
        String deviceCode
) {}

