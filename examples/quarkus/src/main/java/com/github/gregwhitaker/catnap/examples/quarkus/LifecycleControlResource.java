package com.github.gregwhitaker.catnap.examples.quarkus;

import com.github.gregwhitaker.catnap.http.CatnapProjection;
import com.github.gregwhitaker.catnap.testkit.probe.StreamLifecycleProbe;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import java.util.Map;
import java.util.UUID;

/** Example-only controls let the same lifecycle contracts exercise an external native process. */
@Path("/test-control/stream/{key}")
@Produces("application/json")
@CatnapProjection(enabled = false)
public class LifecycleControlResource {
  @PUT
  public void create(@PathParam("key") String key) {
    UUID.fromString(key);
    StreamLifecycleProbe.CONNECTIONS.put(key, new StreamLifecycleProbe());
  }

  @GET
  public Map<String, Object> state(@PathParam("key") String key) {
    var probe = probe(key);
    return Map.of("finished", probe.finished.isDone(), "emitted", probe.emitted.get());
  }

  @POST
  @Path("/release")
  public void release(@PathParam("key") String key) {
    probe(key).release.complete(null);
  }

  @DELETE
  public void remove(@PathParam("key") String key) {
    var probe = StreamLifecycleProbe.CONNECTIONS.remove(key);
    if (probe != null) probe.release.complete(null);
  }

  private static StreamLifecycleProbe probe(String key) {
    var probe = StreamLifecycleProbe.CONNECTIONS.get(key);
    if (probe == null) throw new NotFoundException();
    return probe;
  }
}
