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

import { TestBed } from "@angular/core/testing";
import { AgentChatComponent } from "./agent-chat.component";
import { AgentService } from "../../../../service/agent/agent.service";
import { WorkflowActionService } from "../../../../service/workflow-graph/model/workflow-action.service";
import { NotificationService } from "../../../../../common/service/notification/notification.service";
import { WorkflowPersistService } from "../../../../../common/service/workflow-persist/workflow-persist.service";
import { AgentState } from "../../../../service/agent/agent-types";
import { commonTestProviders } from "../../../../../common/testing/test-utils";

describe("AgentChatComponent revert controls", () => {
  let component: AgentChatComponent;
  const revertTurn = vi.fn();
  const redo = vi.fn();

  beforeEach(async () => {
    revertTurn.mockReset();
    redo.mockReset();
    // compileComponents validates the standalone component's template (including the
    // new revert button + nz-popconfirm wiring). The component itself is then
    // instantiated directly so the revert-helper logic can be exercised without
    // standing up the full render-time DI graph (NzModalService, etc.).
    await TestBed.configureTestingModule({
      imports: [AgentChatComponent],
      providers: [
        { provide: AgentService, useValue: { revertTurn, redo } },
        { provide: WorkflowActionService, useValue: {} },
        { provide: NotificationService, useValue: { error: () => {}, success: () => {}, warning: () => {} } },
        { provide: WorkflowPersistService, useValue: {} },
        ...commonTestProviders,
      ],
    }).compileComponents();

    component = new AgentChatComponent(
      { revertTurn, redo } as unknown as AgentService,
      {} as WorkflowActionService,
      {} as NotificationService,
      { detectChanges: () => {} } as any,
      {} as WorkflowPersistService
    );
    component.agentInfo = { id: "agent-1", name: "Bob", modelType: "gpt-5-mini" } as any;
  });

  it("disallows reverting while generating or stopping, allows it otherwise", () => {
    component.agentState = AgentState.GENERATING;
    expect(component.canRevert()).toBe(false);

    component.agentState = AgentState.STOPPING;
    expect(component.canRevert()).toBe(false);

    component.agentState = AgentState.AVAILABLE;
    expect(component.canRevert()).toBe(true);
  });

  it("delegates revertTurn to the agent service with the agent id and turn", () => {
    component.revertTurn("msg-7");
    expect(revertTurn).toHaveBeenCalledWith("agent-1", "msg-7");
  });

  it("allows redo only when redo is available and not generating", () => {
    component.canRedoValue = true;
    component.agentState = AgentState.AVAILABLE;
    expect(component.canRedo()).toBe(true);

    component.agentState = AgentState.GENERATING;
    expect(component.canRedo()).toBe(false);

    component.agentState = AgentState.STOPPING;
    component.canRedoValue = true;
    expect(component.canRedo()).toBe(false);

    component.agentState = AgentState.AVAILABLE;
    component.canRedoValue = false;
    expect(component.canRedo()).toBe(false);
  });

  it("delegates redo to the agent service", () => {
    component.redo();
    expect(redo).toHaveBeenCalledWith("agent-1");
  });
});
