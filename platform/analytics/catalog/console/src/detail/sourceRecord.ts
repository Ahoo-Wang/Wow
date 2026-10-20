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

import type {
  FieldSensitivity,
  SourceReference,
  SourceState,
} from "@/generated/index.ts";

/** A source's state as the detail reads it: the whole record's `state`. */
export function sourceOf(data: Record<string, unknown>): SourceState {
  return (data.state ?? {}) as SourceState;
}

/** A sensitivity's key: `event/path` for a Wow table's value, the column otherwise. */
export function keyOf(field: FieldSensitivity): string {
  return field.event ? `${field.event}/${field.path}` : field.path;
}

/** References as the form writes them: one `column=context.aggregate` a line. */
export function referencesText(references: readonly SourceReference[]): string {
  return references
    .map(({ path, context, aggregate }) => `${path}=${context}.${aggregate}`)
    .join("\n");
}

/**
 * The references a form's text declares, or the first line (1-based) that
 * does not read `column=context.aggregate`. Blank lines are left out.
 */
export function parseReferences(
  text: string,
): { references: SourceReference[] } | { line: number } {
  const references: SourceReference[] = [];
  const lines = text.split("\n");
  for (const [index, raw] of lines.entries()) {
    const line = raw.trim();
    if (line === "") continue;
    const match = /^([^=\s]+)\s*=\s*([^.\s]+)\.([^.\s]+)$/.exec(line);
    if (!match) return { line: index + 1 };
    references.push({ path: match[1], context: match[2], aggregate: match[3] });
  }
  return { references };
}

/** One value the sensitivity form decides, as it opens. */
export interface SensitivityRow {
  key: string;
  field: FieldSensitivity;
  /** The column's type, for an analyst's table. */
  type?: string;
}

/**
 * The values a source's review decides: each column of an analyst's table,
 * with what it was given; each value of a Wow table its row protects.
 */
export function sensitivityRows(state: SourceState): SensitivityRow[] {
  const given = new Map(
    (state.sensitivity ?? []).map((field) => [keyOf(field), field]),
  );
  if (state.kind === "WOW")
    return [...given].map(([key, field]) => ({ key, field }));
  return (state.columns ?? []).map((column) => ({
    key: column.name,
    type: column.type,
    field: given.get(column.name) ?? { path: column.name, level: null },
  }));
}

function sameProtection(a: FieldSensitivity, b: FieldSensitivity): boolean {
  return (
    (a.level ?? null) === (b.level ?? null) &&
    (a.keepPrefix ?? 0) === (b.keepPrefix ?? 0) &&
    (a.keepSuffix ?? 0) === (b.keepSuffix ?? 0)
  );
}

/** The values whose protection `edited` changes: what a review sends. */
export function changedSensitivity(
  rows: readonly SensitivityRow[],
  edited: Readonly<Record<string, FieldSensitivity>>,
): FieldSensitivity[] {
  return rows
    .map((row) => edited[row.key] ?? row.field)
    .filter((field, index) => !sameProtection(field, rows[index].field));
}
