package org.wangshu.ime;

import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputConnection;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Synthetic text and scheduler only. No Android IPC, personal data, or filesystem access. */
public final class AssociationCancellationChecks {
    static int cases, assertions, failures;
    static final AtomicReference<Throwable> boundaryFault = new AtomicReference<>();
    interface Check { void run() throws Exception; }
    static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    static void run(String name, Check test) {
        cases++;
        boundaryFault.set(null);
        try { test.run(); check(boundaryFault.get() == null, "no swallowed host thread/owner violation: " + boundaryFault.get()); System.out.println("PASS " + name); }
        catch (Throwable error) { failures++; System.out.println("FAIL " + name + " :: " + error); }
    }
    static void violation(String message) { AssertionError error = new AssertionError(message); boundaryFault.compareAndSet(null, error); throw error; }
    static void mainOnly() { if (!Looper.isMain()) violation("UI-owned host method called off main"); }
    static void workerOnly() { if (Looper.isMain()) violation("context/provider read on main"); }
    static void await(BooleanSupplier ready, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) { Handler.pump(); Thread.sleep(1); }
        check(ready.getAsBoolean(), message);
        Handler.pump();
    }
    static void awaitWithoutPumping(BooleanSupplier ready, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        check(ready.getAsBoolean(), message);
    }
    enum Stage { NONE, READ1, COMPUTE1, COMPUTE2, READ2 }
    enum Action { CLEAR, PRIVATE_CLEAR, TIMEOUT, CLOSE, REPLACE }
    static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        void block() {
            entered.countDown();
            // Models an already-started, non-cooperative IPC/provider. Cancellation interrupts
            // its thread but does not force the boundary to return; release is explicit.
            boolean done = false;
            while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { }
        }
    }
    static final class Fixture implements AutoCloseable {
        final boolean phrase, fallback;
        final Stage blocked;
        final Gate gate = new Gate();
        final AtomicInteger reads = new AtomicInteger(), computations = new AtomicInteger();
        final InputConnection connection = new InputConnection() { };
        final Object controller;
        final ThreadPoolExecutor worker;
        volatile boolean allowed = true, throwAfterGate, interruptAfterRead;
        int session = 1, displayed, inserted, unavailable;
        volatile Object visible;
        boolean closed;
        Fixture(boolean phrase, Stage blocked, boolean fallback) throws Exception {
            this.phrase = phrase; this.blocked = blocked; this.fallback = fallback;
            if (phrase) controller = new PersonalPhraseAssociation(new PersonalPhraseAssociation.Host() {
                public boolean allowed() { mainOnly(); return allowed; }
                public int session() { mainOnly(); return session; }
                public InputConnection connection() { mainOnly(); return connection; }
                public PersonalPhraseSuggestions.Language language() { mainOnly(); return PersonalPhraseSuggestions.Language.ENGLISH; }
                public SplitSelection.Snapshot read(InputConnection owner) { return readFixture(owner); }
                public PersonalPhraseAssociation.PhraseData phrases() { compute(); return new PersonalPhraseAssociation.PhraseData(1, List.of("thank you")); }
                public long revision() { mainOnly(); return 1; }
                public void show(PersonalPhraseAssociation.Display value) { showFixture(value); }
                public boolean insert(String value) { mainOnly(); inserted++; return true; }
                public void unavailable() { mainOnly(); unavailable++; }
            });
            else controller = new MathAssociation(new MathAssociation.Host() {
                public boolean allowed() { mainOnly(); return allowed; }
                public int session() { mainOnly(); return session; }
                public InputConnection connection() { mainOnly(); return connection; }
                public SplitSelection.Snapshot read(InputConnection owner) { return readFixture(owner); }
                public void show(MathAssociation.Display value) { showFixture(value); }
                public boolean insert(String value) { mainOnly(); inserted++; return true; }
                public void unavailable() { mainOnly(); unavailable++; }
            }, (context, expression) -> { compute(); return ContextMathCandidates.suggest(context, expression); });
            Field field = controller.getClass().getDeclaredField("worker"); field.setAccessible(true);
            worker = (ThreadPoolExecutor) field.get(controller);
        }
        SplitSelection.Snapshot readFixture(InputConnection owner) {
            workerOnly(); if (owner != connection) violation("read reacquired another connection");
            int count = reads.incrementAndGet();
            if ((blocked == Stage.READ1 && count == 1) || (blocked == Stage.READ2 && count == 2)) {
                gate.block(); if (throwAfterGate) throw new IllegalStateException("synthetic cancelled read failure");
            }
            if (interruptAfterRead && count == 1) Thread.currentThread().interrupt();
            String text = phrase ? "thank " : fallback ? "x=2;\nx+1=" : "1+1=";
            return new SplitSelection.Snapshot(text, text.length(), text.length());
        }
        void compute() {
            workerOnly(); int count = computations.incrementAndGet();
            if ((blocked == Stage.COMPUTE1 && count == 1) || (blocked == Stage.COMPUTE2 && count == 2)) gate.block();
        }
        void showFixture(Object value) { mainOnly(); visible = value; if (value != null) displayed++; }
        void request() { if (phrase) ((PersonalPhraseAssociation)controller).request(); else ((MathAssociation)controller).request(); }
        void clear() { if (phrase) ((PersonalPhraseAssociation)controller).clear(); else ((MathAssociation)controller).clear(); }
        void start() { request(); Handler.advance(120); }
        void gated() throws Exception { await(() -> gate.entered.getCount() == 0, "blocked boundary was actually reached"); }
        void drained() throws Exception { await(() -> worker.getActiveCount() == 0 && worker.getQueue().isEmpty(), "physical worker completion, not cancelled Future state"); }
        long generation() throws Exception { Field field=controller.getClass().getDeclaredField("generation");field.setAccessible(true);return field.getLong(controller); }
        public void close() throws Exception {
            if (!closed) { if (phrase) ((PersonalPhraseAssociation)controller).close(); else ((MathAssociation)controller).close(); closed = true; }
            gate.release.countDown(); drained(); Handler.pump();
        }
    }
    static void cancelCase(boolean phrase, Stage stage, Action action, boolean fallback, boolean exception) throws Exception {
        try (Fixture f = new Fixture(phrase, stage, fallback)) {
            f.throwAfterGate = exception; f.start(); f.gated();
            int oldReads = f.reads.get(), oldComputations = f.computations.get();
            if (action == Action.TIMEOUT) Handler.advance(1000);
            else if (action == Action.CLOSE) { if (phrase) ((PersonalPhraseAssociation)f.controller).close(); else ((MathAssociation)f.controller).close(); f.closed = true; }
            else if (action == Action.REPLACE) { f.session++; f.request(); Handler.advance(120); }
            else { if (action == Action.PRIVATE_CLEAR) { f.session++; f.allowed = false; } f.clear(); }
            long posts = Handler.posts();
            f.gate.release.countDown(); f.drained();
            if (action == Action.REPLACE) {
                await(() -> f.visible != null, "replacement request still publishes");
                check(f.reads.get() == oldReads + 2, "old cancelled task must not perform another read; new task performs two");
                check(f.computations.get() == oldComputations + (fallback ? 2 : 1), "old cancelled task must not evaluate; only replacement evaluates");
                check(f.displayed == 1, "only replacement request displays");
                check(Handler.posts() == posts + 1, "only replacement task queues a completion");
            } else {
                check(f.reads.get() == oldReads, "cancelled operation must not start a subsequent context read");
                check(f.computations.get() == oldComputations, "cancelled operation must not start a subsequent computation/private phrase query");
                check(f.visible == null && f.displayed == 0, "cancelled result is not shown");
                check(Handler.posts() == posts, "cancelled task does not enqueue a completion even on exception");
                check(Handler.pending() == 0, "old timer removed");
            }
            check(f.inserted == 0, "request/cancellation never inserts text");
        }
    }
    public static void main(String[] args) throws Exception {
        Looper.getMainLooper();
        for (boolean phrase : new boolean[]{true, false}) {
            String kind = phrase ? "phrase" : "math";
            run(kind + " healthy request", () -> {
                try (Fixture f = new Fixture(phrase, Stage.NONE, false)) {
                    f.start(); await(() -> f.visible != null, "healthy candidate is displayed"); f.drained();
                    check(f.reads.get() == 2 && f.computations.get() == 1 && f.displayed == 1, "healthy pipeline unchanged");
                    check(f.inserted == 0, "suggestion is not automatic input");
                }
            });
            run(kind + " clear before debounce", () -> {
                try (Fixture f = new Fixture(phrase, Stage.NONE, false)) {
                    f.request(); f.clear(); Handler.advance(120); f.drained();
                    check(f.reads.get() == 0 && f.computations.get() == 0 && f.displayed == 0, "no work before debounce");
                }
            });
            for (Action action : Action.values()) {
                run(kind + " " + action + " during first read", () -> cancelCase(phrase, Stage.READ1, action, false, false));
            }
            for (Action action : new Action[]{Action.CLEAR, Action.TIMEOUT, Action.CLOSE}) {
                run(kind + " " + action + " during provider", () -> cancelCase(phrase, Stage.COMPUTE1, action, false, false));
            }
            run(kind + " cancel during second read", () -> cancelCase(phrase, Stage.READ2, Action.CLEAR, false, false));
            run(kind + " cancel then read throws", () -> cancelCase(phrase, Stage.READ1, Action.CLEAR, false, true));
            run(kind + " interruption during first read", () -> {
                try (Fixture f = new Fixture(phrase, Stage.READ1, false)) {
                    f.interruptAfterRead = true; f.start(); f.gated(); f.gate.release.countDown(); f.drained();
                    check(f.reads.get() == 1 && f.computations.get() == 0 && f.displayed == 0, "interrupted worker starts no later read or provider");
                }
            });
            run(kind + " queued old completion and timeout preserve replacement", () -> {
                try (Fixture f = new Fixture(phrase, Stage.COMPUTE2, false)) {
                    long posts=Handler.posts();f.start();
                    awaitWithoutPumping(() -> Handler.posts()>posts&&f.worker.getActiveCount()==0&&f.worker.getQueue().isEmpty(), "first completion queued but not delivered");
                    check(f.visible==null, "old completion has not executed");
                    f.session++;f.request();long epoch=f.generation();Handler.advance(120);f.gated();
                    check(f.displayed==0, "queued old completion does not display into replacement");
                    Handler.advance(880);
                    check(f.generation()==epoch, "old deadline does not cancel newer blocked request");
                    f.gate.release.countDown();f.drained();await(() -> f.visible!=null, "replacement still publishes before its own deadline");
                    check(f.displayed==1&&f.reads.get()==4&&f.computations.get()==2, "only new result displays and both uncancelled readbacks remain exact");
                    check(f.inserted==0, "queued results do not insert text");
                }
            });
        }
        run("math healthy fallback", () -> {
            try (Fixture f = new Fixture(false, Stage.NONE, true)) {
                f.start(); await(() -> f.visible != null, "fallback candidate is displayed"); f.drained();
                check(f.computations.get() == 2 && f.reads.get() == 2, "fallback remains useful");
            }
        });
        run("math cancel before fallback", () -> cancelCase(false, Stage.COMPUTE1, Action.CLEAR, true, false));
        run("math cancel during fallback", () -> cancelCase(false, Stage.COMPUTE2, Action.CLEAR, true, false));
        System.out.println("RESULT cases=" + cases + " assertions=" + assertions + " failures=" + failures + "; actual controllers, synthetic host only; Android NOT_RUN");
        if (failures > 0) System.exit(1);
    }
}
