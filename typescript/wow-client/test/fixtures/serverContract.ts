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
 * The server's REST contract as the Kotlin side commits it: the snapshots
 * `OpenApiCompatibilitySnapshotTest` (wow-openapi) holds the example domain's
 * routes and OpenAPI document to. A change to a route path, a route id or a
 * header name changes these files, so a client test that reads them fails
 * when the client was not changed with it.
 */

import { readFileSync } from 'node:fs';

const SNAPSHOTS = '../../../../wow-openapi/src/test/resources/openapi/';

function readSnapshot<T>(name: string): T {
  return JSON.parse(
    readFileSync(new URL(`${SNAPSHOTS}${name}`, import.meta.url), 'utf8'),
  ) as T;
}

/** One route of `example-domain-contract.snapshot.json`. */
export interface ContractRoute {
  id: string;
  method: string;
  path: string;
  parameterNames: string[];
}

/** Every route the example domain serves, with its id and path. */
export const contractRoutes = readSnapshot<ContractRoute[]>(
  'example-domain-contract.snapshot.json',
);

interface OpenApiDocument {
  components: {
    parameters: Record<string, { in: string; name: string }>;
    headers: Record<string, unknown>;
  };
}

const openApi = readSnapshot<OpenApiDocument>(
  'example-domain-openapi.snapshot.json',
);

const COMPONENT_PREFIX = 'wow.';

/** The request headers the server declares, by name. */
export const requestHeaderNames: string[] = Object.values(
  openApi.components.parameters,
)
  .filter(parameter => parameter.in === 'header')
  .map(parameter => parameter.name);

/** The response headers the server declares, by name. */
export const responseHeaderNames: string[] = Object.keys(
  openApi.components.headers,
).map(key =>
  key.startsWith(COMPONENT_PREFIX) ? key.slice(COMPONENT_PREFIX.length) : key,
);
