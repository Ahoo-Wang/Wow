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

import {
  json,
  type RequestOptions,
  send,
  type WowClientOptions,
} from './http.js';
import type { CommandManifest, Manifest } from './manifestTypes.js';

export type * from './manifestTypes.js';

/** Whether a command creates its aggregate. */
export type Creation = CommandManifest['creation'];

/** The format of the Manifest this client reads. */
export const MANIFEST_FORMAT = 1;

/**
 * The markers of a Manifest's `requires` this client understands, as the
 * Kotlin reader's `ManifestFeatures`: none yet.
 */
export const MANIFEST_FEATURES: ReadonlySet<string> = new Set<string>();

/**
 * Checks that this client can read `manifest`: its format, and every marker it
 * requires.
 *
 * @param manifest - The Manifest
 * @throws TypeError naming what this client does not understand
 */
export function checkManifest(manifest: Manifest): Manifest {
  if (manifest.format !== MANIFEST_FORMAT) {
    throw new TypeError(
      `The Manifest is of format ${manifest.format}; this client reads format ${MANIFEST_FORMAT}.`,
    );
  }
  const unknown = (manifest.requires ?? []).filter(
    marker => !MANIFEST_FEATURES.has(marker),
  );
  if (unknown.length > 0) {
    throw new TypeError(
      `The Manifest requires ${unknown.join(', ')}, which this client does not understand: use a newer @ahoo-wang/wow-client.`,
    );
  }
  return manifest;
}

/**
 * The service's Manifest, `GET /wow/manifest`, authorized as any API.
 *
 * @param client - How the service is reached
 * @param options - Headers and cancellation
 */
export async function fetchManifest(
  client: WowClientOptions = {},
  options?: RequestOptions,
): Promise<Manifest> {
  return checkManifest(
    await json<Manifest>(
      await send(client, { method: 'GET', path: '/wow/manifest', options }),
    ),
  );
}
