package com.moonsama.minecraft.items.perks;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Per-player, per-off-hand scratch data: a cooldown, a charge balance and whatever else a
 * perk needs. In memory only, like the legacy plugin (cooldowns did not survive a restart).
 */
public final class PerkState {
    private long cooldownUntil;
    private long cooldownLength;
    private int balance;
    private long equippedAt;
    private final Map<String, Object> data = new HashMap<>();

    PerkState() {
        this.equippedAt = System.currentTimeMillis();
    }

    public long equippedAt() {
        return equippedAt;
    }

    // ------------------------------------------------------------- cooldown

    public void startCooldown(long millis) {
        cooldownLength = millis;
        cooldownUntil = System.currentTimeMillis() + millis;
    }

    public void clearCooldown() {
        cooldownUntil = 0;
        cooldownLength = 0;
    }

    public boolean ready() {
        return System.currentTimeMillis() >= cooldownUntil;
    }

    public long remainingMs() {
        return Math.max(0, cooldownUntil - System.currentTimeMillis());
    }

    public int remainingTicks() {
        return (int) Math.ceil(remainingMs() / 50.0);
    }

    public long cooldownLength() {
        return cooldownLength;
    }

    // -------------------------------------------------------------- balance

    public int balance() {
        return balance;
    }

    public void setBalance(int balance) {
        this.balance = balance;
    }

    // ------------------------------------------------------- typed scratch

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> initial) {
        return (T) data.computeIfAbsent(key, k -> initial.get());
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

    public void put(String key, Object value) {
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
    }

    public boolean has(String key) {
        return data.containsKey(key);
    }

    public void remove(String key) {
        data.remove(key);
    }
}
