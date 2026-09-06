package io.github.filter;

import io.github.config.DatabaseProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class FilterRuleService {
    private final JdbcClient jdbcClient;
    private final String filterRuleTable;

    public FilterRuleService(JdbcClient jdbcClient, DatabaseProperties databaseProperties) {
        this.jdbcClient = jdbcClient;
        this.filterRuleTable = databaseProperties.schema() + ".filter_rule";
    }

    public boolean addRule(String rule) {
        var normalizedRule = normalizeRule(rule);
        return jdbcClient.sql("insert into " + filterRuleTable + " (rule) values (:rule) "
                        + "on conflict (rule) do nothing")
                .param("rule", normalizedRule)
                .update() == 1;
    }

    public boolean removeRule(long id) {
        requireValidId(id);
        return jdbcClient.sql("delete from " + filterRuleTable + " where id = :id")
                .param("id", id)
                .update() == 1;
    }

    public List<FilterRule> listRules() {
        return jdbcClient.sql("select id, rule from " + filterRuleTable + " order by id")
                .query(FilterRule.class)
                .list();
    }

    public boolean matches(String messageText) {
        if (messageText == null || messageText.isBlank()) {
            return false;
        }
        return listRules().stream().anyMatch(rule -> matchesRule(rule.rule(), messageText));
    }

    static boolean matchesRule(String rule, String messageText) {
        if (rule == null || messageText == null) {
            return false;
        }
        var normalizedText = messageText.toLowerCase(Locale.ROOT);
        var terms = parseTerms(rule);
        return !terms.isEmpty()
                && terms.stream()
                        .filter(term -> term.startsWith("!"))
                        .map(term -> term.substring(1).toLowerCase(Locale.ROOT))
                        .noneMatch(normalizedText::contains)
                && terms.stream()
                        .filter(term -> !term.startsWith("!"))
                        .map(term -> term.toLowerCase(Locale.ROOT))
                        .allMatch(normalizedText::contains);
    }

    private static String normalizeRule(String rule) {
        var terms = parseTerms(rule);
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("rule must contain at least one keyword");
        }
        return String.join(";", terms);
    }

    private static List<String> parseTerms(String rule) {
        if (rule == null) {
            return List.of();
        }
        return Arrays.stream(rule.split(";"))
                .map(String::trim)
                .filter(term -> !term.isEmpty())
                .peek(term -> {
                    if (term.equals("!")) {
                        throw new IllegalArgumentException("minus keyword must contain text after !");
                    }
                })
                .distinct()
                .toList();
    }

    private static void requireValidId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
    }
}
