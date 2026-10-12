package it.gerio.platform.acceptance;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** Giro 012: the live secret key {@code sk_live_giro012_finta} is refused. */
@QuarkusTest
@TestProfile(Giro012Profiles.LiveSecretKey.class)
class Giro012LiveSecretKeyRefusedAcceptanceTest extends Giro012LiveKeyRefused {}
