package com.codearena.submission.dto;

import com.codearena.shared.Language;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Everything a client is allowed to say about a submission.
 *
 * <p>Two fields. There is no userId (it comes from the session), no problemId (it comes
 * from the path), and no status, verdict, runtime, memory, attempts or worker metadata —
 * those are the server's to decide, and a field that does not exist on this record cannot
 * be overposted.
 *
 * <p>{@code language} is an enum, not a string. A value Jackson cannot map to a constant is
 * rejected before any code runs, which is what keeps a request from ever influencing a
 * command line.
 *
 * <p>The source length ceiling is configured rather than annotated here, because the limit
 * is an operational knob and belongs with the other execution limits.
 */
public record SubmissionRequest(

        @Schema(example = "CPP", description = "One of CPP, JAVA, PYTHON.")
        @NotNull(message = "Language is required")
        Language language,

        @Schema(description = "The complete program source. Stored verbatim and compiled inside an isolated container.")
        @NotBlank(message = "Source code is required")
        String sourceCode) {
}
