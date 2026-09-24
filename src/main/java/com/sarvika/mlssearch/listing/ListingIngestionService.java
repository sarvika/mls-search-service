package com.sarvika.mlssearch.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sarvika.mlssearch.mls.SimplyRetsClient;
import com.sarvika.mlssearch.mls.SimplyRetsListing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@ApplicationScoped
public class ListingIngestionService {

    private static final Logger LOG = Logger.getLogger(ListingIngestionService.class);

    private static final String ORIGINATING_SYSTEM_NAME = "simplyrets-demo";

    private static final String UPSERT_SQL = """
            INSERT INTO listings (originating_system_name, listing_key, mls_status, list_price,
                                   bedrooms_total, bathrooms_full, bathrooms_half,
                                   unparsed_address, city, state_or_province, postal_code,
                                   latitude, longitude, geog, media, is_active, modification_timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    ?::jsonb, ?, ?)
            ON CONFLICT (originating_system_name, listing_key) DO UPDATE SET
                mls_status = EXCLUDED.mls_status,
                list_price = EXCLUDED.list_price,
                bedrooms_total = EXCLUDED.bedrooms_total,
                bathrooms_full = EXCLUDED.bathrooms_full,
                bathrooms_half = EXCLUDED.bathrooms_half,
                unparsed_address = EXCLUDED.unparsed_address,
                city = EXCLUDED.city,
                state_or_province = EXCLUDED.state_or_province,
                postal_code = EXCLUDED.postal_code,
                latitude = EXCLUDED.latitude,
                longitude = EXCLUDED.longitude,
                geog = EXCLUDED.geog,
                media = EXCLUDED.media,
                is_active = EXCLUDED.is_active,
                modification_timestamp = EXCLUDED.modification_timestamp
            """;

    @Inject
    DataSource dataSource;

    @Inject
    @RestClient
    SimplyRetsClient simplyRetsClient;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ListingSearchCache cache;

    /**
     * Pulls up to {@code limit} listings from the SimplyRETS demo feed and upserts them into
     * PostGIS. Standin for real RESO Web API incremental polling (§ ingestion pattern) until
     * MLS Grid credentials are available - same upsert/soft-delete shape, different source.
     */
    public int syncFromSimplyRets(int limit) {
        List<SimplyRetsListing> batch = simplyRetsClient.listProperties(limit);
        int ingested = 0;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {

            for (SimplyRetsListing listing : batch) {
                if (listing.listingId() == null || listing.geo() == null
                        || listing.geo().lat() == null || listing.geo().lng() == null) {
                    LOG.warnf("Skipping listing %s - missing id or coordinates", listing.listingId());
                    continue;
                }

                // Direct passthrough of the source's raw status - no normalization/mapping to
                // RESO's constrained StandardStatus lookup happens here, so this is RESO's
                // MlsStatus concept ("a local or regional status... must map to a single
                // StandardStatus"), not StandardStatus itself.
                String mlsStatus = listing.mls() != null ? listing.mls().status() : "Unknown";
                boolean active = "Active".equalsIgnoreCase(mlsStatus);
                SimplyRetsListing.Property property = listing.property();
                Integer bedroomsTotal = property != null ? property.bedrooms() : null;
                Integer bathroomsFull = property != null ? property.bathsFull() : null;
                Integer bathroomsHalf = property != null ? property.bathsHalf() : null;
                double lat = listing.geo().lat();
                double lng = listing.geo().lng();

                int i = 1;
                statement.setString(i++, ORIGINATING_SYSTEM_NAME);
                statement.setString(i++, listing.listingId());
                statement.setString(i++, mlsStatus);
                statement.setObject(i++, listing.listPrice());
                statement.setObject(i++, bedroomsTotal);
                statement.setObject(i++, bathroomsFull);
                statement.setObject(i++, bathroomsHalf);
                statement.setString(i++, listing.address() != null ? listing.address().full() : null);
                statement.setString(i++, listing.address() != null ? listing.address().city() : null);
                statement.setString(i++, listing.address() != null ? listing.address().state() : null);
                statement.setString(i++, listing.address() != null ? listing.address().postalCode() : null);
                statement.setDouble(i++, lat);
                statement.setDouble(i++, lng);
                statement.setDouble(i++, lng); // ST_MakePoint(lng, lat)
                statement.setDouble(i++, lat);
                statement.setString(i++, toJsonArray(listing.photos()));
                statement.setBoolean(i++, active);
                statement.setObject(i, toOffsetDateTime(listing.modified()));

                statement.addBatch();
                ingested++;
            }

            statement.executeBatch();
        } catch (SQLException e) {
            throw new ListingDataAccessException("Failed to ingest SimplyRETS listings", e);
        }

        // Invalidates every previously cached search result - ingestion can change data
        // anywhere, and there's no cheap way to know in advance which bucketed cache keys
        // it affected. Skipped when nothing was actually upserted (empty upstream batch,
        // or every row skipped above) so a no-op poll doesn't needlessly cold the cache.
        if (ingested > 0) {
            cache.bumpGeneration();
        }

        return ingested;
    }

    private static OffsetDateTime toOffsetDateTime(java.time.Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : OffsetDateTime.now(ZoneOffset.UTC);
    }

    private String toJsonArray(List<String> photos) {
        try {
            return objectMapper.writeValueAsString(photos != null ? photos : List.of());
        } catch (Exception e) {
            return "[]";
        }
    }
}
