package com.extantape.homesteader.spot;

/** What a block means for a landing, reduced to the few cases the rules care about. */
public enum Kind {
    /** Air, or something a player stands in safely: grass, flowers, snow layers. */
    OPEN,
    /** Solid ground that's safe to stand on. */
    SOLID,
    /** Leaves. Never ground, because landing on a tree isn't a start. */
    LEAVES,
    /** Water, lava, waterlogged blocks, kelp, seagrass and bubble columns. */
    LIQUID,
    /** Hurts or traps: lava, magma, fire, cactus, berry bushes, powder snow, cobwebs, dripstone, bedrock. */
    HAZARD
}
