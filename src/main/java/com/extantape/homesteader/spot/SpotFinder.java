package com.extantape.homesteader.spot;

import com.extantape.homesteader.claims.ClaimCheck;
import com.extantape.homesteader.config.MoveSettings;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * Finds a safe landing without changing any block or freezing the server.
 *
 * <p>Each attempt picks a point in the ring, preferring chunks that already exist, loads that one
 * chunk with Paper's async loader, copies it on the main thread, checks its columns on a worker
 * thread, then checks the chosen column again against the live world, the border and claims on the
 * main thread. Only two searches run at once, so a crowd arriving together queues instead of
 * generating a burst of chunks.
 */
public final class SpotFinder {
    /** Columns this close to a chunk edge are skipped, so the neighbourhood check stays inside the chunk. */
    private static final int EDGE = SpotRules.LIQUID_RADIUS;
    private static final int GENERATED_SAMPLES = 8;

    private final Plugin plugin;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Homesteader spot check");
        t.setDaemon(true);
        return t;
    });
    /** Waits for a free search slot, so the scan thread never blocks. */
    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Homesteader spot queue");
        t.setDaemon(true);
        return t;
    });
    private final Semaphore slots = new Semaphore(2);

    public final AtomicLong chunksLoaded = new AtomicLong();
    public final AtomicLong chunksGenerated = new AtomicLong();

    public SpotFinder(Plugin plugin) {
        this.plugin = plugin;
    }

    public void shutdown() {
        queue.shutdownNow();
        worker.shutdownNow();
    }

    /** Result of one search. {@code spot} is null when none was found; {@code reason} says why. */
    public record Result(Location spot, int attempts, String reason, double distance) {
        Result(Location spot, int attempts, String reason) {
            this(spot, attempts, reason, Double.NaN);
        }
    }

    /** The centre of the ring: the configured point, or the world spawn. */
    public static double[] center(World world, MoveSettings s) {
        Location spawn = world.getSpawnLocation();
        return new double[] {s.centerX() != null ? s.centerX() : spawn.getX(), s.centerZ() != null ? s.centerZ() : spawn.getZ()};
    }

    /** Searches. The future completes on the main thread. */
    public CompletableFuture<Result> find(World world, MoveSettings s, ClaimCheck claims) {
        CompletableFuture<Result> out = new CompletableFuture<>();
        queue.execute(() -> {
            try {
                slots.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                out.complete(new Result(null, 0, "shutting down"));
                return;
            }
            out.whenComplete((r, t) -> slots.release());
            Bukkit.getScheduler().runTask(plugin, () -> attempt(world, s, claims, 1, out));
        });
        return out;
    }

    private void attempt(World world, MoveSettings s, ClaimCheck claims, int n, CompletableFuture<Result> out) {
        if (!plugin.isEnabled()) {
            out.complete(new Result(null, n - 1, "plugin disabled"));
            return;
        }
        if (n > s.attempts()) {
            out.complete(new Result(null, n - 1, "no safe spot in " + s.attempts() + " tries"));
            return;
        }
        double[] p = pickPoint(world, s, ThreadLocalRandom.current());
        if (p == null) {
            out.complete(new Result(null, n - 1, "the ring doesn't fit inside the world border"));
            return;
        }
        int cx = ((int) Math.floor(p[0])) >> 4;
        int cz = ((int) Math.floor(p[1])) >> 4;
        boolean generated = world.isChunkGenerated(cx, cz);
        world.getChunkAtAsync(cx, cz, true).whenComplete((chunk, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || chunk == null) {
                attempt(world, s, claims, n + 1, out);
                return;
            }
            chunksLoaded.incrementAndGet();
            if (!generated) {
                chunksGenerated.incrementAndGet();
            }
            ChunkSnapshot snap = chunk.getChunkSnapshot(false, false, false);
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            CompletableFuture.supplyAsync(() -> scan(snap, minY, maxY), worker).whenComplete((cols, e2) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Location spot = e2 == null ? confirm(world, chunk, cols, s, claims) : null;
                        if (spot != null) {
                            double[] c = center(world, s);
                            out.complete(new Result(spot, n, null, Math.hypot(spot.getX() - c[0], spot.getZ() - c[1])));
                        } else {
                            attempt(world, s, claims, n + 1, out);
                        }
                    }));
        }));
    }

    /** A point in the ring, preferring one whose chunk already exists. */
    private static double[] pickPoint(World world, MoveSettings s, RandomGenerator rnd) {
        double[] c = center(world, s);
        double cx = c[0];
        double cz = c[1];
        WorldBorder b = world.getWorldBorder();
        double half = b.getSize() / 2;
        double[] last = null;
        for (int i = 0; i < GENERATED_SAMPLES; i++) {
            double[] p = Annulus.pick(rnd, cx, cz, s.minDistance(), s.maxDistance(),
                    b.getCenter().getX(), b.getCenter().getZ(), half, s.borderMargin() + 16);
            if (p == null) {
                return last;
            }
            last = p;
            if (world.isChunkGenerated(((int) Math.floor(p[0])) >> 4, ((int) Math.floor(p[1])) >> 4)) {
                return p;
            }
        }
        return last;
    }

    /** Off the main thread: every safe column in the chunk, as {x, groundY, z} in chunk coordinates, shuffled. */
    private static List<int[]> scan(ChunkSnapshot snap, int minY, int maxY) {
        BlockView view = new BlockView() {
            @Override
            public Kind kind(int x, int y, int z) {
                if (y < minY || y >= maxY) {
                    return Kind.OPEN;
                }
                return Kinds.of(snap.getBlockData(x, y, z));
            }

            @Override
            public int minY() {
                return minY;
            }

            @Override
            public int maxY() {
                return maxY;
            }
        };
        List<int[]> found = new ArrayList<>();
        for (int x = EDGE; x < 16 - EDGE; x++) {
            for (int z = EDGE; z < 16 - EDGE; z++) {
                OptionalInt g = SpotRules.evaluate(view, x, z);
                if (g.isPresent()) {
                    found.add(new int[] {x, g.getAsInt(), z});
                }
            }
        }
        Collections.shuffle(found);
        return found;
    }

    /** Main thread: the first column that still passes on the live world, inside the border and away from claims. */
    private static Location confirm(World world, Chunk chunk, List<int[]> cols, MoveSettings s, ClaimCheck claims) {
        if (cols == null || cols.isEmpty()) {
            return null;
        }
        int bx = chunk.getX() << 4;
        int bz = chunk.getZ() << 4;
        BlockView live = liveView(world);
        WorldBorder border = world.getWorldBorder();
        double[] center = center(world, s);
        int checked = 0;
        for (int[] c : cols) {
            if (checked >= 8) {
                break;
            }
            int x = bx + c[0];
            int z = bz + c[2];
            Location feet = new Location(world, x + 0.5, c[1] + 1, z + 0.5);
            double d = Math.hypot(feet.getX() - center[0], feet.getZ() - center[1]);
            if (d < s.minDistance() || d > s.maxDistance()) {
                continue; // The chunk was picked from the ring, but its edges can stick out of it.
            }
            if (!insideBorder(border, feet, s.borderMargin())) {
                continue;
            }
            checked++; // Only the live re-checks are limited, as they're the costly part.
            OptionalInt g = SpotRules.evaluate(live, x, z);
            if (g.isEmpty() || g.getAsInt() != c[1]) {
                continue;
            }
            if (claims.nearClaim(feet, s.claimBuffer())) {
                continue;
            }
            return feet;
        }
        return null;
    }

    /** The live world as a view. Main thread only, and only inside loaded chunks. */
    public static BlockView liveView(World world) {
        return new BlockView() {
            @Override
            public Kind kind(int x, int y, int z) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    return Kind.OPEN;
                }
                return Kinds.of(world.getBlockData(x, y, z));
            }

            @Override
            public int minY() {
                return world.getMinHeight();
            }

            @Override
            public int maxY() {
                return world.getMaxHeight();
            }
        };
    }

    public static boolean insideBorder(WorldBorder b, Location l, int margin) {
        double half = b.getSize() / 2 - margin;
        return Math.abs(l.getX() - b.getCenter().getX()) <= half && Math.abs(l.getZ() - b.getCenter().getZ()) <= half;
    }
}
