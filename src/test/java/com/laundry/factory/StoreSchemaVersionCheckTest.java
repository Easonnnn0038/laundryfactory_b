package com.laundry.factory;

import com.laundry.factory.config.StoreSchemaVersionCheck;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StoreSchemaVersionCheckTest {
    @Test
    void refusesOldSchemaAndAcceptsRequiredVersion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(13, 14);
        var check = new StoreSchemaVersionCheck(jdbc);
        assertTrue(assertThrows(IllegalStateException.class, () -> check.run(null)).getMessage().contains("V14"));
        assertDoesNotThrow(() -> check.run(null));
    }
}
