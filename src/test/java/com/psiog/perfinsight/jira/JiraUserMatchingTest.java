package com.psiog.perfinsight.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.psiog.perfinsight.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JiraUserMatchingTest {

    private final ObjectMapper om = new ObjectMapper();
    private final JiraUserStatsService svc = new JiraUserStatsService(null, new AppProperties());

    @Test
    void emailLocalPartIsAnExactMatch() throws Exception {
        var u = om.readTree("{\"accountId\":\"1\",\"displayName\":\"Nithish K\",\"emailAddress\":\"nithish.kumar@psiog.com\"}");
        assertThat(svc.score("nithish.kumar", u)).isEqualTo(1.0);
    }

    @Test
    void dottedNameMatchesDisplayNameWhenEmailHidden() throws Exception {
        var u = om.readTree("{\"accountId\":\"1\",\"displayName\":\"Nithish Kumar\"}");
        assertThat(svc.score("nithish.kumar", u)).isEqualTo(0.98);
        var other = om.readTree("{\"accountId\":\"2\",\"displayName\":\"Naveen Kumar\"}");
        assertThat(svc.score("nithish.kumar", other)).isLessThan(0.98);
    }

    @Test
    void prettyHours() {
        assertThat(JiraUserStatsService.pretty(45_000)).isEqualTo("12h 30m");
    }
}
