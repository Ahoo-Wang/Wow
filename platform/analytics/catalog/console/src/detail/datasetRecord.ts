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

import type { DatasetDefinition, DatasetState } from "@/generated/index.ts";

/** A dataset's state as the detail reads it: the whole record's `state`. */
export function datasetOf(data: Record<string, unknown>): DatasetState {
  return (data.state ?? {}) as DatasetState;
}

/** A definition as the editor writes it: indented JSON. */
export function definitionText(
  definition: DatasetDefinition | null | undefined,
): string {
  return definition ? JSON.stringify(definition, null, 2) : "";
}

/**
 * The definition the editor's text holds, or why it is not one. Only that it
 * is a JSON object is checked here: whether it binds is the catalog's to say.
 */
export function parseDefinition(
  text: string,
): { definition: DatasetDefinition } | { error: string } {
  try {
    const parsed: unknown = JSON.parse(text);
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed))
      return { error: "a definition is an object" };
    return { definition: parsed as DatasetDefinition };
  } catch (error) {
    return { error: error instanceof Error ? error.message : String(error) };
  }
}

/** A new dataset's definition to start from: one source, its every column to write. */
export const DEFINITION_TEMPLATE: DatasetDefinition = {
  subject: "o",
  sources: [{ alias: "o", sourceId: "" }],
  columns: [{ name: "id", from: "o.id" }],
  identity: "id",
};
