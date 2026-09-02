package io.github.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("database")
public record DatabaseProperties(
    @NotBlank
    @Pattern(regexp = "[a-z_][a-z0-9_]*", message = "must be a valid lowercase SQL identifier")
    String schema,
    @NotBlank
    @Pattern(regexp = "[a-z_][a-z0-9_]*", message = "must be a valid lowercase SQL identifier")
    String tableName) {
}
