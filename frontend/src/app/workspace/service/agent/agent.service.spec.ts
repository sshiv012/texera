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
import { HttpClientTestingModule, HttpTestingController } from "@angular/common/http/testing";
import { AgentService, AgentInfo, OperatorResultSummary } from "./agent.service";
import { NotificationService } from "../../../common/service/notification/notification.service";
import { WorkflowPersistService } from "../../../common/service/workflow-persist/workflow-persist.service";
import { ComputingUnitStatusService } from "../../../common/service/computing-unit/computing-unit-status/computing-unit-status.service";
import { DashboardWorkflowComputingUnit } from "../../../common/type/workflow-computing-unit";
import { commonTestProviders } from "../../../common/testing/test-utils";

describe("AgentService", () => {
  let service: AgentService;
  let httpMock: HttpTestingController;
  let selectedUnit: DashboardWorkflowComputingUnit | null;

  const apiAgent = {
    id: "agent-1",
    name: "Bob",
    modelType: "gpt-5-mini",
    state: "AVAILABLE",
    createdAt: "2026-06-11T00:00:00.000Z",
  };

  beforeEach(() => {
    selectedUnit = null;
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        AgentService,
        { provide: NotificationService, useValue: { error: () => {}, success: () => {}, info: () => {} } },
        { provide: WorkflowPersistService, useValue: {} },
        {
          provide: ComputingUnitStatusService,
          useValue: { getSelectedComputingUnitValue: () => selectedUnit },
        },
        ...commonTestProviders,
      ],
    });
    service = TestBed.inject(AgentService);
    httpMock = TestBed.inject(HttpTestingController);
    // The constructor syncs the local agent cache with the backend.
    httpMock.expectOne(req => req.method === "GET" && req.url === "/api/agents").flush({ agents: [] });
  });

  afterEach(() => {
    httpMock.verify();
  });

  describe("createAgent", () => {
    it("creates an agent without putting the user token in the payload", () => {
      let created: AgentInfo | undefined;
      service.createAgent("gpt-5-mini", "Bob").subscribe(agent => (created = agent));

      const req = httpMock.expectOne(r => r.method === "POST" && r.url === "/api/agents");
      expect(req.request.body.userToken).toBeUndefined();
      expect(req.request.body.modelType).toEqual("gpt-5-mini");
      expect(req.request.body.name).toEqual("Bob");
      expect(req.request.body.workflowId).toBeUndefined();
      expect(req.request.body.computingUnitId).toBeUndefined();
      req.flush(apiAgent);

      expect(created?.id).toEqual("agent-1");
      expect(created?.modelType).toEqual("gpt-5-mini");
    });

    it("includes workflowId and the selected computing unit id in the payload", () => {
      selectedUnit = { computingUnit: { cuid: 7 } } as unknown as DashboardWorkflowComputingUnit;
      service.createAgent("gpt-5-mini", "Bob", 42).subscribe();

      const req = httpMock.expectOne(r => r.method === "POST" && r.url === "/api/agents");
      expect(req.request.body.workflowId).toEqual(42);
      expect(req.request.body.computingUnitId).toEqual(7);
      expect(req.request.body.userToken).toBeUndefined();
      req.flush(apiAgent);
    });
  });

  describe("fetchOperatorResults", () => {
    it("pulls operator results over REST and pushes them to operatorResultSummaries$", () => {
      let latest: Map<string, OperatorResultSummary> | undefined;
      service.operatorResultSummaries$.subscribe(m => (latest = m));

      service.fetchOperatorResults("agent-1");

      const req = httpMock.expectOne(r => r.method === "GET" && r.url === "/api/agents/agent-1/operator-results");
      req.flush({
        results: {
          "op-1": { sampleRecords: [{ a: 1 }], resultStatistics: { a: "{}" } },
        },
      });

      expect(latest?.has("op-1")).toBe(true);
      expect(latest?.get("op-1")?.sampleRecords).toEqual([{ a: 1 }]);
    });

    it("falls back to empty results when the request fails", () => {
      let latest: Map<string, OperatorResultSummary> | undefined;
      service.operatorResultSummaries$.subscribe(m => (latest = m));

      service.fetchOperatorResults("agent-1");

      httpMock
        .expectOne(r => r.method === "GET" && r.url === "/api/agents/agent-1/operator-results")
        .flush("boom", { status: 500, statusText: "Server Error" });

      expect(latest?.size).toBe(0);
    });
  });

  describe("stopGeneration", () => {
    it("sends a stop command over the websocket when one is open", () => {
      const send = vi.fn();
      (service as any).agentStateTracking.set("agent-1", {
        websocket: { readyState: WebSocket.OPEN, send },
      });

      service.stopGeneration("agent-1");

      expect(send).toHaveBeenCalledWith(JSON.stringify({ type: "WsClientStopCommand" }));
    });

    it("falls back to the REST stop endpoint when no websocket is open", () => {
      (service as any).agentStateTracking.set("agent-1", {
        websocket: { readyState: WebSocket.CLOSED, send: vi.fn() },
      });

      service.stopGeneration("agent-1");

      httpMock
        .expectOne(r => r.method === "POST" && r.url === "/api/agents/agent-1/stop")
        .flush({ status: "stopping" });
    });
  });

  describe("revertTurn", () => {
    it("sends a revert command for the given turn over the websocket", () => {
      const send = vi.fn();
      (service as any).agentStateTracking.set("agent-1", {
        websocket: { readyState: WebSocket.OPEN, send },
      });

      service.revertTurn("agent-1", "msg-7");

      expect(send).toHaveBeenCalledWith(JSON.stringify({ type: "WsClientRevertCommand", messageId: "msg-7" }));
    });

    it("does not send when no websocket is open", () => {
      const send = vi.fn();
      (service as any).agentStateTracking.set("agent-1", {
        websocket: { readyState: WebSocket.CLOSED, send },
      });

      service.revertTurn("agent-1", "msg-7");

      expect(send).not.toHaveBeenCalled();
    });
  });

  describe("redo", () => {
    it("sends a redo command over the websocket", () => {
      const send = vi.fn();
      (service as any).agentStateTracking.set("agent-1", {
        websocket: { readyState: WebSocket.OPEN, send },
      });

      service.redo("agent-1");

      expect(send).toHaveBeenCalledWith(JSON.stringify({ type: "WsClientRedoCommand" }));
    });
  });

  describe("sendMessage clears redo", () => {
    it("resets canRedo when a new prompt is sent (mirrors the backend clearing its stack)", () => {
      const send = vi.fn();
      const tracking = (service as any).getOrCreateStateTracking("agent-1");
      tracking.websocket = { readyState: WebSocket.OPEN, send };
      tracking.canRedoSubject.next(true);
      (service as any).agents.set("agent-1", { id: "agent-1", name: "Bob" });

      service.sendMessage("agent-1", "hello");

      expect(send).toHaveBeenCalled();
      expect(tracking.canRedoSubject.getValue()).toBe(false);
    });
  });

  describe("WsServerHeadChangeEvent handling", () => {
    it("advances the head pointer and reloads the workflow on a revert", () => {
      const tracking = (service as any).getOrCreateStateTracking("agent-1");
      const content = { operators: [], operatorPositions: {}, links: [], commentBoxes: [], settings: {} };

      (service as any).handleWebSocketMessage("agent-1", tracking, {
        type: "WsServerHeadChangeEvent",
        headId: "step-initial",
        workflowContent: content,
      });

      expect(tracking.headIdSubject.getValue()).toEqual("step-initial");
      expect(tracking.workflowSubject.getValue()?.content).toEqual(content);
      expect(tracking.wsWorkflowActive).toBe(true);
    });

    it("applies workflowContent and canRedo from a snapshot (reconnect after revert)", () => {
      const tracking = (service as any).getOrCreateStateTracking("agent-1");
      const content = { operators: [], operatorPositions: {}, links: [], commentBoxes: [], settings: {} };

      (service as any).handleWebSocketMessage("agent-1", tracking, {
        type: "WsServerSnapshotEvent",
        state: "AVAILABLE",
        steps: [],
        headId: "step-initial",
        workflowContent: content,
        canRedo: true,
      });

      expect(tracking.headIdSubject.getValue()).toBe("step-initial");
      expect(tracking.workflowSubject.getValue()?.content).toEqual(content);
      expect(tracking.canRedoSubject.getValue()).toBe(true);
    });

    it("updates canRedo from the head-change event", () => {
      const tracking = (service as any).getOrCreateStateTracking("agent-1");

      (service as any).handleWebSocketMessage("agent-1", tracking, {
        type: "WsServerHeadChangeEvent",
        headId: "step-initial",
        workflowContent: { operators: [], operatorPositions: {}, links: [], commentBoxes: [], settings: {} },
        canRedo: true,
      });

      expect(tracking.canRedoSubject.getValue()).toBe(true);
    });
  });
});
