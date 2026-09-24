package com.sarvika.mlssearch;

import com.sarvika.mlssearch.testsupport.PostgisRedisTestResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * The endpoints the Kubernetes probes hit.
 */
// Needs a live Docker daemon for the PostGIS/Redis Testcontainers PostgisRedisTestResource
// starts - run `./gradlew test -PexcludeDockerTests` in a CI environment whose job
// containers have no Docker daemon available.
@Tag("requires-docker")
@QuarkusTest
@WithTestResource(value = PostgisRedisTestResource.class, scope = TestResourceScope.GLOBAL)
class HealthCheckTest {

    @Test
    void livenessIsUp() {
        given().when().get("/q/health/live")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void readinessIsUpAndCoversTheDatasource() {
        given().when().get("/q/health/ready")
                .then().statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.status", everyItem(equalTo("UP")))
                .body("checks.name", hasItem("Database connections health check"));
    }

    /**
     * {@code quarkus.redis.health.enabled=false} keeps the Redis readiness check off the
     * probe. With it registered, a cache outage would fail {@code /q/health/ready} and take
     * healthy search traffic out of the load balancer - exactly what the cache's
     * fall-through-to-Postgres behaviour exists to avoid.
     */
    @Test
    void readinessDoesNotDependOnRedis() {
        given().when().get("/q/health/ready")
                .then().statusCode(200)
                .body("checks.name", not(hasItem("Redis connection health check")));
    }
}
