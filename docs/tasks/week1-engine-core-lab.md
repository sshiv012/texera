# Week 1 Lab — Amber Engine Core

> Your working notebook for Week 1. Read a section, open the cited files, then do the
> **exercises** (debug / verify / trace). Answers you write go under each "Your notes".
> Rule: after AI explains, close it and re-derive — that's the learning.

---

## Concept 1 — Logical plan vs Physical plan

The user draws a **logical** DAG (operators + links). The compiling-service turns each
logical operator into one or more **physical** operators, producing a `PhysicalPlan`.

- `common/workflow-core/.../workflow/PhysicalPlan.scala`
  - `case class PhysicalPlan(operators: Set[PhysicalOp], links: Set[PhysicalLink])`
  - Builds a jgrapht `DirectedAcyclicGraph` (`dag`) for topological traversal.
  - `getSourceOperatorIds` = ops with in-degree 0.
  - `getPhysicalOpsOfLogicalOp` = the 1→N logical→physical fan-out.
- `common/workflow-core/.../workflow/PhysicalOp.scala`
  - A physical op carries: schema-propagation function, parallelism/worker config, and
    executor init info (`OpExecInitInfo`).
  - `sourcePhysicalOp`: source ops init on the coordinator JVM, have **1 worker**, **no input ports**.

**Why 1 logical → N physical:** one logical operator can expand into several physical stages
(e.g. a hash join → build + probe), and each physical op is then parallelized into many
**worker actors**. Three levels: logical op → physical op → worker actors.

### Exercises 1
1. **Verify the fan-out.** In the UI build: `CSV scan → hash join (self-join is fine) → sink`.
   Then trace in code how the join's logical op maps to physical ops. Grep:
   `grep -rn "getPhysicalOpsOfLogicalOp\|def getPhysicalPlan" common workflow-compiling-service`.
   Write: which logical ops expand to >1 physical op, and why?
2. **Schema propagation.** Find `SchemaPropagationFunc` usages. Question to answer: when a
   filter has input schema S, what determines its output schema, and where is that function
   invoked during compile?

_Your notes:_

---

## Concept 2 — The actor model (Controller + Workers, Pekko)

Execution is a set of **actors** over Apache Pekko (Akka fork). One **Controller** actor
drives many **Worker** actors (one worker per parallel instance of a physical op).

- Controller: `amber/.../architecture/controller/` (drives scheduling, lifecycle, stats).
- Worker: `amber/.../architecture/worker/WorkflowWorker.scala` — the actor;
  `DataProcessor.scala` — the per-worker execution loop that pulls input, runs the operator
  executor, pushes output.
- Identities: `ActorVirtualIdentity` (which actor), `ChannelIdentity` (a directed comm channel
  between two actors) — these are god nodes; message routing is built on them.

**Mental model:** the physical DAG is "logical wiring"; at runtime it's actors sending
tuple batches to each other over channels, coordinated by one controller. There is no shared
memory — everything is messages.

### Exercises 2
1. **Map the message types.** `grep -rn "final case class" amber/src/main/scala/org/apache/texera/amber/engine/architecture/messaginglayer | head -30`.
   Categorize: which are **data** messages vs **control** messages?
2. **Find worker creation.** Trace where worker actors are spawned for a physical op (grep
   `context.actorOf` / worker assignment). Answer: who decides *how many* workers a physical
   op gets, and where is that number stored? (hint: `ResourceConfig`, worker count on `PhysicalOp`).

_Your notes:_

---

## Concept 3 — Region-based scheduling (the research core)

You can't always run the whole DAG at once. Some links are **pipelined** (tuples flow
through immediately); some are **blocking/materialized** (downstream can't start until
upstream fully finishes — e.g. a sort, or a hash-join build side). The scheduler cuts the
physical DAG into **Regions** at those boundaries and runs regions in dependency order.

- `amber/.../scheduling/Region.scala` — `case class Region(physicalOps, physicalLinks, ports, resourceConfig)`; its own sub-DAG.
- `amber/.../scheduling/ScheduleGenerator.scala` (abstract base):
  - `generate(): (Schedule, PhysicalPlan)`
  - *"A schedule is a ranking on the regions of a region plan. Regions are dispatched in
    batches of up to `AmberConfig.maxConcurrentRegions`, respecting DAG dependencies.
    maxConcurrentRegions == 1 ⇒ fully sequential."*
  - `generateScheduleFromRegionPlan` does a Kahn's-algorithm topological dispatch (in-degree
    queue) over the region DAG.
- Two strategies:
  - `ExpansionGreedyScheduleGenerator.scala` — greedy region expansion.
  - `CostBasedScheduleGenerator.scala` — searches for a low-cost region cut
    (`SearchResult(state: Set[PhysicalLink], ...)` = which links are materialized).
- `CostBasedScheduleGenerator.effectiveExecutionMode`: if any op
  `requiresMaterializedExecution` (e.g. loop operators with a cross-region back-edge), the
  whole plan is forced to `MATERIALIZED` mode.
- `scheduling/config/ResourceConfig.scala`, `resourcePolicies/ResourceAllocator.scala` —
  how many workers / what resources each region's ops get.

**The key insight:** a Region is the unit of "can run concurrently." The cut points are the
blocking links. Cost-based scheduling is choosing *which* links to materialize to minimize
total cost — that's the searched-over decision.

### Exercises 3
1. **Find the cut rule.** In the schedule generator(s), find where a link is decided to be
   pipelined vs blocking/materialized. Grep: `grep -rn "blocking\|materialize\|pipelined\|requiresMaterialized" amber/src/main/scala/org/apache/texera/amber/engine/architecture/scheduling common/workflow-core`.
   Write the rule in one sentence.
2. **Trace a 2-region workflow.** Build `CSV → sort → sink` (sort is blocking). Predict: how
   many regions? Which link is the cut? Then confirm by reading how `Region`s are built.
3. **Debug drill (read-and-reason):** `CostBasedScheduleGenerator.effectiveExecutionMode`
   forces MATERIALIZED if any op `requiresMaterializedExecution`. Question: what would break
   if a loop operator's back-edge were scheduled as pipelined instead? (Reason about deadlock /
   consistency — you don't need to run it.)

_Your notes:_

---

## Concept 4 — Flow control / backpressure (credit-based)

A fast upstream worker must not flood a slow downstream. Amber uses **credit-based** flow
control: a receiver grants credits; a sender may only send while it has credit.

- Scala: `amber/.../messaginglayer/FlowControl.scala`;
  `pythonworker/PythonWorkflowWorker.scala::handleBackpressure`.
- Python: `core/architecture/handlers/actorcommand/backpressure_handler.py`,
  `core/models/internal_queue.py` (the bounded queue + `DCMElement`).

**Mental model:** backpressure propagates *backwards* — a slow sink slows its upstream, which
slows its upstream, all the way to the source. Credits are the mechanism.

### Exercises 4
1. **Find the credit accounting.** Grep `grep -rn "credit\|Credit" amber/src/main/scala/org/apache/texera/amber/engine/architecture/messaginglayer`.
   Answer: where are credits decremented (send) and replenished (receive)?
2. **Debug drill:** `internal_queue.py` is bounded. What happens on the Python side when the
   queue is full and more data arrives — where does the backpressure signal originate, and how
   does it reach the Scala sender? Trace `BackpressureHandler` → `handleBackpressure`.

_Your notes:_

---

## Concept 5 — The Scala ↔ Python bridge (Apache Arrow Flight)

Python UDF operators run in a **separate Python process**; the JVM worker talks to it over
**Arrow Flight** (gRPC + Arrow columnar batches). Tuples cross as Arrow record batches;
control messages cross as Flight "actions".

- Scala: `pythonworker/PythonProxyServer.scala`, `PythonProxyClient.scala`,
  `PythonWorkflowWorker.scala`.
- Python: `core/proxy/proxy_server.py` (`do_put` = receive data), `proxy_client.py`
  (`call_action`, `_handshake`).

**Mental model:** each Python worker is a Flight server the JVM connects to; data batches go
via `do_put`/`do_get`, control via `call_action`. Arrow's columnar format avoids
serialize/deserialize overhead across the language boundary.

### Exercises 5
1. **Trace one tuple batch** from a Scala worker into a Python UDF and back. Files above.
   Draw the sequence: JVM → Flight → Python `process_tuple` → Flight → JVM.
2. **Debug drill:** `_handshake` — what's exchanged at startup, and what breaks if the ports
   in the K8s/agent config don't match? (You saw the port wiring during deployment.)

_Your notes:_

---

## Week 1 capstone (prove-it, unaided)

Whiteboard, with AI off:
> "A workflow `CSV scan → Python UDF map → sort → sink`, run with 4-way parallelism. Trace
>  everything: logical→physical expansion, how many regions and where the cuts are, which
>  workers exist, how a tuple batch reaches the Python UDF and comes back, and what happens to
>  throughput if the sink is slow."

If you can narrate that end to end citing the real classes, Week 1 is done.

## Graph-tutor shortcuts
- `graphify explain "PhysicalOp"` · `graphify explain "Region"`
- `graphify path "WorkflowActionService" "PhysicalOp"` (FE→engine seam)
- `graphify path "PythonProxyServer" "InternalQueue"` (Scala↔Python data path)
- `graphify query "how does credit based flow control work"`
