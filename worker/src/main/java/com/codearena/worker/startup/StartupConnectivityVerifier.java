package com.codearena.worker.startup;

import com.codearena.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Fails the worker fast if it cannot reach its two hard dependencies.
 *
 * <p>A worker exists only to pull jobs from Redis and record results in PostgreSQL. If
 * either is unreachable at startup the process has no useful work it could ever do, and
 * crashing immediately is far easier to diagnose than a silently idle container. The
 * orchestrator (docker compose here, ECS or Kubernetes later) restarts it, so this
 * doubles as a retry loop while the dependencies come up.
 *
 * <p>This is a startup check only. Failures that happen later are handled by the job
 * pipeline's own retry and dead-letter logic rather than by killing the process.
 */
@Component
public class StartupConnectivityVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupConnectivityVerifier.class);

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final WorkerProperties properties;

    public StartupConnectivityVerifier(DataSource dataSource,
                                       StringRedisTemplate redisTemplate,
                                       WorkerProperties properties) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        verifyDatabase();
        verifyRedis();
        log.info("Worker {} ready (concurrency={})", properties.id(), properties.concurrency());
    }

    private void verifyDatabase() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("PostgreSQL connectivity probe returned no rows");
            }
            log.info("PostgreSQL reachable ({})", connection.getMetaData().getURL());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Worker cannot reach PostgreSQL; refusing to start", e);
        }
    }

    private void verifyRedis() {
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            throw new IllegalStateException("No Redis connection factory is configured");
        }
        try (RedisConnection connection = factory.getConnection()) {
            String pong = connection.ping();
            log.info("Redis reachable (PING -> {})", pong);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Worker cannot reach Redis; refusing to start", e);
        }
    }
}
