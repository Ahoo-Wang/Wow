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
import type { OpsCaller, SignedOut } from "@/services/caller.ts";
import type { OpsServices } from "@/services/ops.ts";

const ServicesContext = createContext<OpsServices | null>(null);

export function ServicesProvider({
  services,
  children,
}: PropsWithChildren<{ services: OpsServices }>) {
  return (
    <ServicesContext.Provider value={services}>
      {children}
    </ServicesContext.Provider>
  );
}

export function useServices(): OpsServices {
  const services = useContext(ServicesContext);
  if (!services)
    throw new Error("No ops services: render under ServicesProvider.");
  return services;
}

/** Why the console was last signed out, redrawn when it changes. */
export function useSignedOut(): SignedOut | null {
  const { callers } = useServices();
  return useSyncExternalStore(callers.subscribe, callers.signedOut);
}

/** The caller in force, redrawn when it changes. */
export function useCaller(): OpsCaller | null {
  const { callers } = useServices();
  return useSyncExternalStore(callers.subscribe, callers.get);
}
