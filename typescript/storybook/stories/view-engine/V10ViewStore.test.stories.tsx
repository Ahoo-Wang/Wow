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
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import {
  Fetcher,
  type FetchExchange,
  type RequestInterceptor,
} from '@ahoo-wang/fetcher';
import { DatasetClient } from '@ahoo-wang/wow-client/v10';
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
import { DataWorkbench, zhCN } from '@ahoo-wang/wow-view-engine/ui';
import { viewSourceOf, WowViewStore } from '@ahoo-wang/wow-view-store/v10';
import {
  fakeV10ViewStore,
  type FakeV10ViewStore,
} from '../../../wow-view-store/test/support/fakeV10ViewStore.js';
import { AppShell } from '../shared/AppShell.js';
import {
  HOST_LANGUAGE,
  ORDERS,
  ordersDefinition,
  savedViews,
} from './fixtures.js';
import { rowSource } from './rowSource.js';
import { StoryEngine } from './StoryEngine.js';
import { readColumn } from './readTable.js';
import {
  PENDING_BY_AMOUNT,
  addSort,
  confirmSharedSave,
  listItem,
  managerRow,
  openManager,
  pressWhenEnabled,
  say,
} from './recordWorkbenchTest.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * The record workbench on a Wow **V10** service, through
 * `@ahoo-wang/wow-view-store/v10` (roadmap 7.3a): the views, the
 * preferences and the system views in the V10 view store, the rows through
 * a V10 dataset query route (`viewSourceOf`). The service is the in-memory
 * stand-in `wow-view-store`'s own suites run on, installed as the page's
 * `fetch` — the routes, scopes, request ids, `If-Match` and problems of the
 * real one, which `typescript/integration-test/test/view-store-v10/` holds
 * against the service itself. Every gesture below therefore crosses the
 * same requests a host on V10 sends, and each play reads them back.
 *
 * Regression only: no catalogue entry, no navigation.
 */
const meta = {
  title: 'View Engine/回归夹具/V10 视图存储',
  component: V10Workbench,
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { layout: 'fullscreen' },
  beforeEach: installService,
} satisfies Meta<typeof V10Workbench>;

export default meta;

type Story = StoryObj<typeof meta>;

const BASE_URL = 'http://view-store.test/';

/** The service of the story on screen, and the ids it seeded. */
const service: {
  current?: FakeV10ViewStore;
  shared?: string;
  personal?: string;
} = {};

/** The headers the service's caller resolver reads: a user with the shared role. */
class Caller implements RequestInterceptor {
  readonly name = 'V10StoryCaller';
  readonly order = 0;
  intercept(exchange: FetchExchange): void {
    Object.assign(exchange.ensureRequestHeaders(), {
      'X-User': 'alice',
      'X-Tenant': 'retail',
      'X-Role': 'view-store.shared-writer',
      'CoSec-App-Id': 'orders-console',
    });
  }
}

function caller(): Fetcher {
  const fetcher = new Fetcher({ baseURL: BASE_URL });
  fetcher.interceptors.request.use(new Caller());
  return fetcher;
}

/**
 * Installs a fresh service as the page's `fetch`, with the orders as a
 * dataset and one configured system view, and seeds a shared and a
 * personal view through the V10 store itself. The page's own `fetch` comes
 * back when the story ends.
 */
async function installService(): Promise<() => void> {
  const fake = fakeV10ViewStore({
    datasets: [
      { context: 'sales', dataset: 'orders', source: rowSource(ORDERS) },
    ],
    systemViews: [
      {
        id: 'orders-all',
        definitionId: 'orders',
        title: '全部订单',
        config: savedViews[0].config as unknown as Record<string, unknown>,
      },
    ],
  });
  const original = globalThis.fetch;
  globalThis.fetch = (input, init) =>
    String(input instanceof Request ? input.url : input).startsWith(BASE_URL)
      ? fake.fetch(input, init)
      : original(input, init);
  const store = new WowViewStore({ fetcher: caller() });
  const [pending, , mine] = savedViews;
  const shared = await store.create(
    { ...pending, scope: 'shared' },
    { requestId: 'seed-shared' },
  );
  const personal = await store.create(
    { ...mine, scope: 'personal' },
    { requestId: 'seed-personal' },
  );
  service.current = fake;
  service.shared = shared.id;
  service.personal = personal.id;
  return () => {
    globalThis.fetch = original;
  };
}

function engineOnV10(): ViewEngine {
  const fetcher = caller();
  return new ViewEngine({
    resources: [
      {
        definition: ordersDefinition,
        source: viewSourceOf(
          new DatasetClient({ fetcher }).dataset('sales', 'orders'),
        ),
      },
    ],
    store: new WowViewStore({ fetcher }),
  });
}

function V10Workbench() {
  return (
    <AppShell service={{ fixture: 'V10 视图存储（内存）· 六条订单' }}>
      <StoryEngine create={engineOnV10}>
        {engine => (
          <DataWorkbench
            engine={engine}
            definitionId="orders"
            instanceId={service.shared}
            messages={HOST_LANGUAGE.messages}
            locale={HOST_LANGUAGE.locale}
          />
        )}
      </StoryEngine>
    </AppShell>
  );
}

/** The requests the service received to a path, as it saw them. */
function sent(method: string, path: RegExp) {
  return service.current!.requests.filter(
    request => request.method === method && path.test(request.path),
  );
}

/**
 * The sidebar lists what the V10 service keeps — the configured system view
 * from its own route, the shared view read as `(shared)`, the caller's own —
 * and the table shows the rows the V10 dataset route answered for the
 * shared view's condition.
 */
export const ListsAndQueriesOnV10: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
    for (const title of ['全部订单', '待出库订单', '我盯的大额单'])
      await expect(listItem(canvasElement, title)).toBeDefined();
    const group = (title: string) =>
      listItem(canvasElement, title)
        .closest('[data-slot="view-group"]')
        ?.querySelector('[data-slot="view-group-heading"]')?.textContent;
    await expect(group('待出库订单')).toBe(zhCN['label.scope.group.shared']);
    await expect(group('我盯的大额单')).toBe(
      zhCN['label.scope.group.personal'],
    );

    // The routes a V10 host reaches: the system views route, the snapshot
    // query as the caller and as `(shared)`, the dataset's query route.
    await expect(
      sent('GET', /^\/view-store\/system-views$/).map(request =>
        request.query.get('definitionId'),
      ),
    ).toContain('orders');
    await expect(
      sent('POST', /^\/view-store\/view\/snapshot\/query$/).map(request =>
        request.headers.get('Wow-Scope'),
      ),
    ).toEqual(expect.arrayContaining([null, 'owner="(shared)"']));
    await expect(
      sent('POST', /^\/wow\/dataset\/sales\/orders\/query$/).map(
        request => (request.body as { kind: string }).kind,
      ),
    ).toContain('paged');
  },
};

/**
 * Saving the shared view over the V10 store: one `save-view` as `(shared)`,
 * carrying the revision it was read at as `If-Match` and the engine's
 * request id; the revision the service answers is the one the next save
 * expects.
 */
export const SavesASharedViewOnV10: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
    // Any change makes the view dirty: a second sort is one.
    await addSort(table, '订单号');
    await pressWhenEnabled(
      canvas.getByRole('button', { name: zhCN['label.save.save'] }),
    );
    await confirmSharedSave();

    await waitFor(() => expect(sent('POST', /\/save-view$/)).toHaveLength(1));
    const [saveView] = sent('POST', /\/save-view$/);
    await expect(saveView.path).toBe(
      `/view-store/view/${service.shared}/save-view`,
    );
    await expect(saveView.headers.get('If-Match')).toBe('"1"');
    await expect(saveView.headers.get('Wow-Scope')).toBe('owner="(shared)"');
    await expect(saveView.headers.get('Wow-Request-Id')).toBeTruthy();
  },
};

/**
 * 设为共享 and 设为个人 on the V10 store: the share is sent as the view's
 * owner and waits for the saga that moves it to `(shared)`; the claim is
 * sent as `(shared)` and makes the caller the owner again. The id stays
 * through both, and the manager moves the row each time.
 */
export const SharesAndClaimsOnV10: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const row = await openManager(canvas, '我盯的大额单');
    await userEvent.click(
      within(row).getByRole('button', { name: zhCN['label.manage.share'] }),
    );
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="view-manager-announcement"]')
          ?.textContent,
      ).toBe(say('label.manage.shared', { title: '我盯的大额单' })),
    );
    const [share] = sent('POST', /\/share-view$/);
    await expect(share.path).toBe(
      `/view-store/view/${service.personal}/share-view`,
    );
    await expect(share.headers.get('Wow-Scope')).toBeNull();
    await expect(share.headers.get('Wow-Wait')).toContain(
      'ViewAudienceSaga.onAudienceChanged',
    );

    await pressWhenEnabled(
      within(managerRow('我盯的大额单')).getByRole('button', {
        name: zhCN['label.manage.make-personal'],
      }),
    );
    await waitFor(() => expect(sent('POST', /\/claim-view$/)).toHaveLength(1));
    const [claim] = sent('POST', /\/claim-view$/);
    await expect(claim.path).toBe(
      `/view-store/view/${service.personal}/claim-view`,
    );
    await expect(claim.headers.get('Wow-Scope')).toBe('owner="(shared)"');
    await waitFor(() =>
      expect(
        document.querySelector<HTMLElement>(
          '[data-slot="view-manager-group"][data-audience="personal"]',
        )?.textContent ?? '',
      ).toContain('我盯的大额单'),
    );
  },
};
