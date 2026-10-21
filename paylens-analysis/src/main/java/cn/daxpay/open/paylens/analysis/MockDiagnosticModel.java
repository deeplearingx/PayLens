package cn.daxpay.open.paylens.analysis;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class MockDiagnosticModel implements DiagnosticModel {
    private final ObjectMapper mapper;
    public MockDiagnosticModel(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public String analyze(String prompt) throws Exception {
        JsonNode request = mapper.readTree(prompt);
        ArrayNode evidence = (ArrayNode) request.path("evidence");
        JsonNode knowledge = request.path("knowledge");
        if (request.has("incident")) return analyzeIncident(evidence, knowledge, request.path("incident"));
        ObjectNode root = mapper.createObjectNode();
        root.put("summary", "模拟分析：请先核对平台订单与通道侧状态，以下内容仅为排查建议。");
        ArrayNode facts = root.putArray("facts");
        if (!evidence.isEmpty()) {
            ObjectNode fact = facts.addObject();
            fact.put("statement", "本次分析快照包含这些交易证据，具体状态以证据内容为准。");
            ArrayNode ids = fact.putArray("evidenceIds");
            evidence.forEach(item -> ids.add(item.path("id").asText()));
        }
        root.putArray("possibleCauses");
        ArrayNode steps = root.putArray("checkSteps");
        steps.add("核对平台交易时间线中的回调和查单记录。");
        steps.add("查询通道订单最终状态，记录查询时间和返回状态。");
        ArrayNode missing = root.putArray("missingInformation");
        if (evidence.isEmpty()) {
            facts.removeAll();
            steps.removeAll();
            missing.add("当前没有可核验的交易证据，无法判断具体原因。");
        }
        root.putArray("knowledgeCitations");
        root.putNull("knowledgeOmissionReason");
        if (knowledge.isArray() && !knowledge.isEmpty()) {
            root.put("knowledgeOmissionReason", "假模型不判断检索知识与本案的相关性。");
        }
        return mapper.writeValueAsString(root);
    }

    private String analyzeIncident(ArrayNode evidence, JsonNode knowledge, JsonNode incident) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("commonPhenomenon", evidence.isEmpty()
                ? "当前事件快照没有可核验的成员证据。"
                : "模拟事件分析：请结合成员证据核对支付异常的共同表现。这里只验证流程，不代表实际根因判断。" );
        ArrayNode facts = root.putArray("facts");
        if (!evidence.isEmpty()) {
            ObjectNode fact = facts.addObject();
            fact.put("statement", "本次事件快照包含列出的证据记录；统计数字以服务端保存的数据为准。");
            fact.putArray("evidenceIds").add(evidence.get(0).path("id").asText());
        }
        root.putArray("differences");
        root.putArray("possibleExplanations");
        ArrayNode steps = root.putArray("checkSteps");
        if (!evidence.isEmpty()) steps.add("抽查事件中的最早、最新记录及不同错误类别，确认现象是否一致。");
        ArrayNode missing = root.putArray("missingInformation");
        int omitted = incident.path("omittedEvidenceCount").asInt(0);
        if (omitted > 0) missing.add("本次输入省略了 " + omitted + " 条成员证据，请按事件详情抽样核对。");
        if (evidence.isEmpty()) missing.add("当前没有可引用的成员证据。");
        root.putArray("knowledgeCitations");
        root.putNull("knowledgeOmissionReason");
        if (knowledge.isArray() && !knowledge.isEmpty()) {
            root.put("knowledgeOmissionReason", "假模型不判断检索知识与当前事件是否相关。");
        }
        return mapper.writeValueAsString(root);
    }

    @Override public String modelId() { return "paylens-mock-v1"; }
}
