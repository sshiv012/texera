# Week 1 — Lessons Learned (guided session notes)

> Running record of the tutored deep-dive into the Amber engine core. Each lesson =
> a small code chunk read from the real source, the insight, and the checkpoint Q&A.
> File:line references are to the current `main` build.

---

## Prerequisites (reading-level only)

**Protobuf (6 facts, enough to *read* Amber's core types):**
1. Schema language for structured data; a compiler generates real classes in Scala **and**
   Python. Amber uses it so the *same* type exists identically on both sides of the JVM↔Python boundary.
2. `message Foo { ... }` → a Scala `case class` + a Python class.
3. `int32 id = 1;` — the `= 1` is the **wire tag** (field identity on the wire), **not** a default value.
4. `repeated X foo` → a list/`Seq[X]`.
5. `enum` → enum; the `= 0` member is the default.
6. `[(scalapb.field)...]` / `option (scalapb.options)` = ScalaPB generator hints — **ignore while reading**.
   `no_box = true` just means "plain non-nullable field, not wrapped in `Option`".

**Rule of thumb:** when a core type's `.scala` file looks auto-generated (serialization boilerplate),
its real definition is a `.proto` in `common/workflow-core/src/main/protobuf/…`.

**Tiered (learn just-in-time):** DAG + topological sort (Kahn) · actor model (message-passing, no
shared memory; Pekko = Akka fork) · credit-based backpressure · Apache Arrow / Arrow Flight ·
Scala *reading* idioms (`case class`, `Option`, `lazy val`, `@transient`, `match`, collection ops).
**Skip:** authoring protobuf, Pekko/gRPC/Arrow internals, Scala implicits wizardry.

**zsh gotcha:** quote globs passed as flag values — `--include='*.scala'` (unquoted `*.scala`
errors with "no matches found" under zsh's `nomatch`). Or use `rg --type scala 'a|b'` (real regex).

---

## Lesson 1 — The plan is *data*, and the data is *schema-first*

**Files:** `common/workflow-core/src/main/protobuf/org/apache/texera/amber/core/workflow.proto`

The engine's core vocabulary is proto-defined (cross-language). Key messages:

```proto
message PortIdentity { int32 id = 1; bool internal = 2; }

message PhysicalLink {
  PhysicalOpIdentity fromOpId = 1;  PortIdentity fromPortId = 2;
  PhysicalOpIdentity toOpId   = 3;  PortIdentity toPortId   = 4;
}

message InputPort  { PortIdentity id = 1; repeated PortIdentity dependencies = 4; ... }
message OutputPort { enum OutputMode { SET_SNAPSHOT=0; SET_DELTA=1; SINGLE_SNAPSHOT=2; }
                     bool blocking = 3; bool reuseStorage = 5; ... }
```

**Insights:**
- An operator has **ports**, not a single in/out. `PortIdentity.internal` = system-wired port
  (e.g. hash-join plumbing) vs user-visible.
- **A `PhysicalLink` connects *(op, port) → (op, port)*, not op→op.** This is what lets a 2-input
  operator (hash join) disambiguate left vs right: `scanA → join.port0`, `scanB → join.port1`.
- `InputPort.dependencies` = "this input port can't start until *these other ports* of the same op
  finish" (e.g. probe depends on build). Seed of blocking behavior.
- `OutputPort.blocking` + the `OutputMode` enum (the SNAPSHOT/DELTA modes seen all over operator docs)
  live here — a property of the **output port**.
- Core types are protobuf because a `PhysicalLink` must cross JVM→Python identically; JVM (Java)
  serialization is JVM-only, Python can't read it. Protobuf = language-neutral wire format.

**Checkpoint Q&A:**
- *Why (op,port)→(op,port)?* Multi-input ops (hash join) would be ambiguous as op→op; the port id
  says which input each link feeds.
- *When can a link stream vs wait?* Streams iff producer `OutputPort.blocking == false` AND consumer
  `InputPort.dependencies` empty; otherwise must wait (→ materialization boundary). **[predicted the
  scheduler rule here — confirmed in Lesson 3]**
- *Why protobuf not case class?* Python can't deserialize JVM types; protobuf is language-neutral.

---

## Lesson 2 — Three levels of identity (logical → physical → actor)

**Files:** `…/protobuf/…/virtualidentity.proto`, `common/workflow-core/…/util/VirtualIdentityUtils.scala`

```proto
message OperatorIdentity    { string id = 1; }                    // (1) LOGICAL — what the user drew
message PhysicalOpIdentity  { OperatorIdentity logicalOpId = 1;   // (2) PHYSICAL — a compile "layer"
                              string layerName = 2; }
message ActorVirtualIdentity{ string name = 1; }                  // (3) ACTOR — a running worker
message ChannelIdentity { ActorVirtualIdentity fromWorkerId=1; toWorkerId=2; bool isControl=3; }
message WorkflowIdentity { int64 id = 1; }    message ExecutionIdentity { int64 id = 1; }
```

**The identity ladder (spine of the engine):**
`1 logical op → N physical ops (layers) → M worker actors per layer.`
A hash join: 1 `OperatorIdentity` → physical `(join,"build")` + `(join,"probe")` → e.g. 3 workers
each = 6 `ActorVirtualIdentity`s.

**ChannelIdentity + `isControl` — the two-plane architecture:** a channel is a *directed pipe between
two worker actors*. A single compile-time `PhysicalLink` fans out at runtime into many channels (one
per upstream-worker → downstream-worker pair; 4×4 layers ⇒ up to 16). `isControl` splits every
actor pair into a **data plane** (tuple batches) and a **control plane** (pause / add-credit /
reconfigure). Putting the split in the *channel identity* (not per-message) gives the control plane
its own queue/ordering so control can **overtake** data — avoids head-of-line blocking (you never want
"PAUSE" stuck behind a data backlog).

**`WorkflowIdentity` vs `ExecutionIdentity`:** one saved workflow, many runs; recovery/replay/trace key
off the specific `ExecutionIdentity`.

**Naming = identity (VirtualIdentityUtils):**
```scala
s"Worker:WF${workflowId.id}-$operator-$layerName-$workerId"   // e.g. Worker:WF7-filter-x-main-2
private val workerNamePattern = raw"Worker:WF(\d+)-(.+)-(\w+)-(\d+)".r
require(!layerName.contains('-'), "layerName must not contain '-' ...")   // load-bearing invariant
```
The actor's **name string IS its identity** — `getPhysicalOpId`/`getWorkerIndex` regex-parse the flat
name back into structured identity, no lookup table. `-` is the delimiter, hence the `require` guard.

---

## Lesson 2b — Worker spawning: config vs stats

**Files:** `…/scheduling/config/WorkerConfig.scala`, `…/scheduling/resourcePolicies/ResourceAllocator.scala`,
`…/web/service/ExecutionStatsService.scala`

**Where the worker COUNT is decided** (`WorkerConfig.generateWorkerConfigs`):
```scala
val workerCount = if (physicalOp.parallelizable) {
  physicalOp.suggestedWorkerNum match {
    case Some(num) => num
    case None      => ApplicationConfig.numWorkerPerOperatorByDefault
  }
} else 1                        // non-parallelizable → exactly 1 worker
(0 until workerCount).map(idx => VirtualIdentityUtils.createWorkerIdentity(physicalOp.workflowId, physicalOp.id, idx))
```
Called per-operator by `ResourceAllocator.allocate(region)` → stored in a `ResourceConfig`.
**Workers are allocated per *region*** (allocate takes a `Region`) — foreshadows Lesson 3.

**The config-vs-stats distinction (senior reading skill):**
- **Config** (`WorkerConfig`/`ResourceConfig`/`OperatorConfig`) = the *decided plan* ("run N workers"),
  produced at scheduling time.
- **Stats** (`OperatorStatistics`) = *observability* streamed to the UI during execution:
  `inputMetrics, outputMetrics, numWorkers, dataProcessingTime, controlProcessingTime, idleTime`,
  pushed as `OperatorStatisticsUpdateEvent` over WebSocket.
- Same word `numWorkers`: one **sets** it (config), one **observes** it (stats). Always ask which
  world you're in. Note `data`/`controlProcessingTime` — the two-plane split resurfaces even in metrics.

**Why a Sort gets 1 worker (conceptual):** Filter decides each tuple independently → embarrassingly
parallel. Sort needs *all* tuples for a total order → inherently **blocking** (can't emit until it
consumes the last input) → not parallelizable. That blocking property is what forces a region cut.

---

## Lesson 3 — Region-based scheduling (pipeline vs materialize)

**Files:** `common/workflow-core/…/workflow/PhysicalPlan.scala`,
`amber/…/engine/architecture/scheduling/{Region,ScheduleGenerator,CostBasedScheduleGenerator}.scala`

**Pipeline vs Materialize — the core dichotomy:**
- **Pipeline (stream):** upstream sends tuple batches directly, in memory, to downstream; both run
  concurrently. Low latency, no storage; both occupy resources at once.
- **Materialize:** upstream writes its *entire* output to a storage **document** (Iceberg/VFS — Week 2);
  downstream starts only after upstream fully finishes, then reads it back. Decoupled in time.
- **A region boundary IS a materialization point.** (`OutputPort.reuseStorage`'s comment: the region
  scheduler provisions the port's **output document** — that's the materialized result.)
- Why materialize: (1) correctness — a blocking op can't stream; (2) resource/cost — bound memory by
  not running everything at once; (3) reuse / fault tolerance (Week 2).

**The region walls** (`PhysicalPlan.getBlockingAndDependeeLinks`): a link is a *mandatory* wall iff
```scala
getOperator(toOp).isInputLinkDependee(link)            // consumer input port is a dependee  (InputPort.dependencies)
|| getOperator(fromOp).isOutputLinkBlocking(link)       // producer output port is blocking     (OutputPort.blocking)
```
→ exactly the two proto signals predicted in Lesson 1.

**The candidate cuts** (`getNonBridgeNonBlockingLinks`):
```scala
this.links.diff(getBlockingAndDependeeLinks).diff(bridges)   // bridges via jgrapht BiconnectivityInspector
```
Two link categories:
1. **Mandatory walls** — always materialized (a Sort *must* block). Non-negotiable.
2. **Candidate links** — the **cost-based optimizer's search space**; it chooses which *optional* links
   to additionally materialize to minimize cost (`CostBasedScheduleGenerator.SearchResult(state: Set[PhysicalLink])`).

**Bridges** (an edge whose removal disconnects the DAG) are excluded from the candidate set:
- Materializing a bridge is *strictly worse* — it forces the two halves into separate regions (sequential
  + storage cost) with **no** upside, because a bridge is the sole connector (no alternative path makes
  cutting worthwhile). Pipelining a bridge lets both halves run concurrently for free.
- A **non-bridge** link *can* be worth materializing when another path *already* forces those endpoints
  into different regions — then cutting the parallel link may be "free"/beneficial. Only then does the CBO
  deliberate.
- Excluding bridges **halves the search space per bridge** (search is over subsets of candidates, `2^n`)
  with **zero** risk of losing the optimum. Hence the method name `getNonBridgeNonBlockingLinks`.

**The schedule** (`ScheduleGenerator`): "a schedule is a ranking on the regions of a region plan; regions
are dispatched in batches up to `AmberConfig.maxConcurrentRegions`, respecting DAG deps
(`maxConcurrentRegions==1` ⇒ fully sequential)." Dispatch = Kahn's topological algorithm over the region DAG.
`CostBasedScheduleGenerator.effectiveExecutionMode`: any op with `requiresMaterializedExecution`
(e.g. loop ops, whose back-edge is a cross-region materialized state channel) forces the whole plan to
`MATERIALIZED`.

**One-liner to keep:** *regions = materialization boundaries, chosen by a cost-based search over the
non-mandatory, non-bridge links.*

---

## Progress tracker

| # | Concept | Status |
|---|---|---|
| 1 | Logical→Physical plan (ports, links, plan-is-data) | ✅ |
| 2 | Actor model & identities + worker spawning + config-vs-stats | ✅ |
| 3 | Region-based scheduling (pipeline vs materialize, walls, bridges, CBO) | 🔶 in progress — next: how link-sets become `Region`/`RegionPlan` objects |
| 4 | Credit-based flow control / backpressure | ⬜ |
| 5 | Scala↔Python Arrow-Flight bridge | ⬜ |
| — | Capstone: whiteboard `CSV → Python UDF → sort → sink` end-to-end, unaided | ⬜ |

**Open checkpoint (to answer next):** linear chain `CSV → Filter → Sort → Sink`, Sort fed by a blocking
link, all links are bridges → how many regions, where's the single materialization boundary, and did the
cost-based search have *any* candidates to deliberate over? (Insight: with only bridges + one mandatory
wall, the CBO's candidate set is empty — nothing to search.)
