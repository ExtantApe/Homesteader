package com.extantape.homesteader.record;

/**
 * What happened for one player in one group. Immutable; the store swaps whole records.
 *
 * @param starter  where the starter is
 * @param grantId  the tag on the items of a pending or finished grant, or null
 * @param commandsRun how many of the starter's console commands have been dispatched. Each one is
 *                    counted, and written, just before it runs, so none can run twice
 * @param move     where the one-time move is
 * @param updated  epoch millis of the last change
 */
public record Record(Starter starter, String grantId, int commandsRun, Move move, long updated) {

    public enum Starter {
        /** Nothing given yet. */
        NONE,
        /** Items were being given. On the next visit the inventory is checked for the tag. */
        PENDING,
        /** Given. */
        DONE,
        /** An admin marked it done without giving anything. */
        MARKED,
        /** The player already had an inventory in this group when Homesteader first saw them. */
        EXISTING;

        public boolean finished() {
            return this == DONE || this == MARKED || this == EXISTING;
        }
    }

    public enum Move {
        /** Not tried yet, or tried without finding a safe spot. */
        NONE,
        DONE,
        /** Not applicable: the first visit wasn't to the move world, or the starter was finished another way. */
        SKIPPED;

        public boolean finished() {
            return this != NONE;
        }
    }

    public static final Record EMPTY = new Record(Starter.NONE, null, 0, Move.NONE, 0);

    public Record withStarter(Starter s, String id) {
        return new Record(s, id, commandsRun, move, System.currentTimeMillis());
    }

    public Record withCommandsRun(int n) {
        return new Record(starter, grantId, n, move, System.currentTimeMillis());
    }

    public Record withMove(Move m) {
        return new Record(starter, grantId, commandsRun, m, System.currentTimeMillis());
    }
}
