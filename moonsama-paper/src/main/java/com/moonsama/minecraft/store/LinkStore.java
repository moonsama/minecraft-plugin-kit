package com.moonsama.minecraft.store;

import java.util.Optional;
import java.util.UUID;

public interface LinkStore extends AutoCloseable {
    void initialize();

    void createAttempt(OAuthAttempt attempt);

    Optional<OAuthAttempt> consumeAttempt(String state);

    void saveLink(LinkedPlayer player);

    Optional<LinkedPlayer> findLink(UUID mojangUuid);

    Optional<LinkedPlayer> findLinkByPlayerId(String playerId);

    @Override
    default void close() {
    }
}
