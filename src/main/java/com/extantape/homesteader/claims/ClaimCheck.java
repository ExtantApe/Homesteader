package com.extantape.homesteader.claims;

import org.bukkit.Location;

/** Whether a spot is inside or near someone's claim. Main thread only. */
public interface ClaimCheck {
    boolean nearClaim(Location loc, int buffer);

    String name();

    ClaimCheck NONE = new ClaimCheck() {
        @Override
        public boolean nearClaim(Location loc, int buffer) {
            return false;
        }

        @Override
        public String name() {
            return "none";
        }
    };
}
