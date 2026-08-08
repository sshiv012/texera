# 30-Day Roadmap: From Contributor to Committer-Track Maintainer (Apache Texera)

> Grounded in the graphify map of the codebase (god nodes, community structure) and
> how this project actually operates (test-heavy culture, ASF governance).

## Reality check first (read this)

**"Committer in 30 days" is a stretch goal, not a guarantee.** At Apache, committer is a
**merit + trust** decision made by the PMC — it rewards a *track record* of quality
contributions and good community behavior, not a checklist you complete. 30 focused days
can realistically make you a **credible candidate**: 3–6 merged PRs, active review
participation, and demonstrated architectural understanding. Whether the vote happens on
day 30 or day 90 is the PMC's call. Optimize for **being obviously mergeable and helpful**,
and the title follows.

**Using AI is fine — but the bar is that YOU can defend every line.** Maintainers are
trusted to review others' code and say "no." If you can't explain why your PR is correct
without the AI, you're not ready to be the person who approves someone else's. Every day
below has a "prove it without AI" checkpoint.

---

## The map (what you're actually learning)

Texera is a **visual big-data workflow platform**: users build operator DAGs in a browser;
a distributed engine executes them. From the graph, the system splits into these subsystems
(with their god nodes — the most-connected abstractions, i.e. where understanding pays off most):

| Subsystem | What it is | Key god nodes / anchors |
|---|---|---|
| **Frontend (Angular)** | The workflow editor UI, dashboard, agent panel | `WorkflowActionService` (canvas mutations), `JointGraphWrapper`, `WorkflowGraph` |
| **Amber engine (Scala + Python)** | Distributed operator execution (Pekko actors) | `PhysicalOp`, `Table`, `Workflow`, worker/controller architecture |
| **workflow-core / operators** | Operator definitions, logical→physical compile | `PhysicalOp`, `LogicalOp`, operator descriptors |
| **Microservices** | compiling, execution, file, access-control, config, computing-unit, agent | Dropwizard (Scala) + one Bun/TS service (agent) |
| **DAO / jOOQ** | Postgres persistence, generated from schema | `UserRecord`, `WorkflowExecutionsRecord`, `PrivilegeEnum` |
| **Platform / CI** | Build, backport, release automation, self-hosted runners | `build.yml`, `precheck.yml`, ARC runner tiers |

**Data flow to internalize:** browser edits (`WorkflowActionService`) → workflow JSON →
compiling-service (logical→physical plan) → Amber engine schedules `PhysicalOp`s across
workers → results stream back over WebSocket → frontend renders. Persistence (workflows,
users, executions) runs through the jOOQ DAO into Postgres. That one sentence is 60% of the
architecture.

**Start where the god nodes cluster.** `WorkflowActionService`, `Table`, `PhysicalOp`,
`WorkflowGraph` are the highest-degree nodes — learning them first gives maximum leverage.

---

## Week 1 — Orient, build, ship something tiny (days 1–7)

**Goal:** the whole stack runs locally, you can trace one workflow end-to-end, and you have
one trivial merged-or-open PR.

- **Day 1–2 · Get it running.** Bring the stack up (`bin/local-dev.sh up`). Build a workflow
  in the UI (CSV scan → filter → visualize), run it, watch results stream. Read `AGENTS.md`
  and `CONTRIBUTING.md` cover to cover — they encode the project's norms (labels, conventional
  commits, test expectations).
- **Day 3–4 · Trace one execution.** Pick a simple operator (e.g. Filter). Follow it:
  frontend operator descriptor → workflow JSON → compiling-service → `PhysicalOp` in Amber →
  result. Use AI to explain each hop, then **redraw the path from memory on paper.**
- **Day 5 · Read the god nodes.** `WorkflowActionService` (frontend) and `PhysicalOp` /
  `Workflow` (Amber). Don't aim to understand everything — map responsibilities.
- **Day 6–7 · Ship #1 (trivial).** Find a **docs typo, a broken link, or a missing operator
  doc** (operator docs are templated — easy, real, welcomed). Open your first PR. Goal here is
  learning the *process*: fork, branch, conventional-commit, CI (`precheck`/`build`), review
  etiquette — not impact.
- **Prove-it checkpoint:** explain, out loud, what happens when a user clicks "Run."

**Ship target: 1 docs/typo PR.**

## Week 2 — Depth in one subsystem + a real (small) code PR (days 8–14)

**Goal:** go deep in ONE subsystem (pick by your strength — Angular/TS or Scala), and land a
real code change with tests.

- **Day 8–10 · Pick a lane and go deep.**
  - *Frontend lane:* `WorkflowActionService`, the joint-graph model, how operators/links are
    added/validated, the undo/redo (yjs) system, the testing setup (vitest jsdom vs browser —
    the `detectChanges Coverage Switch` god node is literally about this).
  - *Engine lane:* Amber's actor model (Pekko), controller/worker split, `PhysicalOp`
    lifecycle, how `Table`/tuples flow, the Scala↔Python bridge.
- **Day 11–12 · Learn the test culture.** This project **values tests heavily** (you'll see
  hundreds of `*Spec.scala`, frontend `.spec.ts`, agent-service bun tests). Read existing
  specs in your lane; understand the harness. **Test PRs are the classic committer on-ramp** —
  low-risk, high-trust, genuinely wanted.
- **Day 13–14 · Ship #2 (test coverage).** Add a spec for an under-tested class/component in
  your lane. This is the single highest-ROI contribution type: it proves you understand the
  code, it's mergeable, and it builds reviewer trust.
- **Prove-it checkpoint:** review a real open PR in your lane and leave one substantive,
  correct comment (like the ARC-runner review we did — find a concrete issue with file:line).

**Ship target: 1 test-coverage PR + 1 substantive PR review.**

## Week 3 — Cross-cutting understanding + a feature/bugfix (days 15–21)

**Goal:** understand how subsystems talk to each other, and land a user-visible change.

- **Day 15–16 · The seams.** WebSocket protocols (how the engine streams to the frontend; how
  the agent-service talks to the UI — you've already seen this deeply with the revert/redo
  work), the jOOQ DAO layer (`UserRecord`, `WorkflowExecutionsRecord`, `PrivilegeEnum` — RBAC),
  and the compiling-service boundary.
- **Day 17–18 · Pick a real issue.** Grab a `good first issue` / small bug from the tracker,
  or productize something you understand. **You already have a shippable feature: the agent
  turn revert/redo** — polish it, write the PR body from the RFC, address the review points
  already raised, and open it against `apache/texera`. That's a substantial, defensible
  contribution.
- **Day 19–21 · Ship #3 (feature or bugfix with tests).** Land it with tests + a clear PR
  description that explains *why*. Respond to review fast and graciously.
- **Prove-it checkpoint:** explain the RBAC/privilege flow (`PrivilegeEnum` →
  `*_user_access` tables → resource access) without notes.

**Ship target: 1 feature/bugfix PR (the revert/redo PR is a strong candidate).**

## Week 4 — Breadth, ownership behavior, and visibility (days 22–30)

**Goal:** act like a maintainer — review broadly, understand the build/release machinery, and
make your work visible to the PMC.

- **Day 22–24 · The platform layer.** Understand `build.yml` / `precheck.yml`, the backport &
  release workflows, and the self-hosted runner setup (you've already reviewed PR #7172 here).
  Maintainers own CI health — knowing it matters.
- **Day 25–27 · Review like a maintainer.** Review 2–3 more PRs across different subsystems.
  Aim for *correct, specific, kind* feedback. This is the behavior the PMC is actually
  evaluating — can you be trusted to gatekeep?
- **Day 28–29 · Ship #4 + engage the community.** Land one more PR (docs/test/small fix), and
  engage on the `dev@texera` mailing list / discussions — propose an improvement (e.g. your
  streaming `streamText` RFC), ask thoughtful questions, be present. Committership is decided
  by people who need to *know you exist and trust you.*
- **Day 30 · Consolidate.** Write a short "what I learned + what I shipped" summary. Have
  3–6 merged/open PRs, several reviews, and a visible mailing-list presence.

**Ship target: 1 more PR + broad review participation + mailing-list engagement.**

---

## How ASF committership actually happens (so you optimize correctly)

1. A PMC member notices sustained, quality contributions and proposes you privately.
2. The PMC discusses and votes.
3. It's about **trust and merit**, not volume. Five thoughtful PRs + good reviews beat fifty
   trivial ones.
4. **Community behavior counts as much as code.** Responsiveness, humility taking review,
   helping others — these are load-bearing.

**What moves the needle most, ranked:** (1) test coverage PRs, (2) substantive, correct code
reviews of others' work, (3) a well-executed feature/bugfix with tests, (4) mailing-list/
discussion presence, (5) docs. Do all five; weight toward 1–2.

## Using AI without faking competence

- **Let AI accelerate**: explaining code, scaffolding tests, drafting PR descriptions,
  navigating the graph (`/graphify query`).
- **Never let AI substitute understanding**: before every PR, do the "prove-it" checkpoint —
  explain it with the AI turned off. If you can't, you're not ready to defend it in review,
  and definitely not ready to review someone else's.
- **The graph is your tutor**: `graphify query "how does X work"`, `graphify explain "PhysicalOp"`,
  `graphify path "WorkflowActionService" "PhysicalOp"` to trace across subsystems.

## Success criteria by day 30
- [ ] Whole stack runs locally; you can trace a workflow end-to-end from memory.
- [ ] Deep in one subsystem, literate in the rest.
- [ ] 3–6 PRs opened/merged (weighted to tests + one real feature).
- [ ] 4+ substantive PR reviews across subsystems.
- [ ] Visible on the dev mailing list / discussions.
- [ ] Can explain the execution path, RBAC, and the frontend↔engine seam unaided.
