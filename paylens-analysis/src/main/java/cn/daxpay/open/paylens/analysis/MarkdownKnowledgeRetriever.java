package cn.daxpay.open.paylens.analysis;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Component
@ConditionalOnProperty(prefix = "paylens.analysis", name = "enabled", havingValue = "true")
public class MarkdownKnowledgeRetriever implements KnowledgeRetriever {
    private final List<KnowledgeChunk> chunks;
    private final String version;

    public MarkdownKnowledgeRetriever() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:paylens/knowledge/*.md");
            List<KnowledgeChunk> loaded = new ArrayList<>();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("markdown-keyword-retriever-v2-aggregate-status".getBytes(StandardCharsets.UTF_8));
            Arrays.sort(resources, Comparator.comparing(resource ->
                    java.util.Objects.requireNonNullElse(resource.getFilename(), "")));
            for (Resource resource : resources) {
                byte[] bytes = resource.getContentAsByteArray();
                digest.update(java.util.Objects.requireNonNullElse(resource.getFilename(), "").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
                String markdown = new String(bytes, StandardCharsets.UTF_8);
                KnowledgeChunk chunk = parse(markdown);
                if (chunk != null) loaded.add(chunk);
            }
            this.chunks = List.copyOf(loaded);
            this.version = HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("读取 PayLens 故障知识文档失败", e);
        }
    }

    private KnowledgeChunk parse(String markdown) {
        if (!markdown.startsWith("---")) return null;
        int end = markdown.indexOf("\n---", 3);
        if (end < 0) return null;
        Map<String, String> header = new TreeMap<>();
        for (String line : markdown.substring(3, end).split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0) header.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        String id = header.get("id");
        String title = header.get("title");
        if (id == null || title == null) return null;
        return new KnowledgeChunk(id, title, split(header.get("keywords")),
                split(header.get("statuses")), markdown.substring(end + 4).trim());
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    @Override
    public List<KnowledgeChunk> retrieve(DiagnosticInput input, int limit) {
        if (limit < 1) return List.of();
        String haystack = (input.abnormalType() + " " + input.localStatus() + " " + input.channelStatus() + " " +
                input.evidence().stream().map(DiagnosticEvidence::summary).reduce("", (a, b) -> a + " " + b))
                .toLowerCase(Locale.ROOT);
        boolean aggregatedIncident = "INCIDENT".equalsIgnoreCase(input.abnormalType());
        return chunks.stream().filter(c -> aggregatedIncident || c.statuses().isEmpty() || c.statuses().contains("ANY") ||
                        c.statuses().contains(input.localStatus()) || c.statuses().contains(input.channelStatus()))
                .map(c -> Map.entry(c, c.keywords().stream().map(String::toLowerCase)
                        .filter(haystack::contains).mapToInt(k -> k.length() > 3 ? 2 : 1).sum()))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<KnowledgeChunk, Integer>comparingByValue().reversed()
                        .thenComparing(e -> e.getKey().id()))
                .limit(limit).map(Map.Entry::getKey).toList();
    }

    @Override public String version() { return version; }
    @Override public KnowledgeRetrieval retrieveDetailed(DiagnosticInput input, int limit, String mode) {
        if (!"KEYWORD".equals(mode)) throw new IllegalStateException("RETRIEVAL_MODE_UNAVAILABLE");
        List<KnowledgeChunk> ranked = retrieve(input, limit);
        List<KnowledgeRetrieval.Hit> hits = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            hits.add(new KnowledgeRetrieval.Hit(ranked.get(i), i + 1,
                    (double) keywordScore(input, ranked.get(i)), null, 1.0 / (61 + i)));
        }
        return new KnowledgeRetrieval(mode, version(), hits);
    }
    int keywordScore(DiagnosticInput input, KnowledgeChunk chunk) {
        String haystack = (input.abnormalType() + " " + input.localStatus() + " " + input.channelStatus() + " " +
                input.evidence().stream().map(DiagnosticEvidence::summary).reduce("", (a, b) -> a + " " + b))
                .toLowerCase(Locale.ROOT);
        return chunk.keywords().stream().map(String::toLowerCase).filter(haystack::contains)
                .mapToInt(k -> k.length() > 3 ? 2 : 1).sum();
    }
    List<KnowledgeChunk> chunks() { return chunks; }
}
