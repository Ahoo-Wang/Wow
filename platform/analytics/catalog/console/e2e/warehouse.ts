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

import { expect, type APIRequestContext } from "@playwright/test";

/** A column of a table the suite puts in the warehouse. */
export interface Column {
  name: string;
  type: string;
}

/**
 * Puts the table `name` with `columns` in the server's warehouse
 * (`WarehouseSimulatorRoutes`), and answers its source's id.
 */
export async function putTable(
  request: APIRequestContext,
  name: string,
  columns: Column[],
): Promise<string> {
  const response = await request.post("/testing/warehouse/tables", {
    data: { name, columns },
  });
  expect(response.ok()).toBe(true);
  return ((await response.json()) as { id: string }).id;
}

/** The datasets' views the server created, as `<database>.<view>`. */
export async function views(request: APIRequestContext): Promise<string[]> {
  const response = await request.get("/testing/warehouse/views");
  expect(response.ok()).toBe(true);
  return (await response.json()) as string[];
}

/** The database the warehouse's tables are in. */
export const DATABASE = "analytics";

/** The catalog's connection in the warehouse simulator. */
export const CONNECTION = "warehouse";
