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

/**
 * The versions published, the one serving first, then the newest first:
 * the list a data owner rolls back from.
 */
export function versionsInOrder(state: DatasetState): DatasetState["versions"] {
  const newest = [...(state.versions ?? [])].reverse();
  return [
    ...newest.filter(({ artifact }) => artifact.version === state.current),
    ...newest.filter(({ artifact }) => artifact.version !== state.current),
  ];
}

/** A definition as the editor writes it: indented JSON. */
export function definitionText(
  definition: DatasetDefinition | null | undefined,
): string {
  return definition ? JSON.stringify(definition, null, 2) : "";
}

/**
 * Why the editor's text is not a definition: not JSON, where it stops
 * being JSON (`line`, `column`, from 1), or JSON that is not an object.
 * Said in the console's words, not the browser parser's.
 */
export type DefinitionError =
  { kind: "json"; line?: number; column?: number } | { kind: "object" };

/** Where a JSON text stops being JSON: the index a reader of it fails at. */
class NotJson extends Error {
  constructor(readonly at: number) {
    super(`Not JSON at ${at}`);
  }
}

/**
 * The index `text` first goes wrong at, read as JSON from the start, or
 * `null` when it is JSON. The browser's own parser knows, but says it in
 * its own words, in its own language, and some (V8) no longer say where.
 */
export function notJsonAt(text: string): number | null {
  let at = 0;
  const fail = (): never => {
    throw new NotJson(at);
  };
  const space = () => {
    while (at < text.length && " \t\n\r".includes(text[at])) at++;
  };
  const expect = (char: string) => {
    space();
    if (text[at] !== char) fail();
    at++;
  };
  const word = (literal: string) => {
    if (!text.startsWith(literal, at)) fail();
    at += literal.length;
  };
  const string = () => {
    at++;
    while (at < text.length) {
      const char = text[at];
      if (char === '"') {
        at++;
        return;
      }
      if (char < " ") fail();
      at += char === "\\" ? 2 : 1;
    }
    fail();
  };
  const number = () => {
    const found = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(
      text.slice(at),
    );
    if (!found) fail();
    at += found![0].length;
  };
  // Items between `open` and `close`, each read by `item`, comma-separated.
  const items = (close: string, item: () => void) => {
    at++;
    space();
    if (text[at] === close) {
      at++;
      return;
    }
    for (;;) {
      item();
      space();
      if (text[at] === close) {
        at++;
        return;
      }
      expect(",");
    }
  };
  const value = (): void => {
    space();
    const char = text[at];
    if (char === "{")
      return items("}", () => {
        space();
        if (text[at] !== '"') fail();
        string();
        expect(":");
        value();
      });
    if (char === "[") return items("]", value);
    if (char === '"') return string();
    if (char === "t") return word("true");
    if (char === "f") return word("false");
    if (char === "n") return word("null");
    if (char === "-" || (char >= "0" && char <= "9")) return number();
    fail();
  };
  try {
    value();
    space();
    if (at < text.length) fail();
    return null;
  } catch (error) {
    if (error instanceof NotJson) return error.at;
    throw error;
  }
}

/** The line and column of `index` in `text`, from 1. */
function lineAndColumn(
  text: string,
  index: number,
): { line: number; column: number } {
  const before = text.slice(0, index).split("\n");
  return { line: before.length, column: before[before.length - 1].length + 1 };
}

/**
 * The definition the editor's text holds, or why it is not one. Only that it
 * is a JSON object is checked here: whether it binds is the catalog's to say.
 */
export function parseDefinition(
  text: string,
): { definition: DatasetDefinition } | { error: DefinitionError } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    const at = notJsonAt(text);
    return {
      error: { kind: "json", ...(at === null ? {} : lineAndColumn(text, at)) },
    };
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed))
    return { error: { kind: "object" } };
  return { definition: parsed as DatasetDefinition };
}

/** A new dataset's definition to start from: one source, its every column to write. */
export const DEFINITION_TEMPLATE: DatasetDefinition = {
  subject: "o",
  sources: [{ alias: "o", sourceId: "" }],
  columns: [{ name: "id", from: "o.id" }],
  identity: "id",
};
