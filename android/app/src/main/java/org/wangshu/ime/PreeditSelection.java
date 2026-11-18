package org.wangshu.ime;

import java.util.ArrayDeque;

/** Distinguish an editor move from the caret feedback for our confirmed insertions. */
final class PreeditSelection {
    private static final int MAX_PENDING = 8;
    private final ArrayDeque<Insertion> pending = new ArrayDeque<>();
    private int start = -1, end = -1;

    static final class Insertion {
        final int caret;
        Insertion(int caret) { this.caret = caret; }
    }

    void reset(int start, int end) {
        pending.clear();
        this.start = start;
        this.end = end;
    }

    Insertion expect(int utf16Units) {
        Insertion last = pending.peekLast();
        int left = last == null ? Math.min(start, end) : last.caret;
        if (left < 0 || utf16Units <= 0 || utf16Units > Integer.MAX_VALUE - left) return null;
        Insertion next = new Insertion(left + utf16Units);
        if (pending.size() == MAX_PENDING) pending.removeFirst();
        pending.addLast(next);
        return next;
    }

    void refused(Insertion insertion) {
        if (insertion != null) pending.removeLastOccurrence(insertion);
    }

    boolean externalMove(int oldStart, int oldEnd, int newStart, int newEnd) {
        // Android may coalesce multiple notifications into the latest caret.
        Insertion matching = null;
        if (newStart == newEnd) {
            for (Insertion insertion : pending) {
                if (insertion.caret == newStart) {
                    matching = insertion;
                    break;
                }
            }
        }
        if (matching != null) {
            while (pending.removeFirst() != matching) { /* Consume through the acknowledged caret. */ }
        } else if (oldStart != newStart || oldEnd != newEnd) {
            reset(newStart, newEnd);
            return true;
        }
        start = newStart;
        end = newEnd;
        return false;
    }
}
