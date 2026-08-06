package com.unitedair.ai.shared;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every tunable the application exposes, bound from the {@code unitedair.*} tree of
 * application.yml.
 *
 * <p>Values that trace to a numbered SRS clause carry that clause in their comment. The
 * defaults here are the SRS-mandated ones, so a missing or truncated configuration file
 * degrades to compliant behaviour rather than to permissive behaviour.
 */
@ConfigurationProperties(prefix = "unitedair")
public class UnitedAirProperties {

    /** live | offline | auto. Resolved once at startup by {@code AiModeResolver}. */
    private String aiMode = "auto";

    private Rag rag = new Rag();
    private ChatMemory chatMemory = new ChatMemory();
    private Followups followups = new Followups();
    private Security security = new Security();
    private Providers providers = new Providers();
    private Ingestion ingestion = new Ingestion();
    private Cors cors = new Cors();
    private OperationalQuery operationalQuery = new OperationalQuery();

    // ------------------------------------------------------------------ RAG ---

    public static class Rag {
        /** SRS 4.1.1 - chunks scoring below this are never shown to the model. */
        private double similarityThreshold = 0.50;
        /** SRS 2.5 / 4.2.2 - below this the turn is escalated instead of answered. */
        private double escalationConfidence = 0.40;
        /** Optional exact-token lane. Disabled by default; vector retrieval remains primary. */
        private boolean lexicalEnabled = false;
        /** SRS 4.1.1 - must stay false; no context means no generated answer. */
        private boolean allowEmptyContext = false;

        private LaneConfig fast = new LaneConfig(12, 12, 6, 4);
        private LaneConfig deep = new LaneConfig(20, 20, 8, 6);

        private int maxRepairAttempts = 1;
        private int maxToolCallsPerRequest = 3;
        /** Reciprocal Rank Fusion smoothing constant. */
        private int rrfK = 60;

        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double v) { this.similarityThreshold = v; }
        public double getEscalationConfidence() { return escalationConfidence; }
        public void setEscalationConfidence(double v) { this.escalationConfidence = v; }
        public boolean isLexicalEnabled() { return lexicalEnabled; }
        public void setLexicalEnabled(boolean v) { this.lexicalEnabled = v; }
        public boolean isAllowEmptyContext() { return allowEmptyContext; }
        public void setAllowEmptyContext(boolean v) { this.allowEmptyContext = v; }
        public LaneConfig getFast() { return fast; }
        public void setFast(LaneConfig v) { this.fast = v; }
        public LaneConfig getDeep() { return deep; }
        public void setDeep(LaneConfig v) { this.deep = v; }
        public int getMaxRepairAttempts() { return maxRepairAttempts; }
        public void setMaxRepairAttempts(int v) { this.maxRepairAttempts = v; }
        public int getMaxToolCallsPerRequest() { return maxToolCallsPerRequest; }
        public void setMaxToolCallsPerRequest(int v) { this.maxToolCallsPerRequest = v; }
        public int getRrfK() { return rrfK; }
        public void setRrfK(int v) { this.rrfK = v; }
    }

    /** Retrieval breadth for one lane. FAST is the default; DEEP is the repair lane. */
    public static class LaneConfig {
        private int vectorTopK;
        private int lexicalTopK;
        private int rerankKeep;
        private int generateFrom;

        public LaneConfig() { }

        public LaneConfig(int vectorTopK, int lexicalTopK, int rerankKeep, int generateFrom) {
            this.vectorTopK = vectorTopK;
            this.lexicalTopK = lexicalTopK;
            this.rerankKeep = rerankKeep;
            this.generateFrom = generateFrom;
        }

        public int getVectorTopK() { return vectorTopK; }
        public void setVectorTopK(int v) { this.vectorTopK = v; }
        public int getLexicalTopK() { return lexicalTopK; }
        public void setLexicalTopK(int v) { this.lexicalTopK = v; }
        public int getRerankKeep() { return rerankKeep; }
        public void setRerankKeep(int v) { this.rerankKeep = v; }
        public int getGenerateFrom() { return generateFrom; }
        public void setGenerateFrom(int v) { this.generateFrom = v; }
    }

    // --------------------------------------------------------- chat memory ---

    public static class ChatMemory {
        /** SRS 4.3.1 - last 10 turns retained in the active window. */
        private int maxTurns = 10;
        /** SRS 4.3.1 - memory cleared after this much inactivity. */
        private int sessionTimeoutMinutes = 30;

        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int v) { this.maxTurns = v; }
        public int getSessionTimeoutMinutes() { return sessionTimeoutMinutes; }
        public void setSessionTimeoutMinutes(int v) { this.sessionTimeoutMinutes = v; }
    }

    public static class Followups {
        /** SRS 4.3.3 - at least two per response. */
        private int minimum = 2;
        public int getMinimum() { return minimum; }
        public void setMinimum(int v) { this.minimum = v; }
    }

    // ------------------------------------------------------------ security ---

    public static class Security {
        private String jwtSecret = "";
        private int jwtTtlMinutes = 60;

        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String v) { this.jwtSecret = v; }
        public int getJwtTtlMinutes() { return jwtTtlMinutes; }
        public void setJwtTtlMinutes(int v) { this.jwtTtlMinutes = v; }
    }

    // ----------------------------------------------------------- providers ---

    public static class Providers {
        private String mode = "simulator";
        private Endpoint ndc = new Endpoint();
        private Endpoint reservation = new Endpoint();

        public String getMode() { return mode; }
        public void setMode(String v) { this.mode = v; }
        public Endpoint getNdc() { return ndc; }
        public void setNdc(Endpoint v) { this.ndc = v; }
        public Endpoint getReservation() { return reservation; }
        public void setReservation(Endpoint v) { this.reservation = v; }

        public static class Endpoint {
            private String baseUrl = "";
            private String apiKey = "";
            private int connectTimeoutMs = 3000;
            private int readTimeoutMs = 5000;

            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String v) { this.baseUrl = v; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String v) { this.apiKey = v; }
            public int getConnectTimeoutMs() { return connectTimeoutMs; }
            public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }
            public int getReadTimeoutMs() { return readTimeoutMs; }
            public void setReadTimeoutMs(int v) { this.readTimeoutMs = v; }
        }
    }

    // ----------------------------------------------------------- ingestion ---

    public static class Ingestion {
        private int maxFileMb = 20;
        private List<String> allowedTypes = new ArrayList<>(List.of("PDF", "DOCX", "TXT"));
        private int embedBatchSize = 16;
        private long embedBackoffMs = 2000;
        private int embedMaxRetries = 4;
        private int chunkTargetChars = 1100;
        private int chunkOverlapChars = 150;
        private int chunkMinChars = 200;
        private boolean seedOnStartup = true;
        private String seedDirectory = "../kb";

        public int getMaxFileMb() { return maxFileMb; }
        public void setMaxFileMb(int v) { this.maxFileMb = v; }
        public List<String> getAllowedTypes() { return allowedTypes; }
        public void setAllowedTypes(List<String> v) { this.allowedTypes = v; }
        public int getEmbedBatchSize() { return embedBatchSize; }
        public void setEmbedBatchSize(int v) { this.embedBatchSize = v; }
        public long getEmbedBackoffMs() { return embedBackoffMs; }
        public void setEmbedBackoffMs(long v) { this.embedBackoffMs = v; }
        public int getEmbedMaxRetries() { return embedMaxRetries; }
        public void setEmbedMaxRetries(int v) { this.embedMaxRetries = v; }
        public int getChunkTargetChars() { return chunkTargetChars; }
        public void setChunkTargetChars(int v) { this.chunkTargetChars = v; }
        public int getChunkOverlapChars() { return chunkOverlapChars; }
        public void setChunkOverlapChars(int v) { this.chunkOverlapChars = v; }
        public int getChunkMinChars() { return chunkMinChars; }
        public void setChunkMinChars(int v) { this.chunkMinChars = v; }
        public boolean isSeedOnStartup() { return seedOnStartup; }
        public void setSeedOnStartup(boolean v) { this.seedOnStartup = v; }
        public String getSeedDirectory() { return seedDirectory; }
        public void setSeedDirectory(String v) { this.seedDirectory = v; }
    }

    public static class Cors {
        private List<String> allowedOrigins =
                new ArrayList<>(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> v) { this.allowedOrigins = v; }
    }

    public static class OperationalQuery {
        private int maxQueriesPerTurn = 3;
        private int maxJoinsPerQuery = 2;
        private int defaultRowLimit = 20;
        private int maxRowLimit = 50;
        private int maxCellsPerTurn = 1000;
        private int timeoutMs = 1500;

        public int getMaxQueriesPerTurn() { return maxQueriesPerTurn; }
        public void setMaxQueriesPerTurn(int v) { this.maxQueriesPerTurn = v; }
        public int getMaxJoinsPerQuery() { return maxJoinsPerQuery; }
        public void setMaxJoinsPerQuery(int v) { this.maxJoinsPerQuery = v; }
        public int getDefaultRowLimit() { return defaultRowLimit; }
        public void setDefaultRowLimit(int v) { this.defaultRowLimit = v; }
        public int getMaxRowLimit() { return maxRowLimit; }
        public void setMaxRowLimit(int v) { this.maxRowLimit = v; }
        public int getMaxCellsPerTurn() { return maxCellsPerTurn; }
        public void setMaxCellsPerTurn(int v) { this.maxCellsPerTurn = v; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int v) { this.timeoutMs = v; }
    }

    // ------------------------------------------------------------ accessors ---

    public String getAiMode() { return aiMode; }
    public void setAiMode(String v) { this.aiMode = v; }
    public Rag getRag() { return rag; }
    public void setRag(Rag v) { this.rag = v; }
    public ChatMemory getChatMemory() { return chatMemory; }
    public void setChatMemory(ChatMemory v) { this.chatMemory = v; }
    public Followups getFollowups() { return followups; }
    public void setFollowups(Followups v) { this.followups = v; }
    public Security getSecurity() { return security; }
    public void setSecurity(Security v) { this.security = v; }
    public Providers getProviders() { return providers; }
    public void setProviders(Providers v) { this.providers = v; }
    public Ingestion getIngestion() { return ingestion; }
    public void setIngestion(Ingestion v) { this.ingestion = v; }
    public Cors getCors() { return cors; }
    public void setCors(Cors v) { this.cors = v; }
    public OperationalQuery getOperationalQuery() { return operationalQuery; }
    public void setOperationalQuery(OperationalQuery v) { this.operationalQuery = v; }
}
