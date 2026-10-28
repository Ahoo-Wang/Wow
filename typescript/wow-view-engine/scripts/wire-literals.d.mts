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

import type ts from 'typescript';

/** The marker a test about the spelling itself carries; see the `.mjs`. */
export const WIRE_SPELLING_MARKER: string;

/** The vocabularies of Wow's query protocol: each enum's or table's values. */
export function wireVocabularies(dsl?: string): Set<string>[];

/** The program of a tsconfig, over [roots] when given. */
export function wireProgram(
  tsconfig: string,
  roots?: readonly string[],
): ts.Program;

/** What a scan reads: one tsconfig, and scopes of files by name. */
export interface WireScanRequest {
  tsconfig: string;
  scopes: Record<string, { files: readonly string[]; honourMarker?: boolean }>;
}

/** One wire literal a scan found. */
export interface WireLiteral {
  path: string;
  value: string;
  line: number;
}

/** The wire literals of each scope, over one program of all their files. */
export function scanWireLiterals(
  request: WireScanRequest,
): Record<string, WireLiteral[]>;

/** [scanWireLiterals] in a Node process of its own, out of a test run's coverage. */
export function scanWireLiteralsApart(
  request: WireScanRequest,
): Record<string, WireLiteral[]>;

/** The literals of a file that are a Wow wire value by the type of their slot. */
export function wireLiterals(
  program: ts.Program,
  path: string,
  vocabularies: readonly ReadonlySet<string>[],
  options?: { honourMarker?: boolean },
): { value: string; line: number }[];
