package org.wangshu.ime;

public final class VoiceCommitUndoChecks {
    private static final SplitSelection.Snapshot BEFORE =
            new SplitSelection.Snapshot("前缀后文", 2, 2);
    private static final SplitSelection.Snapshot AFTER =
            new SplitSelection.Snapshot("前缀👩🏽‍💻\n后文", 10, 10);

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        SplitSelection.Snapshot[] actual = {AFTER};
        VoiceCommitUndo undo = new VoiceCommitUndo(BEFORE, AFTER);
        int[] writes = {0};
        require(undo.restore(() -> true, () -> actual[0], units -> {
            writes[0]++;
            require(units == "👩🏽‍💻\n".length(), "Only inserted UTF-16 units");
            actual[0] = BEFORE;
            return true;
        }) == VoiceCommitUndo.Result.RESTORED, "Exact original text and caret");
        require(undo.restore(() -> true, () -> AFTER, units -> {
            throw new AssertionError("Repeated tap");
        }) == VoiceCommitUndo.Result.REJECTED && writes[0] == 1, "One write per insertion");
        require(new VoiceCommitUndo(BEFORE, AFTER).restore(() -> false, () -> AFTER, units -> {
            throw new AssertionError("Foreign editor");
        }) == VoiceCommitUndo.Result.REJECTED, "Owner mismatch");
        require(new VoiceCommitUndo(BEFORE, AFTER).restore(() -> true,
                () -> new SplitSelection.Snapshot(AFTER.text, 9, 9), units -> {
                    throw new AssertionError("Stale caret");
                }) == VoiceCommitUndo.Result.REJECTED, "Selection mismatch");
        boolean[] owned = {true};
        require(new VoiceCommitUndo(BEFORE, AFTER).restore(() -> owned[0], () -> {
            owned[0] = false;
            return AFTER;
        }, units -> {
            throw new AssertionError("Focus changed during read");
        }) == VoiceCommitUndo.Result.REJECTED, "Recheck owner after read");
        require(new VoiceCommitUndo(BEFORE, AFTER).restore(() -> true, () -> AFTER,
                units -> true) == VoiceCommitUndo.Result.UNCERTAIN, "Require exact post-write readback");
        require(new VoiceCommitUndo(BEFORE, AFTER).restore(() -> true, () -> AFTER, units -> {
            throw new IllegalStateException("Unknown write result");
        }) == VoiceCommitUndo.Result.UNCERTAIN, "Write exception stays uncertain");
        System.out.println("VoiceCommitUndoChecks PASS (synthetic snapshots, no Android editor)");
    }
}
