package com.harness.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ReportTest {

    static final List<String> LINES = List.of(
            "{\"ts\":1,\"event\":\"guard.block\",\"source\":\"claude\",\"session\":\"a\",\"rule\":\"no-verify\"}",
            "{\"ts\":2,\"event\":\"edit.check\",\"source\":\"claude\",\"session\":\"a\",\"kind\":\"compile\",\"result\":\"fail\",\"ms\":\"3000\"}",
            "{\"ts\":3,\"event\":\"verify\",\"source\":\"claude\",\"session\":\"a\",\"mode\":\"--hook\",\"result\":\"fail\",\"step\":\"mvn test\",\"ms\":\"9000\"}",
            "{\"ts\":4,\"event\":\"verify\",\"source\":\"claude\",\"session\":\"a\",\"mode\":\"--hook\",\"result\":\"pass\",\"ms\":\"8000\"}",
            "{\"ts\":5,\"event\":\"verify\",\"source\":\"claude\",\"session\":\"b\",\"mode\":\"--hook\",\"result\":\"pass\",\"ms\":\"7000\"}",
            "{\"ts\":6,\"event\":\"verify\",\"source\":\"git\",\"session\":\"git-1\",\"mode\":\"--cli\",\"result\":\"fail\",\"step\":\"contract-diff\"}",
            "{\"ts\":7,\"event\":\"verify.escalate\",\"source\":\"claude\",\"session\":\"c\"}",
            "not json at all");

    @Test
    void computesLayersAndSessionKpis() {
        Report r = new Report(Report.parse(LINES), "test");
        assertThat(r.layers()).extracting(Report.Layer::count).containsExactly(1L, 1L, 1L, 1L);
        Report.Kpis k = r.kpis();
        assertThat(k.sessions()).isEqualTo(3);        // a, b, c
        assertThat(k.firstPass()).isEqualTo("50%");   // a 首次失败，b 首次通过
        assertThat(k.avgLoops()).isEqualTo("0.5");
        assertThat(k.earlyShare()).isEqualTo("50%");  // L0+L1 = 2 / 4
        assertThat(k.escalations()).isEqualTo(1);
        assertThat(r.markdown()).contains("| Stop 一次通过率 | 50% |");
        assertThat(r.html()).contains("<title>Harness 质量看板</title>").doesNotContain("{{");
    }
}
