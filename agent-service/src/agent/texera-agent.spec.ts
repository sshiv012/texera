/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// Exercises revert/redo over REAL sendMessage() turns (driven by a mock model),
// so the step-tree, HEAD movement, per-step workflow snapshots, and redo-stack
// lifecycle are covered end to end rather than via hand-built internal state.

import { describe, expect, test } from "bun:test";
import { MockLanguageModelV3 } from "ai/test";
import { TexeraAgent } from "./texera-agent";

function textModel(text: string): any {
  return new MockLanguageModelV3({
    doGenerate: async () =>
      ({
        content: [{ type: "text", text }],
        finishReason: "stop",
        usage: { inputTokens: 1, outputTokens: 1, totalTokens: 2 },
        warnings: [],
      }) as any,
  });
}

function throwingModel(message: string): any {
  return new MockLanguageModelV3({
    doGenerate: async () => {
      throw new Error(message);
    },
  });
}

function makeAgent(model: any): TexeraAgent {
  return new TexeraAgent({ model, modelType: "mock", agentId: "test-agent" });
}

describe("TexeraAgent revert/redo over real turns", () => {
  test("a completed turn records a workflow snapshot on every step", async () => {
    const agent = makeAgent(textModel("done"));
    await agent.sendMessage("hello");

    const steps = agent.getAllSteps();
    expect(steps.length).toBeGreaterThan(0);
    for (const s of steps) {
      expect(s.afterWorkflowContent).toBeDefined();
    }
    // HEAD is the turn's last step and is on the visible path.
    expect(agent.getVisibleReActSteps().at(-1)?.id).toBe(agent.getHead());
  });

  test("a new prompt clears the redo stack (a branch invalidates redo)", async () => {
    const agent = makeAgent(textModel("done"));
    await agent.sendMessage("first");

    const userStep = agent.getAllSteps().find(s => s.role === "user");
    expect(userStep).toBeDefined();
    agent.revertToTurnStart(userStep!.messageId);
    expect(agent.canRedo()).toBe(true);

    await agent.sendMessage("second"); // branch from the reverted HEAD
    expect(agent.canRedo()).toBe(false);
  });

  test("revert then redo round-trips HEAD across a real turn", async () => {
    const agent = makeAgent(textModel("done"));
    await agent.sendMessage("only turn");
    const leaf = agent.getHead();
    const userStep = agent.getAllSteps().find(s => s.role === "user")!;

    const reverted = agent.revertToTurnStart(userStep.messageId);
    expect(agent.getHead()).toBe(reverted.headId);
    expect(agent.getHead()).not.toBe(leaf);

    const redone = agent.redo();
    expect(redone.headId).toBe(leaf);
    expect(agent.getHead()).toBe(leaf);
    expect(agent.canRedo()).toBe(false);
  });

  test("a turn that errors still snapshots its final step (so it can be reverted/redone)", async () => {
    const agent = makeAgent(throwingModel("boom"));
    const result = await agent.sendMessage("hello");
    expect(result.error).toBeDefined();

    const headStep = agent.getStepsById().get(agent.getHead());
    expect(headStep?.afterWorkflowContent).toBeDefined();

    // The errored turn is still revertable + redoable.
    const userStep = agent.getAllSteps().find(s => s.role === "user")!;
    agent.revertToTurnStart(userStep.messageId);
    const redone = agent.redo();
    expect(redone.workflowContent).toBeDefined();
  });
});
