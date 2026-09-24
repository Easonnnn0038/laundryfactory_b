package com.laundry.factory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.factory.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class FactoryDeviceTokenFilter extends OncePerRequestFilter {
    private final Map<String, byte[]> deviceTokens;
    private final ObjectMapper objectMapper;

    public FactoryDeviceTokenFilter(@Value("${factory.security.device-tokens}") String configuredTokens,
                                    ObjectMapper objectMapper) {
        try {
            this.deviceTokens = Arrays.stream(configuredTokens.split(","))
                    .map(value -> value.split(":", 2))
                    .collect(Collectors.toUnmodifiableMap(
                            pair -> pair[0].trim(),
                            pair -> {
                                if (pair.length != 2 || pair[1].trim().length() < 24) throw new IllegalArgumentException();
                                return pair[1].trim().getBytes(StandardCharsets.UTF_8);
                            }));
        } catch (RuntimeException e) {
            throw new IllegalStateException("FACTORY_DEVICE_TOKENS格式应为 device01:至少24字符令牌[,device02:令牌]", e);
        }
        if (deviceTokens.isEmpty() || deviceTokens.containsKey("")) {
            throw new IllegalStateException("至少配置一个工厂设备令牌");
        }
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !path.startsWith("/api/factory/")
                || "/api/factory/public/status".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String deviceCode = request.getHeader("X-Factory-Device");
        byte[] expectedToken = deviceCode == null ? null : deviceTokens.get(deviceCode);
        byte[] actual = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (expectedToken == null || !MessageDigest.isEqual(expectedToken, actual)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Result.error(401, "工厂设备未授权或令牌已失效"));
            return;
        }
        request.setAttribute("factoryDeviceCode", deviceCode);
        filterChain.doFilter(request, response);
    }
}
