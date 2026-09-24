package com.sarvika.mlssearch.listing;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/listings")
@Produces(MediaType.APPLICATION_JSON)
public class ListingsResource {

    private static final String SELECT_COLUMNS =
            "listing_key, unparsed_address, city, state_or_province, list_price, "
            + "bedrooms_total, bathrooms_full, bathrooms_half, latitude, longitude";

    // Index-accelerated pre-filter (&&) then exact ST_Intersects.
    private static final String BBOX_SQL = """
            SELECT %s
            FROM listings
            WHERE is_active = true
              AND geog && ST_MakeEnvelope(?, ?, ?, ?, 4326)::geography
              AND ST_Intersects(geog::geometry, ST_MakeEnvelope(?, ?, ?, ?, 4326))
            LIMIT 200
            """.formatted(SELECT_COLUMNS);

    // ST_DWithin on geography is GiST-index-aware and computes true great-circle distance -
    // do not switch this to ST_Distance() in a WHERE clause, that forces a full table scan.
    private static final String RADIUS_SQL = """
            SELECT %s
            FROM listings
            WHERE is_active = true
              AND ST_DWithin(geog, ST_MakePoint(?, ?)::geography, ?)
            LIMIT 200
            """.formatted(SELECT_COLUMNS);

    // Listings are points, so ST_Contains (point-in-polygon) is correct here, not ST_Intersects.
    private static final String POLYGON_SQL = """
            SELECT %s
            FROM listings
            WHERE is_active = true
              AND ST_Contains(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), geog::geometry)
            LIMIT 200
            """.formatted(SELECT_COLUMNS);

    @Inject
    DataSource dataSource;

    @Inject
    ListingIngestionService ingestionService;

    @Inject
    ListingSearchCache cache;

    @POST
    @Path("/ingest/simplyrets")
    public Response ingestFromSimplyRets(@QueryParam("limit") @DefaultValue("25") int limit) {
        int ingested = ingestionService.syncFromSimplyRets(limit);
        return Response.ok(Map.of("ingested", ingested)).build();
    }

    /**
     * Map viewport / bounding-box search - triggered on every pan/zoom.
     */
    @GET
    public List<ListingSummary> searchByBoundingBox(
            @QueryParam("west") double west,
            @QueryParam("south") double south,
            @QueryParam("east") double east,
            @QueryParam("north") double north) {
        String cacheKey = cache.bboxKey(west, south, east, north);
        List<ListingSummary> cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<ListingSummary> results = query(BBOX_SQL, statement -> {
            statement.setDouble(1, west);
            statement.setDouble(2, south);
            statement.setDouble(3, east);
            statement.setDouble(4, north);
            statement.setDouble(5, west);
            statement.setDouble(6, south);
            statement.setDouble(7, east);
            statement.setDouble(8, north);
        });
        cache.put(cacheKey, results);
        return results;
    }

    /**
     * Radius search around a point, e.g. "within 5 miles of this address" (~8047m).
     */
    @GET
    @Path("/radius")
    public List<ListingSummary> searchByRadius(
            @QueryParam("lat") double lat,
            @QueryParam("lng") double lng,
            @QueryParam("radiusMeters") double radiusMeters) {
        String cacheKey = cache.radiusKey(lat, lng, radiusMeters);
        List<ListingSummary> cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<ListingSummary> results = query(RADIUS_SQL, statement -> {
            statement.setDouble(1, lng);
            statement.setDouble(2, lat);
            statement.setDouble(3, radiusMeters);
        });
        cache.put(cacheKey, results);
        return results;
    }

    /**
     * Drawn polygon / geofence search - the request body is a raw GeoJSON Polygon geometry
     * (e.g. what a map-drawing UI like Mapbox GL Draw emits), not a full Feature/FeatureCollection.
     */
    @POST
    @Path("/polygon")
    @Consumes(MediaType.APPLICATION_JSON)
    public List<ListingSummary> searchByPolygon(String polygonGeoJson) {
        return query(POLYGON_SQL, statement -> statement.setString(1, polygonGeoJson));
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private List<ListingSummary> query(String sql, StatementBinder binder) {
        List<ListingSummary> results = new ArrayList<>();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            binder.bind(statement);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new ListingDataAccessException("Listing search failed", e);
        }

        return results;
    }

    private static ListingSummary mapRow(ResultSet rs) throws SQLException {
        return new ListingSummary(
                rs.getString("listing_key"),
                rs.getString("unparsed_address"),
                rs.getString("city"),
                rs.getString("state_or_province"),
                rs.getBigDecimal("list_price"),
                (Integer) rs.getObject("bedrooms_total"),
                (Integer) rs.getObject("bathrooms_full"),
                (Integer) rs.getObject("bathrooms_half"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude")
        );
    }
}
