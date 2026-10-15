package it.gerio.platform.acceptance;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** Giro 012: the live restricted key {@code rk_live_giro012_finta} is refused. */
@QuarkusTest
@TestProfile(Giro012Profiles.LiveRestrictedKey.class)
class Giro012LiveRestrictedKeyRefusedAcceptanceTest extends Giro012LiveKeyRefused {}
