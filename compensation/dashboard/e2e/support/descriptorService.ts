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

import { readFileSync } from "node:fs";
import type { Page } from "@playwright/test";

/**
 * A query capability descriptor as the service answers
 * `GET execution_failed/{snapshot,event}/schema`. Loosely typed: the e2e
 * suite reads and edits it as JSON, the engine does the reading proper.
 */
export type Descriptor = Record<string, unknown> & {
  version: string;
  record: Record<string, unknown>;
};

function fixture(name: string): Descriptor {
  return JSON.parse(
    readFileSync(
      new URL(`./descriptors/${name}.json`, import.meta.url),
      "utf8",
    ),
  ) as Descriptor;
}

/**
 * What a compensation server on MongoDB answered (`mongo-snapshot.json`,
 * `mongo-event.json`, read from a local server started as RELEASING.md §C′
 * step 3 does): no full-text search, since the collection has no text index
 * (G15).
 */
export function mongoDescriptors(): {
  snapshot: Descriptor;
  event: Descriptor;
} {
  return { snapshot: fixture("mongo-snapshot"), event: fixture("mongo-event") };
}

/**
 * The same snapshot model on a storage that searches the errors by phrase or
 * by words, as an Elasticsearch snapshot store answers it.
 */
export function searchingSnapshotDescriptor(): Descriptor {
  const { snapshot } = mongoDescriptors();
  return {
    ...snapshot,
    version: "sha256:searching",
    record: {
      ...snapshot.record,
      search: {
        modes: ["PHRASE", "TERMS"],
        fields: ["state.error.errorMsg", "state.error.stackTrace"],
      },
    },
  };
}

/**
 * No descriptor, as a server before Wow 9.2 answers: 404 on both schema
 * routes, and the console runs on its definitions as declared. The query
 * stubs install it, so a suite that is not about descriptors reads none.
 */
export async function stubNoDescriptors(page: Page): Promise<void> {
  await page.route("**/execution_failed/*/schema", (route) =>
    route.fulfill({ status: 404, body: "" }),
  );
}

/**
 * Answers the two schema routes with the given descriptors, and a 304 when
 * the page sends back the version it holds. Returns how often each was read.
 */
export async function stubDescriptors(
  page: Page,
  descriptors: { snapshot: Descriptor; event: Descriptor },
): Promise<{ snapshot: number; event: number }> {
  const reads = { snapshot: 0, event: 0 };
  await page.route(
    /\/execution_failed\/(snapshot|event)\/schema$/,
    async (route) => {
      const model = route.request().url().includes("/snapshot/")
        ? "snapshot"
        : "event";
      reads[model] += 1;
      const descriptor = descriptors[model];
      const etag = `"${descriptor.version}"`;
      if (route.request().headers()["if-none-match"] === etag) {
        await route.fulfill({ status: 304, headers: { ETag: etag } });
        return;
      }
      await route.fulfill({
        contentType: "application/json",
        headers: { ETag: etag },
        body: JSON.stringify(descriptor),
      });
    },
  );
  return reads;
}
