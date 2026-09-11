package com.nexor.payments.testsupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for every {@code @SpringBootTest} that needs a real PostgreSQL database via Testcontainers.
 *
 * <p>Uses the Testcontainers Singleton Container pattern: a single PostgreSQL container is started
 * once and shared across all test classes in the JVM test run. This prevents Testcontainers JUnit Jupiter
 * extension from stopping the container between test classes while Spring Boot retains its cached
 * ApplicationContext and Hikari connection pool.
 *
 * <p>If Docker is not available in the execution environment, tests inheriting from this class
 * are gracefully skipped via {@code @EnabledIf("isDockerAvailable")}.
 */
@Tag("uses-docker")
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractContainerizedTest {

    @ServiceConnection
    public static final PostgreSQLContainer<?> POSTGRES;

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
            POSTGRES.start();
        } else {
            POSTGRES = null;
        }
    }
}

