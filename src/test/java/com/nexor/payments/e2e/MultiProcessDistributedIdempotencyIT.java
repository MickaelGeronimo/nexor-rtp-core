package com.nexor.payments.e2e;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real multi-process distributed idempotency test.
 *
 * <p>{@code MultiInstanceDistributedIdempotencyStressTest} simulates 3 instances by creating 3
 * {@code PaymentSagaOrchestrator} objects inside one JVM. That proves the database-level lock
 * works under concurrent Java threads, but it never proves the app is safe under real network
 * latency, real connection pools, or 3 independent processes racing each other — which is what
 * "3 pods" actually means in production.
 *
 * <p>This test launches the packaged Spring Boot jar as 3 separate OS processes on 3 ports,
 * all pointing at the same PostgreSQL container, fires 100 concurrent HTTP requests with the
 * same {@code Idempotency-Key} split round-robin across the 3 real processes, and asserts —
 * via a direct JDBC connection to the shared database, independent of any of the 3 apps — that
 * exactly one payment was created.
 *
 * <h2>How to run</h2>
 * <pre>
 *   mvn clean package -DskipTests          # build the jar this test launches
 *   mvn failsafe:integration-test failsafe:verify
 * </pre>
 * Requires Docker (for the Postgres container) and a JDK on PATH. Excluded from {@code mvn test}
 * (tagged {@code multiprocess}, see the surefire/failsafe config in pom.xml) because it needs the
 * jar pre-built and takes ~30-60s to boot 3 real JVMs.
 *
 * <p>Kafka is disabled for the 3 launched processes (excluded via autoconfigure) to keep this
 * test focused on the idempotency/ledger race; the real-Kafka path is covered separately by
 * {@code AbstractContainerizedTest}-based {@code @SpringBootTest}s.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("multiprocess")
@DisplayName("Real Multi-Process Distributed Idempotency Test (3 separate JVMs, 1 shared Postgres)")
class MultiProcessDistributedIdempotencyIT {

    private static final Logger log = LoggerFactory.getLogger(MultiProcessDistributedIdempotencyIT.class);
    private static final int[] PORTS = {18091, 18092, 18093};
    private static final String IDEMPOTENCY_KEY = "MULTI-PROCESS-IT-" + System.currentTimeMillis();
    private static final String DEBTOR = "NEXOR:0001:1001-9";
    private static final String CREDITOR = "NEXOR:0001:2002-8";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static final List<Process> processes = new ArrayList<>();
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void startClusterOfRealProcesses() throws Exception {
        POSTGRES.start();

        Path jar = findExecutableJar();
        log.info("[multiprocess-it] launching 3 real JVM processes from {}", jar);

        // Start instance A first and wait for it to finish the @PostConstruct ledger seed
        // (guarded by "if (accountRepo.count() == 0)"). Starting all 3 at once would race that
        // guard and throw a duplicate-key error on the 2nd/3rd process's own seed attempt.
        processes.add(launchInstance(jar, PORTS[0]));
        waitUntilHealthy(PORTS[0], Duration.ofSeconds(60));

        for (int i = 1; i < PORTS.length; i++) {
            processes.add(launchInstance(jar, PORTS[i]));
        }
        for (int port : PORTS) {
            waitUntilHealthy(port, Duration.ofSeconds(60));
        }
    }

    @AfterAll
    static void tearDownCluster() {
        for (Process p : processes) {
            p.destroy();
        }
        for (Process p : processes) {
            try {
                if (!p.waitFor(10, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        POSTGRES.stop();
    }

    @Test
    @DisplayName("100 concurrent requests across 3 real processes sharing one Postgres: exactly 1 payment persisted")
    void shouldSettleExactlyOnceAcrossRealProcesses() throws Exception {
        int totalRequests = 100;
        ExecutorService executor = Executors.newFixedThreadPool(25);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(totalRequests);
        AtomicInteger httpErrors = new AtomicInteger(0);
        List<Integer> statusCodes = new CopyOnWriteArrayList<>();

        String body = """
                {"debtorAccount":"%s","creditorAccount":"%s","amount":"1500.00","currency":"BRL","remittanceInformation":"multi-process IT","rail":"PIX"}
                """.formatted(DEBTOR, CREDITOR);

        for (int i = 0; i < totalRequests; i++) {
            int port = PORTS[i % PORTS.length];
            executor.submit(() -> {
                try {
                    startGate.await();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/api/v1/payments"))
                            .header("Content-Type", "application/json")
                            .header("X-API-Key", "test-pay-key")
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .timeout(Duration.ofSeconds(10))
                            .build();
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    statusCodes.add(response.statusCode());
                } catch (Exception e) {
                    httpErrors.incrementAndGet();
                    log.warn("[multiprocess-it] request failed", e);
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = finishGate.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(httpErrors.get()).as("HTTP-level failures").isZero();
        assertThat(statusCodes).allMatch(code -> code == 200 || code == 201 || code == 202);

        // Verify against Postgres directly — bypasses all 3 app processes, so this checks what
        // actually landed in the shared database, not what any single instance believes happened.
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement()) {

            // idempotency_keys stores the dedup record (no FK to payment_instructions - it's the
            // gate that decided whether a new payment was allowed to be created at all).
            assertThat(countWhere(st, "idempotency_keys", "idempotency_key = '" + IDEMPOTENCY_KEY + "'"))
                    .as("idempotency_keys rows for this key")
                    .isEqualTo(1);

            // This test's DB starts empty and only this test submits payments, so the total row
            // count doubles as "exactly one payment was ever created" across all 3 processes.
            assertThat(countWhere(st, "payment_instructions", "1=1"))
                    .as("total payment_instructions rows across the whole cluster")
                    .isEqualTo(1);

            ResultSet txRs = st.executeQuery("SELECT transaction_id FROM payment_instructions LIMIT 1");
            assertThat(txRs.next()).isTrue();
            String transactionId = txRs.getString(1);

            assertThat(countWhere(st, "outbox_events", "aggregate_id = '" + transactionId + "'"))
                    .as("outbox_events rows for this transaction (FUNDS_RESERVED + SETTLED_CLEARING)")
                    .isEqualTo(2);
        }
    }

    private static int countWhere(Statement st, String table, String where) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Process launchInstance(Path jar, int port) throws IOException {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-jar", jar.toAbsolutePath().toString(),
                "--server.port=" + port,
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                "--nexor.security.api-keys.payment-submitter=test-pay-key",
                "--nexor.security.api-keys.auditor=test-audit-key",
                "--nexor.security.api-keys.admin=test-admin-key",
                "--nexor.security.rate-limit.requests-per-minute=100000"
        );
        pb.redirectOutput(new File(System.getProperty("java.io.tmpdir"), "nexor-it-instance-" + port + ".log"));
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private static void waitUntilHealthy(int port, Duration timeout) throws InterruptedException {
        HttpRequest health = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = http.send(health, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
                    log.info("[multiprocess-it] instance on port {} is healthy", port);
                    return;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Instance on port " + port + " did not become healthy within " + timeout);
    }

    private static Path findExecutableJar() throws IOException {
        Path target = Path.of("target");
        try (Stream<Path> files = Files.list(target)) {
            return files
                    .filter(p -> p.getFileName().toString().matches("nexor-rtp-core-.*\\.jar"))
                    .filter(p -> !p.getFileName().toString().contains("sources"))
                    .filter(p -> !p.getFileName().toString().contains("javadoc"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No executable jar found in target/. Run 'mvn clean package -DskipTests' first."));
        }
    }

}
