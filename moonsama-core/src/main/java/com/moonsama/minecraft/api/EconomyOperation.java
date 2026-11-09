package com.moonsama.minecraft.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EconomyOperation(
        UUID operationId,
        String ownerPlugin,
        String businessKey,
        String portalIdempotencyKey,
        Kind kind,
        State state,
        Receipt receipt,
        Failure failure,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Kind {
        SPEND,
        REWARD,
        REFUND
    }

    public enum State {
        QUEUED,
        SENDING,
        RECOVERING,
        RETRY_WAIT,
        SUCCEEDED,
        REJECTED,
        NEEDS_REAUTH,
        QUARANTINED_BUG,
        CANCELLED
    }

    public record Receipt(
            String receiptId,
            String idempotencyKey,
            Kind kind,
            String reference,
            String description,
            String originalKey,
            long createdAt,
            List<ReceiptLine> items
    ) {
        public Receipt {
            items = List.copyOf(items);
        }
    }

    public record ReceiptLine(
            UUID mojangUuid,
            String collection,
            String tokenId,
            String amount,
            String burned,
            String toTreasury,
            int treasuryBps
    ) {
    }

    public record Failure(Integer status, String code, String message) {
    }
}
