package com.sarvika.mlssearch.mls;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SimplyRetsListing(
        String listingId,
        Address address,
        Geo geo,
        Double listPrice,
        Mls mls,
        Property property,
        List<String> photos,
        Instant modified
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(String full, String city, String state, String postalCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Geo(Double lat, Double lng) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Mls(String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Property(Integer bedrooms, Integer bathsFull, Integer bathsHalf) {
    }
}
