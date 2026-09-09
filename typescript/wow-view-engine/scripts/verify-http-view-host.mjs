/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { LocalStorageViewHost, ViewServiceError } from '../dist/index.js';
import { startViewService } from './fixtures/view-service-server.mjs';
import { loadOrderFixture } from './fixtures/order-fixture.mjs';
import { loadHttpHost } from './fixtures/load-http-host.mjs';
const { HttpViewHost, VIEW_SERVICE_STATUS } = await loadHttpHost();
const fixture = await loadOrderFixture();
const source = {
  paged: async () => {
    throw new Error('Business data is not a view-service endpoint');
  },
  cursor: async () => {
    throw new Error('Business data is not a view-service endpoint');
  },
};
const serveOnly = process.argv.includes('--serve');
const origin = (
  process.env.VIEW_ENGINE_E2E_BASE_URL ?? 'http://127.0.0.1:6006'
).replace(/\/$/, '');
const server = await startViewService({
  Host: LocalStorageViewHost,
  ServiceError: ViewServiceError,
  statuses: VIEW_SERVICE_STATUS,
  ...fixture,
  source,
  port: serveOnly ? 6010 : 0,
  allowedOrigin: new URL(origin).origin,
});
if (serveOnly) {
  console.log(
    `Development view service: ${server.baseUrl} (fake sessions: alice-token, bob-token)`,
  );
  const stop = async () => {
    await server.close();
    process.exit(0);
  };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
} else {
  const require = createRequire(
    new URL('../../../package.json', import.meta.url),
  );
  const { chromium } = createRequire(
    require.resolve('@vitest/browser-playwright'),
  )('playwright');
  const channel = process.env.VIEW_ENGINE_BROWSER_CHANNEL;
  const browser = await chromium.launch({
    ...(channel ? { channel } : {}),
    headless: true,
  });
  try {
    const context = await browser.newContext({
      viewport: { width: 1440, height: 1000 },
    });
    const page = await context.newPage(),
      bobPage = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    bobPage.on('pageerror', error => errors.push(error.message));
    const url = `${origin}/iframe.html?id=development-http-service--http-view-service&viewMode=story&viewService=${encodeURIComponent(server.baseUrl)}`;
    const alice = new HttpViewHost({
      baseUrl: server.baseUrl,
      definitionId: fixture.definition.id,
      headers: () => ({ Authorization: 'Bearer alice-token' }),
      resolveSource: () => source,
    });
    const list = () => alice.instance.list(fixture.definition.id);
    const waitUntil = async predicate => {
      for (let n = 0; n < 100; n++) {
        if (await predicate()) return;
        await delay(25);
      }
      throw new Error('Service assertion timed out');
    };
    await page.goto(url);
    await page.getByRole('cell', { name: 'DEMO-1', exact: true }).waitFor();
    await page
      .getByRole('textbox', { name: '状态显示名称' })
      .fill('HTTP 恢复标签');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await waitUntil(
      async () =>
        (await list()).instances.find(item => item.id === 'pending').config
          .filters.root.props.displayLabel === 'HTTP 恢复标签',
    );
    await page.reload();
    await page.getByRole('cell', { name: 'DEMO-1', exact: true }).waitFor();
    assert.equal(
      await page.getByRole('textbox', { name: '状态显示名称' }).inputValue(),
      'HTTP 恢复标签',
    );
    // All five runtime extension categories work with a restored HTTP-backed view.
    await page.getByLabel('金额 120.00 元', { exact: true }).waitFor();
    const beforeBusiness = await alice.instance.load('pending');
    await page.getByRole('button', { name: '创建订单', exact: true }).click();
    await page.getByRole('cell', { name: 'DEMO-4', exact: true }).waitFor();
    await page
      .getByRole('button', { name: '处理订单 DEMO-1', exact: true })
      .click();
    await page
      .getByRole('cell', { name: 'DEMO-1', exact: true })
      .waitFor({ state: 'detached' });
    await page
      .getByRole('checkbox', { name: '选择记录 DEMO-2', exact: true })
      .click();
    await page
      .getByRole('checkbox', { name: '选择记录 DEMO-4', exact: true })
      .click();
    await page.getByRole('button', { name: '批量处理', exact: true }).click();
    await page.getByRole('img', { name: '暂无记录', exact: true }).waitFor();
    assert.deepEqual(await alice.instance.load('pending'), beforeBusiness);
    await page.reload();
    await page.getByRole('cell', { name: 'DEMO-1', exact: true }).waitFor();
    await page.getByRole('button', { name: '视图选项', exact: true }).click();
    await page.getByRole('menuitem', { name: '另存为', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '另存为视图' });
    await dialog
      .getByRole('textbox', { name: '视图名称' })
      .fill('共享 HTTP 视图');
    await dialog.getByRole('radio', { name: '公共视图', exact: true }).click();
    server.control.dropNextCreateResponse = true;
    await dialog.getByRole('button', { name: '创建视图', exact: true }).click();
    await waitUntil(
      async () =>
        !(await dialog.isVisible()) ||
        (await dialog.innerText()).includes('写入结果未知'),
    );
    assert.equal(
      (await list()).instances.filter(item => item.title === '共享 HTTP 视图')
        .length,
      1,
    );
    // Chromium can transparently retry a reset connection; otherwise the engine retries the same request ID.
    if (await dialog.isVisible())
      await dialog
        .getByRole('button', { name: '创建视图', exact: true })
        .click();
    await dialog.waitFor({ state: 'detached' });
    assert.equal(
      (await list()).instances.filter(item => item.title === '共享 HTTP 视图')
        .length,
      1,
    );
    const shared = (await list()).instances.find(
      item => item.title === '共享 HTTP 视图',
    );
    await bobPage.goto(url + '&args=scopeKey:bob;accessToken:bob-token');
    await bobPage
      .getByRole('button', { name: '共享 HTTP 视图', exact: true })
      .click();
    await bobPage.getByRole('cell', { name: 'DEMO-1', exact: true }).waitFor();
    assert.equal(
      await bobPage.getByRole('textbox', { name: '状态显示名称' }).inputValue(),
      'HTTP 恢复标签',
    );
    assert.equal(
      await bobPage.getByRole('button', { name: '保存', exact: true }).count(),
      0,
    );
    const aliceOrder = (await list()).instances.map(item => item.id);
    await bobPage
      .getByRole('button', { name: '管理视图', exact: true })
      .first()
      .click();
    const manager = bobPage.getByRole('dialog', {
      name: '管理视图',
      exact: true,
    });
    assert.equal(
      await manager
        .getByRole('button', { name: '编辑共享 HTTP 视图名称', exact: true })
        .count(),
      0,
    );
    await manager
      .getByRole('button', { name: '拖动调整共享 HTTP 视图顺序', exact: true })
      .press('ArrowUp');
    await waitUntil(
      async () =>
        (
          await server.hostFor('bob-token').instance.list(fixture.definition.id)
        ).instances
          .map(item => item.id)
          .indexOf(shared.id) === 1,
    );
    assert.deepEqual(
      (await list()).instances.map(item => item.id),
      aliceOrder,
    );

    await page
      .getByRole('textbox', { name: '状态显示名称' })
      .fill('撤权仍保留草稿');
    server.setWriter('alice-token', false);
    await page
      .getByRole('button', { name: '同步服务权限', exact: true })
      .click();
    await page
      .getByRole('button', { name: '保存', exact: true })
      .waitFor({ state: 'detached' });
    assert.equal(
      await page.getByRole('textbox', { name: '状态显示名称' }).inputValue(),
      '撤权仍保留草稿',
    );
    await assert.rejects(
      alice.instance.save(shared),
      error => error.code === 'FORBIDDEN',
    );
    server.setWriter('alice-token', true);
    await page
      .getByRole('button', { name: '同步服务权限', exact: true })
      .click();
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await waitUntil(
      async () =>
        (await alice.instance.load(shared.id)).config.filters.root.props
          .displayLabel === '撤权仍保留草稿',
    );

    server.control.delayNextRead = 2000;
    await page
      .getByRole('button', { name: '重新打开已保存视图', exact: true })
      .click();
    await page.getByText('视图服务请求失败或超时').waitFor();
    await page
      .getByRole('button', { name: '重新加载视图', exact: true })
      .click();
    await page.getByRole('cell', { name: 'DEMO-1', exact: true }).waitFor();
    const delayed = server.control.delayedReads;
    server.control.delayNextRead = 2000;
    await page
      .getByRole('button', { name: '重新打开已保存视图', exact: true })
      .click();
    await waitUntil(() => server.control.delayedReads > delayed);
    await page
      .getByRole('button', { name: '重新打开已保存视图', exact: true })
      .click();
    await page.getByRole('cell', { name: 'DEMO-1', exact: true }).waitFor();
    await waitUntil(() => server.control.abortedReads >= 2);
    assert.deepEqual(errors, []);

    // Exercise real localStorage + Web Locks across two tabs, not merely a Node mutex.
    const coreUrl =
      origin +
      '/@fs' +
      fileURLToPath(new URL('../dist/index.js', import.meta.url));
    for (const tab of [page, bobPage])
      await tab.evaluate(
        async ({ coreUrl, fixture }) => {
          const { LocalStorageViewHost } = await import(coreUrl);
          window.storageHost = new LocalStorageViewHost({
            ...fixture,
            scopeKey: 'alice',
            serviceKey: 'native-lock-contract',
            storage: localStorage,
            lock: (name, operation, signal) =>
              navigator.locks.request(name, { signal }, operation),
            resolveSource: () => {
              throw new Error('not needed');
            },
          });
        },
        { coreUrl, fixture },
      );
    const old = await page.evaluate(() =>
      window.storageHost.instance.load('pending'),
    );
    const race = await Promise.all(
      [page, bobPage].map((tab, index) =>
        tab.evaluate(
          async ({ old, index }) => {
            try {
              await window.storageHost.instance.save({
                ...old,
                title: `writer-${index}`,
              });
              return 'saved';
            } catch (error) {
              return error.code;
            }
          },
          { old, index },
        ),
      ),
    );
    assert.deepEqual(race.sort(), ['REVISION_CONFLICT', 'saved']);
    await page.evaluate(() => {
      window.heldLock = navigator.locks.request(
        window.storageHost.storageKey,
        () =>
          new Promise(resolve => {
            window.releaseLock = resolve;
          }),
      );
    });
    await page.waitForFunction(() => typeof window.releaseLock === 'function');
    await bobPage.evaluate(input => {
      window.cancelCreate = new AbortController();
      window.createOutcome = undefined;
      window.storageHost.instance
        .create(input, {
          requestId: 'native-abort',
          signal: window.cancelCreate.signal,
        })
        .then(
          () => {
            window.createOutcome = 'saved';
          },
          error => {
            window.createOutcome = error.name;
          },
        );
    }, old);
    await bobPage.waitForTimeout(50);
    assert.equal(await bobPage.evaluate(() => window.createOutcome), undefined);
    await bobPage.evaluate(() => window.cancelCreate.abort());
    await bobPage.waitForFunction(() => window.createOutcome === 'AbortError');
    await page.evaluate(() => window.releaseLock());
    assert.equal(
      (
        await page.evaluate(() =>
          window.storageHost.instance.list('demo-orders'),
        )
      ).instances.length,
      2,
    );
    console.log(
      JSON.stringify({
        passed: true,
        checks: [
          'HTTP JSON -> components after reload',
          'five runtime extensions and business/view data isolation',
          'shared content, private order',
          'response-lost create retried exactly once',
          'permission revoke/restore keeps draft',
          'HTTP read timeout/retry and engine cancellation',
          'native cross-tab CAS and queued cancellation',
        ],
      }),
    );
  } finally {
    await browser.close();
    await server.close();
  }
}
