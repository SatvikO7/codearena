package com.codearena.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(

        @Schema(example = "ada_lovelace", description = "Username or email address.")
        @NotBlank(message = "Username or email is required")
        String identifier,

        @NotBlank(message = "Password is required")
        String password) {
}
