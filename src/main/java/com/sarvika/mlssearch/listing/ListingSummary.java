package com.sarvika.mlssearch.listing;

import java.math.BigDecimal;

public record ListingSummary(
        String listingKey,
        String unparsedAddress,
        String city,
        String stateOrProvince,
        BigDecimal listPrice,
        Integer bedroomsTotal,
        Integer bathroomsFull,
        Integer bathroomsHalf,
        double latitude,
        double longitude
) {
}
