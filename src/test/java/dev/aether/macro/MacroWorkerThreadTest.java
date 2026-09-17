package dev.aether.macro;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroWorkerThreadTest {
    @Test
    void cancellingPendingCommandsClearsTheirActivity() {
        MacroWorkerThread worker = new MacroWorkerThread();
        worker.submit("Command", () -> {});
        assertTrue(worker.hasActiveWork());

        worker.cancelCurrent();

        assertTrue(worker.isCancelled());
        assertFalse(worker.hasActiveWork());
        assertFalse(worker.isBusy());
    }

    @Test
    void cancelledClientCallbacksCannotResumeWhenANewCommandStarts() {
        MacroWorkerThread worker = new MacroWorkerThread();
        AtomicInteger actions = new AtomicInteger();
        Runnable oldCallback = worker.cancellable(actions::incrementAndGet);
        worker.cancelCurrent();
        worker.submit("Next command", () -> {});
        Runnable newCallback = worker.cancellable(actions::incrementAndGet);

        oldCallback.run();
        assertEquals(0, actions.get());
        assertTrue(worker.hasActiveWork());
        newCallback.run();
        assertEquals(1, actions.get());
    }

    @Test
    void cancelledWorkerCannotScheduleFreshActions() throws InterruptedException {
        MacroWorkerThread worker = new MacroWorkerThread();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        AtomicInteger actions = new AtomicInteger();
        AtomicReference<Runnable> lateCallback = new AtomicReference<>();
        worker.submit("Command", () -> {
            started.countDown();
            try {
                if (resume.await(2, TimeUnit.SECONDS)) {
                    worker.submit("Stale child command", actions::incrementAndGet);
                    lateCallback.set(worker.cancellable(actions::incrementAndGet));
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        worker.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            worker.cancelCurrent();
            resume.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            worker.submit("Barrier", drained::countDown);
            assertTrue(drained.await(2, TimeUnit.SECONDS));
            lateCallback.get().run();
            assertEquals(0, actions.get());
        } finally {
            resume.countDown();
            worker.cancelCurrent();
        }
    }

    @Test
    void activeWorkerCanBeReplacedByPriorityTask() throws InterruptedException {
        MacroWorkerThread worker = new MacroWorkerThread();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch replace = new CountDownLatch(1);
        CountDownLatch replacementRan = new CountDownLatch(1);
        worker.submit("Current", () -> {
            started.countDown();
            try {
                if (replace.await(2, TimeUnit.SECONDS)) {
                    worker.replaceCurrent("Priority", replacementRan::countDown);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            replace.countDown();
            assertTrue(replacementRan.await(2, TimeUnit.SECONDS));
        } finally {
            replace.countDown();
            worker.cancelCurrent();
        }
    }
}
