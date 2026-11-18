package org.wangshu.ime;

import java.util.ArrayList;
import java.util.List;

public final class VoicePreviewDeliveryChecks {
    private static void require(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }

    public static void main(String[] args) {
        List<Runnable> queue = new ArrayList<>();
        List<String> received = new ArrayList<>();
        VoicePreviewDelivery preview = new VoicePreviewDelivery(queue::add, received::add);
        for (int index = 0; index < 1000; index++) preview.offer("hypothesis " + index);
        require(queue.size() == 1, "A stalled UI has at most one pending delivery");
        queue.remove(0).run();
        require(received.size() == 1 && received.get(0).equals("hypothesis 999"), "Latest revision wins");
        preview.offer("");
        queue.remove(0).run();
        require(received.get(1).isEmpty(), "Recognizer can retract a hypothesis");
        preview.offer("stale");
        preview.close();
        queue.remove(0).run();
        preview.offer("late");
        require(received.size() == 2 && queue.isEmpty(), "Cancel or finalization invalidates queued and later partials");
        VoicePreviewDelivery next = new VoicePreviewDelivery(queue::add, received::add);
        next.offer("new session");
        queue.remove(0).run();
        require(received.get(2).equals("new session"), "New recording has independent delivery state");
        final VoicePreviewDelivery[] reentrant = new VoicePreviewDelivery[1];
        reentrant[0] = new VoicePreviewDelivery(queue::add, text -> reentrant[0].close());
        reentrant[0].offer("close from callback");
        queue.remove(0).run();
        reentrant[0].offer("late");
        require(queue.isEmpty(), "Callback may cancel its recording");
        System.out.println("VoicePreviewDeliveryChecks 6 PASS (host only)");
    }
}
