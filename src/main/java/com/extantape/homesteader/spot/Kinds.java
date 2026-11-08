package com.extantape.homesteader.spot;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

import java.util.EnumSet;
import java.util.Set;

/** Sorts Bukkit blocks into {@link Kind}s. Safe off the main thread: it reads only block data. */
public final class Kinds {
    private static final Set<Material> HAZARDS = EnumSet.noneOf(Material.class);
    private static final Set<Material> LIQUIDS = EnumSet.noneOf(Material.class);

    static {
        for (String n : new String[] {"LAVA", "MAGMA_BLOCK", "FIRE", "SOUL_FIRE", "CAMPFIRE", "SOUL_CAMPFIRE",
                "CACTUS", "SWEET_BERRY_BUSH", "POWDER_SNOW", "COBWEB", "POINTED_DRIPSTONE", "WITHER_ROSE",
                "BEDROCK", "END_PORTAL", "NETHER_PORTAL", "END_GATEWAY", "SCAFFOLDING", "TRIPWIRE",
                "STONECUTTER", "LIGHTNING_ROD", "BIG_DRIPLEAF"}) {
            Material m = Material.getMaterial(n);
            if (m != null) {
                HAZARDS.add(m);
            }
        }
        for (String n : new String[] {"WATER", "BUBBLE_COLUMN", "KELP", "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS"}) {
            Material m = Material.getMaterial(n);
            if (m != null) {
                LIQUIDS.add(m);
            }
        }
    }

    private Kinds() {
    }

    public static Kind of(BlockData data) {
        Material m = data.getMaterial();
        if (m == Material.LAVA) {
            return Kind.HAZARD;
        }
        if (LIQUIDS.contains(m) || (data instanceof Waterlogged w && w.isWaterlogged())) {
            return Kind.LIQUID;
        }
        if (HAZARDS.contains(m)) {
            return Kind.HAZARD;
        }
        if (m.name().endsWith("_LEAVES")) {
            return Kind.LEAVES;
        }
        if (m.isAir() || !m.isSolid()) {
            return Kind.OPEN;
        }
        return Kind.SOLID;
    }
}
