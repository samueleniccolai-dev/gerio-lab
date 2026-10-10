package it.gerio.platform.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

// Plain unit test, no Quarkus: HostNormalizer is a pure function.
class HostNormalizerTest {

  // Copy of the V1 check constraint tenant_domain_hostname_normalized (minus the
  // "no www." part, which normalize() guarantees by stripping it).
  private static final Pattern DB_HOSTNAME =
      Pattern.compile("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$");

  private static final String LABEL_63 = "a".repeat(63);
  private static final String LABEL_64 = "a".repeat(64);

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "fioraia-rossi.it,            fioraia-rossi.it",
    "Fioraia-Rossi.IT,            fioraia-rossi.it",
    "fioraia-rossi.it:8080,       fioraia-rossi.it",
    "fioraia-rossi.it.,           fioraia-rossi.it",
    "fioraia-rossi.it.:443,       fioraia-rossi.it",
    "www.fioraia-rossi.it,        fioraia-rossi.it",
    "WWW.Fioraia-Rossi.it:8080,   fioraia-rossi.it",
    "www.fioraia-rossi.it.,       fioraia-rossi.it",
    // only one "www." is stripped: the rest is part of the name
    "www.www.fioraia-rossi.it,    www.fioraia-rossi.it",
    // "www" inside the name is not a prefix
    "wwwfioraia.it,               wwwfioraia.it",
    "negozi.www.it,               negozi.www.it",
    "fioraia-a.localhost:8081,    fioraia-a.localhost",
    "localhost,                   localhost",
    "localhost:8080,              localhost",
    "www.localhost,               localhost",
    "a.b.c.fioraia.it,            a.b.c.fioraia.it",
    "xn--fiorai-9ua.it,           xn--fiorai-9ua.it",
    "3fiori.it,                   3fiori.it",
  })
  void normalizes(String raw, String expected) {
    assertEquals(Optional.of(expected), HostNormalizer.normalize(raw));
  }

  @Test
  void acceptsTheLongestLabel() {
    assertEquals(
        Optional.of(LABEL_63 + ".it"), HostNormalizer.normalize(LABEL_63.toUpperCase() + ".it"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ",
        ":8080", // port only
        "fioraia.it:", // empty port
        "fioraia.it:abc", // port not a number
        "192.168.1.10", // IPv4: never a shop, never an on-demand certificate
        "192.168.1.10:8080",
        "[::1]", // IPv6
        "[::1]:8080",
        "::1",
        "fioraia rossi.it", // space
        "fioraia_rossi.it", // underscore: valid in some DNS records, never in a hostname
        "-fioraia.it", // label starting with a hyphen
        "fioraia-.it", // label ending with a hyphen
        "fioraia..it", // empty label
        ".fioraia.it",
        "fioraia.it..",
        "fioraia.123", // the last label (TLD) must start with a letter, like in V1
        "fioraia.it/negozio", // path
        "utente@fioraia.it", // userinfo
        "fiorаia.it", // the "а" is Cyrillic: non-ASCII must come in punycode
        "fioràia.it",
        // Kelvin sign (U+212A): lowercases to an ASCII "k", so "Kappa.it"
        // would become the real host "kappa.it" if lowercased before the check
        "Kappa.it",
        "fioraia.it:123456", // port longer than 5 digits
      })
  void rejects(String raw) {
    assertEquals(Optional.empty(), HostNormalizer.normalize(raw));
  }

  @Test
  void rejectsALabelLongerThan63() {
    assertEquals(Optional.empty(), HostNormalizer.normalize(LABEL_64 + ".it"));
  }

  @Test
  void rejectsANameLongerThan253() {
    // 4 labels of 63 + 3 dots + ".it" = 258 characters
    String tooLong = String.join(".", LABEL_63, LABEL_63, LABEL_63, LABEL_63) + ".it";
    assertEquals(Optional.empty(), HostNormalizer.normalize(tooLong));
  }

  // On a server whose default locale is Turkish, "I".toLowerCase() is the dotless
  // "ı": FIORAIA.IT would become "fıoraıa.ıt" and match no shop.
  @Test
  void lowercasesTheSameWayWhateverTheServerLocale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertEquals(Optional.of("fioraia.it"), HostNormalizer.normalize("FIORAIA.IT"));
    } finally {
      Locale.setDefault(original);
    }
  }

  // The contract with V1: whatever the filter looks up has the stored form.
  @ParameterizedTest
  @ValueSource(
      strings = {
        "WWW.Fioraia-Rossi.it:8080",
        "a.b.c.fioraia.it.",
        "xn--fiorai-9ua.it",
        "3fiori.it"
      })
  void outputMatchesTheDatabaseConstraint(String raw) {
    String normalized = HostNormalizer.normalize(raw).orElseThrow();
    assertTrue(DB_HOSTNAME.matcher(normalized).matches(), normalized);
    assertTrue(normalized.length() <= 253);
  }
}
