package com.laundry.factory.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ConfirmProcessRequest(
        @NotBlank(message = "订单号不能为空") String orderNo,
        @NotBlank(message = "工序不能为空") String process,
        String deviceCode,
        String sortTypeCode,
        Boolean needDry,
        Boolean needIron,
        String qualityResult,
        String remark,
        List<String> photoPaths
) {}
