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
  checkManifest,
  CommandClient,
  fetchManifest,
  MANIFEST_FEATURES,
} from '../src/index.js';
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
});
