package com.codearena.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the error contract without booting the application: every failure must
 * produce the documented JSON shape and must not leak internal detail.
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void returnsStructuredErrorAndHidesInternalsWhenHandlerThrows() throws Exception {
        mockMvc.perform(post("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path").value("/probe/boom"))
                .andExpect(jsonPath("$.timestamp").exists())
                // The originating exception message must never reach the client.
                .andExpect(jsonPath("$..*", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("database password is hunter2"))));
    }

    @Test
    void reportsRejectedFieldsForValidationFailures() throws Exception {
        mockMvc.perform(post("/probe/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"code\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
    }

    @Test
    void reportsMalformedJsonAsBadRequest() throws Exception {
        mockMvc.perform(post("/probe/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    void rejectsUnsupportedHttpMethod() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    @RestController
    static class ProbeController {

        @PostMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("database password is hunter2");
        }

        @PostMapping("/probe/validated")
        void validated(@Valid @RequestBody Payload payload) {
            // Reaching this body means validation passed; the tests never expect that.
        }

        record Payload(@NotBlank String name, @Size(min = 3) String code) {
        }
    }
}
