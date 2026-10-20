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
import { WowProblemError, type CommandOptions } from "@ahoo-wang/wow-client";
import {
  analyticsCatalogWait,
  DatasetCommandClient,
  SourceCommandClient,
  type DatasetDefinition,
  type FieldSensitivity,
  type ReviseSource,
} from "@/generated/index.ts";

/**
 * What a data owner sends the catalog (D251 ②): an analyst's sources and
 * datasets, and an approver's decisions. Each resolves once the record's
 * snapshot holds it, so the view read right after shows it.
 */
export interface CatalogCommands {
  draftSource(database: string, table: string, id: string): Promise<unknown>;
  reviseSource(id: string, revision: ReviseSource): Promise<unknown>;
  reviewSensitivity(id: string, fields: FieldSensitivity[]): Promise<unknown>;
  markReviewed(id: string): Promise<unknown>;
  approveSource(id: string): Promise<unknown>;
  approveSensitivityChange(id: string): Promise<unknown>;
  draftDataset(
    context: string,
    name: string,
    definition: DatasetDefinition,
    title?: string,
  ): Promise<unknown>;
  reviseDataset(
    id: string,
    definition: DatasetDefinition,
    title?: string,
  ): Promise<unknown>;
  publish(id: string): Promise<unknown>;
  approvePublication(id: string): Promise<unknown>;
  refusePublication(id: string, reason: string): Promise<unknown>;
  rollback(id: string, version: number): Promise<unknown>;
  retire(id: string): Promise<unknown>;
}

/** Each command waits for the snapshot of its record (`Wow-Wait`). */
const OPTIONS: CommandOptions = {
  wait: { plan: [analyticsCatalogWait.snapshot()] },
};

/** A blank title is no title. */
function titleOf(title: string | undefined): { title?: string } {
  const trimmed = title?.trim();
  return trimmed ? { title: trimmed } : {};
}

/** The commands over the catalog's generated command clients. */
export function catalogCommands(fetcher: Fetcher): CatalogCommands {
  const sources = new SourceCommandClient({ fetcher });
  const datasets = new DatasetCommandClient({ fetcher });
  return {
    draftSource: (database, table, id) =>
      sources.draftSource({ database, table }, { ...OPTIONS, id }),
    reviseSource: (id, revision) => sources.reviseSource(id, revision, OPTIONS),
    reviewSensitivity: (id, fields) =>
      sources.reviewSourceSensitivity(id, { fields }, OPTIONS),
    markReviewed: (id) => sources.markSourceReviewed(id, {}, OPTIONS),
    approveSource: (id) => sources.approveSource(id, {}, OPTIONS),
    approveSensitivityChange: (id) =>
      sources.approveSensitivityChange(id, {}, OPTIONS),
    draftDataset: (context, name, definition, title) =>
      datasets.draftDataset(
        { context, name, definition, ...titleOf(title) },
        { ...OPTIONS, id: `${context}.${name}` },
      ),
    reviseDataset: (id, definition, title) =>
      datasets.reviseDataset(id, { definition, ...titleOf(title) }, OPTIONS),
    publish: (id) => datasets.publishDataset(id, {}, OPTIONS),
    approvePublication: (id) =>
      datasets.approveDatasetPublication(id, {}, OPTIONS),
    refusePublication: (id, reason) =>
      datasets.refuseDatasetPublication(id, { reason }, OPTIONS),
    rollback: (id, version) =>
      datasets.rollbackDataset(id, { version }, OPTIONS),
    retire: (id) => datasets.retireDataset(id, {}, OPTIONS),
  };
}

/**
 * What a data owner reads of a failure: the problem's own words and its code
 * (RFC 9457, `application/problem+json`), such as
 * `… nothing is not a column … (analytics.catalog.definition-invalid)`;
 * else the error's message.
 */
export function problemText(error: unknown): string {
  if (error instanceof WowProblemError) {
    const { problem } = error;
    const words = problem.detail ?? problem.title ?? `HTTP ${problem.status}`;
    const violations = error.violations
      .map((violation) => violation.message)
      .filter(Boolean);
    const said = [words, ...violations].join(" ");
    return error.code ? `${said} (${error.code})` : said;
  }
  return error instanceof Error ? error.message : String(error);
}
