package dev.gtmsyncfix;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared monitor guarding GTCEu's lazily-populated material-entry caches.
 * <p>
 * Lives outside the mixin package on purpose: classes inside a mixin package cannot be
 * loaded directly, but the code merged into ChemicalHelper needs to reference this one.
 */
public final class SyncLock {

    public static final Object LOCK = new Object();

    private static final Logger LOGGER = LogManager.getLogger("gtmsyncfix");
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private SyncLock() {}

    /** Logs exactly once, the first time the injected lock actually runs. Proof the mixin applied. */
    public static void noteActive() {
        if (REPORTED.compareAndSet(false, true)) {
            LOGGER.info("ChemicalHelper.getMaterialEntry lock is active (mixin applied).");
        }
    }
}
