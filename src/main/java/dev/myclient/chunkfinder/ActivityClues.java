
// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre 26.2 by 4ldenz.
// See docs/spectre-base-finder-port.md.

package dev.myclient.chunkfinder;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;

import net.minecraft.block.BeehiveBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;

/**
 * Conservative vanilla generation clues.
 * These clues are not a probability that a base exists.
 */
public final class ActivityClues {

    private ActivityClues() {
    }

    public enum Kind {
        KELP("Kelp"),
        CAVE_VINES("Cave vines"),
        VINES("Vines"),
        AMETHYST("Amethyst"),
        BAMBOO("Bamboo"),
        COCOA("Cocoa"),
        BEES("Bee nests / hives");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public static Kind kind(BlockState state) {
        if (state.isOf(Blocks.KELP) || state.isOf(Blocks.KELP_PLANT)) {
            return Kind.KELP;
        }

        if (state.isOf(Blocks.CAVE_VINES) || state.isOf(Blocks.CAVE_VINES_PLANT)) {
            return Kind.CAVE_VINES;
        }

        if (state.isOf(Blocks.VINE)) {
            return Kind.VINES;
        }

        if (state.isOf(Blocks.AMETHYST_CLUSTER)
                || state.isOf(Blocks.SMALL_AMETHYST_BUD)
                || state.isOf(Blocks.MEDIUM_AMETHYST_BUD)
                || state.isOf(Blocks.LARGE_AMETHYST_BUD)) {
            return Kind.AMETHYST;
        }

        if (state.isOf(Blocks.BAMBOO) || state.isOf(Blocks.BAMBOO_SAPLING)) {
            return Kind.BAMBOO;
        }

        if (state.isOf(Blocks.COCOA)) {
            return Kind.COCOA;
        }

        if (state.isOf(Blocks.BEE_NEST) || state.isOf(Blocks.BEEHIVE)) {
            return Kind.BEES;
        }

        return null;
    }

    public static final class Summary {

        private final EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);

        private int honeyNests;
        private int hives;

        public void add(BlockState state) {
            Kind kind = ActivityClues.kind(state);

            if (kind == null) {
                return;
            }

            counts.merge(kind, 1, Integer::sum);

            if (state.isOf(Blocks.BEE_NEST)
                    && state.get(BeehiveBlock.HONEY_LEVEL) > 0) {
                honeyNests++;
            }

            if (state.isOf(Blocks.BEEHIVE)) {
                hives++;
            }
        }

        // Plant age states are not length measurements.
        // Spatial growth checks belong in SuspectObjects.
        public int score() {
            return Math.min(4, honeyNests * 2)
                    + Math.min(4, hives * 4);
        }

        public boolean empty() {
            return counts.isEmpty();
        }

        public int count(Kind kind) {
            return counts.getOrDefault(kind, 0);
        }

        public String evidence() {
            ArrayList<String> lines = new ArrayList<>();

            if (honeyNests > 0) {
                lines.add("Nests with honey: " + honeyNests);
            }

            if (hives > 0) {
                lines.add("Crafted beehives: " + hives);
            }

            return lines.isEmpty()
                    ? "Natural growth alone is inconclusive"
                    : String.join(" | ", lines);
        }

        public String context() {
            ArrayList<String> lines = new ArrayList<>();

            counts.forEach((kind, count) ->
                    lines.add(kind.label + ": " + count));

            return String.join(" / ", lines);
        }
    }
}
