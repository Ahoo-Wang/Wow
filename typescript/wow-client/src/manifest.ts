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
import { WowProblemError } from './problem.js';
import type {
  AggregateManifest,
  CommandManifest,
  ContextManifest,
  Manifest,
  ServiceRouteManifest,
} from './manifestTypes.js';

export type * from './manifestTypes.js';

/** Whether a command creates its aggregate. */
export type Creation = CommandManifest['creation'];

/**
 * A command as a {@link RoutingManifest} lists it: its name, former names and
 * routes, and the property of its body that names its aggregate, for a command
 * whose body does (`idProperty`).
 */
export type RoutingCommand = Pick<CommandManifest, 'name' | 'routes'> &
  Partial<Pick<CommandManifest, 'aliases' | 'idProperty'>>;

/** A service route as a {@link RoutingManifest} lists it: its name and its route. */
export type RoutingServiceRoute = Pick<ServiceRouteManifest, 'name' | 'route'>;

/**
 * What a `CommandClient` reads of a Manifest to send a command: each
 * context's aggregates and their commands with their routes, and the context's
 * own system commands; and what a `ServiceRoutes` reads to call a service
 * route: the context's routes. A whole {@link Manifest} is one; a generated
 * client carries its service's as data (`manifest.ts`), with no schema.
 */
export interface RoutingManifest extends Pick<Manifest, 'format' | 'requires'> {
  readonly contexts: readonly (Pick<ContextManifest, 'name'> & {
    readonly aggregates?: readonly (Pick<AggregateManifest, 'name'> & {
      readonly commands?: readonly RoutingCommand[];
    })[];
    readonly commands?: readonly RoutingCommand[];
    readonly routes?: readonly RoutingServiceRoute[];
  })[];
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
export function checkManifest<M extends RoutingManifest>(manifest: M): M {
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

/**
 * The Manifest a client routes by (internal): the one it was given, or the
 * service's, read once for every caller and read again by the next caller
 * after a read that failed.
 */
export class ManifestSource {
  private manifest: Promise<RoutingManifest> | undefined;

  /**
   * @param client - How the service is reached
   * @param manifest - The Manifest given; without it the service's is read
   */
  constructor(
    private readonly client: WowClientOptions,
    manifest?: RoutingManifest,
  ) {
    this.manifest =
      manifest === undefined
        ? undefined
        : Promise.resolve(checkManifest(manifest));
  }

  /**
   * The Manifest, for one caller. The read is shared, but each caller's wait
   * is its own: it ends with the caller's `signal`, rejecting with its reason
   * while the read goes on for the others, and a read that fails rejects each
   * caller with an error of its own, so each carries the request id of its
   * own command.
   *
   * @param signal - Cancels this caller's wait
   */
  read(signal?: AbortSignal): Promise<RoutingManifest> {
    if (signal?.aborted) return Promise.reject(signal.reason);
    const shared = (this.manifest ??= fetchManifest(this.client).catch(
      (error: unknown) => {
        this.manifest = undefined;
        throw error;
      },
    ));
    return new Promise((resolve, reject) => {
      const abort = () => reject(signal!.reason);
      signal?.addEventListener('abort', abort, { once: true });
      shared
        .then(resolve, (error: unknown) => reject(ownFailure(error)))
        .finally(() => signal?.removeEventListener('abort', abort));
    });
  }
}

/**
 * A caller's own error for a shared Manifest read that failed with `error`:
 * the same problem when the service answered one, else an error that names
 * the read, under the name and with the cause of what it failed with.
 */
function ownFailure(error: unknown): Error {
  if (error instanceof WowProblemError) {
    return new WowProblemError(error.problem, {
      cause: error,
      ...(error.retryAfterMs === undefined
        ? {}
        : { retryAfterMs: error.retryAfterMs }),
    });
  }
  // It reads as the read's own error: its name, and its cause where it has
  // one (a fetcher's timeout or abort), so a caller's check written for a
  // request that failed alike (`error.cause?.name === 'FetchTimeoutError'`)
  // holds. `{ cause }` of an `Error` is ES2022; this package targets ES2020.
  const failed = error instanceof Error ? error : undefined;
  return Object.assign(
    new Error(
      `The service's Manifest (GET /wow/manifest) could not be read: ${
        failed?.message ?? String(error)
      }`,
    ),
    {
      name: failed?.name ?? 'Error',
      cause: (failed as { cause?: unknown } | undefined)?.cause ?? error,
    },
  );
}
