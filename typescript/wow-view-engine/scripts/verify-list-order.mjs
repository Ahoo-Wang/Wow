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
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { chromium } from 'playwright';
const origin = process.env.VIEW_ENGINE_E2E_BASE_URL ?? 'http://127.0.0.1:6006';
const artifacts =
  process.env.VIEW_ENGINE_ARTIFACTS ?? '/tmp/view-engine-list-order';
await mkdir(artifacts, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  ...(process.env.VIEW_ENGINE_BROWSER_CHANNEL
    ? { channel: process.env.VIEW_ENGINE_BROWSER_CHANNEL }
    : {}),
});
const results = [];
async function run(name, story, exercise, options = {}) {
  const page = await browser.newPage({
    viewport: { width: 1100, height: 1000 },
    ...options,
  });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.goto(
      `${origin}/iframe.html?id=view-engine-list-order--${story}&viewMode=story`,
    );
    await page.getByRole('list', { name: '主列表' }).waitFor();
    await exercise(page);
    await page.waitForFunction(
      () => !document.querySelector('[data-dragging]'),
    );
    assert.deepEqual(errors, [], `${name}: browser errors`);
    await page.waitForFunction(
      () =>
        [...document.querySelectorAll('[aria-label="主列表"] [data-item]')]
          .map(node => node.dataset.item)
          .join(',') ===
        document.querySelector('[data-result="order"]').textContent,
    );
    const order = await page.locator('[data-result="order"]').textContent();
    const dom = await page
      .getByRole('list', { name: '主列表' })
      .locator('[data-item]')
      .evaluateAll(nodes => nodes.map(node => node.dataset.item).join(','));
    assert.equal(dom, order, `${name}: DOM must reflect authoritative data`);
    assert.equal(
      await page.locator('[data-drop-target]').count(),
      0,
      'Drop highlight must clear',
    );
    results.push({ name, order, status: 'passed' });
    console.log(`${name}: passed`);
  } catch (error) {
    await page.screenshot({ path: join(artifacts, `list-order-${name}.png`) });
    throw error;
  } finally {
    await page.close();
  }
}
async function output(page, order, commits) {
  await page.waitForFunction(
    ({ order, commits }) =>
      document.querySelector('[data-result="order"]').textContent === order &&
      document.querySelector('[data-result="commits"]').textContent ===
        String(commits),
    { order, commits },
  );
}
async function start(page) {
  const handle = page.getByRole('button', { name: '移动甲', exact: true });
  await handle.hover();
  const box = await handle.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 12, box.y + box.height / 2, {
    steps: 3,
  });
  await page.waitForSelector('[data-dragging]');
}
async function over(page, id) {
  const box = await page
    .getByRole('list', { name: '主列表' })
    .locator(`[data-item="${id}"]`)
    .last()
    .boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, {
    steps: 12,
  });
  await page.waitForTimeout(100);
}
async function external(page, action) {
  await page
    .locator(`[data-action="${action}"]`)
    .evaluate(button => button.click());
}
try {
  await run('pointer', 'pointer', async page => {
    await start(page);
    await over(page, 'b');
    await output(page, 'a,b,c,fixed', 0);
    await page.mouse.up();
    await output(page, 'b,a,c,fixed', 1);
  });
  await run('wrapped', 'wrapped', async page => {
    await start(page);
    await over(page, 'c');
    await page.mouse.up();
    await output(page, 'b,c,a,fixed', 1);
  });
  await run('locked', 'pointer', async page => {
    await start(page);
    await over(page, 'b');
    await over(page, 'fixed');
    await page.mouse.up();
    await output(page, 'a,b,c,fixed', 0);
  });
  for (const key of ['Space', 'Enter'])
    await run(`keyboard-${key}`, 'pointer', async page => {
      await page.getByRole('button', { name: '移动甲', exact: true }).focus();
      await page.keyboard.press(key);
      await page.waitForSelector('[data-dragging]');
      await page.waitForSelector('[data-dnd-dragging]');
      await page.keyboard.press('ArrowDown');
      await page.waitForSelector('[data-drop-target]');
      await output(page, 'a,b,c,fixed', 0);
      await page.keyboard.press(key);
      await output(page, 'b,a,c,fixed', 1);
      assert.equal(
        await page
          .getByRole('button', { name: '移动甲', exact: true })
          .evaluate(node => node === document.activeElement),
        true,
      );
    });
  await run('escape', 'pointer', async page => {
    await start(page);
    await over(page, 'b');
    await page.keyboard.press('Escape');
    await page.mouse.up();
    await output(page, 'a,b,c,fixed', 0);
  });
  for (const action of ['permission', 'owner', 'remove', 'reorder'])
    await run(`invalidate-${action}`, 'pointer', async page => {
      await start(page);
      await over(page, 'b');
      await external(page, action);
      await page.waitForFunction(
        () => !document.querySelector('[data-dragging]'),
      );
      if (action === 'permission') await external(page, action);
      await page.mouse.up();
      await output(
        page,
        action === 'remove'
          ? 'b,c,fixed'
          : action === 'reorder'
            ? 'fixed,c,b,a'
            : 'a,b,c,fixed',
        0,
      );
    });
  await run('keyboard-remove', 'pointer', async page => {
    await page.getByRole('button', { name: '移动甲', exact: true }).focus();
    await page.keyboard.press('Space');
    await page.waitForSelector('[data-dnd-dragging]');
    await external(page, 'remove');
    await page.waitForFunction(
      () => document.activeElement?.getAttribute('aria-label') === '移动乙',
      null,
      { timeout: 3000 },
    );
    await output(page, 'b,c,fixed', 0);
  });
  await run('latest-title', 'pointer', async page => {
    await start(page);
    await external(page, 'title');
    await over(page, 'b');
    await page.mouse.up();
    await output(page, 'b,a,c,fixed', 1);
    assert.equal(
      await page.getByRole('textbox', { name: 'a标题' }).inputValue(),
      '更新后的甲',
    );
  });
  await run('async-failure', 'async-failure', async page => {
    await start(page);
    await over(page, 'b');
    await page.mouse.up();
    await page.getByRole('alert').filter({ hasText: '顺序保存失败' }).waitFor();
    await output(page, 'a,b,c,fixed', 0);
  });
  await run('portal-cancel', 'portal', async page => {
    const handle = page.getByRole('button', { name: '移动甲', exact: true });
    await handle.focus();
    await page.keyboard.press('Space');
    await page.waitForSelector('[data-dnd-dragging]');
    await page.keyboard.press('ArrowDown');
    await page.waitForSelector('[data-drop-target]');
    await page.keyboard.press('Escape');
    assert.equal(
      await page.getByRole('dialog', { name: '排序设置' }).isVisible(),
      true,
      'Escape cancels sorting before closing the dialog',
    );
    await output(page, 'a,b,c,fixed', 0);
  });
  await run('portal-pointer', 'portal', async page => {
    await start(page);
    await over(page, 'b');
    await page.mouse.up();
    await output(page, 'b,a,c,fixed', 1);
  });
  await run('scroll', 'scroll', async page => {
    const list = page.getByRole('list', { name: '主列表' });
    await start(page);
    const box = await list.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height - 10, {
      steps: 10,
    });
    await page.waitForFunction(
      () => document.querySelector('[aria-label="主列表"]').scrollTop >= 70,
    );
    await over(page, 'c');
    await page.mouse.up();
    await output(page, 'b,c,a,fixed', 1);
  });
  await run('async-success', 'async-success', async page => {
    await start(page);
    await over(page, 'b');
    await page.mouse.up();
    await output(page, 'b,a,c,fixed', 1);
  });
  await run(
    'touch',
    'pointer',
    async page => {
      const session = await page.context().newCDPSession(page);
      const box = await page
        .getByRole('button', { name: '移动甲', exact: true })
        .boundingBox();
      const x = box.x + box.width / 2,
        y = box.y + box.height / 2;
      await session.send('Input.dispatchTouchEvent', {
        type: 'touchStart',
        touchPoints: [{ x, y }],
      });
      await page.waitForTimeout(350);
      await session.send('Input.dispatchTouchEvent', {
        type: 'touchMove',
        touchPoints: [{ x: x + 12, y }],
      });
      await page.waitForSelector('[data-dnd-dragging]');
      const target = await page.locator('[data-item="b"]').boundingBox();
      for (let step = 1; step <= 10; step++)
        await session.send('Input.dispatchTouchEvent', {
          type: 'touchMove',
          touchPoints: [
            { x, y: y + ((target.y + target.height / 2 - y) * step) / 10 },
          ],
        });
      await session.send('Input.dispatchTouchEvent', {
        type: 'touchEnd',
        touchPoints: [],
      });
      await output(page, 'b,a,c,fixed', 1);
    },
    { hasTouch: true, isMobile: true },
  );
  const analysis = await browser.newPage({
    viewport: { width: 1440, height: 1000 },
  });
  try {
    await analysis.goto(
      `${origin}/iframe.html?id=${encodeURIComponent('view-engine-分析视图--mixed')}&viewMode=story`,
    );
    await analysis
      .getByRole('button', { name: '配置查询', exact: true })
      .click();
    const editor = analysis.getByRole('dialog', { name: '配置查询' });
    const labels = () =>
      editor.getByRole('button', { name: /^编辑指标/ }).allTextContents();
    const before = await labels();
    await editor
      .getByRole('button', { name: '排序指标 1', exact: true })
      .press('Space');
    await analysis.waitForSelector('[data-dnd-dragging]');
    await analysis.keyboard.press('ArrowRight');
    await analysis.waitForSelector('[data-drop-target]');
    await editor.getByRole('button', { name: '查看结果', exact: true }).click();
    await editor.waitFor({ state: 'hidden' });
    await analysis.waitForFunction(
      () => !document.querySelector('[data-dnd-dragging]'),
    );
    await analysis.keyboard.press('Space');
    if (!(await editor.isVisible()))
      await analysis
        .getByRole('button', { name: '配置查询', exact: true })
        .click();
    assert.deepEqual(
      await labels(),
      before,
      'Closing a kept-mounted editor must cancel its ordering',
    );
    results.push({ name: 'analysis-hidden-cancel', status: 'passed' });
    console.log('analysis-hidden-cancel: passed');
  } catch (error) {
    await analysis.screenshot({
      path: join(artifacts, 'list-order-analysis-hidden-cancel.png'),
    });
    throw error;
  } finally {
    await analysis.close();
  }
  await writeFile(
    join(artifacts, 'list-order.json'),
    JSON.stringify(results, null, 2) + '\n',
  );
} finally {
  await browser.close();
}
