package com.sarvika.mlssearch.listing;

import com.sarvika.mlssearch.mls.SimplyRetsClient;
import com.sarvika.mlssearch.mls.SimplyRetsListing;
import com.sarvika.mlssearch.testsupport.ListingFixtures;
import com.sarvika.mlssearch.testsupport.PostgisRedisTestResource;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Needs a live Docker daemon for the PostGIS/Redis Testcontainers PostgisRedisTestResource
// starts - run `./gradlew test -PexcludeDockerTests` in a CI environment whose job
// containers have no Docker daemon available.
@Tag("requires-docker")
@QuarkusTest
@WithTestResource(value = PostgisRedisTestResource.class, scope = TestResourceScope.GLOBAL)
class ListingIngestionServiceTest {

    private static final String ORIGINATING_SYSTEM_NAME = "simplyrets-demo";

    @Inject
    ListingIngestionService ingestionService;

    @Inject
    DataSource dataSource;

    @Inject
    RedisDataSource redis;

    @InjectMock
    @RestClient
    SimplyRetsClient simplyRetsClient;

    @BeforeEach
    void resetState() {
        ListingFixtures.deleteAllListings(dataSource);
        redis.flushall();
    }

    // --- mapping -------------------------------------------------------------------

    @Test
    void mapsAListingOntoTheResoNamedColumns() {
        feedReturns(listing("68554270")
                .price(4067000.0)
                .status("Active")
                .address("74434 Hialeah Dr", "Houston", "TX", "77018")
                .rooms(4, 2, 1)
                .at(29.821695, -95.42855)
                .photos(List.of("https://example.test/1.jpg", "https://example.test/2.jpg"))
                .modified(Instant.parse("2017-01-10T04:00:00Z"))
                .build());

        assertThat(ingestionService.syncFromSimplyRets(25)).isEqualTo(1);

        Map<String, Object> row = loadRow("68554270");
        assertThat(row.get("originating_system_name")).isEqualTo(ORIGINATING_SYSTEM_NAME);
        assertThat(row.get("listing_key")).isEqualTo("68554270");
        assertThat(row.get("mls_status")).isEqualTo("Active");
        assertThat(((Number) row.get("list_price")).doubleValue()).isEqualTo(4067000.0);
        assertThat(((Number) row.get("bedrooms_total")).intValue()).isEqualTo(4);
        assertThat(((Number) row.get("bathrooms_full")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("bathrooms_half")).intValue()).isEqualTo(1);
        assertThat(row.get("unparsed_address")).isEqualTo("74434 Hialeah Dr");
        assertThat(row.get("city")).isEqualTo("Houston");
        assertThat(row.get("state_or_province")).isEqualTo("TX");
        assertThat(row.get("postal_code")).isEqualTo("77018");
        assertThat((Double) row.get("latitude")).isEqualTo(29.821695);
        assertThat((Double) row.get("longitude")).isEqualTo(-95.42855);
        assertThat(row.get("is_active")).isEqualTo(true);
        assertThat((String) row.get("media"))
                .contains("https://example.test/1.jpg", "https://example.test/2.jpg");
        assertThat(((OffsetDateTime) row.get("modification_timestamp")).toInstant())
                .isEqualTo(Instant.parse("2017-01-10T04:00:00Z"));
    }

    @Test
    void buildsTheGeographyPointFromLongitudeThenLatitude() {
        // ST_MakePoint takes (x, y) = (lng, lat). Swapping them puts Houston in the ocean
        // off Somalia and every bbox/radius search silently returns nothing.
        feedReturns(listing("geo-1").at(29.821695, -95.42855).build());

        ingestionService.syncFromSimplyRets(25);

        Map<String, Object> point = queryOne("""
                SELECT ST_X(geog::geometry) AS x, ST_Y(geog::geometry) AS y, ST_SRID(geog::geometry) AS srid
                FROM listings WHERE listing_key = 'geo-1'
                """);
        assertThat((Double) point.get("x")).isEqualTo(-95.42855);
        assertThat((Double) point.get("y")).isEqualTo(29.821695);
        assertThat(((Number) point.get("srid")).intValue()).isEqualTo(4326);
    }

    @Test
    void storesAnEmptyMediaArrayWhenThereAreNoPhotos() {
        feedReturns(listing("no-photos").photos(null).build());

        ingestionService.syncFromSimplyRets(25);

        assertThat(loadRow("no-photos").get("media")).isEqualTo("[]");
    }

    @Test
    void defaultsTheModificationTimestampToNowWhenTheFeedOmitsIt() {
        feedReturns(listing("no-modified").modified(null).build());
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);

        ingestionService.syncFromSimplyRets(25);

        OffsetDateTime stored = (OffsetDateTime) loadRow("no-modified").get("modification_timestamp");
        assertThat(stored.toInstant()).isAfter(before);
    }

    // --- is_active / mls_status -----------------------------------------------------

    @Test
    void marksOnlyActiveListingsAsActive() {
        feedReturns(
                listing("s-active").status("Active").build(),
                listing("s-lowercase").status("active").build(),
                listing("s-pending").status("Pending").build(),
                listing("s-sold").status("Sold").build());

        ingestionService.syncFromSimplyRets(25);

        assertThat(loadRow("s-active").get("is_active")).isEqualTo(true);
        assertThat(loadRow("s-lowercase").get("is_active")).isEqualTo(true);
        assertThat(loadRow("s-pending").get("is_active")).isEqualTo(false);
        assertThat(loadRow("s-sold").get("is_active")).isEqualTo(false);
    }

    @Test
    void passesTheSourceStatusThroughUnnormalized() {
        // mls_status is RESO's *raw source* status, not the constrained StandardStatus
        // lookup - a value outside that lookup must survive ingestion untouched.
        feedReturns(listing("s-odd").status("Active Contingent - Kick Out").build());

        ingestionService.syncFromSimplyRets(25);

        assertThat(loadRow("s-odd").get("mls_status")).isEqualTo("Active Contingent - Kick Out");
        assertThat(loadRow("s-odd").get("is_active")).isEqualTo(false);
    }

    @Test
    void defaultsAMissingStatusBlockToUnknownAndInactive() {
        feedReturns(listing("s-null").withoutMls().build());

        ingestionService.syncFromSimplyRets(25);

        assertThat(loadRow("s-null").get("mls_status")).isEqualTo("Unknown");
        assertThat(loadRow("s-null").get("is_active")).isEqualTo(false);
    }

    @Test
    void toleratesAMissingPropertyAndAddressBlock() {
        feedReturns(listing("sparse").withoutProperty().withoutAddress().build());

        assertThat(ingestionService.syncFromSimplyRets(25)).isEqualTo(1);

        Map<String, Object> row = loadRow("sparse");
        assertThat(row.get("bedrooms_total")).isNull();
        assertThat(row.get("bathrooms_full")).isNull();
        assertThat(row.get("unparsed_address")).isNull();
        assertThat(row.get("city")).isNull();
    }

    // --- skipping -------------------------------------------------------------------

    @Test
    void skipsListingsMissingAnIdOrCoordinatesWithoutFailingTheBatch() {
        feedReturns(
                listing(null).build(),
                listing("no-geo").withoutGeo().build(),
                listing("no-lat").at(null, -95.4).build(),
                listing("no-lng").at(29.8, null).build(),
                listing("good").build());

        assertThat(ingestionService.syncFromSimplyRets(25)).isEqualTo(1);
        assertThat(ListingFixtures.countListings(dataSource)).isEqualTo(1);
        assertThat(loadRow("good")).isNotNull();
    }

    @Test
    void returnsZeroForAnEmptyFeed() {
        feedReturns();

        assertThat(ingestionService.syncFromSimplyRets(25)).isZero();
        assertThat(ListingFixtures.countListings(dataSource)).isZero();
    }

    // --- upsert ---------------------------------------------------------------------

    @Test
    void reSyncingTheSameListingUpdatesItInPlace() {
        feedReturns(listing("dup-1").price(500000.0).status("Active").at(29.8, -95.4).build());
        ingestionService.syncFromSimplyRets(25);

        feedReturns(listing("dup-1").price(450000.0).status("Pending").at(29.9, -95.5).build());
        ingestionService.syncFromSimplyRets(25);

        assertThat(ListingFixtures.countListings(dataSource)).isEqualTo(1);
        Map<String, Object> row = loadRow("dup-1");
        assertThat(((Number) row.get("list_price")).doubleValue()).isEqualTo(450000.0);
        assertThat(row.get("mls_status")).isEqualTo("Pending");
        assertThat(row.get("is_active")).isEqualTo(false);
        assertThat((Double) row.get("latitude")).isEqualTo(29.9);
        assertThat((Double) point("dup-1", "ST_X")).isEqualTo(-95.5);
    }

    @Test
    void aListingKeyIsOnlyUniqueWithinItsOriginatingSystem() {
        // The conflict target is (originating_system_name, listing_key), so the same key
        // arriving from a different MLS must coexist rather than overwrite.
        ListingFixtures.listing("68554270").at(29.8, -95.4).insert(dataSource);
        feedReturns(listing("68554270").at(29.8, -95.4).build());

        ingestionService.syncFromSimplyRets(25);

        assertThat(ListingFixtures.countListings(dataSource)).isEqualTo(2);
    }

    // --- cache invalidation -----------------------------------------------------------

    @Test
    void bumpsTheCacheGenerationAfterASuccessfulBatch() {
        feedReturns(listing("gen-1").build());

        ingestionService.syncFromSimplyRets(25);

        assertThat(redis.value(Long.class).get("listings:gen")).isEqualTo(1L);
    }

    @Test
    void leavesTheCacheGenerationAloneWhenNothingWasIngested() {
        // A no-op poll must not needlessly cold every cached viewport.
        feedReturns(listing(null).build(), listing("skipped").withoutGeo().build());

        assertThat(ingestionService.syncFromSimplyRets(25)).isZero();
        assertThat(redis.value(Long.class).get("listings:gen")).isNull();
    }

    // --- client interaction -------------------------------------------------------------

    @Test
    void passesTheRequestedLimitThroughToTheFeed() {
        feedReturns();

        ingestionService.syncFromSimplyRets(7);

        verify(simplyRetsClient).listProperties(7);
    }

    // --- helpers -----------------------------------------------------------------------

    private void feedReturns(SimplyRetsListing... listings) {
        when(simplyRetsClient.listProperties(anyInt())).thenReturn(List.of(listings));
    }

    private static Builder listing(String listingId) {
        return new Builder(listingId);
    }

    /** Mutable builder for the upstream wire record, which has no defaults of its own. */
    private static final class Builder {
        private final String listingId;
        private SimplyRetsListing.Address address =
                new SimplyRetsListing.Address("1 Test Street", "Testville", "CA", "90001");
        private SimplyRetsListing.Geo geo = new SimplyRetsListing.Geo(34.05, -118.25);
        private SimplyRetsListing.Mls mls = new SimplyRetsListing.Mls("Active");
        private SimplyRetsListing.Property property = new SimplyRetsListing.Property(3, 2, 1);
        private Double listPrice = 500000.0;
        private List<String> photos = List.of("https://example.test/1.jpg");
        private Instant modified = Instant.parse("2024-01-01T00:00:00Z");

        private Builder(String listingId) {
            this.listingId = listingId;
        }

        Builder at(Double lat, Double lng) {
            this.geo = new SimplyRetsListing.Geo(lat, lng);
            return this;
        }

        Builder withoutGeo() {
            this.geo = null;
            return this;
        }

        Builder status(String status) {
            this.mls = new SimplyRetsListing.Mls(status);
            return this;
        }

        Builder withoutMls() {
            this.mls = null;
            return this;
        }

        Builder rooms(Integer bedrooms, Integer bathsFull, Integer bathsHalf) {
            this.property = new SimplyRetsListing.Property(bedrooms, bathsFull, bathsHalf);
            return this;
        }

        Builder withoutProperty() {
            this.property = null;
            return this;
        }

        Builder address(String full, String city, String state, String postalCode) {
            this.address = new SimplyRetsListing.Address(full, city, state, postalCode);
            return this;
        }

        Builder withoutAddress() {
            this.address = null;
            return this;
        }

        Builder price(Double listPrice) {
            this.listPrice = listPrice;
            return this;
        }

        Builder photos(List<String> photos) {
            this.photos = photos;
            return this;
        }

        Builder modified(Instant modified) {
            this.modified = modified;
            return this;
        }

        SimplyRetsListing build() {
            return new SimplyRetsListing(listingId, address, geo, listPrice, mls, property, photos, modified);
        }
    }

    private Map<String, Object> loadRow(String listingKey) {
        return queryOne("SELECT * FROM listings WHERE listing_key = '" + listingKey + "'");
    }

    private Double point(String listingKey, String function) {
        Map<String, Object> row = queryOne(
                "SELECT " + function + "(geog::geometry) AS v FROM listings WHERE listing_key = '"
                        + listingKey + "'");
        return (Double) row.get("v");
    }

    private Map<String, Object> queryOne(String sql) {
        List<Map<String, Object>> rows = query(sql);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private List<Map<String, Object>> query(String sql) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    String label = rs.getMetaData().getColumnLabel(i);
                    Object value = "modification_timestamp".equals(label) || "created_at".equals(label)
                            ? rs.getObject(i, OffsetDateTime.class)
                            : rs.getObject(i);
                    // jsonb comes back as a PGobject; unwrap it so assertions read naturally.
                    if (value instanceof PGobject pgObject) {
                        value = pgObject.getValue();
                    }
                    row.put(label, value);
                }
                rows.add(Collections.unmodifiableMap(row));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Test query failed: " + sql, e);
        }
        return rows;
    }
}
