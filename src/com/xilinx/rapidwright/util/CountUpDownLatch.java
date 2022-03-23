package com.xilinx.rapidwright.util;

import java.util.concurrent.atomic.AtomicInteger;

public class CountUpDownLatch {
    private final AtomicInteger count;
    private final Object lock;

    public CountUpDownLatch() {
        count = new AtomicInteger();
        lock = new Object();
    }

    public void countUp() {
        count.incrementAndGet();
    }

    public void countDown() {
        int value = count.decrementAndGet();
        if (value == 0) {
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    public void await() throws InterruptedException {
        synchronized (lock) {
            // AtomicInteger.get() is not synchronized, but it
            // is expected that by the time await() is called
            // all countUp()-s have been completed and only
            // countDown()-s are called.
            while(count.get() > 0) lock.wait();
        }
    }

}
