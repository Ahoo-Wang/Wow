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
 * `fetchManifest`: `GET /wow/manifest` and the Manifests it refuses.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CommandClient,
  fetchManifest,
  MANIFEST_FEATURES,
  WowProblemError,
} from '../src/index.js';
import { ServiceRoutes } from '../src/routes.js';
import { problemOf, problemResponse } from '../src/testing.js';
import { checkManifest } from '../src/manifest.js';
import { BASE_URL, jsonResponse, stubFetch } from './fixtures/fetchStub';
import { TEST_MANIFEST } from './fixtures/manifest';
import { client } from './fixtures/clients';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('fetchManifest', () => {
  it('gets /wow/manifest', async () => {
    const { requests } = stubFetch(() =>
      jsonResponse({ format: 1, contexts: [] }),
    );
    expect(await fetchManifest(client())).toEqual({ format: 1, contexts: [] });
    expect(requests[0].url).toBe(`${BASE_URL}/wow/manifest`);
  });

  it('refuses a Manifest of another format, or requiring what it does not understand', async () => {
    stubFetch(() => jsonResponse({ format: 2, contexts: [] }));
    await expect(fetchManifest(client())).rejects.toThrow(
      'this client reads format 1',
    );
    const requiring = { ...TEST_MANIFEST, requires: ['routes-v2'] };
    expect(() => checkManifest(requiring)).toThrow(
      'requires routes-v2, which this client does not understand',
    );
    expect(() => new CommandClient(client(), { manifest: requiring })).toThrow(
      'does not understand',
    );
    expect(MANIFEST_FEATURES.size).toBe(0);
  });

  // D485 ③: a service's public port serves no Manifest by default
  // (`wow.http.manifest.enabled`), so a client given none hears what to do.
  it.each([
    ['without a code', problemOf(404)],
    ['with a code', problemOf(404, 'wow.http.not-found')],
  ])(
    'says how to route without it when the service answers 404, %s',
    async (_, problem) => {
      stubFetch(() => problemResponse(problem));
      const failure = (await fetchManifest(client()).catch(
        (error: unknown) => error,
      )) as WowProblemError;
      expect(failure).toBeInstanceOf(WowProblemError);
      expect(failure.status).toBe(404);
      expect(failure.code).toBe(problem.code);
      expect(failure.message).toContain(
        'give the client the routing Manifest a generated client carries (its `manifest` option), or have the service serve its own (`wow.http.manifest.enabled: true`)',
      );
      expect(failure.cause).toBeInstanceOf(WowProblemError);
      expect((failure.cause as WowProblemError).problem).toMatchObject(problem);

      // A client given no Manifest rejects with the same words.
      for (const send of [
        () =>
          new CommandClient(client()).wait({
            command: 'c.a.X',
            aggregateId: '1',
          }),
        () => new ServiceRoutes(client()).call('c', 'route', {}),
      ])
        await expect(send()).rejects.toThrow(failure.message);
    },
  );

  it('rejects with any other failed read as it came', async () => {
    stubFetch(() => problemResponse(problemOf(503, 'wow.storage.unavailable')));
    const failure = (await fetchManifest(client()).catch(
      (error: unknown) => error,
    )) as WowProblemError;
    expect(failure).toMatchObject({
      status: 503,
      code: 'wow.storage.unavailable',
    });
    expect(failure.message).not.toContain('wow.http.manifest.enabled');
  });
});
