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

/**
 * The client of a service's own routes: routes whose handler is the service's
 * code, neither a command nor a query, listed in its Manifest with their
 * parameters and schemas. A generated client (`routes.ts`) calls each by its
 * name with typed parameters; a hand-written one calls {@link ServiceRoutes.call}.
 *
 * @packageDocumentation
 */

import {
  type Call,
  type RequestOptions,
  send,
  json,
  WowHeaders,
  type WowClientOptions,
} from './http.js';
import {
  checkManifest,
  fetchManifest,
  type RoutingManifest,
} from './manifest.js';

/** What a call of a service route sends, besides its route. */
export interface ServiceRouteCall {
  /** The path's variables, by name: each of the route's `{name}`. */
  readonly path?: Readonly<Record<string, string | number | boolean>>;
  /** The query parameters, by name; one left undefined is not sent. */
  readonly query?: Readonly<
    Record<string, string | number | boolean | undefined>
  >;
  /** The body, for a route that takes one. */
  readonly body?: unknown;
  /** The request's id (`Wow-Request-Id`); the service makes one without it. */
  readonly requestId?: string;
}

/** Options of a {@link ServiceRoutes}. */
export interface ServiceRoutesOptions {
  /**
   * The service's Manifest, or the part of it that lists the routes; without
   * it the client reads the service's (`GET /wow/manifest`) once.
   */
  readonly manifest?: RoutingManifest;
}

const VARIABLE = /\{([A-Za-z][A-Za-z0-9]*)}/g;

/**
 * Calls a service's routes by the names its Manifest gives them. The answer
 * is the route's JSON, `undefined` for one that answers `204 No Content`; a
 * refusal rejects with the {@link WowProblemError} of its code, a parameter
 * that does not read with `wow.route.malformed`.
 */
export class ServiceRoutes {
  private manifest: Promise<RoutingManifest> | undefined;

  /**
   * @param client - How the service is reached
   * @param options - The service's Manifest
   */
  constructor(
    readonly client: WowClientOptions = {},
    options: ServiceRoutesOptions = {},
  ) {
    this.manifest =
      options.manifest === undefined
        ? undefined
        : Promise.resolve(checkManifest(options.manifest));
  }

  /**
   * Calls the route `name` of `context` and answers what it answers.
   *
   * @param context - The bounded context whose route it is
   * @param name - The route's name in the Manifest
   * @param call - Its path variables, query parameters and body
   * @param options - The scope or space to act in, headers and cancellation
   * @throws TypeError when the Manifest lists no such route, or a path
   *   variable is missing
   */
  async call<T = unknown>(
    context: string,
    name: string,
    call: ServiceRouteCall = {},
    options: RequestOptions = {},
  ): Promise<T | undefined> {
    const route = (await this.manifestOf()).contexts
      .find(item => item.name === context)
      ?.routes?.find(item => item.name === name)?.route;
    if (route === undefined) {
      throw new TypeError(
        `The Manifest lists no service route ${name} of ${context}.`,
      );
    }
    const path = route.path.replace(VARIABLE, (_, variable: string) => {
      const value = call.path?.[variable];
      if (value === undefined) {
        throw new TypeError(
          `The service route ${context}.${name} needs its path variable ${variable}.`,
        );
      }
      return encodeURIComponent(String(value));
    });
    const query = call.query
      ? Object.fromEntries(
          Object.entries(call.query).map(([key, value]) => [
            key,
            value === undefined ? undefined : String(value),
          ]),
        )
      : undefined;
    const response = await send(this.client, {
      method: route.method as Call['method'],
      path,
      ...(query === undefined ? {} : { query }),
      ...(call.body === undefined ? {} : { body: call.body }),
      ...(call.requestId === undefined
        ? {}
        : { headers: { [WowHeaders.REQUEST_ID]: call.requestId } }),
      options,
    });
    return response.status === 204 ? undefined : json<T>(response);
  }

  /** The Manifest given, or the service's, fetched once (again after a failure). */
  private manifestOf(): Promise<RoutingManifest> {
    this.manifest ??= fetchManifest(this.client).catch((error: unknown) => {
      this.manifest = undefined;
      throw error;
    });
    return this.manifest;
  }
}
