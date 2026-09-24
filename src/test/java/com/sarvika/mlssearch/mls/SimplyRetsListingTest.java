package com.sarvika.mlssearch.mls;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Deserialization of the raw SimplyRETS wire format.
 *
 * <p>This record deliberately keeps SimplyRETS's own field names ({@code listingId},
 * {@code geo.lat}, {@code mls.status}, {@code property.bathsFull}) rather than RESO Data
 * Dictionary names, because that is what the demo API actually returns. These tests pin that
 * contract: renaming a component to its RESO equivalent silently produces null fields rather
 * than a failure, so a mapping test is the only thing that catches it.
 */
class SimplyRetsListingTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // Trimmed from a real GET https://api.simplyrets.com/properties response, including
    // fields the record does not model (privateRemarks, agent, geo.county, property.area...)
    // so the @JsonIgnoreProperties(ignoreUnknown = true) coverage is exercised too.
    private static final String WIRE_JSON = """
            {
              "privateRemarks": "Two-bedroom home with a great view.",
              "listDate": "2016-08-15T00:00:00.000Z",
              "listingId": "68554270",
              "listPrice": 4067000,
              "modified": "2017-01-10T04:00:00.000Z",
              "address": {
                "crossStreet": "",
                "state": "TX",
                "country": "US",
                "postalCode": "77018",
                "streetName": "Hialeah",
                "city": "Houston",
                "full": "74434 Hialeah Dr"
              },
              "agent": { "firstName": "Sherlock", "lastName": "Holmes" },
              "geo": { "county": "Harris", "lat": 29.821695, "marketArea": "9", "lng": -95.42855 },
              "mls": { "status": "Active", "area": "9", "daysOnMarket": 178, "statusText": "Active" },
              "property": {
                "bathsFull": 2,
                "bathsHalf": 1,
                "bedrooms": 4,
                "area": 2500,
                "type": "RES",
                "subType": "singlefamilyresidence"
              },
              "photos": [
                "https://s3.amazonaws.com/simplyrets/properties/tx/77018/68554270/1.jpg",
                "https://s3.amazonaws.com/simplyrets/properties/tx/77018/68554270/2.jpg"
              ]
            }
            """;

    @Test
    void deserializesTheSimplyRetsWireFormat() throws Exception {
        SimplyRetsListing listing = objectMapper.readValue(WIRE_JSON, SimplyRetsListing.class);

        assertThat(listing.listingId()).isEqualTo("68554270");
        assertThat(listing.listPrice()).isEqualTo(4067000.0);
        assertThat(listing.modified()).isEqualTo(Instant.parse("2017-01-10T04:00:00Z"));
        assertThat(listing.address()).isEqualTo(
                new SimplyRetsListing.Address("74434 Hialeah Dr", "Houston", "TX", "77018"));
        assertThat(listing.geo()).isEqualTo(new SimplyRetsListing.Geo(29.821695, -95.42855));
        assertThat(listing.mls()).isEqualTo(new SimplyRetsListing.Mls("Active"));
        assertThat(listing.property()).isEqualTo(new SimplyRetsListing.Property(4, 2, 1));
        assertThat(listing.photos()).containsExactly(
                "https://s3.amazonaws.com/simplyrets/properties/tx/77018/68554270/1.jpg",
                "https://s3.amazonaws.com/simplyrets/properties/tx/77018/68554270/2.jpg");
    }

    @Test
    void ignoresUnknownFieldsRatherThanFailing() {
        String json = """
                { "listingId": "1", "somethingSimplyRetsAddedLastWeek": { "nested": [1, 2, 3] } }
                """;

        assertThatCode(() -> objectMapper.readValue(json, SimplyRetsListing.class))
                .doesNotThrowAnyException();
    }

    @Test
    void leavesAbsentNestedObjectsNull() throws Exception {
        SimplyRetsListing listing = objectMapper.readValue("{\"listingId\":\"1\"}", SimplyRetsListing.class);

        assertThat(listing.listingId()).isEqualTo("1");
        assertThat(listing.address()).isNull();
        assertThat(listing.geo()).isNull();
        assertThat(listing.mls()).isNull();
        assertThat(listing.property()).isNull();
        assertThat(listing.photos()).isNull();
        assertThat(listing.listPrice()).isNull();
        assertThat(listing.modified()).isNull();
    }

    @Test
    void leavesExplicitNullsAndPartialNestedObjectsNull() throws Exception {
        String json = """
                {
                  "listingId": "1",
                  "listPrice": null,
                  "geo": { "lat": 29.8 },
                  "property": { "bedrooms": 3 },
                  "address": { "city": "Houston" }
                }
                """;

        SimplyRetsListing listing = objectMapper.readValue(json, SimplyRetsListing.class);

        assertThat(listing.listPrice()).isNull();
        assertThat(listing.geo().lat()).isEqualTo(29.8);
        assertThat(listing.geo().lng()).isNull();
        assertThat(listing.property()).isEqualTo(new SimplyRetsListing.Property(3, null, null));
        assertThat(listing.address().city()).isEqualTo("Houston");
        assertThat(listing.address().full()).isNull();
    }

    @Test
    void deserializesAListResponse() throws Exception {
        String json = "[" + WIRE_JSON + ", {\"listingId\":\"2\"}]";

        List<SimplyRetsListing> listings = objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, SimplyRetsListing.class));

        assertThat(listings).hasSize(2);
    }
}
