package com.extantape.homesteader.record;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The once-per-player-and-group record, in one YAML file.
 *
 * <p>Every change is written before the caller goes on: to a temporary file, flushed to disk, then
 * moved over the old one, so the file on disk is always a whole version. If the file can't be read,
 * it is copied aside, left in place, and the store refuses all changes. Homesteader then gives
 * nothing to anyone until an admin fixes or removes the file and reloads. That's what stops a
 * damaged file from re-gifting everyone.
 */
public final class RecordStore {
    private static final int VERSION = 1;

    private final Path file;
    private final Map<UUID, Map<String, Record>> data = new HashMap<>();
    private String problem;

    public RecordStore(Path file) {
        this.file = file;
    }

    /** Loads the file. Returns null if fine, or a description of the problem. */
    public String load() {
        data.clear();
        problem = null;
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(text);
            if (!text.isBlank() && yaml.getInt("version", -1) != VERSION) {
                throw new InvalidConfigurationException("missing or unknown 'version'");
            }
            ConfigurationSection players = yaml.getConfigurationSection("players");
            if (players != null) {
                for (String key : players.getKeys(false)) {
                    UUID id = UUID.fromString(key);
                    ConfigurationSection groups = players.getConfigurationSection(key);
                    if (groups == null) {
                        throw new InvalidConfigurationException("player " + key + " isn't a section");
                    }
                    for (String group : groups.getKeys(false)) {
                        ConfigurationSection r = groups.getConfigurationSection(group);
                        if (r == null) {
                            throw new InvalidConfigurationException("record " + key + "/" + group + " isn't a section");
                        }
                        Record rec = new Record(
                                Record.Starter.valueOf(r.getString("starter", "none").toUpperCase(Locale.ROOT)),
                                r.getString("grant"),
                                r.getInt("commands-run", 0),
                                Record.Move.valueOf(r.getString("move", "none").toUpperCase(Locale.ROOT)),
                                r.getLong("updated", 0));
                        data.computeIfAbsent(id, k -> new HashMap<>()).put(group, rec);
                    }
                }
            }
            return null;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
            data.clear();
            Path copy = file.resolveSibling(file.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
            String kept;
            try {
                Files.copy(file, copy, StandardCopyOption.COPY_ATTRIBUTES);
                kept = "a copy is at " + copy.getFileName();
            } catch (IOException ce) {
                kept = "copying it aside also failed: " + ce.getMessage();
            }
            problem = file.getFileName() + " can't be read (" + e.getMessage() + "); " + kept
                    + ". Homesteader gives nothing until it's fixed or removed and you run /homesteader reload.";
            return problem;
        }
    }

    /** Non-null when the file couldn't be read. Nothing may be given while this is set. */
    public String problem() {
        return problem;
    }

    public Record get(UUID player, String group) {
        Map<String, Record> m = data.get(player);
        Record r = m == null ? null : m.get(group);
        return r == null ? Record.EMPTY : r;
    }

    /** True if there's a record at all, even an empty one left by a reset. */
    public boolean has(UUID player, String group) {
        Map<String, Record> m = data.get(player);
        return m != null && m.containsKey(group);
    }

    public Map<String, Record> all(UUID player) {
        Map<String, Record> m = data.get(player);
        return m == null ? Map.of() : Collections.unmodifiableMap(m);
    }

    public Iterable<UUID> players() {
        return data.keySet();
    }

    /** Stores and writes to disk before returning. Throws if the store is refusing changes or the write fails. */
    public void put(UUID player, String group, Record rec) throws IOException {
        if (problem != null) {
            throw new IOException("record file is damaged; refusing changes");
        }
        Map<String, Record> m = data.computeIfAbsent(player, k -> new HashMap<>());
        Record old = m.put(group, rec);
        try {
            save();
        } catch (IOException e) {
            if (old == null) {
                m.remove(group);
            } else {
                m.put(group, old);
            }
            throw e;
        }
    }

    /** Removes a record, writing to disk. Returns false if there was none. */
    public boolean remove(UUID player, String group) throws IOException {
        if (problem != null) {
            throw new IOException("record file is damaged; refusing changes");
        }
        Map<String, Record> m = data.get(player);
        if (m == null || !m.containsKey(group)) {
            return false;
        }
        Record old = m.remove(group);
        try {
            save();
        } catch (IOException e) {
            m.put(group, old);
            throw e;
        }
        return true;
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(java.util.List.of(
                "Homesteader records: who has had their starter and move, per world group.",
                "Edit only with the server stopped, or use /homesteader reset and /homesteader done."));
        yaml.set("version", VERSION);
        Map<String, Object> sorted = new LinkedHashMap<>();
        data.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    if (e.getValue().isEmpty()) {
                        return;
                    }
                    Map<String, Object> groups = new LinkedHashMap<>();
                    e.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(g -> {
                        Record r = g.getValue();
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("starter", r.starter().name().toLowerCase(Locale.ROOT));
                        if (r.grantId() != null) {
                            out.put("grant", r.grantId());
                        }
                        out.put("commands-run", r.commandsRun());
                        out.put("move", r.move().name().toLowerCase(Locale.ROOT));
                        out.put("updated", r.updated());
                        groups.put(g.getKey(), out);
                    });
                    sorted.put(e.getKey().toString(), groups);
                });
        yaml.createSection("players", sorted);
        byte[] bytes = yaml.saveToString().getBytes(StandardCharsets.UTF_8);

        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
