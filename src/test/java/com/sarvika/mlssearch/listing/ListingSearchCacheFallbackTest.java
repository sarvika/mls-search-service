package com.sarvika.mlssearch.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behaviour when Redis is unreachable or misbehaving.
 *
 * <p>The cache is optional infrastructure: a Redis outage must degrade search to uncached
 * Postgres reads, never surface as an error. This is driven with a mocked
 * {@link RedisDataSource} rather than a real outage so the failure is instant and exact -
 * the equivalent was also confirmed by hand against a stopped {@code redis} container.
 */
class ListingSearchCacheFallbackTest {

    private static final ListingSummary A_LISTING = new ListingSummary(
            "abc-1", "1 Test Street", "Testville", "CA",
            new BigDecimal("500000.00"), 3, 2, 1, 34.05, -118.25);

    private RedisDataSource redis;
    private ListingSearchCache cache;

    @BeforeEach
    void setUp() {
        redis = mock(RedisDataSource.class);
        cache = new ListingSearchCache();
        cache.redis = redis;
        cache.objectMapper = new ObjectMapper();
    }

    /** Connection-level failure: even obtaining the command interface blows up. */
    private void redisIsUnreachable() {
        when(redis.value(any(Class.class))).thenThrow(new RuntimeException("Connection refused"));
    }

    @Test
    void readsMissInsteadOfFailingWhenRedisIsUnreachable() {
        redisIsUnreachable();

        assertThat(cache.get("listings:bbox:v0:whatever")).isNull();
    }

    @Test
    void writesAreSilentlyDroppedWhenRedisIsUnreachable() {
        redisIsUnreachable();

        assertThatCode(() -> cache.put("listings:bbox:v0:whatever", List.of(A_LISTING)))
                .doesNotThrowAnyException();
    }

    @Test
    void generationBumpDoesNotFailIngestionWhenRedisIsUnreachable() {
        redisIsUnreachable();

        // An ingestion run must still report success - the TTL is the safety net here.
        assertThatCode(cache::bumpGeneration).doesNotThrowAnyException();
    }

    @Test
    void keyBuildingFallsBackToGenerationZeroWhenTheCounterCannotBeRead() {
        redisIsUnreachable();

        assertThat(cache.bboxKey(-118.3, 34.0, -118.2, 34.1))
                .isEqualTo("listings:bbox:v0:-118.300:34.000:-118.200:34.100");
        assertThat(cache.radiusKey(34.05, -118.25, 8047))
                .isEqualTo("listings:radius:v0:34.050:-118.250:8000");
    }

    @Test
    void treatsAnAbsentGenerationCounterAsGenerationZero() {
        @SuppressWarnings("unchecked")
        ValueCommands<String, Long> longValues = mock(ValueCommands.class);
        when(redis.value(Long.class)).thenReturn(longValues);
        when(longValues.get("listings:gen")).thenReturn(null);

        assertThat(cache.bboxKey(-118.3, 34.0, -118.2, 34.1)).contains(":v0:");
    }

    @Test
    void readFailureOnTheCommandItselfAlsoDegradesToAMiss() {
        @SuppressWarnings("unchecked")
        ValueCommands<String, String> stringValues = mock(ValueCommands.class);
        when(redis.value(String.class)).thenReturn(stringValues);
        when(stringValues.get(anyString())).thenThrow(new RuntimeException("read timed out"));

        assertThat(cache.get("listings:bbox:v0:whatever")).isNull();
    }

    @Test
    void writeFailureOnTheCommandItselfIsSwallowed() {
        @SuppressWarnings("unchecked")
        ValueCommands<String, String> stringValues = mock(ValueCommands.class);
        when(redis.value(String.class)).thenReturn(stringValues);
        doThrow(new RuntimeException("OOM command not allowed"))
                .when(stringValues).setex(anyString(), anyLong(), anyString());

        assertThatCode(() -> cache.put("listings:bbox:v0:whatever", List.of(A_LISTING)))
                .doesNotThrowAnyException();
    }
}
