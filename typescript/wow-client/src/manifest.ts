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
import type { Ownership } from './wire.js';

/** A route of the Manifest: a method and a path, `{id}` the aggregate id. */
export interface RouteManifest {
  readonly method: string;
  readonly path: string;
}

/** Whether a command creates its aggregate. */
export type Creation = 'NONE' | 'REQUIRED' | 'ALLOWED';

/** A command of an aggregate. */
export interface CommandManifest {
  readonly name: string;
  readonly aliases?: readonly string[];
  /** `#/$defs/<key>`. */
  readonly schema: string;
  readonly creation?: Creation;
  readonly produces?: readonly string[];
  readonly routes?: readonly RouteManifest[];
  readonly mounted?: boolean;
  readonly transformsTo?: readonly string[];
  /**
   * A system command (`wow.*`): a lifecycle command, the snapshot rebuild or
   * a job command, its routes the framework's.
   */
  readonly system?: boolean;
}

/** An event of an aggregate and its revision. */
export interface EventManifest {
  readonly name: string;
  readonly aliases?: readonly string[];
  readonly revision?: number;
  readonly schema: string;
}

/** A query model of an aggregate: `snapshot` or `events`. */
export interface QueryManifest {
  readonly model: string;
  readonly routes?: readonly RouteManifest[];
}

/** An aggregate of a bounded context. */
export interface AggregateManifest {
  readonly name: string;
  readonly description?: string;
  readonly state: { readonly schema: string; readonly fingerprint?: string };
  readonly snapshot?: { readonly enabled?: boolean };
  readonly commands?: readonly CommandManifest[];
  readonly events?: readonly EventManifest[];
  readonly queries?: readonly QueryManifest[];
  readonly schedules?: readonly string[];
  readonly ownership?: Ownership;
}

/** A subscription of a bounded context. */
export interface SubscriptionManifest {
  /** `processor.function`, as a `handled` hop names it. */
  readonly name: string;
  readonly kind: 'PROJECTION' | 'REACTOR' | 'SAGA';
  readonly source: {
    readonly context?: string;
    readonly aggregates?: readonly string[];
    readonly events?: readonly string[];
  };
  readonly derives?: readonly string[];
  readonly rejected?: readonly string[];
  readonly committed?: readonly string[];
  readonly [property: string]: unknown;
}

/** An error code and the HTTP status it is answered with. */
export interface ErrorManifest {
  readonly code: string;
  readonly status: number;
  readonly description?: string;
}

/** A bounded context of the Manifest. */
export interface ContextManifest {
  readonly name: string;
  readonly description?: string;
  readonly aggregates?: readonly AggregateManifest[];
  /** The context's own system commands: its job commands, when it has jobs. */
  readonly commands?: readonly CommandManifest[];
  readonly subscriptions?: readonly SubscriptionManifest[];
  readonly published?: readonly {
    readonly aggregate: string;
    readonly topic: string;
    readonly includeState?: boolean;
  }[];
  readonly errors?: readonly ErrorManifest[];
}

/**
 * A service's Manifest: its contexts, aggregates,
 * commands, events, routes and error codes, and the JSON Schemas they refer
 * to under `$defs`. `wow-generator` generates typed clients from it.
 */
export interface Manifest {
  readonly format: number;
  /**
   * The markers a reader must understand to read the Manifest
   * ({@link MANIFEST_FEATURES}); a reader refuses one it does not know.
   */
  readonly requires?: readonly string[];
  readonly service?: { readonly name: string; readonly version?: string };
  readonly contexts: readonly ContextManifest[];
  readonly errors?: readonly ErrorManifest[];
  readonly $defs?: Readonly<Record<string, unknown>>;
}

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
