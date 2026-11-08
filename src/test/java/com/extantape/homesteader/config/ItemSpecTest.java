package com.extantape.homesteader.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemSpecTest {
    @Test
    void parses() {
        assertEquals(new ItemSpec.Parsed("CHEST", 1), ItemSpec.parse("CHEST"));
        assertEquals(new ItemSpec.Parsed("bread", 8), ItemSpec.parse("  bread   8 "));
    }

    @Test
    void rejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> ItemSpec.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ItemSpec.parse("BREAD lots"));
        assertThrows(IllegalArgumentException.class, () -> ItemSpec.parse("BREAD 0"));
        assertThrows(IllegalArgumentException.class, () -> ItemSpec.parse("BREAD 8 9"));
    }
}
