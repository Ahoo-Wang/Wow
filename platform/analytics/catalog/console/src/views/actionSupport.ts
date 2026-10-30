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
  ActionInput,
  Availability,
  RecordRow,
} from "@ahoo-wang/wow-view-engine";
import { problemText } from "./catalogCommands.ts";

/**
 * The roles of the caller in force where the catalog's roles decide who
 * sends what (`CatalogSettings.rolesDecide`), else `null`: CoSec's policies
 * decide, or the settings are not read, and the service alone says who may.
 */
export type CallerRoles = () => readonly string[] | null;

/** Nobody's roles: the service alone says who may. */
export const UNKNOWN_ROLES: CallerRoles = () => null;

/**
 * `true` where the caller holds one of `any`, or the console cannot tell;
 * else `reason`, so the action is offered disabled with why rather than
 * refused once confirmed (`wow.auth.forbidden`).
 */
export function holds(
  roles: CallerRoles,
  any: readonly string[],
  reason: string,
): Availability {
  const held = roles();
  if (held === null) return true;
  return any.some((role) => held.includes(role)) ? true : reason;
}

/** The first rule that refuses, else `true`. */
export function firstRefusal(
  ...rules: readonly (() => Availability)[]
): Availability {
  for (const rule of rules) {
    const able = rule();
    if (able !== true) return able;
  }
  return true;
}

/** The record's aggregate id: every view of the catalog keys its rows by it. */
export function idOf(row: RecordRow): string {
  return String(row.key);
}

/** The record's state, as its row fields read it. */
export function stateOf(row: RecordRow): Record<string, unknown> {
  return (row.data.state ?? {}) as Record<string, unknown>;
}

/** A typed text, or nothing when it was left blank. */
export function textOf(input: ActionInput, name: string): string | undefined {
  const value = input[name];
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed === "" ? undefined : trimmed;
}

/**
 * The service's refusal as the engine reports it: its problem's words and
 * code rather than the client's wording of the request.
 */
export async function said<T>(command: Promise<T>): Promise<T> {
  try {
    return await command;
  } catch (error) {
    throw new Error(problemText(error), { cause: error });
  }
}
