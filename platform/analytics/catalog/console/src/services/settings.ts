/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type { Fetcher } from "@ahoo-wang/fetcher";
import {
  AnalyticsCatalogRoutes,
  type CatalogSettings,
} from "@/generated/index.ts";

export type { CatalogSettings };

/**
 * The catalog's settings as the console has them: not read yet, read, or
 * not readable — an older catalog without the route, or a failure — in which
 * case the console offers everything and the service has the last word.
 */
export type SettingsState =
  | { status: "reading" }
  | { status: "read"; settings: CatalogSettings }
  | { status: "failed" };

/**
 * What the catalog this console works with is set to (`GET
 * /analytics-catalog/settings`): its connection, the approvals it takes, and
 * whether its roles decide who sends what. Read once a caller is in force —
 * the catalog answers nobody — and kept: they are the node's, the same for
 * every caller.
 */
export class SettingsStore {
  private state: SettingsState = { status: "reading" };
  private reading = false;
  private readonly listeners = new Set<() => void>();

  constructor(private readonly read: () => Promise<CatalogSettings>) {}

  get = (): SettingsState => this.state;

  /** The settings once read, else `null`. */
  settings = (): CatalogSettings | null =>
    this.state.status === "read" ? this.state.settings : null;

  /** Reads the settings, unless they are read or being read; again after a failure. */
  load(): void {
    if (this.reading || this.state.status === "read") return;
    this.reading = true;
    if (this.state.status === "failed") this.settle({ status: "reading" });
    this.read().then(
      (settings) => this.settle({ status: "read", settings }),
      (error: unknown) => {
        console.warn(
          "[settings] the catalog's settings could not be read",
          error,
        );
        this.settle({ status: "failed" });
      },
    );
  }

  private settle(state: SettingsState): void {
    this.reading = state.status === "reading";
    this.state = state;
    for (const listener of this.listeners) listener();
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };
}

/** The settings of the catalog `fetcher` reaches, through its generated client. */
export function settingsStore(fetcher: Fetcher): SettingsStore {
  const routes = new AnalyticsCatalogRoutes({ fetcher });
  return new SettingsStore(() => routes.settings());
}
