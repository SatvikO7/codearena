package com.codearena.worker;

import com.codearena.worker.config.WorkerProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerPropertiesTest {

    @Test
    void acceptsAValidConfiguration() {
        WorkerProperties properties = new WorkerProperties("worker-1", 4);

        assertThat(properties.id()).isEqualTo("worker-1");
        assertThat(properties.concurrency()).isEqualTo(4);
    }

    @Test
    void rejectsNonPositiveConcurrency() {
        assertThatThrownBy(() -> new WorkerProperties("worker-1", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("concurrency");
    }

    @Test
    void rejectsBlankWorkerIdSoThatJobOwnershipIsAlwaysAttributable() {
        assertThatThrownBy(() -> new WorkerProperties("  ", 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }
}
