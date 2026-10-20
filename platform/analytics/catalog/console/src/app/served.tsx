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

/* eslint-disable react-refresh/only-export-components -- The provider and its hook live together. */

import { createContext, useContext, type PropsWithChildren } from "react";
import type { DatasetResource } from "@ahoo-wang/wow-view-engine";
import type { ReferenceActions } from "@/views/referenceActions.ts";

/** The datasets the node serves, as last read. */
export interface Served {
  datasets: readonly DatasetResource[];
  /** The commands each one's rows offer, by definition id; none for one whose could not be read. */
  references: Readonly<Record<string, ReferenceActions>>;
  /** Why the directory could not be read last, if it could not. */
  error: unknown;
  loading: boolean;
  /** Reads the directory again. */
  refresh(): void;
}

const ServedContext = createContext<Served>({
  datasets: [],
  references: {},
  error: null,
  loading: false,
  refresh: () => {},
});

export function ServedProvider({
  served,
  children,
}: PropsWithChildren<{ served: Served }>) {
  return (
    <ServedContext.Provider value={served}>{children}</ServedContext.Provider>
  );
}

/** The datasets served, redrawn when they are read again. */
export function useServed(): Served {
  return useContext(ServedContext);
}
