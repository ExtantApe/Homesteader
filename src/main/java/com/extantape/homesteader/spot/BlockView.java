package com.extantape.homesteader.spot;

/** Read-only view of blocks, in whatever coordinates the caller uses. */
public interface BlockView {
    Kind kind(int x, int y, int z);

    /** Lowest buildable Y, inclusive. */
    int minY();

    /** Highest buildable Y, exclusive. */
    int maxY();
}
