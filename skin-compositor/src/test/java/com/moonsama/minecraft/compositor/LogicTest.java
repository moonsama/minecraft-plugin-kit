package com.moonsama.minecraft.compositor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moonsama.minecraft.compositor.ComponentResolver.ResolvedSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogicTest {
    private static final Logic.Context CONTEXT = new Logic.Context(276L, "minecraft", List.of(
            new ResolvedSlot("base", "moonsama:black_bird", List.of("base", "color"), "minecraft", null),
            new ResolvedSlot("costume", "multiverse_costumes:kringle_kap", List.of("moonsama", "hide-hat", "hide-hair"), "minecraft-moonsama", null)
    ));

    private static boolean test(String json) {
        return Logic.test(JsonParser.parseString(json).getAsJsonObject(), CONTEXT);
    }

    @Test
    void assetRulesMatchSlotContentsAndTags() {
        assertThat(test("{\"type\":\"asset\",\"slot\":\"base\",\"assetId\":\"moonsama:black_bird\"}")).isTrue();
        assertThat(test("{\"type\":\"asset\",\"slot\":\"base\",\"assetId\":\"moonsama:blue_bird\"}")).isFalse();
        assertThat(test("{\"type\":\"asset\",\"slot\":\"costume\",\"assetId\":\"*\",\"tags\":[\"hide-hat\"]}")).isTrue();
        assertThat(test("{\"type\":\"asset\",\"slot\":\"costume\",\"assetId\":\"*\",\"tags\":[\"hide-outfit\"]}")).isFalse();
        // Empty slot: a rule for "any asset" fails, so its inversion passes.
        assertThat(test("{\"type\":\"asset\",\"slot\":\"hat\",\"assetId\":\"*\",\"invert\":true}")).isTrue();
        // Rule that the slot must be empty.
        assertThat(test("{\"type\":\"asset\",\"slot\":\"hat\"}")).isTrue();
        assertThat(test("{\"type\":\"asset\",\"slot\":\"base\"}")).isFalse();
    }

    @Test
    void tokenAndGateRules() {
        assertThat(test("{\"type\":\"token\",\"assetId\":276}")).isTrue();
        assertThat(test("{\"type\":\"token\",\"assetId\":\"277\"}")).isFalse();
        assertThat(test("{\"type\":\"AND\",\"conditions\":["
                + "{\"type\":\"token\",\"assetId\":276},"
                + "{\"type\":\"asset\",\"invert\":true,\"assetId\":\"*\",\"slot\":\"costume\",\"tags\":[\"hide-base\"]}]}")).isTrue();
        assertThat(test("{\"type\":\"OR\",\"conditions\":[{\"type\":\"token\",\"assetId\":1},{\"type\":\"renderType\",\"renderType\":\"minecraft\"}]}")).isTrue();
        assertThat(test("{\"type\":\"none\"}")).isFalse();
        assertThat(test("{\"type\":\"none\",\"invert\":true}")).isTrue();
        assertThat(test("{\"type\":\"NOR\",\"conditions\":[{\"type\":\"token\",\"assetId\":1}]}")).isTrue();
    }

    @Test
    void emptyStatesNeverMatch() {
        assertThat(Logic.test(new JsonObject(), CONTEXT)).isFalse();
    }
}
