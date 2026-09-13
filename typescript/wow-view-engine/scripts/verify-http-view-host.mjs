/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { verifyIndexedDBViewHost } from './verify-indexeddb-view-host.mjs';
import { createRequire } from 'node:module';
import { setTimeout as delay } from 'node:timers/promises';
import { MemoryViewHost, ViewServiceError } from '../dist/index.js';
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
  Host: MemoryViewHost,
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
    await page
      .getByRole('cell', { name: 'SO-202609-1018', exact: true })
      .waitFor();
    await page
      .getByRole('textbox', { name: '状态显示名称' })
      .fill('HTTP 恢复标签');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await waitUntil(
      async () =>
        (await list()).instances.find(item => item.id === 'my-orders').config
          .filters.root.props.displayLabel === 'HTTP 恢复标签',
    );
    await page.reload();
    await page
      .getByRole('cell', { name: 'SO-202609-1018', exact: true })
      .waitFor();
    assert.equal(
      await page.getByRole('textbox', { name: '状态显示名称' }).inputValue(),
      'HTTP 恢复标签',
    );
    // The restored host resolves business data separately from view storage.
    const beforeBusiness = await alice.instance.load('my-orders');
    await page.getByRole('button', { name: '创建订单', exact: true }).click();
    await page.getByRole('button', { name: '确认创建', exact: true }).click();
    await page
      .getByRole('dialog', { name: '订单详情 SO-202609-1019', exact: true })
      .waitFor();
    await page.getByRole('button', { name: '关闭详情', exact: true }).click();
    assert.deepEqual(await alice.instance.load('my-orders'), beforeBusiness);
    await page.reload();
    await page
      .getByRole('cell', { name: 'SO-202609-1018', exact: true })
      .waitFor();
    await page.getByRole('button', { name: '视图选项', exact: true }).click();
    await page.getByRole('menuitem', { name: '另存为', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '另存为视图' });
    await dialog
      .getByRole('textbox', { name: '视图名称' })
      .fill('共享 HTTP 视图');
    await dialog.getByRole('radio', { name: '公共视图', exact: true }).click();
    server.control.dropNextCreateResponse = true;
    await dialog.getByRole('button', { name: '创建视图', exact: true }).click();
    await waitUntil(async () => {
      const texts = await dialog.allTextContents();
      return (
        texts.length === 0 || texts.some(text => text.includes('写入结果未知'))
      );
    });
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
    await bobPage
      .getByRole('cell', { name: 'SO-202609-1018', exact: true })
      .waitFor();
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
    const reorderHandle = manager.getByRole('button', {
      name: '拖动调整共享 HTTP 视图顺序',
      exact: true,
    });
    await reorderHandle.press('Space');
    await bobPage.waitForSelector('[data-dnd-dragging]');
    await bobPage.keyboard.press('ArrowUp');
    await bobPage.waitForSelector('[data-drop-target]');
    await bobPage.keyboard.press('Space');
    await waitUntil(
      async () =>
        (
          await server.hostFor('bob-token').instance.list(fixture.definition.id)
        ).instances
          .map(item => item.id)
          .indexOf(shared.id) ===
        aliceOrder.indexOf(shared.id) - 1,
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
    await page
      .getByRole('cell', { name: 'SO-202609-1018', exact: true })
      .waitFor();
    const delayed = server.control.delayedReads;
    server.control.delayNextRead = 2000;
    await page
      .getByRole('button', { name: '重新打开已保存视图', exact: true })
      .click();
    await waitUntil(() => server.control.delayedReads > delayed);
    await page
      .getByRole('button', { name: '重新打开已保存视图', exact: true })
      .click();
    await page
      .getByRole('cell', { name: 'SO-202609-1018', exact: true })
      .waitFor();
    await waitUntil(() => server.control.abortedReads >= 2);
    assert.deepEqual(errors, []);

    await verifyIndexedDBViewHost({ page, bobPage, origin, fixture });
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
          'IndexedDB cross-tab CAS, rollback, receipts, reset and queued cancellation',
        ],
      }),
    );
  } finally {
    await browser.close();
    await server.close();
  }
}
