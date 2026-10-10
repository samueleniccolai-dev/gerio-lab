package it.gerio.platform.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

// Plain unit test: the host rules of ADR-0004, without Quarkus.
class PlatformDomainTest {

  private final PlatformDomain platform = new PlatformDomain("piattaforma.it", OptionalInt.empty());

  @ParameterizedTest
  @ValueSource(strings = {"Piattaforma.it", "piattaforma.it.", "www.piattaforma.it", "", "1.2.3.4"})
  void refusesABaseDomainThatIsNotNormalized(String base) {
    assertThrows(
        IllegalArgumentException.class, () -> new PlatformDomain(base, OptionalInt.empty()));
  }

  @Test
  void recognizesOnlyTheExactBaseAsThePlatform() {
    assertTrue(platform.isPlatform("piattaforma.it"));
    assertFalse(platform.isPlatform("fioraia.piattaforma.it"));
    assertFalse(platform.isPlatform("evilpiattaforma.it"));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "fioraia.piattaforma.it, fioraia",
    "a.b.piattaforma.it,     ''", // deeper names are never a slug
    "piattaforma.it,         ''",
    "evilpiattaforma.it,     ''", // not below the base: "." + base
    "fioraia.altro.it,       ''",
  })
  void extractsTheSlugOnlyFromOneLabelBelowTheBase(String host, String slug) {
    Optional<String> expected = slug.isEmpty() ? Optional.empty() : Optional.of(slug);
    assertEquals(expected, platform.slugOf(host));
  }

  @Test
  void aHostThatOnlyEndsWithTheBaseIsNotBelowIt() {
    assertTrue(platform.isUnderBase("a.b.piattaforma.it"));
    assertFalse(platform.isUnderBase("evilpiattaforma.it"));
    assertFalse(platform.isUnderBase("piattaforma.it"));
  }

  @Test
  void buildsTheFreeSubdomain() {
    assertEquals("fioraia.piattaforma.it", platform.subdomainOf("fioraia"));
  }
}
