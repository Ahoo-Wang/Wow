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
import type { DatasetsState } from "@ahoo-wang/wow-view-engine/react";
import type { ReferenceActions } from "@/views/referenceActions.ts";

/** The datasets the node serves, as `useDatasets` read them, with the actions their rows offer. */
export interface Served extends DatasetsState {
  /** The commands each one's rows offer, by definition id; none for one whose could not be read. */
  references: Readonly<Record<string, ReferenceActions>>;
}

const ServedContext = createContext<Served>({
  datasets: [],
  duplicates: [],
  references: {},
  status: "idle",
  error: null,
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
