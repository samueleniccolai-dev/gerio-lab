package it.gerio.platform.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

// Proves, without starting Quarkus, that the committed application.properties
// has no production default for the base domain: in the prod profile, without
// PLATFORM_BASE_DOMAIN, PlatformConfig cannot be built, so the app cannot start.
// Only the project's file and a fake environment are used as sources: a real
// PLATFORM_BASE_DOMAIN on the developer's machine cannot make this test pass.
class PlatformConfigTest {

  @Test
  void productionWithoutTheVariableRefusesToStart() {
    RuntimeException failure = assertThrows(RuntimeException.class, () -> build("prod", Map.of()));

    assertTrue(
        failure.getMessage().contains("PLATFORM_BASE_DOMAIN"),
        "unexpected failure: " + failure.getMessage());
  }

  // Counter-test: the same build works once the variable exists, so the test
  // above fails for the missing variable and nothing else.
  @Test
  void productionReadsTheVariable() throws IOException {
    PlatformConfig config = build("prod", Map.of("PLATFORM_BASE_DOMAIN", "piattaforma.it"));

    assertEquals("piattaforma.it", config.baseDomain());
    assertEquals(OptionalInt.empty(), config.redirectPort());
  }

  @Test
  void devAndTestUseLocalhost() throws IOException {
    assertEquals("localhost", build("dev", Map.of()).baseDomain());
    assertEquals("localhost", build("test", Map.of()).baseDomain());
  }

  private static PlatformConfig build(String profile, Map<String, String> environment)
      throws IOException {
    URL properties =
        PlatformConfigTest.class.getClassLoader().getResource("application.properties");
    SmallRyeConfig config =
        new SmallRyeConfigBuilder()
            .withProfile(profile)
            .addDefaultInterceptors() // profiles (%prod) and ${...} expansion
            .withSources(new PropertiesConfigSource(properties, 250))
            .withSources(new PropertiesConfigSource(environment, "fake-environment", 300))
            .withMapping(PlatformConfig.class)
            .build();
    return config.getConfigMapping(PlatformConfig.class);
  }
}
