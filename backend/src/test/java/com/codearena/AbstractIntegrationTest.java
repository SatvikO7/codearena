package com.codearena;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and real Redis, shared by every integration test in the JVM.
 *
 * <p>The containers are {@code static}, so Testcontainers starts them once and every
 * subclass reuses them rather than paying the startup cost per test class.
 *
 * <p>There is deliberately no in-memory substitute. The schema depends on PostgreSQL
 * behaviour that H2 does not reproduce — unique indexes over {@code lower(...)} for
 * case-insensitive identity, and the index names the registration path reads to tell a
 * duplicate username from a duplicate email. A test against a database that disagrees
 * with production would be worse than no test.
 */
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void integrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Cost 12 is right for production and far too slow to pay on every test login.
        // The encoder under test is the same DelegatingPasswordEncoder either way; only
        // the work factor differs.
        registry.add("codearena.security.bcrypt-strength", () -> 4);
    }
}
