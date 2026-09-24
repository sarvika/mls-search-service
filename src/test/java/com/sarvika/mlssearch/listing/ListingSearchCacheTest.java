package com.sarvika.mlssearch.listing;

import com.sarvika.mlssearch.testsupport.PostgisRedisTestResource;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Needs a live Docker daemon for the PostGIS/Redis Testcontainers PostgisRedisTestResource
// starts - run `./gradlew test -PexcludeDockerTests` in a CI environment whose job
// containers have no Docker daemon available.
@Tag("requires-docker")
@QuarkusTest
@WithTestResource(value = PostgisRedisTestResource.class, scope = TestResourceScope.GLOBAL)
class ListingSearchCacheTest {

    private static final ListingSummary A_LISTING = new ListingSummary(
            "abc-1", "1 Test Street", "Testville", "CA",
            new BigDecimal("500000.00"), 3, 2, 1, 34.05, -118.25);

    @Inject
    ListingSearchCache cache;

    @Inject
    RedisDataSource redis;

    @BeforeEach
    void flushRedis() {
        redis.flushall();
    }

    // --- key bucketing -------------------------------------------------------------

    @Test
    void bboxKeyRoundsCoordinatesToAThreeDecimalGrid() {
        String key = cache.bboxKey(-118.2512345, 34.0501111, -118.2004999, 34.1000004);

        assertThat(key).isEqualTo("listings:bbox:v0:-118.251:34.050:-118.200:34.100");
    }

    @Test
    void bboxKeyIsStableAcrossTinyMapMovements() {
        // A pan of ~1m - the whole point of bucketing is that this still hits the cache.
        String before = cache.bboxKey(-118.2510, 34.0500, -118.2000, 34.1000);
        String after = cache.bboxKey(-118.25104, 34.05002, -118.20001, 34.09999);

        assertThat(after).isEqualTo(before);
    }

    @Test
    void bboxKeyDiffersOnceTheViewportMovesPastABucket() {
        String here = cache.bboxKey(-118.2510, 34.0500, -118.2000, 34.1000);
        String there = cache.bboxKey(-118.2560, 34.0550, -118.2050, 34.1050);

        assertThat(there).isNotEqualTo(here);
    }

    @Test
    void bboxKeyNormalizesNegativeZeroSoTheEquatorAndPrimeMeridianBucketTogether() {
        // -0.00004 and 0.00004 are ~4m apart and belong in the same bucket, but plain
        // String.format keeps the sign, yielding "-0.000" vs "0.000".
        String justSouth = cache.bboxKey(-0.00004, -0.00004, -0.00004, -0.00004);
        String justNorth = cache.bboxKey(0.00004, 0.00004, 0.00004, 0.00004);

        assertThat(justSouth).isEqualTo(justNorth);
        assertThat(justSouth).doesNotContain("-0.000");
    }

    @Test
    void radiusKeyBucketsCoordinatesAndRadius() {
        String key = cache.radiusKey(34.0501111, -118.2512345, 8047);

        assertThat(key).isEqualTo("listings:radius:v0:34.050:-118.251:8000");
    }

    @Test
    void radiusKeyRoundsTheRadiusToTheNearest500Metres() {
        // 5 miles (8047m) and a slightly different client-side conversion must share a key.
        assertThat(cache.radiusKey(34.05, -118.25, 8047))
                .isEqualTo(cache.radiusKey(34.05, -118.25, 8100));

        assertThat(cache.radiusKey(34.05, -118.25, 8300)).endsWith(":8500");
        assertThat(cache.radiusKey(34.05, -118.25, 250)).endsWith(":500");
        assertThat(cache.radiusKey(34.05, -118.25, 200)).endsWith(":0");
    }

    @Test
    void radiusKeyDiffersOnceTheRadiusCrossesABucket() {
        assertThat(cache.radiusKey(34.05, -118.25, 8047))
                .isNotEqualTo(cache.radiusKey(34.05, -118.25, 16094));
    }

    // --- read/write ----------------------------------------------------------------

    @Test
    void getReturnsNullForAMissingKey() {
        assertThat(cache.get("listings:bbox:v0:nothing-here")).isNull();
    }

    @Test
    void putThenGetRoundTripsTheListings() {
        String key = cache.bboxKey(-118.3, 34.0, -118.2, 34.1);

        cache.put(key, List.of(A_LISTING));

        assertThat(cache.get(key)).containsExactly(A_LISTING);
    }

    @Test
    void putThenGetPreservesNullableFields() {
        String key = cache.bboxKey(-118.3, 34.0, -118.2, 34.1);
        ListingSummary sparse = new ListingSummary(
                "abc-2", null, null, null, null, null, null, null, 0.0, 0.0);

        cache.put(key, List.of(sparse));

        assertThat(cache.get(key)).containsExactly(sparse);
    }

    @Test
    void cachesAnEmptyResultAsEmptyRatherThanAsAMiss() {
        String key = cache.bboxKey(-118.3, 34.0, -118.2, 34.1);

        cache.put(key, List.of());

        // Distinguishing "we know this viewport is empty" from "not cached" is what stops an
        // empty viewport from re-querying Postgres on every pan.
        assertThat(cache.get(key)).isNotNull().isEmpty();
    }

    @Test
    void writesExpireSoAMissedInvalidationCannotStickForever() {
        String key = cache.bboxKey(-118.3, 34.0, -118.2, 34.1);

        cache.put(key, List.of(A_LISTING));

        assertThat(redis.key().ttl(key)).isBetween(1L, 300L);
    }

    @Test
    void getFallsBackToAMissWhenTheCachedPayloadIsUnreadable() {
        String key = cache.bboxKey(-118.3, 34.0, -118.2, 34.1);
        redis.value(String.class).set(key, "this is not json");

        // A corrupt entry must degrade to a database read, not a 500.
        assertThat(cache.get(key)).isNull();
    }

    // --- generation-counter invalidation --------------------------------------------

    @Test
    void bumpingTheGenerationMakesEveryPreviouslyCachedKeyUnreachable() {
        String bboxKey = cache.bboxKey(-118.3, 34.0, -118.2, 34.1);
        String radiusKey = cache.radiusKey(34.05, -118.25, 8047);
        cache.put(bboxKey, List.of(A_LISTING));
        cache.put(radiusKey, List.of(A_LISTING));

        cache.bumpGeneration();

        assertThat(cache.bboxKey(-118.3, 34.0, -118.2, 34.1)).isNotEqualTo(bboxKey);
        assertThat(cache.radiusKey(34.05, -118.25, 8047)).isNotEqualTo(radiusKey);
        assertThat(cache.get(cache.bboxKey(-118.3, 34.0, -118.2, 34.1))).isNull();
        assertThat(cache.get(cache.radiusKey(34.05, -118.25, 8047))).isNull();
    }

    @Test
    void generationIsEmbeddedInTheKeyAndAdvancesOneStepAtATime() {
        assertThat(cache.bboxKey(-118.3, 34.0, -118.2, 34.1)).contains(":v0:");

        cache.bumpGeneration();
        assertThat(cache.bboxKey(-118.3, 34.0, -118.2, 34.1)).contains(":v1:");

        cache.bumpGeneration();
        assertThat(cache.bboxKey(-118.3, 34.0, -118.2, 34.1)).contains(":v2:");
    }

    @Test
    void bumpingTheGenerationDoesNotDisturbOtherGenerationsEntries() {
        String oldKey = cache.bboxKey(-118.3, 34.0, -118.2, 34.1);
        cache.put(oldKey, List.of(A_LISTING));

        cache.bumpGeneration();
        String newKey = cache.bboxKey(-118.3, 34.0, -118.2, 34.1);
        cache.put(newKey, List.of());

        // The stale entry is simply unreachable (and TTLs out); it is never scanned or deleted.
        assertThat(cache.get(oldKey)).containsExactly(A_LISTING);
        assertThat(cache.get(newKey)).isEmpty();
    }
}
