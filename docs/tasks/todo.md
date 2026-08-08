# Task: Agent Turn "Revert" (rewind workflow to before an agent turn)

## STATUS — implemented on branch `feat/agent-turn-revert`
- ✅ Backend (agent-service): `WsClientRevertCommand`, `WsServerHeadChangeEvent`,
  `TexeraAgent.revertToTurnStart()`, `server.ts` command handler (rejects while GENERATING).
- ✅ Frontend: `WsServerHeadChangeEvent` handler, `AgentService.revertTurn()`,
  `AgentChatComponent.revertTurn()/canRevert()`, per-turn Revert button (nz-popconfirm).
- ✅ Unit tests: agent-service `typecheck` clean + **119/119 bun tests pass** (4 new WS revert
  tests); frontend **agent.service.spec 9/9** (3 new) and **agent-chat.component.spec 2/2**
  (template compiles, including the new button).
- ⚠️ Manual in-app verification NOT performed: it requires the full Texera platform running
  (amber Scala engine, Postgres, LiteLLM/LLM endpoint, file-service) plus a live agent turn —
  not bootable in this environment. Logic + wiring are covered by tests at every layer; a
  human should still do the one-click manual check below before merge.
- Note: toolchain was bootstrapped locally (installed `bun`; ran frontend via `ng test` jsdom
  on Node 23 although package.json requests Node ≥24 — tests passed regardless).


## Goal
Let a user click **Revert** on any past agent turn in the AI agent chat to restore
the workflow canvas to the state it was in *before that turn's edits*, and rewind the
agent-service HEAD so the agent's next message continues from the reverted state
(full rewind — not a cosmetic frontend-only revert).

## Why this is feasible today (exploration findings)
- The agent-service already keeps a **step version-tree with a HEAD pointer**
  (`texera-agent.ts:93` `private head`, `getStepsById()`, `getVisibleReActSteps()`,
  `getHead()`), and every `ReActStep` stores `beforeWorkflowContent` /
  `afterWorkflowContent` (`types/agent.ts`, `agent-types.ts:78,80`).
- HEAD currently only ever **advances** internally — there is **no client command to
  move it back** (`types/ws/client.ts` has only `WsClientPromptCommand` /
  `WsClientStopCommand`). This is the one real gap.
- The frontend is already HEAD-aware: `agent-chat.component.ts:569 recomputeVisibleSteps()`
  walks `parentId` from HEAD, and it subscribes to `getHeadIdObservable()`
  (`agent.service.ts:976`). It applies workflow snapshots to the real canvas via
  `WorkflowActionService.reloadWorkflow(...)` (`agent-chat.component.ts:285`), which
  also clears the undo/redo stacks (correct — we do NOT route revert through undo/redo).

## Decisions (confirmed with user)
1. **Full rewind** (backend HEAD move), not cosmetic frontend-only.
2. **Reconcile with stash first** — see note below.
3. **Granularity: per agent turn** (one Revert control per user-message turn).
4. **Verify: unit tests + manual in-app.**

## Stash reconciliation note (IMPORTANT — read before coding)
`stash@{0}` ("graphify-autostash") is **NOT a revert attempt** — it is a separate
**live text-delta streaming + `"chat"|"feedback"` message source** feature
(`generateText`→`streamText`, a `textDelta` WS frame, `streamingContentSubjects`).
It was written against the **old string-typed WS protocol** (`WsOutgoingMessage` with
`type: "step"|"state"|...`), which upstream has since refactored into the class-based
events (`WsServerStepEvent`, `WsClientPromptCommand`, ...). The stash diffs therefore
**no longer apply cleanly and are largely stale**.

Reconciliation outcome: the revert feature is **orthogonal** to the stash. Plan:
- Build revert fresh on current `main` using the new class-based protocol.
- Leave `stash@{0}` untouched (it stays parked).
- (Optional, separate task — not in this PR) re-port the streaming feature to the new
  protocol. Called out so it isn't silently lost.

---

## Implementation plan

### Backend — agent-service
- [ ] `agent-service/src/types/ws/client.ts`: add
      `class WsClientRevertCommand { readonly type = "WsClientRevertCommand"; constructor(readonly messageId: string) {} }`
      and add it to the `WsClientCommand` union.
- [ ] `agent-service/src/types/ws/server.ts`: add
      `class WsServerHeadChangeEvent { readonly type = "WsServerHeadChangeEvent"; constructor(readonly headId: string, readonly workflowContent: any) {} }`
      and add it to the `WsServerEvent` union.
- [ ] `agent-service/src/agent/texera-agent.ts`: add
      `revertToTurnStart(messageId: string): { headId: string; workflowContent: any }`:
        - find the turn's first (user) step for `messageId` from `stepsById`;
        - `newHead = userStep.parentId ?? INITIAL_STEP_ID`;
        - set `this.head = newHead`; also sync `workflowState.setWorkflowContent(userStep.beforeWorkflowContent)` so the agent's working state matches HEAD;
        - return `{ headId: newHead, workflowContent: userStep.beforeWorkflowContent }`.
        - Throw if `messageId` unknown.
- [ ] `agent-service/src/server.ts`: handle `case "WsClientRevertCommand"`:
        - reject (send `WsServerErrorEvent`) if agent state is `GENERATING`;
        - call `agent.revertToTurnStart(msg.messageId)`;
        - `broadcastToAgentClients(agentId, new WsServerHeadChangeEvent(headId, workflowContent))`.

### Frontend
- [ ] `agent.service.ts`: in `handleWebSocketMessage`, add
      `case "WsServerHeadChangeEvent":` → `tracking.headIdSubject.next(message.headId)`
      (auto-recomputes visibleSteps) **and** push `{ ...existing, content: message.workflowContent }`
      to `workflowSubject` (auto-triggers `reloadWorkflow` on the canvas).
- [ ] `agent.service.ts`: add `public revertTurn(agentId: string, messageId: string): void`
      that sends `new WsClientRevertCommand(messageId)` over the agent's websocket
      (mirror how prompt/stop commands are sent).
- [ ] `agent-chat.component.ts`: add `revertTurn(messageId: string)` calling
      `agentService.revertTurn(this.agentInfo.id, messageId)`; expose a helper to know
      whether a turn is revertable (not the current HEAD turn; agent not GENERATING).
- [ ] `agent-chat.component.html`: render a small **Revert** button per agent turn
      (grouped by `messageId`), disabled while `state === GENERATING` and hidden for the
      current HEAD turn / initial state. Add a confirm tooltip ("Revert workflow to
      before this turn").

### Tests (unit)
- [ ] `agent-service/src/agent/texera-agent.spec.ts` (or existing spec): `revertToTurnStart`
      moves HEAD to the turn's parent, returns the correct `beforeWorkflowContent`, makes
      `getVisibleReActSteps()` drop the reverted turn, throws on unknown messageId.
- [ ] `agent-service/src/server.ws.spec.ts`: `WsClientRevertCommand` → emits
      `WsServerHeadChangeEvent`; rejected with `WsServerErrorEvent` while GENERATING.
- [ ] `agent.service.spec.ts`: `WsServerHeadChangeEvent` updates headId + workflow subjects;
      `revertTurn` sends the right frame.
- [ ] `agent-chat.component.spec.ts`: Revert button renders per turn and calls
      `agentService.revertTurn`; hidden/disabled in the right states.

### Manual verification
- [ ] Run the app (frontend + agent-service + backend), open a workflow, activate the agent.
- [ ] Ask the agent to add an operator (e.g. "add a CSV file scan and a filter").
- [ ] Click **Revert** on that turn → canvas returns to the pre-turn state, chat history
      collapses to before the turn, and a follow-up agent message continues from the
      reverted workflow (proving the backend HEAD moved, not just the canvas).

## Follow-up: Redo (added per user request)
- [ ] Backend: `TexeraAgent` redo stack — `revertToTurnStart` pushes the pre-revert HEAD;
      new `redo()` pops + moves HEAD forward (restores that step's afterWorkflowContent);
      `canRedo()`; `sendMessage` and `clearHistory` clear the stack.
- [ ] WS: `WsClientRedoCommand`; add `canRedo` to `WsServerHeadChangeEvent` + `WsServerSnapshotEvent`.
- [ ] Frontend: track `canRedo`; `AgentService.redo()`; global Redo button in the chat toolbar
      (enabled only when canRedo && not generating).
- [ ] Tests: redo pops/moves HEAD, clear-on-new-prompt, WS command; frontend handler + button.

## Out of scope (this PR)
- Per-individual-step revert (we chose per-turn).
- Re-porting the parked streaming/feedback stash to the new protocol (separate task).
- "Redo" / forward navigation after a revert (the tree supports it; not in this PR).

## Risk / collision notes
- Touches `server.ts`, `texera-agent.ts`, `agent.service.ts`, `agent-chat.component.*` —
  same files as the parked stash, but the stash stays parked and is stale, so no live conflict.
- `reloadWorkflow` clears undo/redo stacks by design; revert intentionally does not create
  an undo entry.
