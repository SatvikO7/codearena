package com.codearena;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Infrastructure acceptance test: the API server boots against a real PostgreSQL and a
 * real Redis, Flyway applies the schema, and both dependencies report healthy.
 *
 * <p>Named {@code *IT} so it runs under Failsafe during {@code mvn verify}. It needs a
 * running Docker daemon; {@code mvn test} skips it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class InfrastructureIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesEveryMigrationInOrder() {
        List<String> applied = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);

        assertThat(applied).containsExactly("1", "2", "3", "4");

        // V1 installs citext. V2 ended up not using it (see the note in that migration),
        // but V1 is already applied everywhere and migrations are immutable, so the
        // extension stays. Asserted here so the migration history stays honest.
        Integer citext = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'citext'", Integer.class);
        assertThat(citext).isEqualTo(1);
    }

    /**
     * The uniqueness guarantees live in the database, not only in application code, so
     * they are asserted against the real schema.
     */
    @Test
    void usersTableCarriesTheExpectedConstraints() {
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'users'::regclass", String.class);

        assertThat(constraints).contains(
                "uq_users_public_id",
                "ck_users_role", "ck_users_username_length", "ck_users_email_length");
    }

    /**
     * Case-insensitive identity is enforced by unique indexes over lower(...). Asserting
     * they are both UNIQUE and functional is the point: a plain index on the raw column
     * would let a case-variant duplicate through.
     */
    @Test
    void identityIsUniqueCaseInsensitively() {
        List<String> definitions = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'users'", String.class);

        assertThat(definitions).anySatisfy(definition -> assertThat(definition)
                .contains("UNIQUE").contains("uq_users_username_lower")
                .contains("lower(").contains("username"));
        assertThat(definitions).anySatisfy(definition -> assertThat(definition)
                .contains("UNIQUE").contains("uq_users_email_lower")
                .contains("lower(").contains("email"));
    }

    /** The problem schema's guarantees live in the database, not only in application code. */
    @Test
    void problemsTableCarriesTheExpectedConstraints() {
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'problems'::regclass", String.class);

        assertThat(constraints).contains(
                "uq_problems_slug", "uq_problems_public_id",
                "ck_problems_status", "ck_problems_difficulty", "ck_problems_slug_format",
                "fk_problems_created_by");
    }

    /**
     * Examples and test cases must vanish with their problem, and a position must be
     * unique per problem so ordering is total rather than whatever the database returns.
     */
    @Test
    void problemChildTablesCascadeAndOrderDeterministically() {
        List<String> exampleConstraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'problem_examples'::regclass", String.class);
        List<String> testCaseConstraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'problem_test_cases'::regclass", String.class);

        assertThat(exampleConstraints).contains("uq_problem_examples_position", "fk_problem_examples_problem");
        assertThat(testCaseConstraints).contains("uq_problem_test_cases_position", "fk_problem_test_cases_problem");

        String deleteRule = jdbcTemplate.queryForObject(
                """
                SELECT confdeltype FROM pg_constraint
                WHERE conname = 'fk_problem_test_cases_problem'
                """, String.class);
        assertThat(deleteRule).as("'c' is ON DELETE CASCADE").isEqualTo("c");
    }

    /**
     * A submission is a historical record. Deleting a problem or an account must not erase
     * the evidence that somebody submitted something, so both foreign keys RESTRICT.
     */
    @Test
    void submissionsTableProtectsHistoryFromCascadingDeletes() {
        List<String> deleteRules = jdbcTemplate.queryForList("""
                SELECT conname || '=' || confdeltype::text
                FROM pg_constraint
                WHERE conrelid = 'submissions'::regclass AND contype = 'f'
                """, String.class);

        // 'r' is ON DELETE RESTRICT; 'c' would be CASCADE and would lose history.
        assertThat(deleteRules).contains(
                "fk_submissions_problem=r", "fk_submissions_user=r");
    }

    @Test
    void submissionsTableCarriesTheExpectedConstraints() {
        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'submissions'::regclass", String.class);

        assertThat(constraints).contains(
                "uq_submissions_public_id", "ck_submissions_status", "ck_submissions_language",
                "ck_submissions_source_not_blank", "ck_submissions_attempts", "ck_submissions_counts");
    }

    /**
     * The sweeper's queries are indexed, and partially so: terminal submissions are the
     * overwhelming majority and are never swept, so indexing them would cost write
     * throughput on every completed judgement for nothing.
     */
    @Test
    void submissionPipelineQueriesAreIndexed() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'submissions'", String.class);

        assertThat(indexes).anySatisfy(definition ->
                assertThat(definition).contains("ix_submissions_user_created"));
        assertThat(indexes).anySatisfy(definition ->
                assertThat(definition).contains("ix_submissions_pending").contains("WHERE"));
        assertThat(indexes).anySatisfy(definition ->
                assertThat(definition).contains("ix_submissions_claimed").contains("WHERE"));
    }

    @Test
    void healthEndpointReportsDatabaseAndRedisUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"));
    }

    @Test
    void systemInfoEndpointReportsServiceMetadata() throws Exception {
        mockMvc.perform(get("/api/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("codearena-backend"))
                .andExpect(jsonPath("$.version").isNotEmpty())
                .andExpect(jsonPath("$.serverTime").exists());
    }

    @Test
    void openApiDocumentIsPublished() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("CodeArena API"))
                .andExpect(jsonPath("$.paths['/api/system/info']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/register']").exists());
    }

    /**
     * Since Phase 2 the API is authenticated by default, so an anonymous caller learns
     * only that credentials are required — not whether the path exists. That is the
     * intended behaviour: route existence should not be probeable by strangers.
     */
    @Test
    void hidesRouteExistenceFromAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.path").value("/api/does-not-exist"));
    }

    @Test
    void returnsTheStandardNotFoundShapeToAnAuthenticatedCaller() throws Exception {
        mockMvc.perform(get("/api/does-not-exist").with(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/does-not-exist"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user("someone").roles("USER");
    }
}
