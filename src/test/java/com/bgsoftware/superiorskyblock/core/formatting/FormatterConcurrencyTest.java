package com.bgsoftware.superiorskyblock.core.formatting;

import com.bgsoftware.superiorskyblock.core.formatting.impl.DateFormatter;
import com.bgsoftware.superiorskyblock.core.formatting.impl.NumberFormatter;
import org.junit.Test;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.Assert.*;

public class FormatterConcurrencyTest {
    @Test public void regionalDateFormattingDoesNotShareMutableCalendar() throws Exception {
        String pattern = "yyyy-MM-dd HH:mm:ss.SSS";
        DateFormatter.setDateFormatter(null, pattern);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int actor = 0; actor < 4; actor++) {
                final long base = actor * 123456789L;
                results.add(workers.submit(() -> {
                    SimpleDateFormat expected = new SimpleDateFormat(pattern);
                    for (int i = 0; i < 32; i++) {
                        Date instant = new Date(base + i * 76543L);
                        assertEquals(expected.format(instant), DateFormatter.getInstance().format(instant));
                    }
                }));
            }
            for (Future<?> result : results) result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
    }
    @Test public void regionalMoneyFormattingKeepsGroupingFractionsAndRounding() throws Exception {
        NumberFormatter.setNumberFormatter("en-US");
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int actor = 0; actor < 4; actor++) results.add(workers.submit(() -> {
                for (int i = 0; i < 32; i++) {
                    assertEquals("1,234.56", NumberFormatter.getInstance().format(new BigDecimal("1234.567")));
                    assertEquals("8.5", NumberFormatter.getInstance().format(new BigDecimal("8.50")));
                    assertEquals("10", NumberFormatter.getInstance().format(new BigDecimal("10.00")));
                }
            }));
            for (Future<?> result : results) result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
    }
}
