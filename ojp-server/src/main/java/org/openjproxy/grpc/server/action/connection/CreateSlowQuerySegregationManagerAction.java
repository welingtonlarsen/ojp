package org.openjproxy.grpc.server.action.connection;

import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.SlowQuerySegregationManager;
import org.openjproxy.grpc.server.action.ActionContext;

/**
 * Helper action for creating slow query segregation managers.
 * This is extracted from createSlowQuerySegregationManagerForDatasource method.
 *
 * This action is implemented as a singleton for thread-safety and memory efficiency.
 */
@Slf4j
public class CreateSlowQuerySegregationManagerAction {

    private static final CreateSlowQuerySegregationManagerAction INSTANCE = new CreateSlowQuerySegregationManagerAction();

    private CreateSlowQuerySegregationManagerAction() {
        // Private constructor prevents external instantiation
    }

    public static CreateSlowQuerySegregationManagerAction getInstance() {
        return INSTANCE;
    }

    /**
     * Create slow query segregation manager for non-XA datasource.
     */
    public void execute(ActionContext context, String connHash, int actualPoolSize) {
        execute(context, connHash, actualPoolSize,
                context.getServerConfiguration().getSlowQueryFastSlotTimeout());
    }

    /**
     * Create slow query segregation manager for non-XA datasource with explicit admission timeout.
     */
    public void execute(ActionContext context, String connHash, int actualPoolSize, long nonXaFastSlotTimeoutMillis) {
        execute(context, connHash, actualPoolSize, false, nonXaFastSlotTimeoutMillis);
    }

    /**
     * Create slow query segregation manager with XA-specific handling.
     *
     * @param context The action context
     * @param connHash The connection hash
     * @param actualPoolSize The actual pool size (max XA transactions for XA, max pool size for non-XA)
     * @param isXA Whether this is an XA connection
     * @param fastSlotTimeoutMillis The fast-slot timeout in milliseconds
     */
    public void execute(ActionContext context, String connHash, int actualPoolSize, boolean isXA, long fastSlotTimeoutMillis) {
        boolean slowQueryEnabled = context.getServerConfiguration().isSlowQuerySegregationEnabled();

        if (isXA) {
            // XA-specific handling
            if (slowQueryEnabled) {
                // XA with slow query segregation enabled: use configured slow/fast slot allocation
                SlowQuerySegregationManager manager = new SlowQuerySegregationManager(
                    actualPoolSize,
                    context.getServerConfiguration().getSlowQuerySlotPercentage(),
                    context.getServerConfiguration().getSlowQueryIdleTimeout(),
                    context.getServerConfiguration().getSlowQuerySlowSlotTimeout(),
                    context.getServerConfiguration().getSlowQueryFastSlotTimeout(),
                    context.getServerConfiguration().getSlowQueryUpdateGlobalAvgInterval(),
                    true
                );
                context.getSlowQuerySegregationManagers().put(connHash, manager);
                log.info("Created SlowQuerySegregationManager for XA datasource {} with pool size {} (slow query segregation enabled)",
                        connHash, actualPoolSize);
            } else {
                // XA with slow query segregation disabled: use SlotManager only (no QueryPerformanceMonitor)
                // Set totalSlots=actualPoolSize, fastSlots=actualPoolSize, slowSlots=0
                // Use xaStartTimeoutMillis as the fast slot timeout
                SlowQuerySegregationManager manager = new SlowQuerySegregationManager(
                    actualPoolSize,
                    0, // slowSlotPercentage = 0 means all slots are fast
                    0, // idleTimeout not relevant
                    0, // slowSlotTimeout not relevant
                    fastSlotTimeoutMillis, // Use XA start timeout for fast slot timeout
                    0, // updateGlobalAvgInterval = 0 means no performance monitoring
                    true // enabled = true to use SlotManager
                );
                context.getSlowQuerySegregationManagers().put(connHash, manager);
                log.info("Created SlowQuerySegregationManager for XA datasource {} with {} slots (all fast, timeout={}ms, no performance monitoring)",
                        connHash, actualPoolSize, fastSlotTimeoutMillis);
            }
        } else {
            // Non-XA handling
            if (slowQueryEnabled) {
                SlowQuerySegregationManager manager = new SlowQuerySegregationManager(
                    actualPoolSize,
                    context.getServerConfiguration().getSlowQuerySlotPercentage(),
                    context.getServerConfiguration().getSlowQueryIdleTimeout(),
                    context.getServerConfiguration().getSlowQuerySlowSlotTimeout(),
                    context.getServerConfiguration().getSlowQueryFastSlotTimeout(),
                    context.getServerConfiguration().getSlowQueryUpdateGlobalAvgInterval(),
                    true
                );
                context.getSlowQuerySegregationManagers().put(connHash, manager);
                log.info("Created SlowQuerySegregationManager for datasource {} with pool size {}",
                        connHash, actualPoolSize);
            } else {
                // Create admission-control-only manager (always-on semaphore)
                SlowQuerySegregationManager manager = new SlowQuerySegregationManager(
                    actualPoolSize,
                    0, // 0% slow slots => all slots used for admission control
                    0,
                    0,
                    fastSlotTimeoutMillis,
                    0,
                    true
                );
                context.getSlowQuerySegregationManagers().put(connHash, manager);
                log.info("Created admission-control-only SlowQuerySegregationManager for datasource {} with pool size {} and timeout {}ms",
                        connHash, actualPoolSize, fastSlotTimeoutMillis);
            }
        }
    }
}
