package it.gerio.platform.tenant;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Test-only non-blocking endpoint (returns {@code Uni}): the tenant filter runs on the event loop
 * for it and must refuse, on every host (ADR-0004 point 11).
 */
@Path("/test/non-blocking")
@Produces(MediaType.TEXT_PLAIN)
public class NonBlockingEchoResource {

  @GET
  public Uni<String> get() {
    return Uni.createFrom().item("should never be reached");
  }
}
