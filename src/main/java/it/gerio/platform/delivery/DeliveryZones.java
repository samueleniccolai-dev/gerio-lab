package it.gerio.platform.delivery;

import io.quarkus.panache.common.Sort;
import it.gerio.platform.catalog.Prices;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The delivery zones of the current shop (Hibernate filters by shop). Zones are loaded and searched
 * in Java: first by exact postal code, then by municipality, active zones only, lowest id first.
 */
@ApplicationScoped
public class DeliveryZones {

  /** A zone as the checkout needs it. */
  public record Zone(long id, String name, long feeCents, long minOrderCents) {
    public String fee() {
      return Prices.format(feeCents);
    }

    public String minimum() {
      return Prices.format(minOrderCents);
    }
  }

  /** The active zones, in a fixed order. */
  public List<Zone> active() {
    return activeEntities().stream().map(DeliveryZones::toZone).toList();
  }

  /** The zone for an address; empty if the shop does not deliver there. */
  public Optional<Zone> find(String postalCode, String city) {
    List<DeliveryZone> zones = activeEntities();
    String code = postalCode == null ? "" : postalCode.strip();
    Optional<DeliveryZone> found =
        zones.stream().filter(zone -> contains(zone.postalCodes, code)).findFirst();
    if (found.isEmpty()) {
      found = zones.stream().filter(zone -> contains(zone.municipalities, city)).findFirst();
    }
    return found.map(DeliveryZones::toZone);
  }

  /** The name of a zone of this shop, for the delivery line sent to Stripe. */
  public Optional<String> nameOf(long id) {
    return Optional.ofNullable(DeliveryZone.<DeliveryZone>findById(id)).map(zone -> zone.name);
  }

  private static List<DeliveryZone> activeEntities() {
    return DeliveryZone.list("active = true", Sort.by("id"));
  }

  private static boolean contains(List<String> values, String wanted) {
    if (wanted == null || wanted.isBlank() || values == null) {
      return false;
    }
    String normalized = wanted.strip().toLowerCase(Locale.ROOT);
    return values.stream().anyMatch(v -> v.strip().toLowerCase(Locale.ROOT).equals(normalized));
  }

  private static Zone toZone(DeliveryZone zone) {
    return new Zone(zone.getId(), zone.name, zone.feeCents, zone.minOrderCents);
  }
}
