/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */

// The performance pages' figures, drawn as SVG from the benchmark reports' headline files
// (test/benchmarks/results/reports/<report>/headline.json) when the site is built, so a figure shows what its
// report holds and nothing typed by hand. PerfFigure.vue renders FIGURES[name] in the page's language.
//
// - A figure draws one report, and every figure it draws must share one build, CPU and JDK; its caption names
//   them. Nothing in a figure is compared across builds.
// - A figure the report does not hold, or a set that differs in build, CPU or JDK, stops the site's build.
// - Each figure carries its table, so every value it draws can be read without the chart.

const SERIES = { postgres: 1, mongo: 2 };
const STORES = { postgres: 'PostgreSQL', mongo: 'MongoDB' };

const WORDS = {
  en: {
    rate: 'Answered rate (/s)',
    p99: 'p99 (ms, log scale)',
    sustainable: 'sustainable',
    clients: 'clients',
    refused: 'refused',
    sustainableStep: 'sustainable step',
    refusedStep: 'more than 1% refused',
    ratio: 'Wow ÷ Axon (log scale)',
    notCompared: 'not compared: a data check failed',
    table: 'The figure as a table',
    build: (commit, cpu, jdk) => `Build ${commit}, ${cpu}, JDK ${jdk}.`,
    measure: { peak: 'peak', sustainable: 'sustainable' },
    keys: { uniform: '', zipf: ', Zipf keys', hot: ', one hot account' },
    pools: ', both pools 32',
    columns: {
      sweep: ['Store', 'Scenario', 'Clients', 'Answered/s', 'p50 ms', 'p99 ms', 'Refused'],
      ratio: ['Case', 'Wow ÷ Axon', 'A/A band', 'Verdict', 'Wow /s', 'Axon /s'],
    },
  },
  zh: {
    rate: '应答速率（/s）',
    p99: 'p99（ms，对数刻度）',
    sustainable: '可持续',
    clients: '客户端',
    refused: '拒绝',
    sustainableStep: '可持续的那一级',
    refusedStep: '拒绝超过 1%',
    ratio: 'Wow ÷ Axon（对数刻度）',
    notCompared: '未比较：数据校验未通过',
    table: '图中数据（表格）',
    build: (commit, cpu, jdk) => `构建 ${commit}，${cpu}，JDK ${jdk}。`,
    measure: { peak: '峰值', sustainable: '可持续' },
    keys: { uniform: '', zipf: '，Zipf 键', hot: '，单一热点账户' },
    pools: '，两侧连接池均为 32',
    columns: {
      sweep: ['存储', '场景', '客户端', '应答/s', 'p50 ms', 'p99 ms', '拒绝'],
      ratio: ['用例', 'Wow ÷ Axon', 'A/A 区间', '结论', 'Wow /s', 'Axon /s'],
    },
  },
};

/**
 * A CPU model as a reader names it: `AMD EPYC 7763 64-Core Processor` is `EPYC 7763`, `Intel(R) Xeon(R) 6975P-C` and
 * `INTEL(R) XEON(R) PLATINUM 8573C` are `Xeon 6975P-C` and `Xeon 8573C`. The pages' tables and spans name it the same
 * way (docs-performance.mjs).
 */
export const cpuModel = cpu =>
  String(cpu)
    .replace(/^AMD\s+/, '')
    .replace(/\s+\d+-Core Processor$/, '')
    .replace(/^Intel\(R\) Xeon\(R\)(\s+Platinum)?\s+/i, 'Xeon ')
    .replace(/\s+CPU\s+@.*$/, '');

/**
 * The figures a page can show, by name. `sweep` draws closed-loop sweeps as the answered rate against p99, a panel a
 * scenario and a line a store, with the 200 ms line. `ratio` draws Wow ÷ Axon as a range a case, inside its A/A band.
 */
export const FIGURES = {
  'm5max-sweep': {
    kind: 'sweep',
    report: 'm5max-snapshot',
    panels: [
      ['sweep.postgres.1.e1-sweep', 'sweep.mongo.1.e1-sweep'],
      ['sweep.postgres.1.e3-sweep', 'sweep.mongo.1.e3-sweep'],
    ],
  },
  'actions-axon': {
    kind: 'ratio',
    report: 'actions-axon',
    rows: [
      'axon.axon-pg.100k.e1.peak',
      'axon.axon-pg.100k.e1.sustainable',
      'axon.axon-pg.100k.e3.peak',
      'axon.axon-pg.100k.e3.sustainable',
    ],
  },
  'm5max-axon': {
    kind: 'ratio',
    report: 'm5max-axon',
    rows: [
      'axon.m5max-axon-peak-e1.e1.peak',
      'axon.m5max-axon-peak-e1.e1.sustainable',
      'axon.m5max-axon-peak-e3.e3.peak',
      'axon.m5max-axon-peak-e3.e3.sustainable',
      'axon.m5max-axon-pool-32-e1.e1.peak',
      'axon.m5max-axon-pool-32-e3.e3.peak',
      'axon.m5max-axon-contention-e1-zipf.e1-zipf.peak',
      'axon.m5max-axon-contention-e1-hot.e1-hot.peak',
    ],
  },
};

const escape = text =>
  String(text).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const grouped = (value, digits = 1) =>
  Number(value).toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits });
const compact = value => (value >= 1000 ? `${Number((value / 1000).toFixed(1))}K` : String(value));
const round = value => Math.round(value * 10) / 10;
const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];

/** The figures [ids] of [report]'s headline, refusing a missing one, or a set that differs in build, CPU or JDK. */
export function figuresOf(headlines, report, ids, name) {
  const headline = headlines[report];
  if (!headline) throw new Error(`figure ${name}: no headline for the report ${report}`);
  const figures = ids.map(id => {
    const figure = headline.figures.find(f => f.id === id);
    if (!figure) throw new Error(`figure ${name}: ${report} holds no figure ${id}`);
    return figure;
  });
  for (const field of ['commit', 'cpu', 'jdk']) {
    const values = [...new Set(figures.map(f => f[field]))];
    if (values.length > 1) throw new Error(`figure ${name}: its figures differ in ${field} (${values.join(', ')})`);
  }
  return figures;
}

const logScale = (lo, hi, from, to) => value =>
  from + ((Math.log10(value) - Math.log10(lo)) / (Math.log10(hi) - Math.log10(lo))) * (to - from);
const linearScale = (lo, hi, from, to) => value => from + ((value - lo) / (hi - lo)) * (to - from);
const TICKS = [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000];
const ticksIn = (lo, hi) => TICKS.filter(t => t >= lo && t <= hi);
/** Round ticks from 0 past [value]: a step of 1, 2 or 5 times a power of ten, at most five steps. */
const niceTicks = value => {
  const power = 10 ** Math.floor(Math.log10(value));
  const step = [0.1, 0.2, 0.5, 1, 2, 5, 10].map(m => m * power).find(s => Math.ceil(value / s) <= 5);
  return Array.from({ length: Math.ceil(value / step) + 1 }, (_, i) => i * step);
};

const svg = (width, height, label, body) =>
  `<svg class="perf-chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="${escape(label)}" ` +
  `xmlns="http://www.w3.org/2000/svg">${body}</svg>`;

const text = (cls, x, y, content, anchor = 'start') =>
  `<text class="${cls}" x="${round(x)}" y="${round(y)}" text-anchor="${anchor}">${escape(content)}</text>`;

const hline = (cls, x1, x2, y) =>
  `<line class="${cls}" x1="${round(x1)}" x2="${round(x2)}" y1="${round(y)}" y2="${round(y)}"/>`;

function sweepPanel(figures, words) {
  const [W, H, L, R, T, B] = [360, 290, 56, 16, 30, 46];
  const steps = figures.flatMap(f => f.steps);
  const xTicks = niceTicks(Math.max(...steps.map(s => s.value)));
  const xMax = xTicks.at(-1);
  const yLo = 10 ** Math.floor(Math.log10(Math.min(...steps.map(s => s.p99Ms))));
  const yHi = 10 ** Math.ceil(Math.log10(Math.max(200, ...steps.map(s => s.p99Ms))));
  const x = linearScale(0, xMax, L, W - R);
  const y = logScale(yLo, yHi, H - B, T);
  let body = text('perf-title', L, 18, figures[0].scenario);
  for (const t of ticksIn(yLo, yHi))
    body += hline('perf-grid', L, W - R, y(t)) + text('perf-tick', L - 6, y(t) + 4, grouped(t, 0), 'end');
  for (const t of xTicks) body += text('perf-tick', x(t), H - B + 16, compact(t), 'middle');
  body += hline('perf-axis', L, W - R, H - B);
  body += hline('perf-limit', L, W - R, y(200)) + text('perf-note', L + 4, y(200) - 5, '200 ms');
  body += text('perf-tick', (L + W - R) / 2, H - 8, words.rate, 'middle');
  body +=
    `<text class="perf-tick" transform="translate(14 ${round((T + H - B) / 2)}) rotate(-90)" ` +
    `text-anchor="middle">${escape(words.p99)}</text>`;
  for (const figure of figures) {
    const series = `perf-series-${SERIES[figure.store]}`;
    const points = figure.steps.map(s => `${round(x(s.value))},${round(y(s.p99Ms))}`).join(' ');
    body += `<polyline class="perf-line ${series}" points="${points}"/>`;
    for (const step of figure.steps) {
      const refused = step.failedShare > 0.01;
      const title =
        `${STORES[figure.store]}, ${step.clients} ${words.clients}: ${grouped(step.value)}/s, p99 ${step.p99Ms} ms` +
        (refused ? `, ${grouped(step.failedShare * 100)}% ${words.refused}` : '');
      body +=
        `<circle class="perf-dot ${series}${refused ? ' perf-hollow' : ''}" cx="${round(x(step.value))}" ` +
        `cy="${round(y(step.p99Ms))}" r="4"><title>${escape(title)}</title></circle>`;
    }
    const best = figure.steps.find(s => s.clients === figure.sustainable?.clients);
    if (best)
      body +=
        `<circle class="perf-dot perf-best ${series}" cx="${round(x(best.value))}" cy="${round(y(best.p99Ms))}" r="6">` +
        `<title>${escape(`${STORES[figure.store]}, ${words.sustainable}: ${grouped(best.value)}/s, p99 ${best.p99Ms} ms`)}</title></circle>`;
  }
  const key = figures
    .filter(f => f.sustainable)
    .map(f => `${STORES[f.store]} ${grouped(f.sustainable.value)}/s`)
    .join(' · ');
  return (
    `<div class="perf-panel">${svg(W, H, `${figures[0].scenario}: ${words.rate}, ${words.p99}`, body)}` +
    `<p class="perf-key-line">${glyph('perf-best')}${escape(words.sustainable)}: ${escape(key)}</p></div>`
  );
}

/** A marker as the plot draws it, in the text's colour: the legend's glyph for what that marker means. */
const glyph = cls =>
  `<svg class="perf-glyph" viewBox="0 0 16 16" aria-hidden="true">` +
  `<circle class="perf-dot ${cls} perf-neutral" cx="8" cy="8" r="${cls === 'perf-best' ? 6 : 4}"/></svg>`;

const legend = (stores, words) =>
  `<p class="perf-legend">` +
  stores.map(s => `<span><i class="perf-key perf-series-${SERIES[s]}"></i>${STORES[s]}</span>`).join('') +
  `<span>${glyph('perf-best')}${escape(words.sustainableStep)}</span>` +
  `<span>${glyph('perf-hollow')}${escape(words.refusedStep)}</span>` +
  `</p>`;

const table = (columns, rows) =>
  `<table><thead><tr>${columns.map(c => `<th>${escape(c)}</th>`).join('')}</tr></thead><tbody>` +
  rows.map(r => `<tr>${r.map(c => `<td>${escape(c)}</td>`).join('')}</tr>`).join('') +
  `</tbody></table>`;

function sweepFigure(definition, words, headlines, name) {
  const all = figuresOf(headlines, definition.report, definition.panels.flat(), name);
  const panels = definition.panels.map(ids => ids.map(id => all.find(f => f.id === id)));
  const rows = all.flatMap(f =>
    f.steps.map(s => [
      STORES[f.store],
      f.scenario,
      s.clients,
      grouped(s.value),
      s.p50Ms,
      s.p99Ms,
      s.failedShare ? `${grouped(s.failedShare * 100)}%` : '0',
    ]),
  );
  return {
    first: all[0],
    chart:
      legend([...new Set(all.map(f => f.store))], words) +
      `<div class="perf-panels">${panels.map(p => sweepPanel(p, words)).join('')}</div>`,
    data: table(words.columns.sweep, rows),
  };
}

const caseOf = (figure, words) =>
  `${figure.scenario}${words.keys[figure.keys] ?? ''}${figure.suite.includes('pool-32') ? words.pools : ''}, ` +
  words.measure[figure.measure];
const range = figure => `${figure.bound === '≥' ? '≥ ' : ''}${figure.lo}–${figure.hi}`;

function ratioFigure(definition, words, headlines, name) {
  const figures = figuresOf(headlines, definition.report, definition.rows, name);
  const shown = figures.filter(f => f.comparable);
  const lo = Math.min(0.8, ...shown.map(f => f.lo * (1 - f.band)));
  const hi = Math.max(1.25, ...shown.map(f => f.hi * (1 + f.band)));
  // Two layouts: wide, a case's label left of its range; narrow (a phone), the label above it, so the labels stay
  // legible without a scroll that would hide the ranges. The page shows one by its width (PerfFigure.vue).
  const chart = ({ W, L, R, ROW, above }) => {
    const [T, B] = [8, 44];
    const H = T + figures.length * ROW + B;
    const x = logScale(lo, hi, L, W - R);
    let body = '';
    // Each row's centre, and the span its value text takes (about 6.5 units a character at the ticks' size).
    const rowY = i => (above ? T + i * ROW + ROW - 14 : T + i * ROW + ROW / 2);
    const valueSpan = figure => {
      const [from, chars] = figure.comparable
        ? [x(figure.hi * (1 + figure.band)) + 6, range(figure).length]
        : [x(1) + 8, words.notCompared.length];
      return [from - 3, from + chars * 6.5 + 3];
    };
    // A vertical line is drawn a row at a time, within the row's band and never through its value text, so that no
    // line crosses a label: a case's name above its row, or its range beside it.
    const vline = (cls, t) =>
      figures
        .map((figure, i) => {
          const [from, to] = valueSpan(figure);
          if (x(t) >= from && x(t) <= to) return '';
          const cy = rowY(i);
          const [y1, y2] = above ? [cy - 10, cy + 10] : [cy - ROW / 2, cy + ROW / 2];
          return `<line class="${cls}" x1="${round(x(t))}" x2="${round(x(t))}" y1="${y1}" y2="${y2}"/>`;
        })
        .join('');
    for (const t of ticksIn(1, hi)) body += vline('perf-grid', t) + text('perf-tick', x(t), H - B + 16, `${t}×`, 'middle');
    body += vline('perf-axis', 1);
    body += text('perf-tick', (L + W - R) / 2, H - 6, words.ratio, 'middle');
    figures.forEach((figure, i) => {
      const cy = rowY(i);
      body += above
        ? text('perf-tick', L, cy - 16, caseOf(figure, words))
        : text('perf-tick', L - 10, cy + 4, caseOf(figure, words), 'end');
      if (!figure.comparable) {
        body += text('perf-note', x(1) + 8, cy + 4, words.notCompared);
        return;
      }
      const hollow = !figure.verdict.startsWith('wow') ? ' perf-hollow' : '';
      const title = `${caseOf(figure, words)}: ${range(figure)}, ${figure.verdict}`;
      const [b0, b1] = [x(figure.lo * (1 - figure.band)), x(figure.hi * (1 + figure.band))];
      body +=
        `<rect class="perf-band perf-series-1" x="${round(b0)}" y="${cy - 8}" width="${round(b1 - b0)}" ` +
        `height="16" rx="4"/>` +
        hline('perf-range perf-series-1', x(figure.lo), x(figure.hi), cy) +
        [figure.lo, figure.hi]
          .map(
            v =>
              `<circle class="perf-dot perf-series-1${hollow}" cx="${round(x(v))}" cy="${cy}" r="4">` +
              `<title>${escape(title)}</title></circle>`,
          )
          .join('') +
        text('perf-label', b1 + 6, cy + 4, range(figure));
    });
    return svg(W, H, words.ratio, body);
  };
  const charts =
    chart({ W: 720, L: 250, R: 84, ROW: 30, above: false }).replace('class="perf-chart"', 'class="perf-chart perf-wide"') +
    chart({ W: 360, L: 12, R: 84, ROW: 44, above: true }).replace('class="perf-chart"', 'class="perf-chart perf-narrow"');
  const rows = figures.map(f => [
    caseOf(f, words),
    f.comparable ? range(f) : '–',
    `±${grouped(f.band * 100)}%`,
    f.comparable ? f.verdict : words.notCompared,
    grouped(median(f.wow)),
    grouped(median(f.axon)),
  ]);
  return { first: figures[0], chart: charts, data: table(words.columns.ratio, rows) };
}

/** The HTML of figure [name] in [locale] (`en` or `zh`), drawn from [headlines] (each report's headline, by name). */
export function figureHtml(name, locale, headlines) {
  const definition = FIGURES[name];
  if (!definition) throw new Error(`no performance figure named ${name}`);
  const words = WORDS[locale];
  if (!words) throw new Error(`figure ${name}: no words for the locale ${locale}`);
  const draw = definition.kind === 'sweep' ? sweepFigure : ratioFigure;
  const { first, chart, data } = draw(definition, words, headlines, name);
  return (
    `<figure class="perf-figure">${chart}` +
    `<figcaption>${escape(words.build(first.commit, cpuModel(first.cpu), first.jdk))}</figcaption>` +
    `<details><summary>${escape(words.table)}</summary>${data}</details></figure>`
  );
}
