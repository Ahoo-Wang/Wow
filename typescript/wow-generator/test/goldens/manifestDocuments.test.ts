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
import {
  cpSync,
  existsSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
} from 'fs';
import * as path from 'path';
import {
  BUNDLER_OPTIONS,
  coldDirectory,
  generateProject,
  NODE_NEXT_OPTIONS,
  PACKAGE_ROOT,
  removeDirectories,
  runGenerated,
  typeCheck,
} from '../support/generation';

/**
 * The Manifests of the eShop's five services (roadmap 5a.3), as each server
 * answers `GET /wow/manifest`, committed under test/manifests/, generate the
 * clients under expected/eshop-manifest/, byte for byte. Regenerate the
 * Manifests from a build of the eShop (each server on Kafka, `curl
 * /wow/manifest`), then accept an intended change with
 *   UPDATE_SNAPSHOTS=true pnpm --filter @ahoo-wang/wow-generator test
 * and review the diff.
 */
const UPDATE_SNAPSHOTS = process.env.UPDATE_SNAPSHOTS === 'true';
const SERVICES = ['catalog', 'basket', 'ordering', 'inventory', 'payment'];
const EXPECTED = path.join(PACKAGE_ROOT, 'expected', 'eshop-manifest');

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

function listFiles(dir: string, base: string = dir): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .flatMap(name => {
      const full = path.join(dir, name);
      return statSync(full).isDirectory()
        ? listFiles(full, base)
        : [path.relative(base, full)];
    })
    .sort();
}

describe('the eShop Manifests, file by file', () => {
  it.each(SERVICES)(
    'generates the clients of eshop-%s',
    async service => {
      const { output, warnings } = await generateProject(
        `manifest-${service}`,
        { input: `test/manifests/eshop-${service}.json` },
        directories,
      );
      const expected = path.join(EXPECTED, service);
      if (UPDATE_SNAPSHOTS) {
        rmSync(expected, { recursive: true, force: true });
        cpSync(output, expected, { recursive: true });
      }
      expect(listFiles(output)).toEqual(listFiles(expected));
      for (const file of listFiles(expected)) {
        expect(
          readFileSync(path.join(output, file), 'utf8'),
          `${service}/${file} diverged from its snapshot`,
        ).toBe(readFileSync(path.join(expected, file), 'utf8'));
      }
      expect(warnings).toBe('');
    },
    30000,
  );

  it.each([
    ['bundler', BUNDLER_OPTIONS],
    ['NodeNext', NODE_NEXT_OPTIONS],
  ] as const)(
    'type-check with %s module resolution',
    (_, options) => {
      const dir = coldDirectory('tsc-eshop-manifest', directories);
      cpSync(EXPECTED, dir, { recursive: true });
      expect(typeCheck(dir, options)).toEqual([]);
    },
    60000,
  );

  it('send what the V10 routes take', () => {
    const dir = coldDirectory('run-eshop-manifest', directories);
    cpSync(EXPECTED, dir, { recursive: true });
    const output = runGenerated(
      dir,
      `const fetcher = new Fetcher({ baseURL: 'http://shop', headers: {} });
const catalog = await import('./catalog/index.js');
const ordering = await import('./ordering/index.js');
const payment = await import('./payment/index.js');
const products = new catalog.ProductCommandClient({ fetcher });
await products.createProduct({ name: 'n', price: 1 });
await products.createProduct({ name: 'n', price: 1 }, { id: 'p-1', wait: { plan: [catalog.eshopCatalogWait.committed()] } });
await products.changeProductPrice('p-1', { price: 2 }, { requestId: 'r-1', expectedVersion: 1 });
await products.publishProduct('p-1');
await products.deleteAggregate('p-1');
await products.applyTags('p-1', { tags: { region: ['eu'] } });
await new catalog.ProductSnapshotQueryClient({ fetcher }).list({ filter: { op: 'EQ', field: 'state.status', value: 'PUBLISHED' }, limit: 2 });
await new catalog.ProductStateClient({ fetcher }).get('p-1', { version: 1 });
const orders = new ordering.OrderCommandClient({ fetcher });
await orders.cancelOrder('o-1', { reason: 'r' }, {
  wait: { plan: [ordering.eshopOrderingWait.committed(), ordering.eshopOrderingWait.handled('OrderProcessSaga.onOrderCancelled')], timeout: 5000 },
});
await new ordering.OrderEventQueryClient({ fetcher }).paged({ filter: { op: 'EQ', field: 'aggregateId', value: 'o-1' } });
await new payment.PaymentCommandClient({ fetcher }).chargebackNotified({ merchantReference: 'o-1', disputeId: 'd', reason: 'x' });
console.log(JSON.stringify(requests.map(({ url, method, headers, body }) => ({
  method, url, body,
  headers: Object.fromEntries(Object.entries(headers).filter(([name]) => name.startsWith('wow-') || name === 'if-match')),
}))));`,
    );
    // A command without a request id is sent with a generated one (review A3-16).
    const GENERATED = expect.stringMatching(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(JSON.parse(output)).toEqual([
      {
        method: 'POST',
        url: 'http://shop/eshop-catalog/product/create-product',
        body: '{"name":"n","price":1}',
        headers: { 'wow-request-id': GENERATED },
      },
      {
        method: 'POST',
        url: 'http://shop/eshop-catalog/product/p-1/create-product',
        body: '{"name":"n","price":1}',
        headers: {
          'wow-request-id': GENERATED,
          'wow-wait': 'committed(eshop-catalog)',
        },
      },
      {
        method: 'POST',
        url: 'http://shop/eshop-catalog/product/p-1/change-product-price',
        body: '{"price":2}',
        headers: { 'wow-request-id': 'r-1', 'if-match': '"1"' },
      },
      {
        method: 'POST',
        url: 'http://shop/eshop-catalog/product/p-1/publish-product',
        body: '{}',
        headers: { 'wow-request-id': GENERATED },
      },
      {
        method: 'DELETE',
        url: 'http://shop/eshop-catalog/product/p-1',
        body: null,
        headers: { 'wow-request-id': GENERATED },
      },
      {
        method: 'POST',
        url: 'http://shop/eshop-catalog/product/p-1/wow.lifecycle.apply-tags',
        body: '{"tags":{"region":["eu"]}}',
        headers: { 'wow-request-id': GENERATED },
      },
      {
        method: 'POST',
        url: 'http://shop/eshop-catalog/product/snapshot/query',
        body: '{"filter":{"op":"EQ","field":"state.status","value":"PUBLISHED"},"limit":2,"kind":"list"}',
        headers: {},
      },
      {
        method: 'GET',
        url: 'http://shop/eshop-catalog/product/p-1/state?version=1',
        body: null,
        headers: {},
      },
      {
        method: 'POST',
        url: 'http://shop/eshop-ordering/order/o-1/cancel-order',
        body: '{"reason":"r"}',
        headers: {
          'wow-request-id': GENERATED,
          'wow-wait':
            'committed(eshop-ordering) > handled(eshop-ordering/OrderProcessSaga.onOrderCancelled)',
          'wow-wait-timeout': '5s',
        },
      },
      {
        method: 'POST',
        url: 'http://shop/eshop-ordering/order/events/query',
        body: '{"filter":{"op":"EQ","field":"aggregateId","value":"o-1"},"kind":"paged"}',
        headers: {},
      },
      {
        method: 'POST',
        url: 'http://shop/eshop-payment/payment/chargeback-notified',
        body: '{"merchantReference":"o-1","disputeId":"d","reason":"x"}',
        headers: { 'wow-request-id': GENERATED },
      },
    ]);
  }, 60000);
});
