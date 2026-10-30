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

/* eslint-disable react-refresh/only-export-components -- The provider and its hooks live together. */

import {
  createContext,
  useContext,
  useSyncExternalStore,
  type PropsWithChildren,
} from "react";
import type { CatalogCaller, SignedOut } from "@/services/caller.ts";
import type { CatalogServices } from "@/services/catalog.ts";
import type { CatalogSettings, SettingsState } from "@/services/settings.ts";

const ServicesContext = createContext<CatalogServices | null>(null);

export function ServicesProvider({
  services,
  children,
}: PropsWithChildren<{ services: CatalogServices }>) {
  return (
    <ServicesContext.Provider value={services}>
      {children}
    </ServicesContext.Provider>
  );
}

export function useServices(): CatalogServices {
  const services = useContext(ServicesContext);
  if (!services)
    throw new Error("No catalog services: render under ServicesProvider.");
  return services;
}

/** Whether this node serves datasets (`null` until asked), redrawn when it changes. */
export function useServing(): boolean | null {
  const { serving } = useServices();
  return useSyncExternalStore(serving.subscribe, serving.get);
}

/** Where reading the catalog's settings stands, redrawn when it changes. */
export function useSettingsState(): SettingsState {
  const { settings } = useServices();
  return useSyncExternalStore(settings.subscribe, settings.get);
}

/** The catalog's settings once read, else `null`. */
export function useSettings(): CatalogSettings | null {
  const state = useSettingsState();
  return state.status === "read" ? state.settings : null;
}

/** Why the console was last signed out, redrawn when it changes. */
export function useSignedOut(): SignedOut | null {
  const { callers } = useServices();
  return useSyncExternalStore(callers.subscribe, callers.signedOut);
}

/** The caller in force, redrawn when it changes. */
export function useCaller(): CatalogCaller | null {
  const { callers } = useServices();
  return useSyncExternalStore(callers.subscribe, callers.get);
}
