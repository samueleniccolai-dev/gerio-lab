package it.gerio.platform.legal;

import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.ShopProfile;
import it.gerio.platform.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.GregorianCalendar;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The terms of sale of the current shop (SPEC 9.2): one template for the page and the PDF. The PDF
 * is kept in memory for (shop, version), so the page and every email carry the same bytes. The shop
 * comes from the {@link TenantContext}, never from the request's host.
 */
@ApplicationScoped
public class TermsDocument {

  /** The version of the terms that new orders accept. */
  public static final String CURRENT_VERSION = "2026-10-12";

  private static final Set<String> KNOWN_VERSIONS = Set.of(CURRENT_VERSION);
  private static final ZoneId ROME = ZoneId.of("Europe/Rome");

  private record Key(String tenantId, String version) {}

  private final Map<Key, byte[]> cache = new ConcurrentHashMap<>();

  @Location("legal/terms.html")
  Template template;

  @Inject TenantContext tenantContext;
  @Inject ShopProfile shopProfile;

  /** The page of the terms in this version; fails on an unknown version. */
  public String html(String version) {
    requireKnown(version);
    ShopProfile.Info seller =
        shopProfile.current().orElseThrow(() -> new IllegalStateException("No shop profile"));
    return template.data("seller", seller).data("version", version).render();
  }

  /** The PDF of the terms in this version; fails on an unknown version. */
  public byte[] pdf(String version) {
    requireKnown(version);
    if (!(tenantContext.require() instanceof CurrentTenant.Shop shop)) {
      throw new IllegalStateException("The terms belong to a shop");
    }
    Key key = new Key(shop.id(), version);
    byte[] cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    byte[] created = render(html(version), version);
    byte[] previous = cache.putIfAbsent(key, created);
    return previous != null ? previous : created;
  }

  /** The name of the attached file and of the download. */
  public static String fileName(String version) {
    return "condizioni-di-vendita-" + version + ".pdf";
  }

  private static void requireKnown(String version) {
    if (!KNOWN_VERSIONS.contains(version)) {
      throw new IllegalArgumentException("Unknown terms version");
    }
  }

  /**
   * Creation and modification dates are the day of the version, so the PDF does not depend on
   * today.
   */
  private static byte[] render(String html, String version) {
    GregorianCalendar date = GregorianCalendar.from(LocalDate.parse(version).atStartOfDay(ROME));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PdfRendererBuilder builder = new PdfRendererBuilder();
    builder.withHtmlContent(html, null);
    builder.toStream(new ByteArrayOutputStream());
    try (PdfBoxRenderer renderer = builder.buildPdfRenderer()) {
      renderer.layout();
      renderer.createPDFWithoutClosing();
      var information = renderer.getPdfDocument().getDocumentInformation();
      information.setCreationDate(date);
      information.setModificationDate(date);
      renderer.getPdfDocument().save(out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
