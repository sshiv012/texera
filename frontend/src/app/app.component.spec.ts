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
import { AppComponent } from "./app.component";
import { GuiConfigService } from "./common/service/gui-config.service";

describe("AppComponent", () => {
  // The component only reads `config.env` in its constructor, so a stub with a
  // getter is enough; the getter either returns normally or throws to simulate
  // the APP_INITIALIZER config load failing.
  function createComponent(guiConfigStub: Pick<GuiConfigService, "env"> | { env: never }): AppComponent {
    TestBed.configureTestingModule({
      declarations: [AppComponent],
      providers: [{ provide: GuiConfigService, useValue: guiConfigStub }],
    });
    // The tests below never call detectChanges(), so the template (and its
    // <router-outlet>) is never rendered — no router setup is needed.
    return TestBed.createComponent(AppComponent).componentInstance;
  }

  const loadedConfigStub = {
    get env() {
      return {} as GuiConfigService["env"];
    },
  };

  it("should be created", () => {
    const component = createComponent(loadedConfigStub);
    expect(component).toBeTruthy();
  });

  it("should set configLoaded to true when the configuration is available", () => {
    const component = createComponent(loadedConfigStub);
    expect(component.configLoaded).toBe(true);
  });

  it("should set configLoaded to false when accessing the configuration throws", () => {
    const component = createComponent({
      get env(): never {
        throw new Error("configuration not loaded");
      },
    });
    expect(component.configLoaded).toBe(false);
  });

  it("should reload the page on retry()", () => {
    // jsdom does not implement navigation, so replace window.location with a
    // minimal stub for the duration of this test.
    const reload = vi.fn();
    const originalLocation = window.location;
    Object.defineProperty(window, "location", { value: { reload }, writable: true });
    try {
      const component = createComponent(loadedConfigStub);
      component.retry();
      expect(reload).toHaveBeenCalledTimes(1);
    } finally {
      Object.defineProperty(window, "location", { value: originalLocation, writable: true });
    }
  });
});
