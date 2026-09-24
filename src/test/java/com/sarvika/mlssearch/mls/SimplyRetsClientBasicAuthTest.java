package com.sarvika.mlssearch.mls;

import com.sarvika.mlssearch.testsupport.PostgisRedisTestResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The computed {@code Authorization} header on {@link SimplyRetsClient}.
 *
 * <p>Exercised through a hand-rolled implementation of the interface rather than the injected
 * REST client proxy, so the assertion is about the {@code default} method's own logic and not
 * about how the proxy dispatches non-{@code @GET} methods.
 */
// Needs a live Docker daemon for the PostGIS/Redis Testcontainers PostgisRedisTestResource
// starts - run `./gradlew test -PexcludeDockerTests` in a CI environment whose job
// containers have no Docker daemon available.
@Tag("requires-docker")
@QuarkusTest
@WithTestResource(value = PostgisRedisTestResource.class, scope = TestResourceScope.GLOBAL)
class SimplyRetsClientBasicAuthTest {

    private final SimplyRetsClient client = new SimplyRetsClient() {
        @Override
        public List<SimplyRetsListing> listProperties(int limit) {
            throw new UnsupportedOperationException("not called by these tests");
        }
    };

    @Test
    void buildsBasicAuthFromTheConfiguredCredentials() {
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("simplyrets:simplyrets".getBytes(StandardCharsets.UTF_8));

        assertThat(client.basicAuthHeader()).isEqualTo(expected);
    }

    /**
     * Quarkus's REST client invokes the computed-header method reflectively as an instance
     * method and blows up with IncompatibleClassChangeError if it is static - so "default,
     * not static" is a runtime contract, not a style choice.
     */
    @Test
    void basicAuthHeaderStaysANonStaticDefaultMethod() throws Exception {
        Method method = SimplyRetsClient.class.getMethod("basicAuthHeader");

        assertThat(method.isDefault()).isTrue();
        assertThat(Modifier.isStatic(method.getModifiers())).isFalse();
        assertThat(method.getReturnType()).isEqualTo(String.class);
    }
}
