package dev.myclient.module.modules;

import dev.myclient.chunkfinder.ChunkScanner;
import dev.myclient.module.Category;
import dev.myclient.module.Module;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;

public final class PerformanceModule extends Module {
    private final NumberSetting scanBudget = num("Scanner Budget (ms)", 1.5, 0.5, 4.0, 0.25);
    private final NumberSetting blocksPerStep = num("Blocks Per Step", 1024, 256, 4096, 256);
    private final Setting<Boolean> pauseInMenus = bool("Pause Scanning In Menus", true);

    public PerformanceModule() {
        super("FPS Booster", Category.RENDER);
        header("Chunk Scanner");
        header("Lower values reduce background scan load; they do not guarantee higher FPS.");
    }

    @Override public void onTick() {
        if (pauseInMenus.value && mc.currentScreen != null) {
            ChunkScanner.configure(0, 0);
            return;
        }
        ChunkScanner.configure(blocksPerStep.asInt(), (long) (scanBudget.get() * 1_000_000L));
    }

    @Override protected void onDisable() { ChunkScanner.configure(1024, 1_500_000L); }
}
