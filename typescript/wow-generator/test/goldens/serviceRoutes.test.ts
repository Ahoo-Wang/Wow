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

import { afterAll, describe, expect, it } from 'vitest';
import { readFileSync, writeFileSync } from 'fs';
import * as path from 'path';
import {
  BUNDLER_OPTIONS,
  generateProject,
  removeDirectories,
  runGenerated,
  typeCheck,
} from '../support/generation';

/**
 * A context's service routes generate `routes.ts`, one typed method per
 * route, listed in the context's routing Manifest and reached from its
 * client; a collection body or answer is an array of its class, and the
 * answer of a route that may answer 204 for an absent one may be `undefined`.
 */
const directories: string[] = [];
afterAll(() => removeDirectories(directories));

describe('the service routes of a Manifest', () => {
  it('generate a typed method per route, which calls it by its Manifest name', async () => {
    const { output, warnings } = await generateProject(
      'service-routes',
      { input: 'test/fixtures/service-routes.manifest.json' },
      directories,
    );
    expect(warnings).toBe('');
    const routes = readFileSync(path.join(output, 'shop', 'routes.ts'), 'utf8');
    expect(routes).toContain('export class ShopRoutes');
    expect(routes).toContain(
      "summary(day: string, query: { readonly limit: number; readonly period?: 'DAY' | 'WEEK'; }, options?: RequestOptions): Promise<Summary>",
    );
    expect(routes).toContain(
      'replaceNotes(body: Note[], options?: RequestOptions): Promise<Note[]>',
    );
    expect(routes).toContain(
      'note(text: string, options?: RequestOptions): Promise<Note | undefined>',
    );
    expect(routes).toContain('ping(options?: RequestOptions): Promise<void>');
    expect(routes).toContain('@deprecated');
    expect(
      readFileSync(path.join(output, 'shop', 'manifest.ts'), 'utf8'),
    ).toContain(
      "{ name: 'summary', route: { method: 'GET', path: '/shop/summaries/{day}' } },",
    );
    expect(
      readFileSync(path.join(output, 'shop', 'client.ts'), 'utf8'),
    ).toContain('readonly routes: ShopRoutes;');
    writeFileSync(
      path.join(output, 'consumer.ts'),
      `import type { Note, ShopRoutes, Summary } from './shop/index.js';
export async function read(routes: ShopRoutes): Promise<void> {
  const summary: Summary = await routes.summary('mon', { limit: 1 });
  const found: Note | undefined = await routes.note('a');
  // @ts-expect-error The answer of a route that may answer 204 may be absent.
  const note: Note = await routes.note('a');
  const nothing: void = await routes.ping();
  void [summary, found, note, nothing];
}
`,
    );
    expect(typeCheck(output, BUNDLER_OPTIONS)).toEqual([]);
    const sent = runGenerated(
      output,
      `const fetcher = new Fetcher({ baseURL: 'http://shop', headers: {} });
const shop = await import('./shop/index.js');
const routes = new shop.ShopClient({ fetcher }).routes;
await routes.summary('mon', { limit: 3 }, { headers: { 'X-Test': '1' } });
await routes.replaceNotes([{ text: 'a' }]);
globalThis.fetch = async (url, init) => {
  requests.push({ url: String(url), method: init?.method, body: init?.body ?? null });
  return new Response(null, { status: 204 });
};
const absent = await routes.note('none');
console.log(JSON.stringify({ absent: absent === undefined, requests: requests.map(({ method, url, body }) => ({ method, url, body })) }));`,
    );
    const { absent, requests } = JSON.parse(sent);
    expect(absent).toBe(true);
    expect(requests).toEqual([
      {
        method: 'GET',
        url: 'http://shop/shop/summaries/mon?limit=3',
        body: null,
      },
      { method: 'PUT', url: 'http://shop/shop/notes', body: '[{"text":"a"}]' },
      { method: 'GET', url: 'http://shop/shop/notes/none', body: null },
    ]);
  }, 60000);
});
