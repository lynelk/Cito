package net.citotech.cito.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.json.JSONObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Covers audit K3: a dedicated E2E test suite. Everything else in this codebase tests one class or
 * one HTTP handler in isolation (mocked collaborators, mocked JDBC). Nothing before this started
 * the real Spring application context, ran the real Flyway migration history against a real
 * database, and made a real HTTP call all the way through the servlet stack and back out - so a
 * class of bug (a bean that fails to wire, a migration that doesn't actually apply cleanly, a
 * controller mapping that's broken) was invisible to the existing test suite no matter how much
 * unit-test coverage existed. This starts the whole app on a random port against a real MySQL 8
 * Testcontainer and drives one real request through it.
 *
 * <p><b>Scope note:</b> a fuller E2E ("real signed /api/v2/native/payments/collect request against
 * a WireMock-stubbed provider, asserting the transaction lands in the DB") was the original
 * aspiration for K3, and audit K2 ({@code AirtelMoneyOpenApiPaymentGatewayWireMockTest}) already
 * independently proves the WireMock side of that combination works. It wasn't combined into this
 * same test because a real payment flow needs merchant channel credentials seeded through the
 * encrypted {@code merchant_channel_credentials} path (a real {@code
 * MERCHANT_CHANNEL_ENCRYPTION_KEY} plus {@code MerchantChannelCryptoService}) and a correctly
 * V2-signed request ({@code V2RequestSecurityService} - signing/nonce/idempotency), and this
 * environment has no running Docker daemon to actually execute and verify that heavier flow end to
 * end before landing it. Getting either wrong here would ship an E2E test that looks like real
 * coverage but was never actually proven to pass - worse than being explicit about the gap. {@code
 * /status/health} was chosen instead because it's a real, unauthenticated, DB-touching endpoint
 * ({@code HealthController} queries pending-transaction and failed-callback counts) that
 * meaningfully proves the full stack works without needing either of those two extra unverified
 * pieces. The signed-payment E2E is the natural next step for whoever has a Docker-capable
 * environment to build and verify it in.
 *
 * <p>Requires a running Docker daemon (for the MySQL Testcontainer), so - like the K1 test - this
 * is tagged {@code "docker"} and excluded from the default {@code mvn test}/{@code mvn verify} run
 * (see {@code docker.tests.excludedGroups} in {@code pom.xml}). Run explicitly with: {@code mvn
 * test -Ddocker.tests.excludedGroups=}
 */
@Tag("docker")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointE2ETest {

    @Container
    private static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.0.36")
                    .withCommand("--log-bin-trust-function-creators=1")
                    .withDatabaseName("cpay_e2e")
                    .withUsername("cpay")
                    .withPassword("cpay");

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // The five other mandatory (no-default) properties in application.properties - real
        // values aren't needed for this test since it never exercises admin/actuator auth,
        // callback signing, or merchant channel decryption, but Spring Boot's ${VAR} placeholder
        // resolution fails context startup entirely if any of these are unset.
        registry.add("actuator.username", () -> "e2e-actuator");
        registry.add("actuator.password", () -> "e2e-actuator-pass");
        registry.add("admin.api.username", () -> "e2e-admin");
        registry.add("admin.api.password", () -> "e2e-admin-pass");
        registry.add("callback.signing.secret", () -> "e2e-callback-signing-secret");
        registry.add(
                "merchant.channel.encryption.key", () -> "e2e-merchant-channel-key-0123456789");
    }

    @LocalServerPort private int port;

    @Test
    void theFullApplicationBootsAgainstARealDatabaseAndServesARealHttpRequest() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/status/health"))
                        .GET()
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JSONObject body = new JSONObject(response.body());
        // Proves the health check's own DB round trip (SELECT-based connectivity + pending-
        // transaction/failed-callback counts) succeeded against the real, Flyway-migrated schema -
        // not a mock returning a canned "UP".
        assertThat(body.getString("db")).isEqualTo("UP");
        assertThat(body.getString("status")).isEqualTo("UP");
        assertThat(body.getLong("pending_transactions")).isZero();
        assertThat(body.getLong("failed_callbacks")).isZero();
    }
}
