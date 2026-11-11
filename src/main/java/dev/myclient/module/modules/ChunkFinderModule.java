
 // SPDX-License-Identifier: GPL-3.0-only
 // Adapted from Spectre 26.2 by 4ldenz.
 // See docs/spectre-base-finder-port.md.

package dev.myclient.module.modules;

import dev.myclient.chunkfinder.ChunkFinderScanner;
import dev.myclient.chunkfinder.ChunkFinderScanner.Report;
import dev.myclient.module.Category;
import dev.myclient.module.Module;
import dev.myclient.module.NumberSetting;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

public final class ChunkFinderModule extends Module {

    private final ChunkFinderScanner scanner = new ChunkFinderScanner();

    private final NumberSetting rescanDelay =
            num("Rescan Delay", 40, 10, 200, 10);

    private int ticks;
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;

    public ChunkFinderModule() {
        super("Chunk Finder", Category.BASE_RAID);
    }

    @Override
    protected void onEnable() {
        ticks = 0;
        lastChunkX = Integer.MIN_VALUE;
        lastChunkZ = Integer.MIN_VALUE;
        scanner.clear();
    }

    @Override
    protected void onDisable() {
        scanner.clear();
        lastChunkX = Integer.MIN_VALUE;
        lastChunkZ = Integer.MIN_VALUE;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            return;
        }

        ticks++;

        ChunkPos playerChunk = mc.player.getChunkPos();
        int chunkX = playerChunk.x;
        int chunkZ = playerChunk.z;

        boolean changed = chunkX != lastChunkX || chunkZ != lastChunkZ;

        if (!changed && ticks < rescanDelay.asInt()) {
            return;
        }

        WorldChunk chunk = mc.world.getChunkManager()
                .getWorldChunk(chunkX, chunkZ);

        if (chunk == null) {
            return;
        }

        Report report = scanner.scan(mc.world, chunk);

        if (report == null) {
            return;
        }

        lastChunkX = chunkX;
        lastChunkZ = chunkZ;
        ticks = 0;

        if (changed) {
            String message = "[Chunk Finder] Chunk "
                    + chunkX + ", " + chunkZ
                    + " | Activity clues: " + report.clues.context()
                    + " | Geode glow: " + report.geodeGlowCount;

            mc.player.sendMessage(Text.literal(message), false);
        }
    }

    public ChunkFinderScanner getScanner() {
        return scanner;
    }
}
