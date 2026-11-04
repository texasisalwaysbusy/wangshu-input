package org.wangshu.ime;

import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Native Looper scheduling with synthetic data and fake clipboard; never reads system clipboard. */
final class ClipboardFenceChecks {
    static String run(Instrumentation test)throws Exception {
        AtomicReference<Throwable> error=new AtomicReference<>();
        test.runOnMainSync(()->{try{ClipboardFenceModel.main(new String[0]);}catch(Throwable failure){error.set(failure);}});
        if(error.get()!=null)throw new AssertionError("synthetic fence model failed",error.get());
        ClipboardFenceModel[] model={null};CountDownLatch oldDelivered=new CountDownLatch(1);
        test.runOnMainSync(()->{try{
            ClipboardFenceModel.ClipboardStore.saved.clear();ClipboardFenceModel h=new ClipboardFenceModel();h.prefs.enabled=true;h.start(false);
            ClipboardFenceModel.ClipboardManager.OnPrimaryClipChangedListener old=h.clipboardManager.listeners.get(0);
            new Handler(Looper.getMainLooper()).post(()->{try{old.onPrimaryClipChanged();}catch(Throwable failure){error.compareAndSet(null,failure);}finally{oldDelivered.countDown();}});
            h.start(true);h.clipboardManager.clip=new ClipboardFenceModel.Clip("PRIVATE_SYNTHETIC",false);h.start(false);model[0]=h;
        }catch(Throwable failure){error.set(failure);oldDelivered.countDown();}});
        if(!oldDelivered.await(5,TimeUnit.SECONDS))throw new AssertionError("old queued synthetic callback timeout");
        test.runOnMainSync(()->{ClipboardFenceModel h=model[0];if(h==null||h.clipboardManager.reads!=0||!h.suppressClipboardUntilChange||!ClipboardFenceModel.ClipboardStore.saved.isEmpty())error.compareAndSet(null,new AssertionError("old callback crossed private session"));});
        if(error.get()!=null)throw new AssertionError("queued synthetic private-boundary check failed",error.get());
        CountDownLatch freshDelivered=new CountDownLatch(1);
        test.runOnMainSync(()->{ClipboardFenceModel h=model[0];h.clipboardManager.clip=new ClipboardFenceModel.Clip("NORMAL_SYNTHETIC",false);ClipboardFenceModel.ClipboardManager.OnPrimaryClipChangedListener current=h.clipboardManager.listeners.get(h.clipboardManager.listeners.size()-1);new Handler(Looper.getMainLooper()).post(()->{try{current.onPrimaryClipChanged();}catch(Throwable failure){error.compareAndSet(null,failure);}finally{freshDelivered.countDown();}});});
        if(!freshDelivered.await(5,TimeUnit.SECONDS))throw new AssertionError("fresh synthetic callback timeout");
        test.runOnMainSync(()->{ClipboardFenceModel h=model[0];if(h.clipboardManager.reads!=1||h.suppressClipboardUntilChange||!ClipboardFenceModel.ClipboardStore.saved.equals(List.of("NORMAL_SYNTHETIC")))error.compareAndSet(null,new AssertionError("current callback did not accept normal test clip"));});
        if(error.get()!=null)throw new AssertionError("queued synthetic normal-event check failed",error.get());
        return "PASS synthetic committed-source fence model and queued old/new listener checks on the configured Looper; fake clipboard only, production service and Android system clipboard NOT_RUN";
    }
}
