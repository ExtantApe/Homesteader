package com.extantape.homesteader.config;

/** Parses "MATERIAL" or "MATERIAL amount". Pure, so it's tested without a server. */
public final class ItemSpec {
    private ItemSpec() {
    }

    public record Parsed(String material, int amount) {
    }

    /** Throws IllegalArgumentException with a readable message if the line isn't valid. */
    public static Parsed parse(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("empty item line");
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length > 2) {
            throw new IllegalArgumentException("'" + line + "': expected MATERIAL or MATERIAL amount");
        }
        int amount = 1;
        if (parts.length == 2) {
            try {
                amount = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + line + "': amount isn't a number");
            }
            if (amount < 1 || amount > 64 * 36) {
                throw new IllegalArgumentException("'" + line + "': amount must be 1 to 2304");
            }
        }
        return new Parsed(parts[0], amount);
    }
}
