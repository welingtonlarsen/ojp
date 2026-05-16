# OJP JDBC-Side Throttling (Server-Triggered) — Options Analysis

## Scope

This document captures **options and ideas** for implementing JDBC-side throttling in OJP, where throttling is:

- fully off by default,
- activated by server-side signals (for example, after consecutive timeout patterns),
- dynamically adjusted by the server per client,
- enforced on the JDBC driver with local queuing and early-fail limits.

This is intentionally a broad option analysis, not a deep implementation design of one single solution.

---

## Current Relevant Capabilities in OJP

### Already available on server

1. **Global gRPC concurrency gate**
    - `ConcurrencyThrottleInterceptor` enforces `maxConcurrentRequests`.
    - On overload, requests are rejected with `RESOURCE_EXHAUSTED`.

2. **Per-datasource admission control**
    - `AdmissionControlManager` + `SlotManager` manage execution slots.
    - Queue depth caps exist (`ojp.server.admissionControl.maxQueueDepth`).
    - Timeouts on admission waits already exist.

3. **Server already emits timeout/overload/error signals**
    - gRPC status codes and SQL metadata are already used by the driver.

### Already available on JDBC driver side

1. **Stable client identity per JVM**
    - `ClientUUID` is static for process lifetime and is sent in `ConnectionDetails`.

2. **Error classification**
    - Driver already distinguishes connection-level errors (`DEADLINE_EXCEEDED`, `UNAVAILABLE`, etc.).

3. **Multinode/session routing**
    - Driver has session stickiness and retry patterns, which can coexist with throttling.

### Main gap

- There is **no explicit control contract** today for dynamic per-client throttle instructions from server to driver.

---

## Design Goals

1. Keep throttling disabled in normal conditions.
2. Activate only when server detects stress signals.
3. Let server compute and update per-client concurrency limits.
4. Queue on client side with bounded memory and bounded waiting.
5. Fail early when queue pressure exceeds limits.
6. Avoid oscillations and avoid penalizing healthy clients too aggressively.

---

## Option Set 1 — Triggering Throttle Activation

### Option A: Consecutive timeout trigger (requested behavior)
- Example: activate for a client after 3 consecutive timeout-class failures.
- Pros: simple, predictable, easy to explain.
- Cons: sensitive to short bursts; may flap.

### Option B: Sliding-window error-rate trigger
- Activate when timeout/overload ratio in last N requests exceeds threshold.
- Pros: more stable than pure consecutive count.
- Cons: slightly more state/complexity.

### Option C: Hybrid trigger
- Fast activation via consecutive threshold + sustained activation via rate threshold.
- Pros: fast response + better stability.
- Cons: more tuning parameters.

**Practical note:** hybrid is usually safest for production behavior.

---

## Option Set 2 — How Server Signals Driver

### Option 1: gRPC response metadata/trailers
- Server includes throttle directives in normal/error response metadata.
- Driver updates local limiter when metadata is present.
- Pros: low protocol overhead, minimal new infrastructure.
- Cons: updates only happen when requests are in flight.

### Option 2: Dedicated control RPC stream
- Add a new bidirectional or server-streaming control channel for throttle updates.
- Pros: near-real-time push updates, richer controls.
- Cons: more lifecycle complexity (stream management, reconnection, ordering).

### Option 3: Implicit only via status codes
- Driver infers throttle mode from `RESOURCE_EXHAUSTED` / timeout patterns.
- Pros: simplest.
- Cons: coarse-grained, weaker server authority and coordination.

**Practical note:** start with metadata-based signaling, evolve to dedicated stream only if needed.

---

## Option Set 3 — Server Computation of Per-Client Limit

### Option A: Equal fair-share
- `limit_per_client = floor(global_budget / active_clients)`, with min/max clamps.
- Pros: simple fairness.
- Cons: ignores client behavior and workload quality.

### Option B: Weighted fair-share
- Allocate by configured client weights (SLA/tenant tier).
- Pros: predictable business control.
- Cons: requires policy management.

### Option C: Adaptive AIMD-like controller
- Increase slowly on success, decrease quickly on timeout/overload.
- Pros: responsive under stress, self-adjusting.
- Cons: needs careful hysteresis/tuning.

### Option D: Latency-aware + queue-aware
- Use admission latency and queue pressure to tune each client.
- Pros: can improve overall throughput/stability.
- Cons: highest complexity.

**Practical note:** fair-share + min/max + cooldown is a pragmatic first step.

---

## Option Set 4 — JDBC Queue and Early-Fail Policies

### Queue scope options
1. Per-JVM global queue.
2. Per-connHash queue (recommended baseline).
3. Per-session queue (highest isolation, highest complexity).

### Queue discipline options
1. FIFO.
2. Priority classes (e.g., transaction-bound/session-bound requests get priority).

### Early-fail policies
1. Max queue depth only.
2. Max queue wait time only.
3. Both depth + wait time (recommended).

### Failure semantics
- Use explicit exception/message for client-side throttle reject (queue full/timeout),
- keep distinct from server-side overload and SQL/database errors.

---

## Option Set 5 — Deactivation and Stability Controls

To avoid throttle flapping:

1. **Hysteresis**
    - Different thresholds for activate vs deactivate.
2. **Cooldown windows**
    - Keep throttling active for a minimum window before relaxing.
3. **Step-limited changes**
    - Cap per-update increase/decrease magnitude.
4. **Safety floor**
    - Maintain minimum concurrency so clients are not starved.

---

## Compatibility and Operational Considerations

1. **Backwards compatibility**
    - New signaling should be optional and ignored safely by older drivers.
2. **Multinode consistency**
    - In cluster mode, server-side throttling policy should be coherent across nodes.
3. **Observability**
    - Add metrics for:
      - throttle activation count,
      - per-client assigned limit,
      - queued requests,
      - early-fail count,
      - throttle duration.
4. **Configuration defaults**
    - Keep off by default.
    - Provide conservative defaults for queue depth and wait timeout when enabled.

---

## Suggested Incremental Rollout Path

### Phase 1: Passive mode (metrics only)
- Compute signals and “would-throttle” decisions; do not enforce.

### Phase 2: Soft enforcement
- Enable driver queue + bounded early fail using static per-client limits.

### Phase 3: Dynamic server-driven limits
- Activate metadata-based server directives for per-client concurrency updates.

### Phase 4: Advanced control channel (optional)
- Introduce dedicated control stream only if metadata approach is insufficient.

---

## Open Questions for Next Iteration

1. Should limit be per `clientUUID`, per datasource (`connHash`), or both?
2. Do we want separate limits for transaction-bound vs stateless operations?
3. Should queue admission be aware of SQL type (reads vs writes)?
4. What SLA do we want for early-fail wait time?
5. In multinode mode, should one node be control leader, or should all nodes compute limits independently?

---

## Summary

The requested behavior is compatible with current OJP architecture.

The best low-risk starting direction is:

1. trigger throttling from server-side timeout/overload patterns (hybrid trigger),
2. send per-client limits to driver through lightweight metadata signaling,
3. enforce per-connHash bounded queues on the JDBC side with early-fail on depth/time,
4. use hysteresis and cooldown to prevent oscillation,
5. keep existing server admission controls as final protection layer.

