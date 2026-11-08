package com.extantape.homesteader.config;

/**
 * The optional one-time move.
 *
 * @param world        the world to move players in. The move is only tried there.
 * @param centerX      centre X, or null for the world spawn
 * @param centerZ      centre Z, or null for the world spawn
 * @param minDistance  closest a landing may be to the centre
 * @param maxDistance  farthest a landing may be from the centre
 * @param claimBuffer  blocks to keep from any claim
 * @param borderMargin blocks to keep from the world border
 * @param attempts     chunks to try before giving up. Each try loads at most one chunk.
 */
public record MoveSettings(boolean enabled, String world, Double centerX, Double centerZ,
                           int minDistance, int maxDistance, int claimBuffer, int borderMargin, int attempts) {
}
