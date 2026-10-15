package com.multimodalAgent.agent.runtime.tool;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Shared bounded-by-time scheduler for blocking tool adapters. */
final class ToolExecutionSchedulers {

    private static final AtomicLong THREAD_SEQUENCE = new AtomicLong();
    private static final ThreadFactory THREAD_FACTORY = task -> {
        Thread thread = new Thread(
                task,
                "agent-tool-" + THREAD_SEQUENCE.incrementAndGet()
        );
        thread.setDaemon(true);
        return thread;
    };
    private static final ExecutorService SHARED = new ThreadPoolExecutor(
            4,
            16,
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            THREAD_FACTORY,
            new ThreadPoolExecutor.AbortPolicy()
    );

    private ToolExecutionSchedulers() {
    }

    static ExecutorService shared() {
        return SHARED;
    }
}
