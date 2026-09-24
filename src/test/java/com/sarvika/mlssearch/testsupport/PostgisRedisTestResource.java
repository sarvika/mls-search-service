package com.sarvika.mlssearch.testsupport;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/**
 * Starts a throwaway PostGIS + Redis pair for the test run and points the app at them.
 *
 * <p>The datasource/redis hosts in {@code application.properties} deliberately target shared
 * infrastructure (and {@code quarkus.datasource.password} expects a {@code DB_PASSWORD} env
 * var that is never committed), so tests must not inherit them - they would need a secret to
 * run and would mutate a shared database. The config returned here overrides both.
 *
 * <p>The database image is {@code postgis/postgis} rather than plain {@code postgres} because
 * {@code V1__create_listings_table.sql} does {@code CREATE EXTENSION postgis} and every search
 * query is PostGIS-specific - a stock Postgres image fails at Flyway migration time.
 */
public class PostgisRedisTestResource implements QuarkusTestResourceLifecycleManager {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName
            .parse("postgis/postgis:16-3.5")
            .asCompatibleSubstituteFor("postgres");

    private PostgreSQLContainer<?> postgis;
    private GenericContainer<?> redis;

    @Override
    public Map<String, String> start() {
        postgis = new PostgreSQLContainer<>(POSTGIS_IMAGE)
                .withDatabaseName("mlssearch")
                .withUsername("mlssearch")
                .withPassword("mlssearch");
        postgis.start();

        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withCommand("redis-server", "--save", "")
                .withExposedPorts(6379);
        redis.start();

        return Map.of(
                "quarkus.datasource.jdbc.url", postgis.getJdbcUrl(),
                "quarkus.datasource.username", postgis.getUsername(),
                "quarkus.datasource.password", postgis.getPassword(),
                "quarkus.redis.hosts",
                "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @Override
    public void stop() {
        if (redis != null) {
            redis.stop();
        }
        if (postgis != null) {
            postgis.stop();
        }
    }
}
