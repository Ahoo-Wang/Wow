/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir, cpus, platform, arch } from 'node:os';
import { join, resolve } from 'node:path';

const require = createRequire(
  new URL('../../../package.json', import.meta.url),
);
const browsers = createRequire(require.resolve('@vitest/browser-playwright'))(
  'playwright',
);
const browserName = process.env.VIEW_ENGINE_BROWSER ?? 'chromium';
assert.ok(['chromium', 'firefox', 'webkit'].includes(browserName));
const channel = process.env.VIEW_ENGINE_BROWSER_CHANNEL;
const browser = await browsers[browserName].launch({
  headless: true,
  ...(browserName === 'chromium' && channel ? { channel } : {}),
});
const artifacts = process.env.VIEW_ENGINE_ARTIFACTS
  ? resolve(process.env.VIEW_ENGINE_ARTIFACTS, browserName)
  : await mkdtemp(join(tmpdir(), `fve-readiness-${browserName}-`));
await mkdir(artifacts, { recursive: true });
const page = await browser.newPage({
  viewport: { width: 1440, height: 1000 },
  reducedMotion: 'reduce',
});
page.setDefaultTimeout(15_000);
const errors = [];
const table = page.getByRole('table', { name: '验收订单', exact: true });
page.on('pageerror', error => errors.push(error.message));
const report = {
  browser: browserName,
  version: browser.version(),
  platform: platform(),
  architecture: arch(),
  cpu: cpus()[0]?.model,
  scale: { rows: 100, columns: 30, filterFields: 100 },
  checks: [],
  timings: {},
  reviewedWarnings: [],
  passed: false,
};
const frame = () =>
  page.evaluate(
    () =>
      new Promise(resolve =>
        requestAnimationFrame(() => requestAnimationFrame(resolve)),
      ),
  );
const metrics = () =>
  page.evaluate(() => ({ ...window.__viewReadiness.metrics }));
const ready = async () => {
  await page.getByRole('cell', { name: 'R-001', exact: true }).waitFor();
  await page.waitForFunction(() => window.__viewReadiness.metrics.active === 0);
  await frame();
};
const refresh = async () => {
  const started = performance.now();
  const before = (await metrics()).queries;
  const observed = performance.now();
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  const clicked = performance.now();
  await page.waitForFunction(
    before =>
      window.__viewReadiness.metrics.queries > before &&
      window.__viewReadiness.metrics.active === 0 &&
      document
        .querySelector('[aria-label="记录结果"]')
        ?.getAttribute('aria-busy') !== 'true',
    before,
  );
  const settled = performance.now();
  await frame();
  return {
    observe: Math.round((observed - started) * 100) / 100,
    click: Math.round((clicked - observed) * 100) / 100,
    settle: Math.round((settled - clicked) * 100) / 100,
    paint: Math.round((performance.now() - settled) * 100) / 100,
  };
};
async function measure(name, operation) {
  await operation(); // Warm the interaction before collecting samples.
  const samples = [];
  const phases = [];
  for (let n = 0; n < 10; n++) {
    const started = performance.now();
    const phaseTimings = await operation();
    samples.push(Math.round((performance.now() - started) * 100) / 100);
    if (phaseTimings) phases.push(phaseTimings);
  }
  const sorted = [...samples].sort((a, b) => a - b);
  const p95 = sorted[Math.ceil(sorted.length * 0.95) - 1];
  report.timings[name] = {
    samples,
    ...(phases.length ? { phases } : {}),
    p95,
    budget: 1000,
    unit: 'ms',
    includes:
      'automation transport and two painted frames; fixture has no network',
  };
}
async function axe(name) {
  // WebKit can start inherited-color transitions on a later painted frame.
  // Poll until settled instead of awaiting only a fixed number of batches.
  const settled = () =>
    page.waitForFunction(
      () =>
        !document
          .getAnimations()
          .some(
            animation =>
              animation.playState === 'running' &&
              animation.effect?.getComputedTiming().endTime !== Infinity,
          ),
    );
  await settled();
  await frame();
  await settled();
  const result = await page.evaluate(async () =>
    window.axe.run(
      '[data-testid="readiness-workbench"], [data-slot="popover-content"]',
      {
        runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21aa'] },
      },
    ),
  );
  await writeFile(
    join(artifacts, `axe-${name}.json`),
    JSON.stringify(result, null, 2),
  );
  const failures = [];
  let reviewedSentinels = false;
  for (const violation of result.violations) {
    for (const node of violation.nodes) {
      const selector = node.target.length === 1 ? node.target[0] : null;
      const guard = selector ? page.locator(selector) : null;
      // Base UI intentionally exposes these WebKit/VoiceOver sentinels; do not patch
      // their semantics or turn off an axe rule. Retain raw findings and test keyboard exit.
      // https://github.com/mui/base-ui/issues/5237 (upstream: expected behavior)
      const sentinel =
        browserName === 'webkit' &&
        violation.id === 'aria-command-name' &&
        guard &&
        (await guard.count()) === 1 &&
        (await guard.evaluate(
          element =>
            element.matches(
              'span[data-base-ui-focus-guard][role="button"][tabindex="0"]',
            ) && getComputedStyle(element).clipPath === 'inset(50%)',
        ));
      if (!sentinel) {
        failures.push({
          id: violation.id,
          target: node.target,
          summary: node.failureSummary,
        });
        continue;
      }
      reviewedSentinels = true;
      report.reviewedWarnings.push({
        scenario: name,
        rule: violation.id,
        target: node.target,
        reason:
          'Base UI WebKit focus sentinel; keyboard entry/exit and Escape checked; VoiceOver not certified',
        upstream: 'https://github.com/mui/base-ui/issues/5237',
      });
    }
  }
  assert.deepEqual(failures, [], `Accessibility: ${name}`);
  if (reviewedSentinels) {
    const picker = page.getByRole('dialog').filter({ hasText: '选择筛选字段' });
    const last = picker.getByRole('checkbox').last();
    await last.focus();
    await last.press('Tab');
    await picker.waitFor({ state: 'hidden' });
    assert.equal(
      await page
        .getByRole('button', { name: '清空条件', exact: true })
        .evaluate(node => node === document.activeElement),
      true,
      'Tab after final field returns to the next workbench control',
    );
    await page
      .getByRole('button', { name: '添加筛选', exact: true })
      .press('Enter');
    await picker.waitFor();
  }
}
try {
  const base = process.env.VIEW_ENGINE_E2E_BASE_URL ?? 'http://127.0.0.1:6006';
  await page.goto(
    new URL(
      '/iframe.html?id=development-view-engine-readiness--workbench&viewMode=story',
      base,
    ).href,
  );
  await ready();
  const axeRequire = createRequire(
    require.resolve('@storybook/addon-a11y/package.json'),
  );
  await page.addScriptTag({ path: axeRequire.resolve('axe-core/axe.min.js') });
  assert.equal(await table.locator('tbody tr').count(), 100);
  assert.ok(
    (await metrics()).cellCommits >= 100,
    'Initial data cells must record commits in the production build',
  );
  assert.equal(await table.locator('thead th').count(), 31);
  assert.equal(
    await table
      .getByRole('link', { name: '客户 1', exact: true })
      .getAttribute('href'),
    '/customers/1',
  );
  assert.equal(
    await table
      .locator('tbody tr')
      .first()
      .getByText('¥0.00', { exact: true })
      .count(),
    1,
  );
  report.checks.push(
    '100 rows/30 columns, nested own paths and built-in link/number/status/tags/date cells',
  );

  const amount = page.getByRole('textbox', { name: '金额值', exact: true });
  const beforeDraft = await metrics();
  await amount.fill('10');
  await frame();
  assert.equal(
    (await metrics()).queries,
    beforeDraft.queries,
    'Draft edit must not query',
  );
  assert.equal(
    (await metrics()).cellCommits,
    beforeDraft.cellCommits,
    'Draft edit must not rerender data cells',
  );
  await amount.press('Enter');
  await page.getByRole('cell', { name: 'R-002', exact: true }).waitFor();
  await page
    .getByRole('cell', { name: 'R-001', exact: true })
    .waitFor({ state: 'hidden' });
  await amount.fill('0');
  await amount.press('Enter');
  await ready();
  report.checks.push(
    'Enter queries; draft editing leaves applied results and cell commits unchanged',
  );

  await measure('refresh', refresh);
  await ready();
  const rowSelection = page.getByRole('checkbox', {
    name: '选择记录 R-001',
    exact: true,
  });
  await measure('selection', async () => {
    const checked = await rowSelection.isChecked();
    await rowSelection.focus();
    await rowSelection.press('Space');
    await frame();
    assert.equal(
      await rowSelection.isChecked(),
      !checked,
      'Space toggles the selected row',
    );
  });
  if (await rowSelection.isChecked()) await rowSelection.uncheck();
  const add = page.getByRole('button', { name: '添加筛选', exact: true });
  await measure('fieldPicker', async () => {
    const phases = {};
    let previous = performance.now();
    const mark = name => {
      const now = performance.now();
      phases[name] = Math.round((now - previous) * 100) / 100;
      previous = now;
    };
    await add.click();
    mark('click');
    await page
      .getByRole('dialog')
      .filter({ hasText: '选择筛选字段' })
      .waitFor();
    mark('visible');
    await frame();
    mark('paintedFrames');
    await page.keyboard.press('Escape');
    mark('escape');
    await page
      .getByRole('dialog')
      .filter({ hasText: '选择筛选字段' })
      .waitFor({ state: 'hidden' });
    mark('hidden');
    return phases;
  });
  for (const [name, { p95, budget }] of Object.entries(report.timings)) {
    assert.ok(p95 <= budget, `${name} p95 ${p95}ms exceeds ${budget}ms`);
  }
  report.checks.push(
    'Warm no-network interaction p95 within 1000ms regression ceiling',
  );

  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    for (const theme of ['light', 'dark']) {
      if (
        (await page
          .getByTestId('readiness-workbench')
          .getAttribute('data-theme')) !== theme
      )
        await page.getByRole('button', { name: '切换验收主题' }).click();
      await frame();
      assert.ok(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
        'Page must not overflow horizontally',
      );
      await axe(`${theme}-${width}`);
      await page.screenshot({ path: join(artifacts, `${theme}-${width}.png`) });
      const top = (await table.boundingBox()).y;
      await add.click();
      const picker = page
        .getByRole('dialog')
        .filter({ hasText: '选择筛选字段' });
      await picker.waitFor();
      assert.equal(await picker.getByRole('checkbox').count(), 100);
      assert.equal(
        Math.round((await table.boundingBox()).y),
        Math.round(top),
        'Field picker must not push the table down',
      );
      const bounds = await picker.boundingBox();
      assert.ok(
        bounds.x >= 0 &&
          bounds.x + bounds.width <= width + 1 &&
          bounds.y >= 0 &&
          bounds.y + bounds.height <= 1001,
        'Picker must fit viewport',
      );
      await page.screenshot({
        path: join(artifacts, `fields-${theme}-${width}.png`),
      });
      await axe(`fields-${theme}-${width}`);
      const beforeToggle = await metrics();
      const customer = picker.getByRole('checkbox', {
        name: '客户',
        exact: true,
      });
      await customer.focus();
      await customer.press('Space');
      await page
        .getByRole('textbox', { name: '客户值', exact: true })
        .waitFor();
      assert.equal(
        (await metrics()).queries,
        beforeToggle.queries,
        'Choosing an unset field must not query',
      );
      await customer.focus();
      await customer.press('Space');
      await page
        .getByRole('textbox', { name: '客户值', exact: true })
        .waitFor({ state: 'hidden' });
      await page.keyboard.press('Escape');
      await picker.waitFor({ state: 'hidden' });
      assert.equal(
        await add.evaluate(node => node === document.activeElement),
        true,
        'Escape restores trigger focus',
      );
    }
  }
  report.checks.push(
    'Light/dark 1440/390px: no unreviewed axe WCAG A/AA violations including portaled dialogs; raw findings retained; viewport bounds, checkbox keyboard toggling and Escape focus',
  );

  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.evaluate(() => {
    window.__viewReadiness.control.fail = true;
  });
  await refresh();
  await page.getByRole('alert', { name: '查询失败' }).waitFor();
  await page.screenshot({ path: join(artifacts, 'query-failure.png') });
  await page.getByRole('button', { name: '重试查询', exact: true }).click();
  await ready();
  await page.evaluate(() => {
    window.__viewReadiness.control.empty = true;
  });
  await refresh();
  await page.getByRole('img', { name: '暂无记录' }).waitFor();
  await page.screenshot({ path: join(artifacts, 'empty.png') });
  await page.evaluate(() => {
    window.__viewReadiness.control.empty = false;
  });
  await refresh();
  await ready();
  report.checks.push(
    'Failure is distinct from empty; retry restores usable records',
  );

  await page.getByRole('button', { name: '卸载视图' }).click();
  await page.waitForFunction(
    () =>
      window.__viewReadiness.metrics.subscriptions === 0 &&
      window.__viewReadiness.metrics.active === 0,
  );
  await page.evaluate(() => {
    window.__viewReadiness.control.slow = true;
  });
  await page.getByRole('button', { name: '挂载视图' }).click();
  const loading = page.getByRole('status', {
    name: '正在加载记录',
    exact: true,
  });
  await loading.waitFor();
  assert.equal(
    await page
      .getByLabel('记录结果', { exact: true })
      .getAttribute('aria-busy'),
    'true',
  );
  assert.equal(
    await page.getByRole('img', { name: '暂无记录' }).count(),
    0,
    'Loading is not an empty result',
  );
  await page.screenshot({ path: join(artifacts, 'loading.png') });
  await ready();
  await loading.waitFor({ state: 'hidden' });
  assert.notEqual(
    await page
      .getByLabel('记录结果', { exact: true })
      .getAttribute('aria-busy'),
    'true',
  );
  report.checks.push(
    'Slow query shows Spin/busy rather than empty and resolves to readable rows',
  );
  await page.getByRole('button', { name: '卸载视图' }).click();
  await page.waitForFunction(
    () =>
      window.__viewReadiness.metrics.active === 0 &&
      window.__viewReadiness.metrics.subscriptions === 0,
  );
  for (let n = 0; n < 20; n++) {
    const before = await metrics();
    await page.getByRole('button', { name: '挂载视图' }).click();
    await page.waitForFunction(
      () => window.__viewReadiness.metrics.active === 1,
    );
    await page.getByRole('button', { name: '卸载视图' }).click();
    await page.waitForFunction(
      () =>
        window.__viewReadiness.metrics.active === 0 &&
        window.__viewReadiness.metrics.subscriptions === 0,
    );
    assert.equal((await metrics()).aborted, before.aborted + 1);
  }
  report.checks.push(
    '20 mount/dispose cycles cancel pending reads and release every host permission subscription',
  );
  await page.evaluate(() => {
    window.__viewReadiness.control.slow = false;
  });
  await page.getByRole('button', { name: '挂载视图' }).click();
  await ready();
  assert.deepEqual(errors, []);
  report.passed = true;
} catch (error) {
  report.error = error.stack ?? String(error);
  await page
    .screenshot({ path: join(artifacts, 'failure.png') })
    .catch(() => {});
  throw error;
} finally {
  report.pageErrors = errors;
  await writeFile(
    join(artifacts, 'readiness.json'),
    JSON.stringify(report, null, 2),
  );
  console.log(JSON.stringify({ ...report, artifacts }));
  await browser.close();
}
