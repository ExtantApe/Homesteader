package com.extantape.homesteader.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordStoreTest {
    @TempDir
    Path dir;

    @Test
    void survivesAReload() throws IOException {
        Path f = dir.resolve("records.yml");
        RecordStore s = new RecordStore(f);
        assertNull(s.load());
        UUID a = UUID.randomUUID();
        s.put(a, "survival", Record.EMPTY.withStarter(Record.Starter.PENDING, "g1").withCommandsRun(2)
                .withMove(Record.Move.DONE));
        s.put(a, "creative", Record.EMPTY.withStarter(Record.Starter.MARKED, null));

        RecordStore t = new RecordStore(f);
        assertNull(t.load());
        Record r = t.get(a, "survival");
        assertEquals(Record.Starter.PENDING, r.starter());
        assertEquals("g1", r.grantId());
        assertEquals(2, r.commandsRun());
        assertEquals(Record.Move.DONE, r.move());
        assertEquals(Record.Starter.MARKED, t.get(a, "creative").starter());
        assertFalse(t.has(UUID.randomUUID(), "survival"));
        assertFalse(Files.exists(dir.resolve("records.yml.tmp")));
    }

    @Test
    void anEmptyResetRecordIsStillARecord() throws IOException {
        Path f = dir.resolve("records.yml");
        RecordStore s = new RecordStore(f);
        s.load();
        UUID a = UUID.randomUUID();
        s.put(a, "g", Record.EMPTY);
        RecordStore t = new RecordStore(f);
        t.load();
        assertTrue(t.has(a, "g"));
        assertEquals(Record.Starter.NONE, t.get(a, "g").starter());
    }

    @Test
    void damagedFileIsKeptReportedAndBlocksChanges() throws IOException {
        Path f = dir.resolve("records.yml");
        Files.writeString(f, "version: 1\nplayers:\n  not-a-uuid:\n    g: {starter: done}\n");
        RecordStore s = new RecordStore(f);
        String problem = s.load();
        assertNotNull(problem);
        assertNotNull(s.problem());
        assertTrue(Files.exists(f), "the damaged file stays where it is");
        try (var files = Files.list(dir)) {
            assertEquals(1, files.filter(p -> p.getFileName().toString().startsWith("records.yml.corrupt-")).count());
        }
        assertThrows(IOException.class, () -> s.put(UUID.randomUUID(), "g", Record.EMPTY));
        assertEquals(Files.readString(f), "version: 1\nplayers:\n  not-a-uuid:\n    g: {starter: done}\n");
    }

    @Test
    void truncatedYamlIsDamaged() throws IOException {
        Path f = dir.resolve("records.yml");
        Files.writeString(f, "version: 1\nplayers:\n  " + UUID.randomUUID() + ":\n    g: {starter: do");
        assertNotNull(new RecordStore(f).load());
    }

    @Test
    void unknownStateIsDamaged() throws IOException {
        Path f = dir.resolve("records.yml");
        Files.writeString(f, "version: 1\nplayers:\n  " + UUID.randomUUID() + ":\n    g: {starter: maybe}\n");
        assertNotNull(new RecordStore(f).load());
    }

    @Test
    void garbageIsDamaged() throws IOException {
        Path f = dir.resolve("records.yml");
        Files.write(f, new byte[] {0, 1, 2, 3, (byte) 0xff});
        assertNotNull(new RecordStore(f).load());
    }
}
