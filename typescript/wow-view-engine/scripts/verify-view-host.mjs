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

// Start the repository Storybook first. This uses an isolated headless browser profile.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(
  new URL('../../../package.json', import.meta.url),
);
const { chromium } = createRequire(
  require.resolve('@vitest/browser-playwright'),
)('playwright');
const baseUrl = (
  process.env.VIEW_ENGINE_E2E_BASE_URL ?? 'http://127.0.0.1:6006'
).replace(/\/$/, '');
const url =
  process.env.VIEW_HOST_E2E_URL ??
  `${baseUrl}/iframe.html?id=development-local-storage--local-storage-views&viewMode=story`;
const key = 'fve:views:["demo-view-service","demo-orders"]';
const channel = process.env.VIEW_ENGINE_BROWSER_CHANNEL;
const browser = await chromium.launch({
  ...(channel ? { channel } : {}),
  headless: true,
});
try {
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1000 },
  });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const read = () =>
    page.evaluate(key => {
      const state = JSON.parse(localStorage.getItem(key));
      if (!state) return null;
      const user = state.users['storybook:local-view-host'];
      const visible = state.instances.filter(
        item =>
          item.ownerKey === null ||
          item.ownerKey === 'storybook:local-view-host',
      );
      const ids = [
        ...user.order.filter(id => visible.some(item => item.id === id)),
        ...visible.map(item => item.id).filter(id => !user.order.includes(id)),
      ];
      return {
        instances: ids.map(id => visible.find(item => item.id === id)),
        defaultInstanceId: user.defaultInstanceId,
      };
    }, key);
  const editor = () =>
    page.getByRole('textbox', { name: '状态显示名称', exact: true });
  const status = () =>
    page.getByRole('combobox', { name: '订单状态', exact: true });
  const save = () =>
    page.getByRole('button', { name: '保存', exact: true }).click();
  const query = () =>
    page.getByRole('button', { name: '查询', exact: true }).click();
  const reload = async () => {
    await page.reload();
    await status().waitFor();
  };
  const openManager = async () => {
    await page
      .getByRole('button', { name: '管理视图', exact: true })
      .first()
      .click();
    const manager = page.getByRole('dialog', { name: '管理视图', exact: true });
    await manager.waitFor();
    return manager;
  };
  const closeManager = manager =>
    manager.getByRole('button', { name: '关闭', exact: true }).click();
  await page.goto(url);
  await page.getByRole('cell', { name: 'DEMO-1', exact: true }).waitFor();
  await editor().fill('只能从组件属性恢复的标签');
  await status().click();
  await page.getByRole('option', { name: '已处理', exact: true }).click();
  await query();
  await page.getByRole('cell', { name: 'DEMO-3', exact: true }).waitFor();
  await save();
  await page.waitForFunction(
    key =>
      JSON.parse(localStorage.getItem(key))?.instances.find(
        item =>
          item.id === 'pending' &&
          item.ownerKey === 'storybook:local-view-host',
      ).config.filters.root.props.selectedId === 'processed',
    key,
  );
  const saved = await read();
  assert(!('filter' in saved.instances[0].config));
  assert.equal(
    saved.instances[0].config.filters.root.props.displayLabel,
    '只能从组件属性恢复的标签',
  );
  await reload();
  await page.getByRole('cell', { name: 'DEMO-3', exact: true }).waitFor();
  assert.equal((await status().textContent()).trim(), '已处理');
  assert.equal(await editor().inputValue(), '只能从组件属性恢复的标签');

  // The same restored runtime resolves all five extension types; record writes do not rewrite view JSON.
  await status().click();
  await page.getByRole('option', { name: '待处理', exact: true }).click();
  await query();
  await page.getByLabel('金额 120.00 元', { exact: true }).waitFor();
  const beforeActions = await read();
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
  assert.deepEqual(await read(), beforeActions);
  await page
    .getByRole('button', { name: '重新打开已保存视图', exact: true })
    .click();
  await page.getByRole('cell', { name: 'DEMO-3', exact: true }).waitFor();
  assert.equal((await status().textContent()).trim(), '已处理');
  await reload();
  await page.getByRole('cell', { name: 'DEMO-3', exact: true }).waitFor();
  assert.equal(
    await page.getByRole('cell', { name: 'DEMO-4', exact: true }).count(),
    0,
  );

  // An unset component must survive even though it compiles to MATCH_ALL.
  await page.getByRole('button', { name: /^清空条件值：/ }).click();
  await page.getByRole('cell', { name: 'DEMO-1', exact: true }).waitFor();
  await save();
  await page.waitForFunction(
    key =>
      !(
        'selectedId' in
        JSON.parse(localStorage.getItem(key)).instances.find(
          item =>
            item.id === 'pending' &&
            item.ownerKey === 'storybook:local-view-host',
        ).config.filters.root.props
      ),
    key,
  );
  await reload();
  await page.getByRole('cell', { name: 'DEMO-1', exact: true }).waitFor();
  assert.equal((await status().textContent()).trim(), '不限');
  assert.equal(await editor().inputValue(), '只能从组件属性恢复的标签');

  let manager = await openManager();
  assert.equal(
    await manager
      .getByRole('button', { name: '编辑全部订单名称', exact: true })
      .count(),
    0,
  );
  assert.equal(
    await manager
      .getByRole('button', { name: '删除全部订单', exact: true })
      .count(),
    0,
  );
  await manager
    .getByRole('button', { name: '编辑待处理订单名称', exact: true })
    .click();
  await manager
    .getByRole('textbox', { name: '待处理订单名称', exact: true })
    .fill('本地验证视图');
  await manager
    .getByRole('button', { name: '保存待处理订单名称', exact: true })
    .click();
  await manager
    .getByRole('button', { name: '编辑本地验证视图名称', exact: true })
    .waitFor();
  await closeManager(manager);
  await page.getByRole('button', { name: '视图选项', exact: true }).click();
  await page.getByRole('menuitem', { name: '另存为', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '另存为视图', exact: true });
  await dialog
    .getByRole('textbox', { name: '视图名称', exact: true })
    .fill('可恢复副本');
  await dialog.getByRole('button', { name: '创建视图', exact: true }).click();
  await page.waitForFunction(
    key =>
      JSON.parse(localStorage.getItem(key)).instances.some(
        item => item.title === '可恢复副本',
      ),
    key,
  );
  const copy = (await read()).instances.find(
    item => item.title === '可恢复副本',
  );
  manager = await openManager();
  await manager
    .getByRole('button', { name: '拖动调整可恢复副本顺序', exact: true })
    .press('ArrowUp');
  await page.waitForFunction(
    ({ key, id }) =>
      JSON.parse(localStorage.getItem(key)).users['storybook:local-view-host']
        .order[0] === id,
    { key, id: copy.id },
  );
  await closeManager(manager);
  await reload();
  manager = await openManager();
  assert.deepEqual(
    await manager
      .getByRole('list', { name: '个人视图顺序' })
      .getByRole('listitem')
      .evaluateAll(items => items.map(item => item.getAttribute('aria-label'))),
    ['可恢复副本', '本地验证视图'],
  );
  await manager
    .getByRole('button', { name: '删除可恢复副本', exact: true })
    .click();
  await page
    .getByRole('dialog', { name: '删除视图', exact: true })
    .getByRole('button', { name: '删除视图', exact: true })
    .click();
  await page.waitForFunction(
    ({ key, id }) =>
      !JSON.parse(localStorage.getItem(key)).instances.some(
        item => item.id === id,
      ),
    { key, id: copy.id },
  );
  await closeManager(manager);
  await reload();
  manager = await openManager();
  assert.equal(
    await manager
      .getByRole('button', { name: '编辑可恢复副本名称', exact: true })
      .count(),
    0,
  );
  await manager
    .getByRole('button', { name: '编辑本地验证视图名称', exact: true })
    .waitFor();
  assert.deepEqual(errors, []);
  console.log(
    JSON.stringify({
      passed: true,
      browser: browser.version(),
      checks: [
        'saved JSON -> fresh host/engine -> custom component',
        'unset props remain recoverable',
        'all five runtime extension types',
        'business records excluded from view storage',
        'rename/create/order/delete survive reload',
        'system view controls protected',
      ],
    }),
  );
} finally {
  await browser.close();
}
