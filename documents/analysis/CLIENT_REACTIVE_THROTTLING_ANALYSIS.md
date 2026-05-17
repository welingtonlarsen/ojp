# Client-Reactive Throttling in OJP — Deep Analysis

## The Idea

Instead of waiting for the server to explicitly tell the client to slow down,
the JDBC driver could observe server-side rejection exceptions on its own and
self-throttle proactively:

1. Driver receives a semaphore-reject signal from the server
   (`RESOURCE_EXHAUSTED` from `ConcurrencyThrottleInterceptor`,
   or a timeout-translated `ServerOverloadException` propagated as `SQLException`).
2. Driver activates a local concurrency limiter for the affected datasource.
3. Subsequent calls queue locally inside the driver, up to a bounded depth.
4. If the local queue is already full, new requests fail immediately with a clear exception,
   without wasting a round-trip to the server.
5. When server responses improve, driver gradually deactivates the limiter.

This is a purely client-reactive model — no new server-side protocol changes needed.

---

## What the Server Already Sends

| Signal source | What the driver receives today |
|---|---|
| `ConcurrencyThrottleInterceptor` | gRPC `RESOURCE_EXHAUSTED` with message `"Server overloaded: too many concurrent requests"` |
| `SlotManager.acquireSlowSlot` / `acquireFastSlot` returning `false` | `ServerOverloadException` thrown server-side, propagated to driver as `SQLException` |
| `SlotManager.canWaitForSlot` returning `false` (queue depth exceeded) | Same `ServerOverloadException` path |
| Slot acquisition timeout | Same `ServerOverloadException` path |

The driver today does not act on these differently from other `SQLException`s.
Everything needed to trigger client-reactive throttling is already there.

---

## Pros

### 1. Zero server-side changes required
The trigger signal already exists in both forms (`RESOURCE_EXHAUSTED` status code and
`ServerOverloadException`). No protocol change, no new gRPC messages, no server rebuild.

### 2. Earlier protection for the calling application
With pure server-side rejection, each refused request still consumed:
- a gRPC channel slot,
- network round-trip latency (even for fast-fail paths),
- application thread time waiting for the exception.

A local queue absorbs bursts silently and prevents those costs from piling up.

### 3. Reduces server-side queue pressure during recovery
When the server is recovering from overload, rejected clients that immediately retry
can re-flood the server. Client-side queuing creates a natural back-pressure buffer,
giving the server room to drain its own semaphore queues.

### 4. Cleaner application error semantics
Without this, application threads see `RESOURCE_EXHAUSTED` / `ServerOverloadException`
directly. With client-side queuing, most requests simply wait a controlled amount of time,
and only fail if the local queue is exhausted — a shorter, more deterministic wait.

### 5. Complements, not replaces, existing server controls
The server admission control (`SlotManager`, `ConcurrencyThrottleInterceptor`) remains
the authoritative gate. Client throttling is a second shield that reduces unnecessary
server hits. Defense in depth.

### 6. Works without server coordination
For multinode setups where different server nodes may be under different loads,
each driver instance reacts to the node it is currently hitting. No need for cross-node
policy agreement at this stage.

---

## Cons

### 1. Client has an incomplete view of server state
The driver knows it was rejected, but it does not know:
- whether the server is still overloaded or already recovered,
- whether other clients are also backing off (leading to under-utilization),
- whether the overload was transient (e.g., a GC pause) or structural.

This can lead to overly cautious throttling that outlasts the actual problem.

### 2. Risk of over-throttling good clients
If one datasource is under load and another is idle, a naive global driver-side
limiter would throttle all datasources equally. Granularity must be per-datasource
(per `connHash`) or the throttling will be too broad.

### 3. Queue memory footprint in the driver
Each queued request holds a thread (or at minimum a waiting object + monitor lock).
With N app threads and M datasources, worst-case queue memory can be significant.
A hard bounded queue depth mitigates this, but sizing it incorrectly causes its own
problems (too small → fast fail even under light load; too large → out-of-memory risk).

### 4. Throttle deactivation is harder than activation
Activation is easy: see a rejection, enable throttling.
Deactivation requires evidence that the server has recovered — but the driver only
gets that evidence by sending requests through. This creates a "cold restart" problem:
the driver may stay throttled long after the server has recovered.

### 5. Latency amplification in the success path
Once throttling is active, every request incurs queue wait overhead even if it
would have succeeded on the server. This raises p99 latency for all users of that
datasource, not just those that would have been rejected.

### 6. Interactions with session stickiness and XA
OJP session stickiness requires the same driver to reuse the same physical server-side
session. If the driver throttles and queues a request that belongs to an open
transaction, and the transaction times out server-side while the request is sitting in
the driver queue, the client will eventually get a stale-session error when it finally
sends the request. The driver queue timeout must be shorter than the server-side
session/transaction timeout.

### 7. Multinode asymmetry
If the driver is in multinode mode and one server node rejects while another is
available, the right answer is to route to the healthy node, not to queue locally.
Client-reactive throttling must be aware of multinode routing and only activate
per-node, not globally.

---

## Concerns

### C1 — What exception types should be the trigger?

The driver currently receives two distinct signals:
- gRPC `RESOURCE_EXHAUSTED` (ConcurrencyThrottleInterceptor)
- `SQLException` wrapping `ServerOverloadException`

These may not be easy to distinguish from SQL-level errors that happen to use
similar gRPC status codes. The driver needs a clear, reliable classification.
**Concern: Is the current exception taxonomy precise enough to act on safely?**

### C2 — Activation threshold tuning

A single rejection → throttle activation is too aggressive.
A hundred rejections before activation is too slow.
The right number depends on query frequency and server capacity.
**Concern: A fixed default threshold will be wrong for many deployments.
This needs to be configurable, and the default requires careful thought.**

### C3 — Flapping between throttled and unthrottled state

Without hysteresis, the driver will oscillate:
1. Receives rejection → activates throttle.
2. First few requests pass through → deactivates throttle.
3. Server is still stressed → rejects → activates again.
...repeat.

This flapping adds noise to metrics and can cause latency spikes.
**Concern: Hysteresis logic is easy to get wrong and hard to test.
It needs dedicated test coverage with simulated server stress patterns.**

### C4 — Queue depth vs wait timeout — which is the primary control?

Both are needed, but they interact:
- A deep queue with a long timeout can hold threads for minutes.
- A shallow queue with a short timeout fails fast but may not protect the server.

**Concern: The right combination is workload-specific. Operators need guidance on
how to tune these together, not just individual knobs.**

### C5 — Impact on connection pool metrics and health checks

If the application uses connection pool health checks (e.g., Spring Boot actuator),
those checks also go through the driver. A throttled driver that queues health check
pings will make a healthy server appear unhealthy.
**Concern: Health check paths should bypass the driver-side throttle queue,
or at minimum use a dedicated permit bucket.**

### C6 — Exception message clarity

When the driver rejects a call due to local queue saturation, the exception message
must clearly distinguish this from a server rejection. The application and operators
need to know whether to blame the client configuration or the server load.
**Concern: "Connection refused" or a raw SQL exception code is not enough.
The exception must name the source: client-side throttle queue exhausted.**

### C7 — Testing difficulty

Server-side admission control can be tested by starting an OJP server and overwhelming
it. Client-reactive throttling is harder to test deterministically because the trigger
depends on receiving specific exceptions from the server, which requires fine-grained
control of the test server's semaphore state.
**Concern: Without dedicated test infrastructure (e.g., a mock gRPC server that injects
RESOURCE_EXHAUSTED on demand), this feature will be hard to test reliably in CI.**

---

## Suggestions

### S1 — Trigger on `RESOURCE_EXHAUSTED` + classify on consecutive count

Use a sliding window of N consecutive `RESOURCE_EXHAUSTED` / `ServerOverloadException`
responses (not just one) to activate throttling. Start with N=3 as default.
This avoids triggering on transient single-request failures.

### S2 — Scope throttle per `connHash` (datasource), not globally

Each OJP driver instance may manage multiple datasources via different `connHash` values.
The local semaphore/queue should be keyed by `connHash` so a loaded datasource does not
block requests to an idle one.

### S3 — Use a probe request for deactivation

Rather than waiting for N successes under throttle, periodically send one "probe" request
through the full path (bypassing local queue) to test whether the server has recovered.
If the probe succeeds, step up concurrency. This is the AIMD (additive increase,
multiplicative decrease) recovery pattern.

### S4 — Short queue, short timeout

Default queue depth: `max(2, threads_per_datasource / 2)`.
Default queue wait: 2–5 seconds.
These are deliberately conservative: fail fast is better than masking an outage.
Make both configurable.

### S5 — Bypass throttle for in-flight transactions

If a JDBC `Connection` is mid-transaction (`autoCommit = false`), its subsequent
statements must not be queued separately — they must be allowed through immediately
to avoid transaction timeout. Track per-connection transaction state in the driver
and exempt in-transaction calls from the queue.

### S6 — Add a metric + log for every throttle state transition

- Log `WARN` when throttle activates, with: datasource, rejection count, active threads.
- Log `INFO` when throttle deactivates, with: datasource, recovered after N seconds.
- Expose via JMX/OpenTelemetry: throttle-active flag, queue depth, rejected request count.

### S7 — Keep the feature off by default

Introduce behind an opt-in property, e.g. `ojp.jdbc.clientThrottle.enabled=false`.
Operators who want the behavior enable it explicitly. Allows gradual rollout.

---

## Questions

**Q1 — Should the local queue block the calling thread or use a callback/future model?**
Blocking the calling thread is JDBC-natural and the simplest implementation.
But it consumes a thread per queued request. If the calling application uses virtual
threads (Java 21+) this is fine. For platform threads, thread starvation is a real risk.

**Q2 — Should client throttling be per `clientUUID` (JVM) or per `connHash` (datasource)?**
Per `clientUUID` would be simpler, but all datasources on one JVM would share a limit.
Per `connHash` is more precise but requires more state. Both?

**Q3 — Should the driver tell the server it is throttling?**
If the driver sends a hint ("I am backing off"), the server can relax its own queue
limit for this client temporarily. This turns the reactive model into a cooperative
one but requires protocol changes.

**Q4 — How does this interact with the circuit breaker?**
OJP already has a circuit breaker timeout (default 60 seconds). If the server is down,
the circuit opens and requests fail fast. Client-reactive throttling targets a different
scenario: server is up but overloaded. These two features must not conflict —
circuit-open should bypass the client throttle queue entirely.

**Q5 — What happens if multiple threads hit the same throttle at the same time?**
The local queue semaphore must be thread-safe. `java.util.concurrent.Semaphore` with fair
mode is the obvious choice. But fair mode adds latency under contention. Is fairness
important here, or is FIFO within the queue sufficient?

**Q6 — How long should the throttle stay active after the last rejection?**
There needs to be a minimum active window (e.g., 10 seconds) even if no more rejections
arrive, to avoid deactivating too early while the server is still under pressure.
What should the default minimum window be?

**Q7 — Should read-only queries be throttled less aggressively than writes?**
Reads are typically idempotent and retryable. Writes may have side effects and
different latency SLAs. Should there be separate queue limits for reads vs writes?

---

## Opinions

**On the overall idea — Confidence: High (80%)**

This is the right first step for a client-side protection layer. It requires no
server changes, leverages existing exception signals, and provides real value by
absorbing burst traffic before it hits the server repeatedly.
The main risk is incorrect deactivation timing — but that is a tuning problem,
not a fundamental flaw.

**On activation threshold — N=3 consecutive rejections is probably right for a default.**
It is fast enough to react to real overload and robust enough to ignore transient
single-request failures (GC pauses, restart hiccups).

**On queue depth — Err small, not large.**
The entire point of fail-fast is to surface the problem to the application quickly.
A large queue hides overload and makes root-cause analysis harder. Start with a
shallow default (e.g., queue depth = active threads / 2) and let operators increase it.

**On blocking threads — This is the JDBC model and should be embraced.**
JDBC is synchronous by contract. Blocking the calling thread is the expected behavior.
A non-blocking/async implementation would require a fundamentally different API that
most applications are not ready for.

**On deactivation — The probe approach is better than time-based deactivation.**
A fixed cooldown window is fragile. If the server recovers in 5 seconds but the cooldown
is 30 seconds, you are throttling for no reason. Probe-based recovery (AIMD) adapts
to actual server state.

**On scope: per-`connHash` is the right granularity.**
OJP is primarily a proxy for multiple datasources. A global driver throttle would be
the wrong abstraction. The connection hash already distinguishes datasources and is
the right key for the throttle state.

**On the interaction with multinode mode — This is a real gap that needs more thought.**
In multinode mode, a rejection from one node should trigger routing to another node
before activating local throttling. If all nodes reject, then activate throttling.
Implementing client throttling without multinode awareness risks masking node failures
that should trigger failover instead of local queuing.

---

## Option: Server-Cooperative Fair-Share Throttling via SessionInfo

This section evaluates a specific design proposed as an alternative to the fully
client-reactive approach above.

### The Proposal

Add two new fields to `SessionInfo` (returned to the driver on every operation):

```proto
message SessionInfo {
    // ... existing fields ...
    int32 clientCount = 9;      // number of distinct clients connected to this connHash
    int32 maxAdmission = 10;    // server's total admission slot budget for this connHash
}
```

The driver uses these to calculate and enforce a local concurrency limiter:

```
perClientLimit = ceil(maxAdmission / clientCount) * numOjpServers * 0.9
```

Where:
- `maxAdmission` is the **per-node** admission slot budget (= HikariCP pool size on that node)
- `clientCount` is the number of distinct JVM processes (`clientUUID`) connected to this
  `connHash` on this node
- `numOjpServers` is the number of UP nodes (derived from the `clusterHealth` field)
- `0.9` is the 10% safety headroom to absorb stale `clientCount`

**Example:**
- `maxAdmission = 20` (per-node pool size), `clientCount = 4`, `numOjpServers = 1`
  → rawLimit = `ceil(20/4) * 1 = 5` → with headroom: **4** per client
- `maxAdmission = 20`, `clientCount = 4`, `numOjpServers = 3`
  → rawLimit = `ceil(20/4) * 3 = 15` → with headroom: **13** per client

---

### Pros

**1. Proactive, not reactive — no rejection needed to set the limit**
The client has its budget from the very first `connect()` response. It does not need to
receive a single rejection before throttling. This eliminates the activation-threshold
tuning problem entirely.

**2. Fair-share is explainable and auditable**
The formula is deterministic. An operator can verify by inspection that each client
holds exactly `(maxAdmission / clientCount) * numOjpServers` permits. There is no
black-box AIMD state machine or sliding-window counter to reason about.

**3. Handles the deactivation problem elegantly**
When the server recovers (e.g., `maxAdmission` increases or `clientCount` drops as
clients disconnect), the next `SessionInfo` response automatically delivers new values.
The driver simply recalculates the semaphore limit. No probe logic, no cooldown window,
no flapping.

**4. Multinode-aware by construction**
The formula already accounts for cluster size. If a node goes down, `clusterHealth`
already carries that signal and the driver can update `numOjpServers` accordingly.
No separate per-node throttle state is needed.

**5. Reduces over-throttling when load drops**
In the reactive model, a client stays throttled after the last rejection for a
configurable window. Here, as clients disconnect and `clientCount` decreases,
remaining clients automatically get a larger budget on the next response.

**6. Backward compatible in proto3**
New `int32` fields default to `0` in proto3. Older drivers that do not understand
the fields will simply ignore them. Servers that do not set the fields send `0`,
which the driver can interpret as "no limit" or "use default."

---

### Cons

**1. `clientCount` is inherently stale**
The server counts clients at the moment it builds the response. By the time the
driver uses the count, clients may have connected or disconnected. Under rapid
churn (e.g., microservice rolling restarts), the count can be significantly wrong
for many seconds.

**2. Integer division discards capacity**
`floor(20 / 7) = 2`, leaving `20 - 14 = 6` slots on the server permanently
unallocated. With many small clients, a significant fraction of server capacity
goes unused. This needs mitigation (e.g., ceiling division, or a minimum-floor +
remainder-distributed approach).

**3. Division by zero when `clientCount = 0`**
The driver must handle the case where no other clients are connected.
The safe fallback is `perClientLimit = maxAdmission * numOjpServers`.

**4. `maxAdmission` is per-node — confirmed by server code**
`totalSlots` in `SlotManager` is set to `actualPoolSize`, which is the HikariCP connection pool
size configured on **this specific OJP node** (`CreateSlowQuerySegregationManagerAction`).
Each node tracks only its own pool. `maxAdmission` is therefore per-node, not cluster-aggregate.
The formula `(maxAdmission / clientCount) * numOjpServers` is correct: it multiplies the
per-node budget by the number of UP nodes to derive the total cluster budget available to
one client. This must be clearly documented in the proto field comment.

**5. Resizing a concurrency limiter — and a cheaper alternative**
`java.util.concurrent.Semaphore` does not support changing its permit count directly.
However, if the driver uses **fail-fast** (non-blocking) concurrency control — which is
the right choice here, since blocking adds latency to every successful request — a plain
`AtomicInteger` counter is sufficient and trivially resizable:

```java
// Per-connHash state stored in the driver
AtomicInteger inFlight = new AtomicInteger(0);
volatile int limit;          // updated when SessionInfo delivers new values

// Acquire (non-blocking, fail-fast):
if (inFlight.incrementAndGet() > limit) {
    inFlight.decrementAndGet();
    throw new SQLException("Client throttle limit exceeded for datasource: " + connHash);
}

// Release (always in finally):
inFlight.decrementAndGet();

// Resize (on every SessionInfo response):
limit = newLimit;            // single volatile write, no locks needed
```

This is **zero-overhead on the happy path** (one atomic increment, one integer comparison)
and resizing is a single volatile write. No drain/inject logic, no races, no lock contention.
The tradeoff is that over-limit requests fail immediately rather than queuing — which is the
desired fail-fast behaviour for a client-side overload guard.

**6. All threads in one JVM share the same limit**
If a single JVM has 50 threads all using the same datasource, the per-JVM semaphore
limits the total to `perClientLimit`, regardless of how many threads are active.
This is correct behavior, but operators must be aware: the limit is per-JVM
(per `clientUUID`), not per-thread.

**7. Thundering herd when a large client disconnects**
When a large client (or many clients) disconnects, `clientCount` drops and every
remaining client gets a suddenly larger budget. All of them may burst to fill their
new limit simultaneously, potentially spiking the server back into overload. A step
limit on permit increases (AIMD-style increase cap) would mitigate this.

**8. Cross-node `clientCount` — plain-language explanation with example**

In a 2-node OJP cluster, clients can connect to different nodes:

```
App1 → OJP Node A  (Node A sees: App1 only → clientCount = 1)
App2 → OJP Node B  (Node B sees: App2 only → clientCount = 1)
```

Node A tells App1: `clientCount = 1, maxAdmission = 10, numOjpServers = 2`
→ App1 calculates: `(10 / 1) * 2 = 20` permits.

Node B tells App2: `clientCount = 1, maxAdmission = 10, numOjpServers = 2`
→ App2 calculates: `(10 / 1) * 2 = 20` permits.

But each node can only handle 10 concurrent requests. App1 hits Node A with up to 20,
App2 hits Node B with up to 20 — a total of 40 in-flight against a real cluster capacity
of 20. Both nodes overload.

**Root cause:** Each node sees only its own connected clients, so it underestimates
`clientCount` and each client gets an over-inflated limit.

**Safe v1 approach (S6):** Accept the per-node snapshot. In the steady state for multinode,
clients spread across nodes. The formula slightly over-throttles when clients unevenly
concentrate on one node, but the server's own admission control (`SlotManager`) remains
the final safety gate regardless. A future version can add cluster-aggregate counts via
cross-node state sharing.

---

### Concerns

**C1 — Resolved: `clientCount` counts distinct `clientUUID` values per `connHash`**
`clientUUID` is a static UUID per JVM process (see `ClientUUID.java`). One JVM running
a 50-connection pool has exactly one `clientUUID`. Counting `clientUUID` values per
`connHash` ensures the formula reflects the number of distinct application processes,
not connections. A single JVM with 50 connections counts as 1 client.

**C2 — Update frequency and oscillation**
`SessionInfo` is returned on every operation. If `clientCount` changes on every
response (e.g., from 4 to 3 to 5 rapidly), the driver's semaphore limit will
fluctuate on every call. This creates micro-oscillations. Consider applying a
hysteresis rule: only update the semaphore if the new limit differs by more than
N% from the current limit.

**C3 — What should happen during the window between `connect()` and the first operation?**
The driver receives `SessionInfo` (with `clientCount` and `maxAdmission`) immediately
on `connect()`. It should create the semaphore before the first statement, not
reactively after the first rejection.

**C4 — Field naming and documentation**
`clientCount` and `maxAdmission` in `SessionInfo` need precise Protobuf comments
explaining the scope: per-node vs cluster-aggregate, per-clientUUID vs per-session,
and what `0` means (field not set → no limit).

**C5 — In-transaction bypass — plain-language explanation with example**

Without a bypass, a mid-transaction thread can block itself via the client semaphore:

1. Thread A sends `BEGIN` (or first statement with `autoCommit = false`). The server
   creates a session and holds a real database connection for Thread A.
2. Other threads (B–Z) are running queries. The client semaphore fills to its limit.
3. Thread A now sends `INSERT INTO orders VALUES (...)` — the next statement in its
   open transaction.
4. The client semaphore is full → Thread A is blocked waiting for a permit.
5. Thread B–Z are also holding their permits, waiting for server responses. Nobody
   finishes quickly; nobody releases.
6. Meanwhile the server's transaction timeout fires. The server rolls back Thread A's
   transaction and releases its database connection.
7. Thread A eventually gets a permit (or times out in the queue), sends the INSERT,
   and receives a "session not found" or "transaction already rolled back" error.

**This is a deadlock-by-timeout:** the client queue blocks the thread that should be
completing an open server-side transaction, while the server's clock runs out.

**Fix:** When a `Connection` has `autoCommit = false`, subsequent statements on that
connection must bypass the client semaphore and go directly to the server. The semaphore
controls admission for *new requests* only — not continuation of an already-admitted,
already-open transaction. Track `autoCommit` state in the driver's `OjpConnection` and
skip the `inFlight` check when `autoCommit == false`.

---

### Suggestions

**S1 — Use ceiling division with a 10% safety headroom**

**Why ceiling division, not floor:**
Floor division permanently wastes capacity. With `maxAdmission = 20` and `clientCount = 7`,
`floor(20/7) = 2` per client. Total allocated = `2 × 7 = 14`. Six slots on the server sit
idle even when clients have work to do. With many clients this waste compounds.

Ceiling division gives `ceil(20/7) = 3`. Total allocated = `3 × 7 = 21` — slightly over
the server capacity, which is why the safety headroom is needed.

**Why 10% safety headroom:**
`clientCount` is always slightly stale (it was accurate when the server built the response,
not when the driver reads it). If one new client joined since the last response, the true
`clientCount` is `clientCount + 1`. Ceiling division without headroom could briefly allow
`clientCount + 1` clients each holding `ceil(20/7) = 3` permits = 24 in-flight against a
server capacity of 20. The 10% reduction absorbs one stale-count error at typical client
counts, without meaningfully reducing steady-state throughput.

```java
// clientCount > 0 is guaranteed by the caller (see C1 for division-by-zero handling)
int rawLimit = (int) Math.ceil((double) maxAdmission / clientCount) * numOjpServers;
int limit = Math.max(1, (int)(rawLimit * 0.9)); // 10% safety headroom, minimum 1
```

**S2 — Derive `numOjpServers` from `clusterHealth`**
The `clusterHealth` field already encodes `"host1:port1(UP);host2:port2(DOWN);..."`.
The driver can count `(UP)` entries instead of requiring a separate field.
This removes a new field and reuses existing protocol.

**S3 — Document `maxAdmission = 0` as "unlimited"**
Servers that have not configured admission control set `maxAdmission = 0`.
The driver should treat this as "no client-side throttle."
This preserves backward compatibility and makes opt-out trivial.

**S4 — AIMD-style step-limited increase when the limit grows**

**Why a step limit on increase:**
When several clients disconnect simultaneously, `clientCount` drops sharply. Every remaining
client immediately recomputes a much larger limit and bursts to fill it.

Example: 8 clients, `maxAdmission = 40`, limit = 5 each. Four clients disconnect.
New `clientCount = 4`, new computed limit = 10. All 4 remaining clients burst to 10
simultaneous requests at the same moment → 40 in-flight, exactly at server capacity.
Any brief measurement noise or one late-joining client pushes the server into overload.

**The AIMD rule (Additive Increase, Multiplicative Decrease):**
- **Decrease:** When the new limit is *lower* than the current limit, apply it immediately.
  Fast response to overload is the priority.
- **Increase:** When the new limit is *higher*, apply only `min(newLimit, currentLimit + step)`
  per `SessionInfo` update, where `step` is a small additive value (default: 1 permit
  per update).

**Example with step = 1:**
- Current limit = 5, new computed limit = 10.
- Update 1: limit → `min(10, 5+1) = 6`
- Update 2: limit → `min(10, 6+1) = 7`
- ... converges to 10 over 5 response cycles.

Under normal query load, `SessionInfo` responses arrive every few milliseconds, so convergence
takes seconds at most — fast enough to be responsive, slow enough to avoid a burst spike.

**Why additive (not multiplicative) increase:**
Each `SessionInfo` update arrives quickly (with every operation). Adding 1 permit per
response converges to the target in seconds. Multiplicative increase (e.g., double each
time) would overshoot the target and cause oscillation. Additive increase is simple,
predictable, and easy to test.

**S5 — Log limit changes at INFO level**
```
[WARN] Client throttle limit for connHash=abc123 reduced: 10 → 5 (clientCount=4 → 8, maxAdmission=20)
[INFO] Client throttle limit for connHash=abc123 increased: 5 → 7 (clientCount=8 → 6, maxAdmission=20)
```

**S6 — On the server side: keep `clientCount` as a per-node snapshot**
Do not attempt cross-node coordination for v1. Document that in multinode mode,
`clientCount` reflects the per-node count. This makes the formula conservative
(each driver slightly over-throttles), which is safe. A future version can add
cluster-aggregate counts via gossip.

---

### Questions

**Q1 — Resolved: `maxAdmission` is per-node**
`totalSlots` in `SlotManager` equals `actualPoolSize`, which is the HikariCP connection
pool size configured on this node (`CreateSlowQuerySegregationManagerAction`). Each OJP
node tracks only its own pool. The formula correctly multiplies by `numOjpServers` to
derive the total cluster budget available to one client.

**Q2 — Should `clientCount` count `clientUUID` (JVMs) or sessions?**
A JVM with a connection pool of 50 connections would inflate `clientCount` by 50 if
sessions are counted, completely distorting the formula.
**Recommendation: count distinct `clientUUID` values per `connHash`.**

**Q3 — How does the server compute `clientCount` efficiently?**
It needs a live count of distinct `clientUUID`s that have an active session for
the same `connHash`. This could be a `ConcurrentHashMap<connHash, Set<clientUUID>>`
updated on session creation/termination. Is this acceptable overhead for the server?

**Q4 — Should the driver store the semaphore per `connHash` or per `(connHash, targetServer)`?**
In multinode mode with session stickiness, a client may have sessions on node A and
node B simultaneously. Should the semaphore be shared across all nodes (per `connHash`)
or per node? Sharing is simpler and correct if `maxAdmission` is cluster-aggregate.

**Q5 — What should the driver do if `maxAdmission` arrives with a value lower than the
current semaphore occupancy?**
For example, current limit is 10, 8 threads hold permits, and the new limit is 5.
The driver cannot revoke in-flight permits. It should stop issuing new permits until
occupancy drops below 5. This is the natural behavior of reducing available permits
but needs explicit handling.

---

### Comparison: Server-Cooperative vs Purely Reactive

| Dimension | Purely Reactive | Server-Cooperative Fair-Share |
|---|---|---|
| Server protocol change | None | Two new `SessionInfo` int fields |
| Client limit source | Inferred from rejections | Explicitly computed from server data |
| `maxAdmission` scope | N/A | Per-node (= HikariCP pool size on that node) |
| Activation | After N rejections | Immediately at `connect()` |
| Deactivation | Probe logic / cooldown | Automatic via `clientCount` update |
| Fairness | Unknown (guesswork) | Guaranteed by formula |
| Multinode awareness | Requires per-node state | Built into formula via `numOjpServers` |
| Stale data risk | High (only sees own rejections) | Low-Medium (clientCount lag) |
| Concurrency control | Semaphore (blocking optional) | AtomicInteger counter (fail-fast, zero-latency) |
| Implementation complexity | Medium (driver only) | Medium (driver + server tracking) |
| Thundering-herd risk | Low (only activates under stress) | Mitigated by AIMD step-limited increase |
| Recommended for | No-protocol-change constraint | General case, new feature development |

**Opinion:** The server-cooperative approach is materially better in production behavior.
The two extra int fields in `SessionInfo` are a minimal protocol cost for a significantly
more stable and fair throttle. If the team is willing to make the server change, this
design should be preferred over the purely reactive model.

The main implementation risk is cross-node `clientCount` accuracy. Starting with
per-node snapshot counts (conservative, safe) and evolving to cluster-aggregate counts
(accurate) is the right phased approach.

---

## Relationship to Existing Analysis

This document is a deep dive into one specific variant described in Option Set 2 / Option 3
("Implicit only via status codes") and Option Set 4 ("JDBC Queue and Early-Fail Policies")
of [`JDBC_SERVER_TRIGGERED_THROTTLING_OPTIONS.md`](./JDBC_SERVER_TRIGGERED_THROTTLING_OPTIONS.md).

The key difference from the other options in that document is that this approach is
**fully client-driven** — the server does not need to send explicit throttle instructions.
The client infers the throttle signal from existing rejection exceptions.

---

## Summary

| Dimension | Assessment |
|---|---|
| Implementation effort | Medium (client-only change, but state management is non-trivial) |
| Server changes required | None |
| Risk level | Low–Medium (can be off by default; staged rollout is easy) |
| Main risk | Over-throttling after recovery; interaction with multinode failover |
| Recommended first step | Proof-of-concept per-`connHash` semaphore in the driver, triggered by N consecutive `RESOURCE_EXHAUSTED` signals, with a shallow bounded queue and short wait timeout |
| Needs more design | Deactivation probe logic; multinode awareness; in-transaction bypass |
