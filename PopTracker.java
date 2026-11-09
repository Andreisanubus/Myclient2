package dev.myclient.module.modules;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Detecteaza cand un jucator isi consuma totemul (totem dispare din mana in timp ce e lovit). */
public final class PopTracker {
    public static final List<Consumer<PlayerEntity>> LISTENERS = new ArrayList<>();
    private static final Map<UUID, Boolean> had = new HashMap<>();

    private PopTracker() {}

    public static void tick(MinecraftClient mc) {
        if (mc.world == null) { had.clear(); return; }
        for (PlayerEntity p : mc.world.getPlayers()) {
            boolean has = p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) || p.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
            Boolean prev = had.put(p.getUuid(), has);
            if (prev != null && prev && !has && p.hurtTime > 0) for (Consumer<PlayerEntity> l : LISTENERS) l.accept(p);
        }
    }
}
