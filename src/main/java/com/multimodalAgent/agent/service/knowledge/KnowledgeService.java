package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeChunk;
import com.multimodalAgent.agent.repository.KnowledgeChunkRepository;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared public-campus knowledge ingestion and bounded Hybrid RAG retrieval. */
@Service
public class KnowledgeService {

    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;
    private static final int RRF_K = 60;
    private static final int EMBEDDING_SCHEMA_VERSION = 1;

    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final multimodalAgentProperties properties;
    private final ChromaGateway chromaGateway;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeChunker chunker = new KnowledgeChunker();
    private final TokenVectorizer legacyVectorizer = new TokenVectorizer();

    public KnowledgeService(
            KnowledgeChunkRepository knowledgeChunkRepository,
            multimodalAgentProperties properties,
            ChromaGateway chromaGateway,
            EmbeddingClient embeddingClient,
            ObjectMapper objectMapper
    ) {
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.properties = properties;
        this.chromaGateway = chromaGateway;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Ingests only administrator-maintained or bundled public-campus material.
     * Student conversation or memory paths do not call this boundary.
     */
    @Transactional
    public int ingest(String source, String content) {
        requireText(source, "source");
        requireText(content, "content");
        List<String> chunks = chunker.chunk(
                content,
                properties.getKnowledge().getChunkSize(),
                properties.getKnowledge().getChunkOverlap()
        );
        long projectedSize = knowledgeChunkRepository.count()
                - knowledgeChunkRepository.countBySource(source)
                + chunks.size();
        if (projectedSize > properties.getKnowledge().getMaxIndexChunks()) {
            throw new IllegalStateException("Shared knowledge index chunk limit exceeded");
        }
        knowledgeChunkRepository.deleteBySource(source);
        chromaGateway.deleteSource(source);
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setSource(source);
            chunk.setSourceIndex(index);
            chunk.setContent(chunks.get(index));
            List<Double> embedding = safeEmbedding(chunks.get(index));
            chunk.setEmbeddingJson(embedding.isEmpty() ? null : serializeEmbedding(embedding));
            KnowledgeChunk saved = knowledgeChunkRepository.save(chunk);
            chromaGateway.mirror(saved);
        }
        return chunks.size();
    }

    /** Uses the configured MVP mode, or the pre-P12 retriever when the feature flag is disabled. */
    @Transactional(readOnly = true)
    public List<SearchResult> retrieve(String query, int topK) {
        return retrieveWithStatus(query, topK).results();
    }

    @Transactional(readOnly = true)
    public KnowledgeRetrievalResult retrieveWithStatus(String query, int topK) {
        KnowledgeRetrievalMode requested = properties.getKnowledge().getRetrievalMode();
        if (!properties.getKnowledge().isHybridMvpEnabled()) {
            long started = System.nanoTime();
            List<SearchResult> legacy = retrieveLegacy(query, topK);
            return result(
                    requested,
                    legacy.isEmpty()
                            ? KnowledgeRetrievalStatus.NO_EVIDENCE
                            : KnowledgeRetrievalStatus.LEGACY_FALLBACK,
                    legacy,
                    started,
                    "hybrid MVP disabled; pre-P12 retriever used"
            );
        }
        return retrieve(query, topK, requested);
    }

    /** Executes one explicitly selected retrieval mode for tests, evaluation, and diagnostics. */
    @Transactional(readOnly = true)
    public KnowledgeRetrievalResult retrieve(
            String query,
            int topK,
            KnowledgeRetrievalMode mode
    ) {
        requireText(query, "query");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        Objects.requireNonNull(mode, "mode must not be null");
        long started = System.nanoTime();
        List<KnowledgeChunk> chunks = boundedChunks();
        if (chunks.isEmpty()) {
            return result(mode, KnowledgeRetrievalStatus.NO_EVIDENCE, List.of(), started,
                    "shared public knowledge index is empty");
        }

        List<SearchResult> lexical = mode == KnowledgeRetrievalMode.DENSE_ONLY
                ? List.of()
                : bm25(query, chunks);
        DenseBranch dense = mode == KnowledgeRetrievalMode.BM25_ONLY
                ? DenseBranch.notRequested()
                : dense(query, chunks);

        return switch (mode) {
            case BM25_ONLY -> finishSingle(
                    mode, KnowledgeRetrievalStatus.BM25_ONLY, lexical, topK, started,
                    "BM25(k1=1.2,b=0.75); Chinese unigrams+bigrams and normalized ASCII terms"
            );
            case DENSE_ONLY -> dense.available()
                    ? finishSingle(
                            mode, KnowledgeRetrievalStatus.DENSE_ONLY, dense.results(), topK,
                            started, dense.detail()
                    )
                    : result(
                            mode, KnowledgeRetrievalStatus.DENSE_UNAVAILABLE, List.of(), started,
                            dense.detail()
                    );
            case HYBRID_RRF -> {
                if (!dense.available()) {
                    List<SearchResult> degraded = limitAndExpand(lexical, topK);
                    KnowledgeRetrievalStatus status = degraded.isEmpty()
                            ? KnowledgeRetrievalStatus.NO_EVIDENCE
                            : KnowledgeRetrievalStatus.LEXICAL_ONLY_DEGRADED;
                    yield result(mode, status, degraded, started, dense.detail());
                }
                List<SearchResult> fused = reciprocalRankFusion(lexical, dense.results(), topK);
                yield result(
                        mode,
                        fused.isEmpty()
                                ? KnowledgeRetrievalStatus.NO_EVIDENCE
                                : KnowledgeRetrievalStatus.HYBRID,
                        limitAndExpand(fused, topK),
                        started,
                        "RRF(k=60) over BM25 and compatible persisted dense embeddings"
                );
            }
        };
    }

    private KnowledgeRetrievalResult finishSingle(
            KnowledgeRetrievalMode mode,
            KnowledgeRetrievalStatus populatedStatus,
            List<SearchResult> ranked,
            int topK,
            long started,
            String detail
    ) {
        List<SearchResult> results = limitAndExpand(ranked, topK);
        return result(
                mode,
                results.isEmpty() ? KnowledgeRetrievalStatus.NO_EVIDENCE : populatedStatus,
                results,
                started,
                detail
        );
    }

    private KnowledgeRetrievalResult result(
            KnowledgeRetrievalMode mode,
            KnowledgeRetrievalStatus status,
            List<SearchResult> results,
            long started,
            String detail
    ) {
        return new KnowledgeRetrievalResult(
                mode, status, results, Math.max(0, System.nanoTime() - started), detail
        );
    }

    private List<KnowledgeChunk> boundedChunks() {
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findAll().stream()
                .sorted(Comparator.comparing(KnowledgeChunk::getId))
                .toList();
        if (chunks.size() > properties.getKnowledge().getMaxIndexChunks()) {
            throw new IllegalStateException("Shared knowledge index exceeds configured chunk limit");
        }
        return chunks;
    }

    private List<SearchResult> bm25(String query, List<KnowledgeChunk> chunks) {
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        List<DocumentTerms> documents = chunks.stream()
                .map(chunk -> new DocumentTerms(chunk, tokenize(chunk.getContent())))
                .toList();
        double averageLength = documents.stream()
                .mapToInt(document -> document.terms().size())
                .average()
                .orElse(0.0);
        if (averageLength == 0.0) {
            return List.of();
        }
        Set<String> uniqueQueryTerms = new LinkedHashSet<>(queryTerms);
        Map<String, Long> documentFrequencies = new HashMap<>();
        for (String term : uniqueQueryTerms) {
            documentFrequencies.put(term, documents.stream()
                    .filter(value -> value.terms().contains(term))
                    .count());
        }
        List<SearchResult> ranked = new ArrayList<>();
        for (DocumentTerms document : documents) {
            Map<String, Integer> frequencies = frequencies(document.terms());
            double score = 0.0;
            for (String term : uniqueQueryTerms) {
                int termFrequency = frequencies.getOrDefault(term, 0);
                if (termFrequency == 0) {
                    continue;
                }
                long documentFrequency = documentFrequencies.get(term);
                double inverseDocumentFrequency = Math.log(
                        1.0 + (documents.size() - documentFrequency + 0.5)
                                / (documentFrequency + 0.5)
                );
                double normalization = termFrequency + BM25_K1 * (
                        1.0 - BM25_B
                                + BM25_B * document.terms().size() / averageLength
                );
                score += inverseDocumentFrequency
                        * (termFrequency * (BM25_K1 + 1.0) / normalization);
            }
            if (score > 0.0) {
                ranked.add(searchResult(document.chunk(), score));
            }
        }
        ranked.sort(rawScoreOrder());
        return List.copyOf(ranked);
    }

    private DenseBranch dense(String query, List<KnowledgeChunk> chunks) {
        List<Double> queryEmbedding = safeEmbedding(query);
        if (queryEmbedding.isEmpty()) {
            return DenseBranch.unavailable("embedding backend unavailable; lexical-only degradation");
        }
        validateEmbedding(queryEmbedding);
        List<SearchResult> ranked = new ArrayList<>();
        int compatible = 0;
        for (KnowledgeChunk chunk : chunks) {
            List<Double> embedding = parseCompatibleEmbedding(chunk.getEmbeddingJson());
            if (embedding.isEmpty()) {
                continue;
            }
            compatible++;
            double score = cosine(queryEmbedding, embedding);
            if (score > 0.0) {
                ranked.add(searchResult(chunk, score));
            }
        }
        if (compatible == 0) {
            return DenseBranch.unavailable(
                    "no persisted embedding matches configured model and dimension"
            );
        }
        ranked.sort(rawScoreOrder());
        return new DenseBranch(true, List.copyOf(ranked),
                "dense cosine over " + compatible + " compatible persisted embeddings");
    }

    private List<SearchResult> reciprocalRankFusion(
            List<SearchResult> lexical,
            List<SearchResult> dense,
            int topK
    ) {
        Map<String, FusedCandidate> candidates = new LinkedHashMap<>();
        addBranch(candidates, lexical);
        addBranch(candidates, dense);
        return candidates.values().stream()
                .sorted(Comparator
                        .comparing(FusedCandidate::score, Rational::compareTo).reversed()
                        .thenComparingInt(FusedCandidate::bestRank)
                        .thenComparing(Comparator.comparingInt(
                                FusedCandidate::contributingBranches
                        ).reversed())
                        .thenComparing(value -> stableChunkId(value.result()),
                                KnowledgeService::compareUnsignedUtf8))
                .limit(topK)
                .map(candidate -> new SearchResult(
                        candidate.result().chunkId(),
                        candidate.result().source(),
                        candidate.result().content(),
                        candidate.score().doubleValue()
                ))
                .toList();
    }

    private void addBranch(Map<String, FusedCandidate> candidates, List<SearchResult> branch) {
        for (int index = 0; index < branch.size(); index++) {
            SearchResult result = branch.get(index);
            String key = stableChunkId(result);
            int rank = index + 1;
            Rational contribution = Rational.of(1, RRF_K + rank);
            FusedCandidate previous = candidates.get(key);
            candidates.put(key, previous == null
                    ? new FusedCandidate(result, contribution, rank, 1)
                    : new FusedCandidate(
                            previous.result(),
                            previous.score().add(contribution),
                            Math.min(previous.bestRank(), rank),
                            previous.contributingBranches() + 1
                    ));
        }
    }

    private List<SearchResult> limitAndExpand(List<SearchResult> ranked, int topK) {
        return expandBestContext(ranked.stream().limit(topK).toList(), topK);
    }

    /** Pre-P12 retrieval retained behind the Hybrid MVP feature flag. */
    private List<SearchResult> retrieveLegacy(String query, int topK) {
        List<SearchResult> chromaResults = chromaGateway.query(query, topK);
        if (!chromaResults.isEmpty()) {
            return expandBestContext(chromaResults, topK);
        }
        List<Double> queryEmbedding = safeEmbedding(query);
        if (!queryEmbedding.isEmpty()) {
            List<SearchResult> dense = knowledgeChunkRepository.findAll().stream()
                    .map(chunk -> new SearchResult(
                            chunk.getId(), chunk.getSource(), chunk.getContent(),
                            cosine(queryEmbedding, parseLegacyEmbedding(chunk.getEmbeddingJson()))
                    ))
                    .filter(result -> result.score() > 0.0)
                    .sorted(rawScoreOrder())
                    .limit(topK)
                    .toList();
            if (!dense.isEmpty()) {
                return expandBestContext(dense, topK);
            }
        }
        List<SearchResult> ranked = knowledgeChunkRepository.findAll().stream()
                .map(chunk -> new SearchResult(
                        chunk.getId(), chunk.getSource(), chunk.getContent(),
                        legacyHybridScore(query, chunk.getContent())
                ))
                .filter(result -> result.score() > 0.0)
                .sorted(rawScoreOrder())
                .limit(topK)
                .toList();
        return expandBestContext(ranked, topK);
    }

    private List<SearchResult> expandBestContext(List<SearchResult> ranked, int topK) {
        if (ranked.isEmpty()) {
            return ranked;
        }
        SearchResult best = ranked.get(0);
        SearchResult expanded = expand(best);
        List<SearchResult> results = new ArrayList<>();
        results.add(expanded);
        ranked.stream()
                .skip(1)
                .filter(result -> !sameChunk(result, expanded))
                .limit(Math.max(0, topK - 1))
                .forEach(results::add);
        return List.copyOf(results);
    }

    private SearchResult expand(SearchResult result) {
        if (result.chunkId() == null) {
            return result;
        }
        return knowledgeChunkRepository.findById(result.chunkId())
                .map(chunk -> {
                    List<KnowledgeChunk> neighbors = knowledgeChunkRepository
                            .findBySourceAndSourceIndexBetweenOrderBySourceIndexAsc(
                                    chunk.getSource(),
                                    Math.max(0, chunk.getSourceIndex() - 1),
                                    chunk.getSourceIndex() + 1
                            );
                    String expandedContent = String.join("\n\n", neighbors.stream()
                            .map(KnowledgeChunk::getContent)
                            .toList());
                    return new SearchResult(
                            chunk.getId(), chunk.getSource(), expandedContent, result.score()
                    );
                })
                .orElse(result);
    }

    private String serializeEmbedding(List<Double> embedding) {
        validateEmbedding(embedding);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", EMBEDDING_SCHEMA_VERSION);
        root.put("model", embeddingClient.modelName());
        root.put("dimension", embedding.size());
        root.set("vector", objectMapper.valueToTree(embedding));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize embedding", exception);
        }
    }

    private List<Double> parseCompatibleEmbedding(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(embeddingJson);
            if (!root.isObject()
                    || root.path("schemaVersion").asInt(-1) != EMBEDDING_SCHEMA_VERSION
                    || !embeddingClient.modelName().equals(root.path("model").asText())) {
                return List.of();
            }
            List<Double> values = vector(root.path("vector"));
            if (values.size() != root.path("dimension").asInt(-1)
                    || !embeddingDimensionAllowed(values.size())) {
                return List.of();
            }
            return values;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Double> parseLegacyEmbedding(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(embeddingJson);
            return root.isArray() ? vector(root) : vector(root.path("vector"));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Double> vector(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<Double> values = new ArrayList<>(node.size());
        for (JsonNode value : node) {
            double number = value.asDouble(Double.NaN);
            if (!Double.isFinite(number)) {
                return List.of();
            }
            values.add(number);
        }
        return List.copyOf(values);
    }

    private void validateEmbedding(List<Double> embedding) {
        if (embedding.isEmpty() || !embeddingDimensionAllowed(embedding.size())
                || embedding.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalStateException("Embedding model identity or dimension is incompatible");
        }
    }

    private boolean embeddingDimensionAllowed(int dimension) {
        int configured = properties.getKnowledge().getEmbeddingDimensions();
        return dimension > 0
                && dimension <= properties.getKnowledge().getMaxEmbeddingDimensions()
                && (configured == 0 || configured == dimension);
    }

    private List<Double> safeEmbedding(String text) {
        try {
            List<Double> embedding = embeddingClient.embed(text);
            return embedding == null ? List.of() : List.copyOf(embedding);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.size(); index++) {
            double a = left.get(index);
            double b = right.get(index);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        return leftNorm == 0.0 || rightNorm == 0.0
                ? 0.0
                : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private List<String> tokenize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        normalized.codePoints().forEach(codePoint -> {
            if (isCjk(codePoint)) {
                flushAscii(ascii, terms);
                cjk.appendCodePoint(codePoint);
                return;
            }
            flushCjk(cjk, terms);
            if (Character.isLetterOrDigit(codePoint)) {
                ascii.appendCodePoint(codePoint);
            } else {
                flushAscii(ascii, terms);
            }
        });
        flushAscii(ascii, terms);
        flushCjk(cjk, terms);
        return List.copyOf(terms);
    }

    private boolean isCjk(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private void flushAscii(StringBuilder token, List<String> terms) {
        if (!token.isEmpty()) {
            terms.add(token.toString());
            token.setLength(0);
        }
    }

    private void flushCjk(StringBuilder sequence, List<String> terms) {
        if (sequence.isEmpty()) {
            return;
        }
        int[] points = sequence.codePoints().toArray();
        for (int point : points) {
            terms.add(new String(Character.toChars(point)));
        }
        for (int index = 0; index + 1 < points.length; index++) {
            terms.add(new String(points, index, 2));
        }
        sequence.setLength(0);
    }

    private Map<String, Integer> frequencies(List<String> terms) {
        Map<String, Integer> result = new HashMap<>();
        terms.forEach(term -> result.merge(term, 1, Integer::sum));
        return result;
    }

    private Comparator<SearchResult> rawScoreOrder() {
        return Comparator.comparingDouble(SearchResult::score).reversed()
                .thenComparing(KnowledgeService::stableChunkId,
                        KnowledgeService::compareUnsignedUtf8);
    }

    private SearchResult searchResult(KnowledgeChunk chunk, double score) {
        return new SearchResult(chunk.getId(), chunk.getSource(), chunk.getContent(), score);
    }

    private boolean sameChunk(SearchResult left, SearchResult right) {
        return stableChunkId(left).equals(stableChunkId(right));
    }

    private double legacyHybridScore(String query, String content) {
        double semantic = legacyVectorizer.cosine(query, content);
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        List<String> terms = List.of(normalizedQuery.split("[\\s，。！？、；：,.!?;:]+"));
        long matched = terms.stream()
                .filter(term -> term.length() >= 2)
                .filter(normalizedContent::contains)
                .count();
        double keyword = terms.isEmpty()
                ? 0.0
                : Math.min(1.0, matched / (double) terms.size());
        return semantic * 0.75 + keyword * 0.25;
    }

    private static String stableChunkId(SearchResult result) {
        return result.chunkId() == null
                ? result.source() + "\u0000" + result.content()
                : Long.toString(result.chunkId());
    }

    private static int compareUnsignedUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < Math.min(leftBytes.length, rightBytes.length); index++) {
            int comparison = Integer.compare(
                    Byte.toUnsignedInt(leftBytes[index]),
                    Byte.toUnsignedInt(rightBytes[index])
            );
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private record DocumentTerms(KnowledgeChunk chunk, List<String> terms) {
    }

    private record DenseBranch(boolean available, List<SearchResult> results, String detail) {
        private DenseBranch {
            results = List.copyOf(results);
        }

        private static DenseBranch unavailable(String detail) {
            return new DenseBranch(false, List.of(), detail);
        }

        private static DenseBranch notRequested() {
            return new DenseBranch(false, List.of(), "dense branch not requested");
        }
    }

    private record FusedCandidate(
            SearchResult result,
            Rational score,
            int bestRank,
            int contributingBranches
    ) {
    }

    /** Exact positive rational used only for RRF ordering. */
    private record Rational(BigInteger numerator, BigInteger denominator)
            implements Comparable<Rational> {

        private Rational {
            if (denominator.signum() <= 0) {
                throw new IllegalArgumentException("denominator must be positive");
            }
            BigInteger divisor = numerator.gcd(denominator);
            numerator = numerator.divide(divisor);
            denominator = denominator.divide(divisor);
        }

        private static Rational of(long numerator, long denominator) {
            return new Rational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
        }

        private Rational add(Rational other) {
            return new Rational(
                    numerator.multiply(other.denominator)
                            .add(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator)
            );
        }

        private double doubleValue() {
            return numerator.doubleValue() / denominator.doubleValue();
        }

        @Override
        public int compareTo(Rational other) {
            return numerator.multiply(other.denominator)
                    .compareTo(other.numerator.multiply(denominator));
        }
    }
}
