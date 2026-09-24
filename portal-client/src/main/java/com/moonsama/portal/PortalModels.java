package com.moonsama.portal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class PortalModels {
    private PortalModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rights(boolean spend, boolean reward) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogEntry(
            String collection,
            String description,
            List<String> idRange,
            String assetType,
            int decimals,
            boolean enabled,
            Rights rights
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlayerStanding(
            String playerId,
            String gamerTag,
            String standing,
            String firstSeenAt
    ) {
        public boolean isOk() {
            return "ok".equals(standing);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChainBalance(
            String chainKey,
            String balance
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Holding(
            String collection,
            String tokenId,
            String balance,
            List<ChainBalance> breakdown
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlayerHoldings(
            String playerId,
            boolean known,
            List<Holding> holdings
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CollectionHoldings(
            String playerId,
            String collection,
            List<Holding> holdings
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RedeemedCode(
            String gamerTag,
            String standing,
            List<Holding> holdings
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenMetadata(
            String collection,
            String tokenId,
            String name,
            String description,
            String image,
            Object attributes
    ) {
    }

    public record Line(
            String playerId,
            String collection,
            String tokenId,
            String amount
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WriteRequest(
            List<Line> items,
            String description,
            String reference
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RefundRequest(
            String originalKey,
            List<Line> items,
            String description,
            String reference
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReceiptItem(
            String playerId,
            String collection,
            String tokenId,
            String amount,
            String burned,
            String toTreasury,
            int treasuryBps,
            Long sweptAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Receipt(
            String receiptId,
            String idempotencyKey,
            String kind,
            String outcome,
            String reference,
            String description,
            String originalKey,
            long createdAt,
            List<ReceiptItem> items
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HoldRequest(
            String playerId,
            String collection,
            String tokenId,
            int ttlSeconds,
            String reference
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hold(
            String holdId,
            String playerId,
            String collection,
            String tokenId,
            String expiresAt,
            String releasedAt,
            String reference,
            String createdAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(
            String playerId,
            String collection,
            long updatedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Erasure(
            String playerId,
            long erasedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FeedPage<T>(
            List<T> results,
            long nextSince,
            String nextCursor,
            boolean hasMore
    ) {
    }

    public record FeedStart(Long since, String cursor) {
        public FeedStart {
            if ((since == null) == (cursor == null)) {
                throw new IllegalArgumentException("Exactly one of since or cursor is required");
            }
            if (since != null && since < 0) {
                throw new IllegalArgumentException("since must be non-negative");
            }
        }

        public static FeedStart since(long since) {
            return new FeedStart(since, null);
        }

        public static FeedStart cursor(String cursor) {
            return new FeedStart(null, cursor);
        }
    }

    public record RateLimit(
            long limit,
            long remaining,
            long reset
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenSet(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("refresh_token_expires_in") long refreshTokenExpiresIn,
            @JsonProperty("id_token") String idToken
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserInfo(
            String sub,
            @JsonProperty("preferred_username") String preferredUsername
    ) {
    }
}
