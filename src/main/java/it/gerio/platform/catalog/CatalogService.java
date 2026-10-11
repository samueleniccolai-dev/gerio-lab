package it.gerio.platform.catalog;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What the shop window shows. Reads only through Hibernate, so every query is limited to the
 * request's shop. A product is visible when it is active, its category is visible and it has at
 * least one active variant.
 */
@ApplicationScoped
public class CatalogService {

  /** A visible category with its visible products. */
  public record CategoryView(String name, String slug, List<ProductSummary> products) {}

  public record ProductSummary(String name, String slug, String fromPrice) {}

  public record VariantView(String name, String price) {}

  /** The product page: variants are the active ones, in order. */
  public record ProductView(
      String name,
      String slug,
      String description,
      String categorySlug,
      List<VariantView> variants,
      boolean seasonalComposition,
      boolean withdrawalExcluded,
      String preparation) {}

  /** Categories whose products get the seasonal-composition note (PROGETTO.md §2.3). */
  private static final List<String> SEASONAL_CATEGORIES = List.of("bouquet", "composizioni");

  public List<CategoryView> catalog() {
    return visible(null);
  }

  public Optional<CategoryView> category(String slug) {
    return visible(slug).stream().findFirst();
  }

  public Optional<ProductView> product(String slug) {
    Product product = Product.<Product>find("slug", slug).firstResult();
    if (product == null || !product.active || product.categoryId == null) {
      return Optional.empty();
    }
    Category category = Category.findById(product.categoryId);
    if (category == null || !category.visible) {
      return Optional.empty();
    }
    List<VariantView> variants =
        activeVariants(product.getId()).stream()
            .map(variant -> new VariantView(variant.name, Prices.format(variant.priceCents)))
            .toList();
    if (variants.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new ProductView(
            product.name,
            product.slug,
            product.description,
            category.slug,
            variants,
            SEASONAL_CATEGORIES.contains(category.slug),
            product.withdrawalExcluded,
            preparation(product.leadTimeDays)));
  }

  private static String preparation(int days) {
    if (days <= 0) {
      return null;
    }
    return "Tempo di preparazione: " + days + (days == 1 ? " giorno" : " giorni");
  }

  /** Visible categories in order, each with its visible products; one slug, or all if null. */
  private List<CategoryView> visible(String categorySlug) {
    List<Category> categories =
        categorySlug == null
            ? Category.list("visible = true order by position, id")
            : Category.list("visible = true and slug = ?1", categorySlug);
    if (categories.isEmpty()) {
      return List.of();
    }
    Map<Long, List<Product>> products =
        Product.<Product>list("active = true order by position, id").stream()
            .filter(product -> product.categoryId != null)
            .collect(Collectors.groupingBy(product -> product.categoryId));
    Map<Long, Long> cheapest = cheapestActivePrices();
    List<CategoryView> views = new ArrayList<>();
    for (Category category : categories) {
      List<ProductSummary> summaries =
          products.getOrDefault(category.getId(), List.of()).stream()
              .filter(product -> cheapest.containsKey(product.getId()))
              .map(
                  product ->
                      new ProductSummary(
                          product.name, product.slug, Prices.from(cheapest.get(product.getId()))))
              .toList();
      views.add(new CategoryView(category.name, category.slug, summaries));
    }
    return views;
  }

  private Map<Long, Long> cheapestActivePrices() {
    return ProductVariant.<ProductVariant>list("active = true").stream()
        .collect(
            Collectors.toMap(
                variant -> variant.productId, variant -> variant.priceCents, Math::min));
  }

  private List<ProductVariant> activeVariants(long productId) {
    return ProductVariant.list("productId = ?1 and active = true order by position, id", productId);
  }
}
