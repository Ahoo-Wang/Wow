/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  anomalyTable,
  bench,
  labelOf,
  num,
  quantile,
  readJsonIfThere,
} from './bench-common.mjs';

// bench.sh's sink programs, of D412 and D413 (d412-d413-design.md): `clickhouse-insert`, what computed columns cost a
// ClickHouse insert a row, each column's own JSONExtract against one into a tuple, and whether a skip index holds
// under FINAL; `sink`, the parts and inserts the ClickHouse sink makes of E1's commits.

/** The programs of this module, as a `measure` suite names them. */
export const SINK_PROGRAMS = ['clickhouse-insert', 'sink'];

/**
 * clickhouse-insert's settings: the rows of an insert, its blocks (the sink's batch), the inserts an arm, the column
 * counts, and D412 §2's bound: the tuple form only if per column costs more than `ratio` times the tuple's extra a row
 * at `columns` columns, and more than `micros` µs a row.
 */
export const INSERT = {
  rows: 200000,
  block: 1000,
  repeats: 5,
  widths: [5, 20, 50],
  bound: { columns: 20, ratio: 2, micros: 5 },
};

/**
 * sink's settings: E1's rate a second and its seconds, and the scenarios: the commits spread over many aggregates,
 * over a few hot ones, and over many with half of them to one.
 */
export const SINK = {
  rate: 200,
  seconds: 60,
  scenarios: [
    { name: 'spread', accounts: 1000, distribution: 'uniform' },
    { name: 'hot', accounts: 10, distribution: 'uniform' },
    {
      name: 'mixed',
      accounts: 1000,
      distribution: 'hot',
      hotFraction: 0.5,
      hotKeys: 1,
    },
  ],
};

/** The sink's tables of the benchmark's account, in the order the tables show them. */
const SINK_TABLES = [
  'transfer_account_state_events',
  'transfer_account_state',
  'transfer_account_events',
];

/** The `sink` group's suites, on [arm]: the head's build on PostgreSQL. */
export function sinkSuites(arm) {
  return {
    'ch-insert': {
      group: 'sink',
      kind: 'measure',
      measure: 'clickhouse-insert',
      arms: [arm],
      answers:
        "What computed columns cost a ClickHouse insert (D412): N = 5, 20 and 50 columns read from a state's JSON text, each by its own JSONExtract or all by one into a tuple, inserted into the table and through a materialized view into its TO table, as the sink fills `_state`; and whether a skip index is used under FINAL, and still reads the newest row. The per-column or tuple choice is read against D412's bound.",
    },
    'sink-ch': {
      group: 'sink',
      kind: 'measure',
      measure: 'sink',
      arms: [arm],
      answers:
        "The ClickHouse sink's parts and inserts (D413's before): one node, E1 deposits at a fixed rate over many accounts, a few hot ones, and many with one hot; then the parts each of the sink's tables got (system.part_log) and the inserts the sink sent (system.query_log).",
    },
  };
}

/** The `bench.sh measure` options of each run of [definition], a suite of one of SINK_PROGRAMS. */
function runsOf(definition) {
  if (definition.measure === 'clickhouse-insert') {
    return [
      {
        name: 'insert',
        args: [
          `--rows=${INSERT.rows}`,
          `--block=${INSERT.block}`,
          `--repeats=${INSERT.repeats}`,
        ],
      },
    ];
  }
  return SINK.scenarios.map(scenario => ({
    name: scenario.name,
    args: [
      `--accounts=${scenario.accounts}`,
      `--rate=${SINK.rate}`,
      `--seconds=${SINK.seconds}`,
      `--distribution=${scenario.distribution}`,
      ...(scenario.hotKeys
        ? [
            `--hot-fraction=${scenario.hotFraction}`,
            `--hot-keys=${scenario.hotKeys}`,
          ]
        : []),
    ],
  }));
}

/** Runs a suite of one of SINK_PROGRAMS into [suite], once a run, on its one arm, as runMeasure runs the others. */
export function runSink(
  definition,
  suite,
  { benchPath, out, cpuset, arms, save },
) {
  const [arm] = arms;
  const read = definition.measure === 'sink' ? readSink : readInsert;
  for (const spec of runsOf(definition)) {
    const dir = join(out, spec.name);
    const status = bench(
      benchPath,
      [
        'measure',
        `--program=${definition.measure}`,
        `--backend=${arm.backend}`,
        ...spec.args,
        ...(cpuset.server ? [`--server-cpus=${cpuset.server}`] : []),
        `--out=${dir}`,
      ],
      arm.props,
    );
    const run = { name: spec.name, arm: arm.id, status: 'failed' };
    const measured = status === 0 ? read(dir) : undefined;
    if (measured) {
      run.status = 'ok';
      run.measure = measured;
    } else {
      suite.failures++;
    }
    suite.runs.push(run);
    save();
  }
}

/**
 * clickhouse-insert's results from bench.sh's --out [dir]: per arm (mode, columns, into the table or the view), the
 * median CPU (user and all) µs a row and ms per 1,000 rows of its inserts; the skip index's counts and plans; undefined
 * when measure.json or insert.jsonl is missing.
 */
export function readInsert(dir) {
  const manifest = readJsonIfThere(join(dir, 'measure.json'));
  const file = join(dir, 'insert.jsonl');
  if (!manifest || !existsSync(file)) return undefined;
  const groups = new Map();
  for (const line of readFileSync(file, 'utf8').split('\n').filter(Boolean)) {
    const insert = JSON.parse(line);
    const key = `${insert.mode}|${insert.columns}|${insert.target}`;
    groups.set(key, [...(groups.get(key) ?? []), insert]);
  }
  const perRow = (list, field) =>
    quantile(
      list.map(it => Number(it[field]) / Number(it.rows)),
      0.5,
    );
  const arms = [...groups.values()].map(list => ({
    mode: list[0].mode,
    columns: Number(list[0].columns),
    target: list[0].target,
    inserts: list.length,
    userMicrosPerRow: perRow(list, 'userMicros'),
    cpuMicrosPerRow: perRow(list, 'cpuMicros'),
    msPerThousand: perRow(list, 'durationMs') * 1000,
  }));
  return {
    server: manifest.server,
    rows: manifest.rows,
    block: manifest.block,
    repeats: manifest.repeats,
    arms,
    skip: readJsonIfThere(join(dir, 'skip.json')),
  };
}

/**
 * sink's results from bench.sh's --out [dir]: the commits the load made and the sink wrote, its inserts and their rows,
 * and the parts and rows of each of its tables; undefined when sink.json is missing.
 */
export function readSink(dir) {
  const sink = readJsonIfThere(join(dir, 'sink.json'));
  if (!sink) return undefined;
  const inserts = Number(sink.inserts?.inserts ?? 0);
  return {
    accounts: sink.accounts,
    expected: Number(sink.expected),
    written: Number(sink.written),
    inserts,
    rowsPerInsert:
      inserts > 0 ? Number(sink.inserts.writtenRows) / inserts : null,
    parts: Object.fromEntries(
      (sink.parts ?? []).map(it => [
        it.table,
        { parts: Number(it.parts), rows: Number(it.rows) },
      ]),
    ),
  };
}

/** [value] µs, or a dash. */
const micros = value => (Number.isFinite(value) ? `${num(value, 2)} µs` : '-');

/**
 * clickhouse-insert's tables: per column count and target, each mode's CPU (user) µs a row and the two forms' cost
 * above none, with D412 §2's bound read at INSERT.bound.columns; then the skip index under FINAL.
 */
export function insertTables(suite) {
  const run = suite.runs.find(it => it.status === 'ok');
  if (!run) return ['No run completed.'];
  const measured = run.measure;
  const of = (mode, columns, target) =>
    measured.arms.find(
      arm =>
        arm.mode === mode && arm.columns === columns && arm.target === target,
    )?.userMicrosPerRow;
  const lines = [
    `ClickHouse ${measured.server}: ${num(measured.rows, 0)} rows an insert in blocks of ${num(measured.block, 0)}, ${measured.repeats} inserts an arm; the median CPU (user) a row.`,
    '',
    '| Columns | Into | None | Per column | Tuple | Per column above none | Tuple above none | Per column / tuple |',
    '| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |',
  ];
  const verdicts = [];
  for (const columns of INSERT.widths) {
    for (const target of ['table', 'view']) {
      const none = of('none', columns, target);
      const perColumn = of('per-column', columns, target) - none;
      const tuple = of('tuple', columns, target) - none;
      const ratio = tuple > 0 ? perColumn / tuple : NaN;
      lines.push(
        `| ${columns} | ${target} | ${micros(none)} | ${micros(perColumn + none)} | ${micros(tuple + none)} | ${micros(perColumn)} | ${micros(tuple)} | ${Number.isFinite(ratio) ? `×${num(ratio, 2)}` : '-'} |`,
      );
      if (columns === INSERT.bound.columns) {
        const crossed =
          perColumn > INSERT.bound.micros && ratio > INSERT.bound.ratio;
        verdicts.push(
          `- ${columns} columns into the ${target}: the bound (more than ×${INSERT.bound.ratio} the tuple's and over ${INSERT.bound.micros} µs a row) is ${crossed ? '**crossed**: the tuple form' : 'not crossed: per column'}.`,
        );
      }
    }
  }
  return [...lines, '', ...verdicts, '', ...skipLines(measured.skip)];
}

/** The skip index under FINAL: per setting, the count against the right one, the rows read, and whether the plan uses it. */
function skipLines(skip) {
  if (!skip) return ['No skip-index result.'];
  return [
    `A set index on \`status\` of a \`_state\` whose newer rows moved half of the aggregates to another status; the right count is ${num(skip.correct, 0)}.`,
    '',
    '| use_skip_indexes_if_final | Count | Right | Rows read | Index in the plan |',
    '| ---: | ---: | --- | ---: | --- |',
    ...skip.settings.map(
      it =>
        `| ${it.useSkipIndexesIfFinal} | ${num(it.count, 0)} | ${it.count === skip.correct ? 'yes' : '**no**'} | ${num(it.readRows, 0)} | ${String(it.explain).includes('status_set') ? 'yes' : 'no'} |`,
    ),
  ];
}

/** sink's table: per scenario, the commits, the inserts and their rows, and each table's parts and rows a part. */
export function sinkTables(suite) {
  const lines = [
    '| Scenario | Accounts | Commits | Written | Inserts | Rows an insert | Parts (state_events, state, events) | Rows a part (state_events, state, events) |',
    '| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |',
  ];
  for (const run of suite.runs) {
    if (run.status !== 'ok') {
      lines.push(`| ${run.name} | ${run.status} | | | | | | |`);
      continue;
    }
    const measured = run.measure;
    const parts = SINK_TABLES.map(table => measured.parts[table]);
    lines.push(
      `| ${run.name} | ${num(measured.accounts, 0)} | ${num(measured.expected, 0)} | ${num(measured.written, 0)} | ${num(measured.inserts, 0)} | ${measured.rowsPerInsert === null ? '-' : num(measured.rowsPerInsert, 1)} | ${parts.map(it => num(it?.parts ?? 0, 0)).join(', ')} | ${parts.map(it => (it?.parts ? num(it.rows / it.parts, 1) : '-')).join(', ')} |`,
    );
  }
  return lines;
}

/** The anomalies of a suite of one of SINK_PROGRAMS: a run that failed, commits the sink did not write, a wrong FINAL. */
export function sinkAnomalies(suite) {
  const rows = [];
  const row = (run, kind, detail) =>
    rows.push({
      suite: labelOf(suite),
      run: run.name,
      context: suite.program,
      kind,
      detail,
    });
  for (const run of suite.runs) {
    if (run.status === 'failed')
      row(run, 'run failed', 'bench.sh failed; see the job log');
    if (run.status !== 'ok') continue;
    const measured = run.measure;
    if (suite.program === 'sink' && measured.written < measured.expected)
      row(
        run,
        'commits not written',
        `${measured.written} of ${measured.expected} in the sink's tables`,
      );
    const plain = measured.skip?.settings?.find(
      it => it.useSkipIndexesIfFinal === 0,
    );
    if (plain && plain.count !== measured.skip.correct)
      row(
        run,
        'FINAL answered wrong',
        `${plain.count} rows without skip indexes, ${measured.skip.correct} right`,
      );
  }
  return rows;
}

/** The Markdown summary of a suite of one of SINK_PROGRAMS, [definition] its SUITES entry. */
export function renderSink(suite, definition) {
  const lines = [
    `## ${labelOf(suite)}`,
    '',
    definition?.answers ?? '',
    '',
    `Runner: ${suite.runner?.cpu ?? '?'}, ${suite.runner?.cores ?? '?'} cores.`,
    '',
    ...(suite.program === 'sink' ? sinkTables(suite) : insertTables(suite)),
    '',
  ];
  const found = anomalyTable(sinkAnomalies(suite));
  if (found.length > 0) lines.push('Anomalies:', '', ...found, '');
  if (suite.failures > 0)
    lines.push(`**${suite.failures} run(s) failed**; see the job log.`, '');
  return `${lines.join('\n')}\n`;
}
