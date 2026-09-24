package com.sarvika.mlssearch.mls;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@RegisterRestClient(configKey = "simplyrets-api")
@Path("/properties")
@ClientHeaderParam(name = "Authorization", value = "{basicAuthHeader}")
public interface SimplyRetsClient {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<SimplyRetsListing> listProperties(@QueryParam("limit") int limit);

    // Computed header method (MP Rest Client convention) - reads mls.simplyrets.* config
    // rather than hardcoding the demo credentials, so a real account only needs env vars.
    // Must be a default method: Quarkus's REST client invokes this reflectively as an
    // instance method and throws IncompatibleClassChangeError if it's static.
    default String basicAuthHeader() {
        String username = ConfigProvider.getConfig().getValue("mls.simplyrets.username", String.class);
        String password = ConfigProvider.getConfig().getValue("mls.simplyrets.password", String.class);
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
