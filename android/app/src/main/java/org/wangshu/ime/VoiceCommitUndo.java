package org.wangshu.ime;

import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/** One acknowledged voice insertion, observed at a collapsed caret. */
final class VoiceCommitUndo {
    enum Result { RESTORED, REJECTED, UNCERTAIN }

    private final SplitSelection.Snapshot before;
    private final SplitSelection.Snapshot after;
    private boolean consumed;

    VoiceCommitUndo(SplitSelection.Snapshot before, SplitSelection.Snapshot after) {
        this.before = before;
        this.after = after;
    }

    boolean atCaret(int start, int end) {
        return start == after.start && end == after.end;
    }

    Result restore(BooleanSupplier owned, Supplier<SplitSelection.Snapshot> read, IntPredicate delete) {
        if (consumed) return Result.REJECTED;
        consumed = true;
        boolean attempted = false;
        try {
            if (!owned.getAsBoolean() || !matches(after, read.get()) || !owned.getAsBoolean()) {
                return Result.REJECTED;
            }
            attempted = true;
            if (!delete.test(after.start - before.start)) return Result.UNCERTAIN;
            if (!owned.getAsBoolean() || !matches(before, read.get()) || !owned.getAsBoolean()) {
                return Result.UNCERTAIN;
            }
            return Result.RESTORED;
        } catch (RuntimeException failure) {
            return attempted ? Result.UNCERTAIN : Result.REJECTED;
        }
    }

    private static boolean matches(SplitSelection.Snapshot expected, SplitSelection.Snapshot actual) {
        return actual != null && expected.text.equals(actual.text)
                && expected.start == actual.start && expected.end == actual.end;
    }
}
