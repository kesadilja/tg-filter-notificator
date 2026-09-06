package io.github.filter;

import io.github.config.DatabaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(FilterRuleService.class)
@EnableConfigurationProperties(DatabaseProperties.class)
@SpringBootTest
class FilterRuleServiceTest {

    @Autowired
    private FilterRuleService service;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DatabaseProperties databaseProperties;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("truncate table " + databaseProperties.schema() + ".filter_rule restart identity").update();
    }

    @Test
    void matchesAllKeywordsAndRejectsMinusKeywordsIgnoringCase() {
        assertThat(FilterRuleService.matchesRule("Java; Spring; !Junior", "Senior JAVA and spring engineer"))
                .isTrue();
        assertThat(FilterRuleService.matchesRule("Java; Spring; !Junior", "Junior Java and Spring engineer"))
                .isFalse();
        assertThat(FilterRuleService.matchesRule("Java; Spring", "Java engineer"))
                .isFalse();
    }

    @Test
    void supportsMinusOnlyRulesAndIgnoresEmptySegments() {
        assertThat(FilterRuleService.matchesRule(" ; !spam; ", "A useful message")).isTrue();
        assertThat(FilterRuleService.matchesRule(" ; !spam; ", "This is SPAM")).isFalse();
        assertThat(FilterRuleService.matchesRule(" ; ; ", "A useful message")).isFalse();
    }

    @Test
    void persistsAndAppliesFilterRules() {
        assertThat(service.addRule(" Java ; Spring ; !Junior ")).isTrue();
        assertThat(service.addRule("Java;Spring;!Junior")).isFalse();

        var rule = service.listRules().getFirst();
        assertThat(rule.rule()).isEqualTo("Java;Spring;!Junior");
        assertThat(service.matches("Senior java and SPRING developer")).isTrue();
        assertThat(service.matches("Junior Java and Spring developer")).isFalse();
        assertThat(service.removeRule(rule.id())).isTrue();
        assertThat(service.matches("Senior Java and Spring developer")).isFalse();
    }

    @Test
    void realRules() {
        var rule = "одежда;детская;!школьник";
        assertFalse(FilterRuleService.matchesRule(rule, "Детская одежда на дошкольника"));
        assertFalse(FilterRuleService.matchesRule(rule, "Детская одежда для школьника"));
        assertFalse(FilterRuleService.matchesRule(rule, "Одежда для ребёнков"));
        assertTrue(FilterRuleService.matchesRule(rule, "Одежда детская: бодики, слипы"));
    }
}
