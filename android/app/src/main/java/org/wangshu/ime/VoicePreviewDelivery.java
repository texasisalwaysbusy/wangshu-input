package org.wangshu.ime;

/** Coalesces worker hypotheses into one pending UI delivery per recording. */
final class VoicePreviewDelivery {
    interface Scheduler { void post(Runnable action); }
    interface Listener { void partial(String text); }

    private final Scheduler scheduler;
    private final Listener listener;
    private String pending;
    private boolean scheduled;
    private boolean closed;

    VoicePreviewDelivery(Scheduler scheduler, Listener listener) {
        this.scheduler = scheduler;
        this.listener = listener;
    }

    void offer(String text) {
        synchronized (this) {
            if (closed) return;
            pending = text;
            if (scheduled) return;
            scheduled = true;
        }
        scheduler.post(this::deliver);
    }

    private synchronized void deliver() {
        scheduled = false;
        if (closed) return;
        String text = pending;
        pending = null;
        listener.partial(text);
    }

    synchronized void close() {
        closed = true;
        pending = null;
    }
}
