package io.github;

import io.github.config.TelegramProperties;
import io.github.config.DatabaseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({TelegramProperties.class, DatabaseProperties.class})
public class TgCollectorApplication {

  public static void main(String[] args) {
    SpringApplication.run(TgCollectorApplication.class, args);
  }
}
