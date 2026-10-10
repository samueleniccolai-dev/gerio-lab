package it.gerio.platform.tenant;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns the host of a request into the form stored in {@code tenant_domain.hostname} (PROGETTO.md
 * §5.2 point 2): lowercase, no port, no trailing dot, without one leading {@code www.}.
 *
 * <p>Rejects anything that is not a DNS hostname: IP addresses (never a shop), non-ASCII names
 * (internationalized domains arrive in punycode, {@code xn--...}), empty labels, invalid
 * characters. Single-label names such as {@code localhost} are accepted: the platform uses them in
 * dev and test.
 *
 * <p>Contract, checked by {@code HostNormalizerTest}: a multi-label output always satisfies the V1
 * check constraint {@code tenant_domain_hostname_normalized}.
 */
public final class HostNormalizer {

  /**
   * Every character a host with an optional port may contain, in either case. Anything else (IPv6
   * brackets, "@", "/", spaces, "_", any non-ASCII letter) makes the host invalid.
   */
  private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("[A-Za-z0-9.:-]+");

  private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");

  /** One DNS label (RFC 1123): 1-63 characters, no hyphen at either end. */
  private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

  private static final int MAX_LENGTH = 253;
  private static final String WWW = "www.";

  private HostNormalizer() {}

  /**
   * @param rawHost the request authority, e.g. {@code "WWW.Fioraia-Rossi.it:8080"}; may be null
   * @return the normalized host, e.g. {@code "fioraia-rossi.it"}, or empty if it is not a valid
   *     hostname
   */
  public static Optional<String> normalize(String rawHost) {
    // 1. ASCII only, checked BEFORE lowercasing: toLowerCase maps some non-ASCII
    //    characters to ASCII ones (the Kelvin sign U+212A becomes "k"), so a
    //    non-ASCII host could otherwise land on a real shop.
    if (rawHost == null || !ALLOWED_CHARACTERS.matcher(rawHost).matches()) {
      return Optional.empty();
    }
    // 2. Port first: "fioraia.it.:443" must lose ":443" before its trailing dot.
    Optional<String> withoutPort = stripPort(rawHost);
    if (withoutPort.isEmpty()) {
      return Optional.empty();
    }
    // 3. Locale.ROOT: with the server's default locale a Turkish machine would
    //    turn "I" into the dotless "ı" and match no shop.
    String host = withoutPort.get().toLowerCase(Locale.ROOT);
    // 4. "fioraia.it." is the fully qualified form of "fioraia.it": same host.
    //    Only one dot: "fioraia.it.." stays invalid (empty label).
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    // 5. After the trailing dot, so "www.fioraia.it." works too. Only one prefix.
    if (host.startsWith(WWW)) {
      host = host.substring(WWW.length());
    }
    return isHostname(host) ? Optional.of(host) : Optional.empty();
  }

  /** Removes ":port" if present; empty if the port is not 1-5 digits. */
  private static Optional<String> stripPort(String host) {
    int colon = host.lastIndexOf(':');
    if (colon < 0) {
      return Optional.of(host);
    }
    // "::1" leaves ":" behind, which the label check then rejects.
    return PORT.matcher(host.substring(colon + 1)).matches()
        ? Optional.of(host.substring(0, colon))
        : Optional.empty();
  }

  private static boolean isHostname(String host) {
    if (host.isEmpty() || host.length() > MAX_LENGTH) {
      return false;
    }
    // Limit -1 keeps empty labels ("a..b", ".a"), so they fail the check below.
    String[] labels = host.split("\\.", -1);
    for (String label : labels) {
      if (!LABEL.matcher(label).matches()) {
        return false;
      }
    }
    // The last label (the TLD) starts with a letter, like in V1: this rules out
    // IPv4 addresses ("192.168.1.10") and numeric TLDs.
    return Character.isLetter(labels[labels.length - 1].charAt(0));
  }
}
