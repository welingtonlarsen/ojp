package org.openjproxy.grpc.server;

import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-datasource connection admission gate for lazy session allocation.
 * The permit is acquired before pool borrow and must be held until session termination.
 */
@Slf4j
public class ConnectionAdmissionManager {

    private final int totalPermits;
    private final Semaphore connectionSlots;
    private final long timeoutMs;

    public ConnectionAdmissionManager(int totalSlots, long timeoutMs) {
        this.totalPermits = Math.max(totalSlots, 1);
        this.connectionSlots = new Semaphore(totalPermits, true);
        this.timeoutMs = timeoutMs;
    }

    public ConnectionPermit acquirePermit(String connHash) throws SQLException {
        try {
            boolean acquired = connectionSlots.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                int availablePermits = connectionSlots.availablePermits();
                throw new SQLException(String.format(
                        "Connection admission timeout for hash: %s after %dms (phase=admission, totalPermits=%d, currentHolders=%d)",
                        connHash, timeoutMs, totalPermits, totalPermits - availablePermits));
            }
            return new ConnectionPermit(connectionSlots);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for connection admission permit", e);
        }
    }

    public int getAvailablePermits() {
        return connectionSlots.availablePermits();
    }

    /**
     * One-shot permit handle.
     */
    @Slf4j
    public static final class ConnectionPermit {
        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private ConnectionPermit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                semaphore.release();
                log.debug("Released connection admission permit");
            }
        }
    }
}
