/*
 * Copyright (c) 2021, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @summary Test implementations of ExecutorService.close
 * @library ../lib
 * @run junit CloseTest
 */

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class CloseTest {

    // setup to ensure each test runs under a new thread, to preclude
    // stale interrupts from changing expected close() behavior

    static abstract class TestAction {
        abstract void run() throws Exception;
    }
    static final class CheckedAction implements Runnable {
        final TestAction action;
        volatile Exception error;
        CheckedAction(TestAction a) { action = a; }
        public void run() {
            try {
                action.run();
            } catch (Exception ex) {
                error = ex;
            }
        }
    }

    // Avoids unwanted interrupts when run inder jtreg
    static ThreadGroup topThreadGroup() {
        ThreadGroup g = Thread.currentThread().getThreadGroup();
        for (ThreadGroup p;;) {
            try {
                p = g.getParent();
            } catch (Exception ok) { // possible under SecurityManager
                break;
            }
            if (p == null)
                break;
            g = p;
        }
        return g;
    }
    static final ThreadGroup closeTestThreadGroup =
        new ThreadGroup(topThreadGroup(), "closeTestThreadGroup");
    static void testInNewThread(TestAction a) throws Exception {
        var wrapper =  new CheckedAction(a);
        try {
            Thread t = new Thread(closeTestThreadGroup, wrapper);
            t.start();
            for (;;) { // ignore stray test harness exceptions
                try {
                    t.join();
                    break;
                } catch (InterruptedException ignode) {
                }
            }
        } finally {
            Exception e = wrapper.error;
            if (e != null)
                throw e;
        }
    }

    static Stream<ExecutorService> executors() {
        return Stream.of(
                // ensures that default close method is tested
                new DelegatingExecutorService(Executors.newCachedThreadPool()),

                // implementations that may override close
                Executors.newCachedThreadPool(),
                Executors.newVirtualThreadPerTaskExecutor(),
                new ForkJoinPool()
        );
    }

    /**
     * Test close with no tasks running.
     */
    @ParameterizedTest
    @MethodSource("executors")
    void testCloseWithNoTasks(ExecutorService executor) throws Exception {
        testInNewThread(new TestAction() { void run() throws Exception {
            executor.close();
            assertTrue(executor.isShutdown());
            assertTrue(executor.isTerminated());
            assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
        }});
    }

    /**
     * Test close with tasks running.
     */
    @ParameterizedTest
    @MethodSource("executors")
    void testCloseWithRunningTasks(ExecutorService executor) throws Exception {
        testInNewThread(new TestAction() { void run() throws Exception {
            Future<?> future = executor.submit(() -> {
                    Thread.sleep(Duration.ofMillis(100));
                    return "foo";
                });
            executor.close();  // waits for task to complete
            assertFalse(Thread.interrupted());
            assertTrue(executor.isShutdown());
            assertTrue(executor.isTerminated());
            assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
            assertEquals("foo", future.resultNow());
        }});
    }

    /**
     * Test shutdown with tasks running.
     */
    @ParameterizedTest
    @MethodSource("executors")
    void testShutdownWithRunningTasks(ExecutorService executor) throws Exception {
        testInNewThread(new TestAction() { void run() throws Exception {
            Future<?> future = executor.submit(() -> {
                    Thread.sleep(Duration.ofMillis(100));
                    return "foo";
                });
            executor.shutdown();
            assertFalse(Thread.interrupted());
            assertTrue(executor.isShutdown());
            assertTrue(executor.awaitTermination(1000,  TimeUnit.MILLISECONDS));
            assertTrue(executor.isTerminated());
            assertEquals("foo", future.resultNow());
        }});
    }

    /**
     * Test close with multiple tasks running
     */
    @ParameterizedTest
    @MethodSource("executors")
    void testCloseWith2RunningTasks(ExecutorService executor) throws Exception {
        testInNewThread(new TestAction() { void run() throws Exception {
            Future<?> f1 = executor.submit(() -> {
                    Thread.sleep(Duration.ofMillis(100));
                    return "foo";
                });
            Future<?> f2 = executor.submit(() -> {
                    Thread.sleep(Duration.ofMillis(100));
                    return "bar";
                });
            executor.close();  // waits for task to complete
            assertFalse(Thread.interrupted());
            assertTrue(executor.isShutdown());
            assertTrue(executor.isTerminated());
            assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
            assertEquals("foo", f1.resultNow());
            assertEquals("bar", f2.resultNow());
        }});
    }

    /**
     * Test shutdown with multiple tasks running
     */
    @ParameterizedTest
    @MethodSource("executors")
    void testShutdownWith2RunningTasks(ExecutorService executor) throws Exception {
        testInNewThread(new TestAction() { void run() throws Exception {
            Future<?> f1 = executor.submit(() -> {
                    Thread.sleep(Duration.ofMillis(100));
                    return "foo";
                });
            Future<?> f2 = executor.submit(() -> {
                    Thread.sleep(Duration.ofMillis(100));
                    return "bar";
                });
            executor.shutdown();
            assertFalse(Thread.interrupted());
            assertTrue(executor.isShutdown());
            assertTrue(executor.awaitTermination(1000,  TimeUnit.MILLISECONDS));
            assertTrue(executor.isTerminated());
            assertEquals("foo", f1.resultNow());
            assertEquals("bar", f2.resultNow());
        }});
    }

    /**
     * Test close when executor is shutdown but not terminated.
     */
    @ParameterizedTest
    @MethodSource("executors")
    void testShutdownBeforeClose(ExecutorService executor) throws Exception {
         testInNewThread(new TestAction() { void run() throws Exception {
             Phaser phaser = new Phaser(2);
             Future<?> future = executor.submit(() -> {
                     phaser.arriveAndAwaitAdvance();
                     Thread.sleep(Duration.ofMillis(100));
                     return "foo";
                 });
             phaser.arriveAndAwaitAdvance();   // wait for task to start
             executor.shutdown();  // shutdown, will not immediately terminate
             executor.close();
             assertTrue(executor.isShutdown());
             assertTrue(executor.isTerminated());
             assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
             assertEquals("foo", future.resultNow());
        }});
    }

    /**
     * Test close when terminated.
     */
    @ParameterizedTest
    @MethodSource("executors")
    void testTerminateBeforeClose(ExecutorService executor) throws Exception {
         testInNewThread(new TestAction() { void run() throws Exception {
             executor.shutdown();
             assertTrue(executor.isTerminated());
             executor.close();
             assertTrue(executor.isShutdown());
             assertTrue(executor.isTerminated());
             assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
        }});
    }

    /**
     * Test invoking close with interrupt status set.
     */
    @ParameterizedTest
    @MethodSource("executors")
    void testInterruptBeforeClose(ExecutorService executor) throws Exception {
        testInNewThread(new TestAction() { void run() throws Exception {
            Phaser phaser = new Phaser(2);
            Future<?> future = executor.submit(() -> {
                    phaser.arriveAndAwaitAdvance();
                    Thread.sleep(Duration.ofDays(1));
                    return null;
                });
            phaser.arriveAndAwaitAdvance();  // wait for task to start
            Thread.currentThread().interrupt();
            try {
                executor.close();
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();  // clear interrupt status
            }
            assertTrue(executor.isShutdown());
            assertTrue(executor.isTerminated());
            assertTrue(executor.awaitTermination(10, TimeUnit.MILLISECONDS));
            assertThrows(ExecutionException.class, future::get);
        }});
    }

    /**
     * Test interrupting thread blocked in close.
     */
    @ParameterizedTest
    @MethodSource("executors")
    void testInterruptDuringClose(ExecutorService executor) throws Exception {
        testInNewThread(new TestAction() { void run() throws Exception {
            Phaser phaser = new Phaser(2);
            Future<?> future = executor.submit(() -> {
                    phaser.arriveAndAwaitAdvance();
                    Thread.sleep(Duration.ofDays(1));
                    return null;
                });
            phaser.arriveAndAwaitAdvance();  // wait for task to start
            // schedule main thread to be interrupted
            Thread thread = Thread.currentThread();
            new Thread(() -> {
                    try {
                        Thread.sleep( Duration.ofMillis(100));
                    } catch (Exception ignore) { }
                    thread.interrupt();
            }).start();
            try {
                executor.close();
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();  // clear interrupt status
            }
            assertTrue(executor.isShutdown());
            assertTrue(executor.isTerminated());
            assertTrue(executor.awaitTermination(10, TimeUnit.MILLISECONDS));
            assertThrows(ExecutionException.class, future::get);
        }});
    }
}
