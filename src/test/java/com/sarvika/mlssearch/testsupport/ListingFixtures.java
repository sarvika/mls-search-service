package com.sarvika.mlssearch.testsupport;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Seeds {@code listings} rows directly through JDBC so search tests control exactly which
 * points exist, independent of the SimplyRETS feed.
 */
public final class ListingFixtures {

    private static final String INSERT_SQL = """
            INSERT INTO listings (originating_system_name, listing_key, mls_status, list_price,
                                  bedrooms_total, bathrooms_full, bathrooms_half,
                                  unparsed_address, city, state_or_province, postal_code,
                                  latitude, longitude, geog, media, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?::jsonb, ?)
            """;

    private ListingFixtures() {
    }

    public static Builder listing(String listingKey) {
        return new Builder(listingKey);
    }

    public static void deleteAllListings(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM listings");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear the listings table", e);
        }
    }

    public static long countListings(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM listings")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count listings", e);
        }
    }

    public static final class Builder {
        private final String listingKey;
        private String originatingSystemName = "test-mls";
        private String mlsStatus = "Active";
        private BigDecimal listPrice = new BigDecimal("500000.00");
        private Integer bedroomsTotal = 3;
        private Integer bathroomsFull = 2;
        private Integer bathroomsHalf = 1;
        private String unparsedAddress = "1 Test Street";
        private String city = "Testville";
        private String stateOrProvince = "CA";
        private String postalCode = "90001";
        private double latitude = 34.05;
        private double longitude = -118.25;
        private String media = "[]";
        private boolean active = true;

        private Builder(String listingKey) {
            this.listingKey = listingKey;
        }

        public Builder at(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
            return this;
        }

        public Builder inactive() {
            this.active = false;
            this.mlsStatus = "Sold";
            return this;
        }

        public Builder address(String unparsedAddress, String city, String stateOrProvince) {
            this.unparsedAddress = unparsedAddress;
            this.city = city;
            this.stateOrProvince = stateOrProvince;
            return this;
        }

        public Builder price(String listPrice) {
            this.listPrice = new BigDecimal(listPrice);
            return this;
        }

        public Builder rooms(Integer bedroomsTotal, Integer bathroomsFull, Integer bathroomsHalf) {
            this.bedroomsTotal = bedroomsTotal;
            this.bathroomsFull = bathroomsFull;
            this.bathroomsHalf = bathroomsHalf;
            return this;
        }

        public void insert(DataSource dataSource) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                int i = 1;
                statement.setString(i++, originatingSystemName);
                statement.setString(i++, listingKey);
                statement.setString(i++, mlsStatus);
                statement.setBigDecimal(i++, listPrice);
                statement.setObject(i++, bedroomsTotal);
                statement.setObject(i++, bathroomsFull);
                statement.setObject(i++, bathroomsHalf);
                statement.setString(i++, unparsedAddress);
                statement.setString(i++, city);
                statement.setString(i++, stateOrProvince);
                statement.setString(i++, postalCode);
                statement.setDouble(i++, latitude);
                statement.setDouble(i++, longitude);
                statement.setDouble(i++, longitude); // ST_MakePoint(lng, lat)
                statement.setDouble(i++, latitude);
                statement.setString(i++, media);
                statement.setBoolean(i, active);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to insert test listing " + listingKey, e);
            }
        }
    }
}
