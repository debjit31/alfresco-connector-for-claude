package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Query expansion engine (Stage 5 of the pipeline): generates multiple
 * reformulations of a user query to improve recall.
 *
 * <h3>Techniques implemented</h3>
 * <ul>
 *   <li><b>Synonym expansion</b> — adds lexical variants of key terms.</li>
 *   <li><b>Sub-query decomposition</b> — breaks complex queries into
 *       focused sub-queries.</li>
 *   <li><b>HyDE-lite</b> — generates a hypothetical answer fragment that's
 *       likely to be similar to relevant passages (lightweight, no LLM).</li>
 * </ul>
 *
 * <p>All expansion is done locally and deterministically (no LLM call).
 * For production HyDE with an LLM, add an Ollama/OpenAI generate call or
 * plug in a more sophisticated expansion strategy.</p>
 *
 * <p>Gated on {@code rag.query-expansion.enabled}. When disabled, the
 * original query is returned as-is.</p>
 */
@Component
public class QueryExpander {

    private static final Logger log = LoggerFactory.getLogger(QueryExpander.class);

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9]+");

    /** Domain synonym map: maps common terms to their variants. */
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("document", List.of("file", "report", "paper")),
            Map.entry("file", List.of("document", "attachment")),
            Map.entry("report", List.of("document", "analysis", "summary")),
            Map.entry("meeting", List.of("conference", "session", "gathering")),
            Map.entry("budget", List.of("financial plan", "expenditure", "funding")),
            Map.entry("policy", List.of("guideline", "regulation", "rule", "procedure")),
            Map.entry("project", List.of("initiative", "program", "plan")),
            Map.entry("review", List.of("assessment", "evaluation", "analysis")),
            Map.entry("contract", List.of("agreement", "deal", "arrangement")),
            Map.entry("employee", List.of("staff", "worker", "personnel")),
            Map.entry("revenue", List.of("income", "earnings", "sales")),
            Map.entry("strategy", List.of("plan", "approach", "roadmap")),
            Map.entry("compliance", List.of("regulatory", "conformance", "adherence")),
            Map.entry("security", List.of("safety", "protection", "access control")),
            Map.entry("update", List.of("change", "modification", "revision")),
            Map.entry("approval", List.of("authorization", "sign-off", "consent"))
    );

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "but", "if", "of", "to", "in", "on", "at",
            "by", "for", "with", "as", "is", "are", "was", "were", "be", "been", "it",
            "this", "that", "do", "does", "did", "has", "have", "had", "about", "from",
            "what", "which", "who", "how", "where", "when", "why", "find", "show", "get",
            "me", "my", "all", "any", "can", "will"
    );

    private final RagProperties.QueryExpansion cfg;

    public QueryExpander(RagProperties props) {
        this.cfg = props.getQueryExpansion();
        log.info("QueryExpander active: enabled={}, variants={}", cfg.isEnabled(), cfg.getVariants());
    }

    /**
     * Expand a query into multiple variants for broader retrieval.
     *
     * @param query the original user query
     * @return list of query variants (always includes the original as first element)
     */
    public List<String> expand(String query) {
        if (!cfg.isEnabled() || query == null || query.isBlank()) {
            return List.of(query != null ? query : "");
        }

        List<String> variants = new ArrayList<>();
        variants.add(query); // Always include original

        // 1. Synonym expansion
        String synonymExpanded = expandWithSynonyms(query);
        if (!synonymExpanded.equals(query)) {
            variants.add(synonymExpanded);
        }

        // 2. Key-term focused sub-query
        String focused = generateFocusedQuery(query);
        if (focused != null && !focused.equals(query)) {
            variants.add(focused);
        }

        // 3. HyDE-lite: hypothetical answer fragment
        String hyde = generateHyDEFragment(query);
        if (hyde != null && !hyde.isBlank()) {
            variants.add(hyde);
        }

        // Limit to configured number of variants
        int maxVariants = Math.max(1, cfg.getVariants());
        if (variants.size() > maxVariants) {
            variants = variants.subList(0, maxVariants);
        }

        log.debug("Query expansion: '{}' → {} variants", truncate(query, 60), variants.size());
        return variants;
    }

    /**
     * Expand key terms with their synonyms.
     * Example: "quarterly revenue report" → "quarterly revenue income earnings report document analysis"
     */
    private String expandWithSynonyms(String query) {
        List<String> tokens = tokenize(query);
        Set<String> expanded = new LinkedHashSet<>(tokens);

        for (String token : tokens) {
            List<String> syns = SYNONYMS.get(token);
            if (syns != null) {
                // Add at most 2 synonyms per term to avoid dilution
                syns.stream().limit(2).forEach(expanded::add);
            }
        }

        if (expanded.size() == tokens.size()) {
            return query; // No expansion happened
        }

        return String.join(" ", expanded);
    }

    /**
     * Extract the most important terms and create a focused sub-query.
     */
    private String generateFocusedQuery(String query) {
        List<String> tokens = tokenize(query);
        if (tokens.size() <= 2) return null;

        // Keep only non-stopword terms — these are the "content" words
        List<String> contentWords = tokens.stream()
                .filter(t -> !STOPWORDS.contains(t) && t.length() > 2)
                .collect(Collectors.toList());

        if (contentWords.size() < 2 || contentWords.size() == tokens.size()) {
            return null;
        }

        return String.join(" ", contentWords);
    }

    /**
     * Generate a lightweight HyDE (Hypothetical Document Embedding) fragment.
     *
     * <p>Instead of calling an LLM, this creates a "hypothetical passage" by
     * reformulating the query as a declarative statement with domain context.
     * This helps bridge the query-document vocabulary gap.</p>
     */
    private String generateHyDEFragment(String query) {
        List<String> tokens = tokenize(query);
        List<String> contentWords = tokens.stream()
                .filter(t -> !STOPWORDS.contains(t) && t.length() > 2)
                .collect(Collectors.toList());

        if (contentWords.isEmpty()) return null;

        // Construct a hypothetical passage-like statement
        String terms = String.join(" ", contentWords);

        // Pattern: "This document discusses [terms]. It contains information about [terms]."
        return "This document discusses " + terms +
                ". It contains detailed information about " + terms +
                " with relevant data and analysis.";
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        var matcher = TOKEN_PATTERN.matcher(text.toLowerCase());
        while (matcher.find()) {
            String tok = matcher.group();
            if (tok.length() >= 2) {
                tokens.add(tok);
            }
        }
        return tokens;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

