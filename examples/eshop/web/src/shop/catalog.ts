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

import { filter } from "@ahoo-wang/wow-client/dsl";
import { useSession } from "./session";
import { useLoad, type Loaded } from "./useLoad";

/** A product's stock in the inventory: what is free to reserve, and what is on hand. */
export interface Stock {
  readonly available: number;
  readonly onHand: number;
}

/** The ids, each once, as a load's key. */
function keyOf(ids: readonly string[]): string {
  return [...new Set(ids)].sort().join(",");
}

/**
 * The catalog's names of the products `ids` names, by id, in one query; a
 * product the catalog does not have, or one not loaded yet, has none, and is
 * shown by its id.
 */
export function useProductNames(ids: readonly string[]): ReadonlyMap<string, string> {
  const { clients } = useSession();
  const unique = [...new Set(ids)];
  const [names] = useLoad(
    async (signal) =>
      unique.length === 0
        ? new Map<string, string>()
        : new Map(
            (
              await clients.catalog.product.snapshots.list(
                { filter: filter.ids(unique), limit: unique.length },
                { signal },
              )
            ).map((record) => [record.aggregateId, record.state.name]),
          ),
    `names:${keyOf(unique)}`,
  );
  return names.status === "loaded" ? names.value : new Map();
}

/**
 * The inventory's stock of the products `ids` names, by id, in one query
 * rather than a state read per product: a product never stocked is not in
 * it. A new `revision` reads it again.
 */
export function useStocks(
  ids: readonly string[],
  revision = 0,
): Loaded<ReadonlyMap<string, Stock>> {
  const { clients } = useSession();
  const unique = [...new Set(ids)];
  const [stocks] = useLoad(
    async (signal) =>
      unique.length === 0
        ? new Map<string, Stock>()
        : new Map(
            (
              await clients.inventory.stock.snapshots.list(
                { filter: filter.ids(unique), limit: unique.length },
                { signal },
              )
            ).map((record) => [
              record.aggregateId,
              { available: record.state.available, onHand: record.state.onHand },
            ]),
          ),
    `stocks:${keyOf(unique)}:${revision}`,
  );
  return stocks;
}

/** A product's name, or its id when the catalog has none for it. */
export function nameOf(names: ReadonlyMap<string, string>, id: string): string {
  return names.get(id) ?? id;
}
