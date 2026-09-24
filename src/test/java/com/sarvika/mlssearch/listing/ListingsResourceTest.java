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
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end coverage of the three geospatial search endpoints and the ingest trigger,
 * against a real PostGIS database.
 */
// Needs a live Docker daemon for the PostGIS/Redis Testcontainers PostgisRedisTestResource
// starts - run `./gradlew test -PexcludeDockerTests` in a CI environment whose job
// containers have no Docker daemon available.
@Tag("requires-docker")
@QuarkusTest
@WithTestResource(value = PostgisRedisTestResource.class, scope = TestResourceScope.GLOBAL)
class ListingsResourceTest {

    // A bounding box over downtown Los Angeles, and points inside/outside it.
    private static final double WEST = -118.30;
    private static final double SOUTH = 34.00;
    private static final double EAST = -118.20;
    private static final double NORTH = 34.10;
    private static final double INSIDE_LAT = 34.05;
    private static final double INSIDE_LNG = -118.25;

    private static final String LA_POLYGON = """
            {"type":"Polygon","coordinates":[[
              [-118.30, 34.00], [-118.20, 34.00], [-118.20, 34.10], [-118.30, 34.10], [-118.30, 34.00]
            ]]}
            """;

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

    // --- bounding box ---------------------------------------------------------------

    @Test
    void boundingBoxSearchReturnsOnlyListingsInsideTheViewport() {
        ListingFixtures.listing("inside").at(INSIDE_LAT, INSIDE_LNG).insert(dataSource);
        ListingFixtures.listing("west-of-box").at(INSIDE_LAT, -118.40).insert(dataSource);
        ListingFixtures.listing("north-of-box").at(34.20, INSIDE_LNG).insert(dataSource);
        ListingFixtures.listing("other-city").at(40.7128, -74.0060).insert(dataSource);

        searchBoundingBox()
                .statusCode(200)
                .body("listingKey", contains("inside"));
    }

    @Test
    void boundingBoxSearchExcludesSoftDeletedListings() {
        ListingFixtures.listing("active").at(INSIDE_LAT, INSIDE_LNG).insert(dataSource);
        ListingFixtures.listing("sold").at(INSIDE_LAT, INSIDE_LNG).inactive().insert(dataSource);

        searchBoundingBox().body("listingKey", contains("active"));
    }

    @Test
    void boundingBoxSearchIncludesListingsOnTheEdgeOfTheViewport() {
        ListingFixtures.listing("on-the-corner").at(SOUTH, WEST).insert(dataSource);
        ListingFixtures.listing("on-the-edge").at(NORTH, INSIDE_LNG).insert(dataSource);

        searchBoundingBox().body("listingKey", containsInAnyOrder("on-the-corner", "on-the-edge"));
    }

    @Test
    void boundingBoxSearchReturnsAnEmptyArrayWhenNothingMatches() {
        ListingFixtures.listing("other-city").at(40.7128, -74.0060).insert(dataSource);

        searchBoundingBox().statusCode(200).body("$", empty());
    }

    @Test
    void boundingBoxSearchReturnsTheResoNamedSummaryFields() {
        ListingFixtures.listing("summary-1")
                .at(INSIDE_LAT, INSIDE_LNG)
                .address("74434 Hialeah Dr", "Houston", "TX")
                .price("4067000.00")
                .rooms(4, 2, 1)
                .insert(dataSource);

        searchBoundingBox()
                .body("[0].listingKey", equalTo("summary-1"))
                .body("[0].unparsedAddress", equalTo("74434 Hialeah Dr"))
                .body("[0].city", equalTo("Houston"))
                .body("[0].stateOrProvince", equalTo("TX"))
                .body("[0].listPrice", is(4067000.00f))
                .body("[0].bedroomsTotal", equalTo(4))
                .body("[0].bathroomsFull", equalTo(2))
                .body("[0].bathroomsHalf", equalTo(1))
                // REST Assured deserializes JSON numbers as floats.
                .body("[0].latitude", is((float) INSIDE_LAT))
                .body("[0].longitude", is((float) INSIDE_LNG));
    }

    @Test
    void boundingBoxSearchToleratesListingsWithNullOptionalFields() {
        ListingFixtures.listing("sparse")
                .at(INSIDE_LAT, INSIDE_LNG)
                .rooms(null, null, null)
                .insert(dataSource);

        searchBoundingBox()
                .statusCode(200)
                .body("[0].bedroomsTotal", equalTo(null))
                .body("[0].bathroomsFull", equalTo(null))
                .body("[0].bathroomsHalf", equalTo(null));
    }

    @Test
    void boundingBoxSearchCapsResultsAtTwoHundred() {
        for (int i = 0; i < 205; i++) {
            ListingFixtures.listing("bulk-" + i)
                    .at(INSIDE_LAT + i * 0.0001, INSIDE_LNG)
                    .insert(dataSource);
        }

        searchBoundingBox().body("$", hasSize(200));
    }

    // --- radius ---------------------------------------------------------------------

    @Test
    void radiusSearchReturnsOnlyListingsWithinTheRadius() {
        ListingFixtures.listing("about-100m-away").at(INSIDE_LAT + 0.0009, INSIDE_LNG).insert(dataSource);
        ListingFixtures.listing("about-5km-away").at(INSIDE_LAT + 0.045, INSIDE_LNG).insert(dataSource);

        searchRadius(INSIDE_LAT, INSIDE_LNG, 1000)
                .statusCode(200)
                .body("listingKey", contains("about-100m-away"));
    }

    @Test
    void radiusSearchMeasuresRealGroundDistanceNotDegrees() {
        // 0.045 degrees is ~5km of latitude; a degree-based comparison would wrongly
        // treat it as "within 1000" of anything.
        ListingFixtures.listing("far").at(INSIDE_LAT + 0.045, INSIDE_LNG).insert(dataSource);

        searchRadius(INSIDE_LAT, INSIDE_LNG, 1000).body("$", empty());
        searchRadius(INSIDE_LAT, INSIDE_LNG, 6000).body("listingKey", contains("far"));
    }

    @Test
    void radiusSearchInterpretsTheCentreAsLatitudeAndLongitudeNotXAndY() {
        // The SQL binds ST_MakePoint(lng, lat); binding them the other way round would
        // place the centre thousands of kilometres away and return nothing.
        ListingFixtures.listing("nearby").at(INSIDE_LAT, INSIDE_LNG).insert(dataSource);

        searchRadius(INSIDE_LAT, INSIDE_LNG, 500).body("listingKey", contains("nearby"));
        searchRadius(INSIDE_LNG, INSIDE_LAT, 500).body("$", empty());
    }

    @Test
    void radiusSearchExcludesSoftDeletedListings() {
        ListingFixtures.listing("active").at(INSIDE_LAT, INSIDE_LNG).insert(dataSource);
        ListingFixtures.listing("sold").at(INSIDE_LAT, INSIDE_LNG).inactive().insert(dataSource);

        searchRadius(INSIDE_LAT, INSIDE_LNG, 1000).body("listingKey", contains("active"));
    }

    @Test
    void radiusSearchCapsResultsAtTwoHundred() {
        for (int i = 0; i < 205; i++) {
            ListingFixtures.listing("bulk-" + i)
                    .at(INSIDE_LAT + i * 0.00001, INSIDE_LNG)
                    .insert(dataSource);
        }

        searchRadius(INSIDE_LAT, INSIDE_LNG, 1000).body("$", hasSize(200));
    }

    // --- polygon ---------------------------------------------------------------------

    @Test
    void polygonSearchReturnsOnlyListingsInsideTheDrawnShape() {
        ListingFixtures.listing("inside").at(INSIDE_LAT, INSIDE_LNG).insert(dataSource);
        ListingFixtures.listing("outside").at(34.20, -118.40).insert(dataSource);

        searchPolygon(LA_POLYGON)
                .statusCode(200)
                .body("listingKey", contains("inside"));
    }

    @Test
    void polygonSearchExcludesSoftDeletedListings() {
        ListingFixtures.listing("active").at(INSIDE_LAT, INSIDE_LNG).insert(dataSource);
        ListingFixtures.listing("sold").at(INSIDE_LAT, INSIDE_LNG).inactive().insert(dataSource);

        searchPolygon(LA_POLYGON).body("listingKey", contains("active"));
    }

    @Test
    void polygonSearchHandlesAConcaveShape() {
        // An L-shape: a point in the notch must be excluded even though it is inside the
        // shape's bounding box - i.e. this is a real point-in-polygon test, not a bbox test.
        String lShape = """
                {"type":"Polygon","coordinates":[[
                  [-118.30, 34.00], [-118.20, 34.00], [-118.20, 34.05], [-118.25, 34.05],
                  [-118.25, 34.10], [-118.30, 34.10], [-118.30, 34.00]
                ]]}
                """;
        ListingFixtures.listing("in-the-arm").at(34.02, -118.22).insert(dataSource);
        ListingFixtures.listing("in-the-notch").at(34.08, -118.22).insert(dataSource);

        searchPolygon(lShape).body("listingKey", contains("in-the-arm"));
    }

    @Test
    void polygonSearchCapsResultsAtTwoHundred() {
        for (int i = 0; i < 205; i++) {
            ListingFixtures.listing("bulk-" + i)
                    .at(INSIDE_LAT + i * 0.0001, INSIDE_LNG)
                    .insert(dataSource);
        }

        searchPolygon(LA_POLYGON).body("$", hasSize(200));
    }

    // --- ingest trigger ----------------------------------------------------------------

    @Test
    void ingestEndpointReportsHowManyListingsWereStored() {
        when(simplyRetsClient.listProperties(anyInt())).thenReturn(List.of(
                feedListing("ingest-1", INSIDE_LAT, INSIDE_LNG),
                feedListing("ingest-2", INSIDE_LAT, INSIDE_LNG)));

        given().when().post("/listings/ingest/simplyrets?limit=25")
                .then().statusCode(200).body("ingested", equalTo(2));

        assertThatListingCountIs(2);
    }

    @Test
    void ingestEndpointDefaultsTheLimitToTwentyFive() {
        when(simplyRetsClient.listProperties(anyInt())).thenReturn(List.of());

        given().when().post("/listings/ingest/simplyrets")
                .then().statusCode(200).body("ingested", equalTo(0));

        verify(simplyRetsClient).listProperties(25);
    }

    // --- caching ------------------------------------------------------------------------

    @Test
    void repeatedBoundingBoxSearchesAreServedFromTheCache() {
        ListingFixtures.listing("cached").at(INSIDE_LAT, INSIDE_LNG).insert(dataSource);
        searchBoundingBox().body("listingKey", contains("cached"));

        ListingFixtures.deleteAllListings(dataSource);

        // The row is gone from Postgres, so a second identical viewport can only still
        // return it if it came out of Redis.
        searchBoundingBox().body("listingKey", contains("cached"));
    }

    @Test
    void repeatedRadiusSearchesAreServedFromTheCache() {
        ListingFixtures.listing("cached").at(INSIDE_LAT, INSIDE_LNG).insert(dataSource);
        searchRadius(INSIDE_LAT, INSIDE_LNG, 1000).body("listingKey", contains("cached"));

        ListingFixtures.deleteAllListings(dataSource);

        searchRadius(INSIDE_LAT, INSIDE_LNG, 1000).body("listingKey", contains("cached"));
    }

    @Test
    void nearIdenticalViewportsShareACachedResult() {
        ListingFixtures.listing("cached").at(INSIDE_LAT, INSIDE_LNG).insert(dataSource);
        searchBoundingBox().body("listingKey", contains("cached"));

        ListingFixtures.deleteAllListings(dataSource);

        // A pan of a fraction of a metre - bucketed to the same key, so still a hit.
        searchBoundingBox(WEST + 0.00002, SOUTH - 0.00003, EAST + 0.00001, NORTH - 0.00002)
                .body("listingKey", contains("cached"));
    }

    @Test
    void polygonSearchesAreDeliberatelyNotCached() {
        ListingFixtures.listing("drawn").at(INSIDE_LAT, INSIDE_LNG).insert(dataSource);
        searchPolygon(LA_POLYGON).body("listingKey", contains("drawn"));

        ListingFixtures.deleteAllListings(dataSource);

        searchPolygon(LA_POLYGON).body("$", empty());
    }

    @Test
    void ingestionInvalidatesPreviouslyCachedSearchResults() {
        ListingFixtures.listing("stale").at(INSIDE_LAT, INSIDE_LNG).insert(dataSource);
        searchBoundingBox().body("listingKey", contains("stale"));

        ListingFixtures.deleteAllListings(dataSource);
        when(simplyRetsClient.listProperties(anyInt()))
                .thenReturn(List.of(feedListing("elsewhere", 40.7128, -74.0060)));
        given().when().post("/listings/ingest/simplyrets").then().statusCode(200);

        // The generation counter moved, so the old key is unreachable and the viewport
        // is re-read from Postgres - where the stale listing no longer exists.
        searchBoundingBox().body("$", empty());
    }

    // --- helpers ---------------------------------------------------------------------------

    private io.restassured.response.ValidatableResponse searchBoundingBox() {
        return searchBoundingBox(WEST, SOUTH, EAST, NORTH);
    }

    private io.restassured.response.ValidatableResponse searchBoundingBox(
            double west, double south, double east, double north) {
        return given()
                .queryParam("west", west)
                .queryParam("south", south)
                .queryParam("east", east)
                .queryParam("north", north)
                .when().get("/listings")
                .then().statusCode(200);
    }

    private io.restassured.response.ValidatableResponse searchRadius(
            double lat, double lng, double radiusMeters) {
        return given()
                .queryParam("lat", lat)
                .queryParam("lng", lng)
                .queryParam("radiusMeters", radiusMeters)
                .when().get("/listings/radius")
                .then().statusCode(200);
    }

    private io.restassured.response.ValidatableResponse searchPolygon(String geoJson) {
        return given()
                .contentType(ContentType.JSON)
                .body(geoJson)
                .when().post("/listings/polygon")
                .then().statusCode(200);
    }

    private void assertThatListingCountIs(long expected) {
        org.assertj.core.api.Assertions.assertThat(ListingFixtures.countListings(dataSource))
                .isEqualTo(expected);
    }

    private static SimplyRetsListing feedListing(String listingId, double lat, double lng) {
        return new SimplyRetsListing(
                listingId,
                new SimplyRetsListing.Address("1 Test Street", "Testville", "CA", "90001"),
                new SimplyRetsListing.Geo(lat, lng),
                500000.0,
                new SimplyRetsListing.Mls("Active"),
                new SimplyRetsListing.Property(3, 2, 1),
                List.of(),
                Instant.parse("2024-01-01T00:00:00Z"));
    }
}
