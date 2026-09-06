package io.github.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("telegram")
public record TelegramProperties(
    @NotBlank String phoneNumber,
    @Min(1) int apiId,
    @NotBlank String apiHash,
    @NotNull Path databaseDirectory,
    @NotNull Long notificationChannelId) {
}
