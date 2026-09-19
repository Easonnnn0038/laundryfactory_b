package com.laundry.factory.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record DispatchReturnBatchRequest(
        @NotEmpty(message = "请选择至少一个大件") List<Long> packageIds,
        String deviceCode
) {}
