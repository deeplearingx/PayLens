package cn.daxpay.open.paylens.analysis;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentAnalysisPromptBuilderTest {
    @Test
    void promptSpellsOutTheVerificationFieldType() {
        IncidentAnalysisInput input = new IncidentAnalysisInput(Map.of("observationCount", 1), List.of(), 0);

        String prompt = IncidentAnalysisPromptBuilder.build(input, List.of(), JsonMapper.builder().build());

        assertTrue(prompt.contains("verification 必须是一个字符串，不能写成数组"));
        assertTrue(IncidentAnalysisPromptBuilder.PROMPT_VERSION.endsWith("v2"));
    }
}
