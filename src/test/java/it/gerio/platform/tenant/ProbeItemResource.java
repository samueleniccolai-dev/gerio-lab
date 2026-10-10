package it.gerio.platform.tenant;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Test-only endpoint written the naive way a real one might be: it trusts the id in the URL and
 * relies on the tenant filter alone. {@code EndpointIsolationTest} proves that is enough for shop
 * tables, end to end through the HTTP filter.
 */
@Path("/probe-items/{id}")
@Produces(MediaType.TEXT_PLAIN)
public class ProbeItemResource {

  @GET
  public String get(@PathParam("id") long id) {
    return find(id).name;
  }

  @PUT
  @Consumes(MediaType.TEXT_PLAIN)
  @Transactional
  public String rename(@PathParam("id") long id, String name) {
    ProbeItem item = find(id);
    item.name = name;
    return item.name;
  }

  private static ProbeItem find(long id) {
    ProbeItem item = ProbeItem.findById(id);
    if (item == null) {
      throw new NotFoundException();
    }
    return item;
  }
}
