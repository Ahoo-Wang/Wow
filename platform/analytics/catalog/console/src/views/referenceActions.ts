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
import { toWowProblemError } from "@ahoo-wang/wow-client";
import {
  actions,
  type ActionForm,
  type ActionInput,
  type RecordActions,
  type RecordRow,
} from "@ahoo-wang/wow-view-engine";
import { said } from "./actionSupport.ts";
import type { Words } from "./words.ts";

/** One input of a command a row offers: a property of its flat body. */
export interface CommandInput {
  name: string;
  type: "string" | "number" | "integer" | "boolean";
  required: boolean;
  nullable?: boolean;
  options?: string[] | null;
  description?: string | null;
}

/** A command a row offers on the aggregate a reference field holds the id of. */
export interface ReferenceCommand {
  name: string;
  description?: string | null;
  inputs: CommandInput[];
}

/** A dataset's REFERENCE field, the aggregate it refers to, and the commands its rows offer on it. */
export interface ReferenceField {
  field: string;
  context: string;
  aggregate: string;
  commands: ReferenceCommand[];
}

/** What `GET /analytics-catalog/datasets/{dataset}/reference-actions` answers. */
export interface ReferenceActions {
  dataset: string;
  fields: ReferenceField[];
}

/** Sends a row's command through the catalog's relay, answering its result. */
export type ReferenceSend = (
  dataset: string,
  field: string,
  id: string,
  command: string,
  body: Record<string, unknown>,
) => Promise<unknown>;

/** The words of the reference actions, per language. */
export const REFERENCE_WORDS = {
  en: {
    confirm: "{action} on {count} rows?",
    body: "The catalog sends it to {aggregate}, as you: that service decides whether you may.",
    noReference: "This row refers to no {aggregate}.",
  },
  "zh-CN": {
    confirm: "对 {count} 行执行 {action}？",
    body: "目录以你的身份将其发送给 {aggregate}：由该服务决定你是否可以执行。",
    noReference: "此行没有引用 {aggregate}。",
  },
} satisfies Words;

const BASE = "/analytics-catalog/datasets";

/** The response `request` answers, or the problem it was refused with as a `WowProblemError`. */
async function problems(request: () => Promise<Response>): Promise<Response> {
  try {
    return await request();
  } catch (error) {
    throw (await toWowProblemError(error)) ?? error;
  }
}

/** The commands each REFERENCE field of the served dataset `dataset` offers on its rows. */
export async function fetchReferenceActions(
  fetcher: Fetcher,
  dataset: string,
): Promise<ReferenceActions> {
  const response = await problems(() =>
    fetcher.get(`${BASE}/${encodeURIComponent(dataset)}/reference-actions`, {
      headers: { Accept: "application/json" },
    }),
  );
  return (await response.json()) as ReferenceActions;
}

/** Sends a row's command through the catalog's relay, as the caller in force. */
export function referenceSend(fetcher: Fetcher): ReferenceSend {
  return async (dataset, field, id, command, body) => {
    const path = [dataset, "rows", field, id, command]
      .map(encodeURIComponent)
      .join("/");
    const response = await problems(() =>
      fetcher.post(`${BASE}/${path}`, {
        body: JSON.stringify(body),
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
        },
      }),
    );
    return response.json();
  };
}

/** The form of `inputs`: a choice for an enumeration, a number, a yes/no or a line of text. */
export function formOf(inputs: readonly CommandInput[]): ActionForm {
  return Object.fromEntries(
    inputs.map((input) => [
      input.name,
      {
        label: input.description ?? input.name,
        required: input.required,
        ...(input.options?.length
          ? { options: input.options.map((value) => ({ value, label: value })) }
          : input.type === "boolean"
            ? { input: "boolean" as const }
            : input.type === "string"
              ? {}
              : { input: "number" as const }),
      },
    ]),
  );
}

/**
 * The body a filled form gives: each value as its input's type takes it, one
 * left blank out (or `null`, where the input may be null and is required).
 */
export function bodyOf(
  inputs: readonly CommandInput[],
  input: ActionInput,
): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  for (const { name, type, required, nullable } of inputs) {
    const value = input[name];
    if (value === undefined || value === null || value === "") {
      if (required && nullable) body[name] = null;
      continue;
    }
    if (type === "boolean") body[name] = value === true || value === "true";
    else if (type === "number" || type === "integer")
      body[name] = Number(value);
    else body[name] = String(value);
  }
  return body;
}

/** The id the row's `field` holds, or nothing. */
function idIn(row: RecordRow, field: string): string | null {
  const value = (row.data as Record<string, unknown>)[field];
  return value === undefined || value === null || value === ""
    ? null
    : String(value);
}

/**
 * A served dataset's row actions (D238 ⑤, D257): each command a REFERENCE
 * field offers, on the aggregate whose id the row holds there, sent through
 * the catalog's relay (`send`), which sends it to the owning service as the
 * caller. That service authorizes it, and its refusal is said in its words.
 * Its words are `t`, the console's language's: the commands are the
 * services', not the definition's, so they are text, not keys.
 */
export function referenceActions(
  served: ReferenceActions,
  send: ReferenceSend,
  t: (typeof REFERENCE_WORDS)["en"] = REFERENCE_WORDS.en,
): RecordActions {
  return actions(
    served.fields.flatMap((field) =>
      field.commands.map((command) => {
        const aggregate = `${field.context}.${field.aggregate}`;
        const action = `${command.name} · ${field.field}`;
        return {
          id: `${field.field}.${command.name}`,
          label: action,
          available: (row: RecordRow) =>
            idIn(row, field.field) === null
              ? t.noReference.replace("{aggregate}", aggregate)
              : true,
          ...(command.inputs.length ? { form: formOf(command.inputs) } : {}),
          confirm: {
            title: t.confirm.replace("{action}", action),
            body: command.description
              ? `${command.description} ${t.body.replace("{aggregate}", aggregate)}`
              : t.body.replace("{aggregate}", aggregate),
          },
          run: (row: RecordRow, input: ActionInput) =>
            said(
              send(
                served.dataset,
                field.field,
                idIn(row, field.field)!,
                command.name,
                bodyOf(command.inputs, input),
              ),
            ),
        };
      }),
    ),
  );
}
