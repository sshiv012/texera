# 30-Day Texera Mastery Roadmap (core-team onboarding, knowledge-first)

> Goal: understand Texera — conceptually AND code-wise — deeper than the existing
> contributors. Not about shipping to earn trust (you're already core team + an ASF
> committer). About *owning the mental model of the whole system*, especially the hard
> cross-cutting internals most contributors never touch. Grounded in the graphify map
> (real files/classes cited below).

## The differentiator thesis

Most contributors know **one lane** (an operator, the frontend, one service). You out-know
them by mastering the **three things almost nobody fully holds in their head**:

1. **The distributed execution engine (Amber)** — actors, scheduling, flow control, and the
   Scala↔Python boundary.
2. **The research-grade internals** — determinant-based fault recovery, region-based
   scheduling, and Fries live reconfiguration. These come from published papers; knowing the
   *why* behind them is what separates a maintainer from a contributor.
3. **The seams** — how the frontend, services, engine, storage, and DAO actually talk.

If you can whiteboard those three unaided, you're ahead of the field. Everything else
(operators, one CRUD service, a UI component) you can learn on demand.

---

## Layer 0 — The map (internalize before anything)

Texera = visual workflow platform (VLDB 2024 paper — read the README citation first). A
browser DAG editor drives a distributed engine.

**Execution spine (memorize this path):**
`WorkflowActionService` (frontend canvas) → workflow JSON → **compiling-service** builds a
`LogicalPlan` → `PhysicalPlan` (`common/workflow-core/.../workflow/PhysicalPlan.scala`) →
**Amber** partitions it into **Regions** and schedules them → Controller drives Workers
(one per `PhysicalOp`) over Pekko → tuples (`Table`) stream between workers with flow control
→ results stream back over WebSocket → frontend renders. Persistence via **jOOQ DAO** →
Postgres; datasets/results via **Iceberg + LakeFS + MinIO**.

Highest-degree god nodes (learn first, max leverage): `WorkflowActionService`, `Table`,
`PhysicalOp`, `WorkflowGraph`, `PrivilegeEnum`.

---

## Week 1 — The engine core (days 1–7)

This is the hardest and highest-value week. Do it first while fresh.

- **Day 1 · Run + read the paper.** Bring the stack up. Read the Texera VLDB paper (README
  citation) and `AGENTS.md`'s architecture map. Draw the execution spine from memory.
- **Day 2–3 · Physical plan & the actor model.** `PhysicalPlan` / `PhysicalOp`
  (`common/workflow-core`), the Controller/Worker split, Pekko actor messaging. How does one
  operator become N worker actors? How do messages route (`ActorVirtualIdentity`,
  `ChannelIdentity`)?
- **Day 4 · Region-based scheduling** (research-grade — most contributors don't know this):
  - `amber/.../scheduling/Region.scala`, `RegionPlan.scala`
  - `CostBasedScheduleGenerator.scala` vs `ExpansionGreedyScheduleGenerator.scala`
  - `resourcePolicies/ResourceAllocator.scala`, `config/ResourceConfig.scala`
  - Understand *why* the DAG is cut into regions (pipelined vs blocking edges) and how
    execution order is chosen.
- **Day 5 · Flow control / backpressure** (credit-based):
  - `handlers/actorcommand/backpressure_handler.py`, `models/internal_queue.py` (Python side)
  - `pythonworker/PythonWorkflowWorker.scala::handleBackpressure` (Scala side)
  - `messaginglayer/FlowControl.scala`. How do credits prevent a fast upstream from
    overwhelming a slow downstream?
- **Day 6 · The Scala↔Python bridge** (Arrow Flight):
  - Scala: `pythonworker/PythonProxyServer.scala`, `PythonProxyClient.scala`
  - Python: `core/proxy/proxy_server.py`, `proxy_client.py` (`do_put`, `call_action`,
    `_handshake`). How does a Python UDF operator exchange tuple batches + control messages
    with the JVM?
- **Day 7 · Consolidate.** Whiteboard: "a Python UDF operator in a 3-operator workflow — trace
  every actor, message, and data transfer from Run to result." **Prove-it: do it unaided.**

## Week 2 — Fault tolerance, reconfiguration, storage (days 8–14)

The research internals. This week is what makes you *the* person who understands Texera.

- **Day 8–9 · Determinant-based fault recovery / log replay:**
  - `architecture/logreplay/` — `ReplayLogManager.scala`, `ReplayLogGenerator.scala`,
    `OrderEnforcer.scala` / `ReplayOrderEnforcer.scala`, `ReplayLogger*.scala`,
    `AsyncReplayLogWriter.scala`.
  - Concept: why nondeterminism (message arrival order) must be logged as *determinants* so a
    crashed worker can be replayed to an identical state. This is causal-logging FT research —
    understand the theory, then map it to these classes.
- **Day 10–11 · Live reconfiguration (Fries):**
  - `engine/common/FriesReconfigurationAlgorithm.scala`,
    `coordinator/promisehandlers/ReconfigurationHandler.scala`,
    `web/service/ExecutionReconfigurationService.scala`, `internal_marker.py` (epoch markers).
  - Concept: how do you swap an operator's logic *mid-execution* consistently, using epoch
    markers that flow through the dataflow like punctuations? (Also a published algorithm.)
- **Day 12–13 · Storage layer:**
  - Iceberg catalog: `core/storage/IcebergCatalogInstance.scala`, `DocumentFactory.scala`,
    `VFSURIFactory.scala`, `FileResolver.scala`.
  - Python side: `core/storage/vfs_uri_factory.py`, `document_factory.py`; large-binary
    streaming (`pytexera/storage/large_binary_*`).
  - LakeFS/MinIO wiring (you've seen the compose/helm). Concept: how results and datasets are
    versioned and materialized; what a VFS URI resolves to.
- **Day 14 · Consolidate.** Whiteboard: "worker X crashes mid-execution — exactly how does the
  system recover to a consistent state?" and "how is a 2GB result written and later read back?"
  **Prove-it unaided.**

## Week 3 — Compile pipeline, services, RBAC, the seams (days 15–21)

- **Day 15–16 · Logical→physical compile.** compiling-service: how `LogicalOp` +
  operator descriptors → `PhysicalPlan`; schema propagation; port/link validation. This is the
  bridge between "what the user drew" and "what the engine runs."
- **Day 17 · The microservice topology.** compiling, execution, file, access-control, config,
  computing-unit, agent (the one Bun/TS service). What each owns, how they're deployed
  (Dropwizard + Envoy gateway routing you saw in the helm charts), and their config model.
- **Day 18 · RBAC / DAO deep-dive.** `PrivilegeEnum` → `*_user_access` tables →
  resource access checks; jOOQ code generation from the SQL schema (`sql/`), `UserRecord`,
  `WorkflowExecutionsRecord`. How is a "can user U read workflow W?" decision made end to end?
- **Day 19 · Computing units & multi-region.** How workflows get isolated compute
  (Kubernetes scale sets, the `ci:self-hosted` / ARC runner story you reviewed), and the
  multi-region execution path.
- **Day 20 · Frontend architecture.** `WorkflowActionService`, joint-graph model, the yjs
  collaborative editing / undo-redo, the WebSocket clients (execution + agent), and the
  vitest jsdom-vs-browser test model (`detectChanges` coverage).
- **Day 21 · Consolidate the seams.** Whiteboard every inter-process boundary: FE↔services
  (REST + WS), services↔engine, engine Scala↔Python, engine↔storage, services↔Postgres.
  **Prove-it unaided.**

## Week 4 — Breadth, history, and active ownership (days 22–30)

- **Day 22–23 · Read the git/PR history like a detective.** Skim the last ~6 months of merged
  PRs and the backport/release machinery (`build.yml`, `precheck.yml`, the runner tiers). Learn
  *why* things are the way they are — the decisions behind the code. This context is what
  senior contributors have and newcomers lack.
- **Day 24–25 · The operator SDK surface.** How a new operator is authored (Java/Python/Scala
  descriptors + executors), the codegen, HuggingFace/sklearn families. You don't need every
  operator — you need the *pattern* cold.
- **Day 26–27 · Pick two "dark corners" and go to the bottom.** Candidates: the Arrow Flight
  batch protocol details, checkpoint/replay interaction with scheduling, Iceberg commit
  semantics under concurrent writes, or the agent-service ReAct/version-tree (you already know
  this one). Depth in a couple of scary areas cements the "knows it better than anyone" status.
- **Day 28–29 · Teach it back.** Write an internal architecture note (or improve `AGENTS.md` /
  docs) explaining one deep subsystem in your own words — the ultimate comprehension test, and
  genuinely useful to the team. Engage on `dev@texera` about a real design question.
- **Day 30 · Self-audit.** Go through the "can I explain unaided?" checklist below. Any "no"
  becomes next week's focus.

---

## Use the graph as your tutor (fastest path in)

- `graphify explain "PhysicalOp"` / `"Region"` / `"ReplayLogManager"` — neighborhoods of any abstraction.
- `graphify query "how does fault recovery work"` — pulls the relevant cluster.
- `graphify path "WorkflowActionService" "PhysicalOp"` — trace across the FE↔engine seam.
- `graphify path "PythonProxyServer" "InternalQueue"` — the Scala↔Python data path.
Then **turn the AI off and re-derive it** — that's the actual learning.

## Mastery checklist (can you explain, unaided?)
- [ ] The full execution spine: click-Run → results, every hop.
- [ ] Why the plan is cut into **Regions** and how schedule order is chosen (cost vs greedy).
- [ ] **Credit-based backpressure** across the Scala↔Python boundary.
- [ ] The **Arrow Flight** bridge: tuple batches + control messages between JVM and Python.
- [ ] **Determinant logging & replay**: how a crashed worker recovers consistently.
- [ ] **Fries reconfiguration**: swapping operator logic mid-run via epoch markers.
- [ ] Storage: VFS URIs, Iceberg versioning, large-binary streaming, LakeFS/MinIO roles.
- [ ] Logical→physical compile + schema propagation.
- [ ] RBAC decision path (`PrivilegeEnum` → access tables) and jOOQ codegen.
- [ ] Frontend: `WorkflowActionService`, yjs collab/undo, the two WebSocket clients.
- [ ] The microservice topology + Envoy gateway routing + computing-unit isolation.

When every box is checked unaided, you know Texera better than most of the people who wrote it.

## Papers / external reading (the "why" behind the code)
- Texera system paper (VLDB 2024 — README citation).
- The group's work on **fault-tolerant dataflow via determinant/causal logging** (maps to `logreplay/`).
- **Fries** — live reconfiguration of running dataflows (maps to `FriesReconfigurationAlgorithm`).
- Region-based / cost-based scheduling background (maps to `scheduling/`).
Read each paper, then open the corresponding package and confirm the code matches the theory —
that round-trip is the highest-signal learning you can do.
