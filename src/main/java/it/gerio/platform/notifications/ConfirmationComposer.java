package it.gerio.platform.notifications;

import io.quarkus.mailer.Mail;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import it.gerio.platform.legal.TermsDocument;
import it.gerio.platform.order.OrderLookup;
import it.gerio.platform.order.OrderLookup.Confirmation;
import it.gerio.platform.tenant.CurrentTenant;
import it.gerio.platform.tenant.ShopProfile;
import it.gerio.platform.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Builds the confirmation email of a paid order. Runs in a new transaction: Hibernate fixes the
 * shop when the session opens, so the caller sets the {@link TenantContext} to the shop first.
 */
@ApplicationScoped
public class ConfirmationComposer {

  @Location("email/order-confirmation.html")
  Template html;

  @Location("email/order-confirmation.txt")
  Template text;

  @Inject TenantContext tenantContext;
  @Inject OrderLookup orders;
  @Inject ShopProfile shopProfile;
  @Inject TermsDocument terms;

  @ConfigProperty(name = "app.mail.from-address")
  String fromAddress;

  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public Mail compose(long orderId, String shopHost) {
    if (!(tenantContext.require() instanceof CurrentTenant.Shop shop)) {
      throw new IllegalStateException("The confirmation is composed for a shop");
    }
    Confirmation order =
        orders
            .confirmation(orderId)
            .orElseThrow(() -> new IllegalStateException("No order " + orderId + " in the shop"));
    ShopProfile.Info seller =
        shopProfile.current().orElseThrow(() -> new IllegalStateException("No shop profile"));
    String link = "https://" + shopHost + "/ordine/" + order.token();
    Mail mail =
        Mail.withHtml(
                order.customerEmail(),
                "Conferma ordine " + order.number() + " — " + shop.displayName(),
                html.data("order", order).data("seller", seller).data("link", link).render())
            .setText(text.data("order", order).data("seller", seller).data("link", link).render())
            .setFrom(from(shop.displayName()));
    mail.addAttachment(
        TermsDocument.fileName(order.termsVersion()),
        terms.pdf(order.termsVersion()),
        "application/pdf");
    return seller.email() == null ? mail : mail.setReplyTo(seller.email());
  }

  /** {@code "Shop tramite Gerio" <ordini@…>}; the shop's name cannot break the header. */
  private String from(String displayName) {
    String name =
        displayName.replaceAll("[\\r\\n]+", " ").replace("\\", "\\\\").replace("\"", "\\\"");
    return "\"" + name + " tramite Gerio\" <" + fromAddress + ">";
  }
}
