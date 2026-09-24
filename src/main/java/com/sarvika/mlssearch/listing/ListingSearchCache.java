package com.sarvika.mlssearch.listing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Redis-backed cache for bbox/radius search results. Coordinates are bucketed (rounded
 * to a grid) so repeat pans/zooms over roughly the same area actually hit - caching on
 * raw double query params would almost never hit since they change on every pixel of
 * map movement. A generation counter embedded in every key gives O(1) invalidation on
 * ingest (no SCAN/pattern-delete needed), with a TTL as a safety net. Every Redis call
 * is caught and logged rather than propagated - a cache outage must never break search,
 * only make it fall through to Postgres uncached.
 */
@ApplicationScoped
public class ListingSearchCache {

    private static final Logger LOG = Logger.getLogger(ListingSearchCache.class);
    private static final String GENERATION_KEY = "listings:gen";
    private static final long TTL_SECONDS = 300;
    private static final TypeReference<List<ListingSummary>> LISTING_LIST_TYPE = new TypeReference<>() {
    };

    @Inject
    RedisDataSource redis;

    @Inject
    ObjectMapper objectMapper;

    public String bboxKey(double west, double south, double east, double north) {
        return "listings:bbox:v%d:%s:%s:%s:%s".formatted(
                currentGeneration(), bucket(west), bucket(south), bucket(east), bucket(north));
    }

    public String radiusKey(double lat, double lng, double radiusMeters) {
        return "listings:radius:v%d:%s:%s:%s".formatted(
                currentGeneration(), bucket(lat), bucket(lng), bucketRadius(radiusMeters));
    }

    public List<ListingSummary> get(String key) {
        try {
            String json = values().get(key);
            return json != null ? objectMapper.readValue(json, LISTING_LIST_TYPE) : null;
        } catch (Exception e) {
            LOG.warnf(e, "Redis cache read failed for %s - falling back to the database", key);
            return null;
        }
    }

    public void put(String key, List<ListingSummary> value) {
        try {
            values().setex(key, TTL_SECONDS, objectMapper.writeValueAsString(value));
        } catch (Exception e) {
            LOG.warnf(e, "Redis cache write failed for %s - result was still served, just not cached", key);
        }
    }

    /** Called after a successful ingestion batch so every previously cached search result is invalidated. */
    public void bumpGeneration() {
        try {
            redis.value(Long.class).incr(GENERATION_KEY);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to bump the search cache generation - stale results may be served until TTL expiry");
        }
    }

    private long currentGeneration() {
        try {
            Long gen = redis.value(Long.class).get(GENERATION_KEY);
            return gen != null ? gen : 0L;
        } catch (Exception e) {
            LOG.warnf(e, "Failed to read the search cache generation - treating as un-generationed (0)");
            return 0L;
        }
    }

    private ValueCommands<String, String> values() {
        return redis.value(String.class);
    }

    // ~110m grid at the equator (3 decimal places) - fine enough for map-search reuse,
    // coarse enough to make repeat pans over roughly the same area actually hit.
    // "-0.000" is normalized to "0.000" - String.format keeps the sign of a negative
    // value that rounds to zero magnitude, so without this a request just south of the
    // equator (e.g. -0.00004) and one just north of it (0.00004) would otherwise land
    // in what should be the same bucket but get different cache keys.
    private static String bucket(double value) {
        String formatted = String.format(Locale.ROOT, "%.3f", value);
        return formatted.equals("-0.000") ? "0.000" : formatted;
    }

    // Buckets to the nearest 500m so nearby radius values (e.g. 8047m for "5 miles")
    // still land on the same cache key.
    private static String bucketRadius(double radiusMeters) {
        return String.valueOf(Math.round(radiusMeters / 500.0) * 500);
    }
}
