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
  roundOrder,
} from './bench-common.mjs';

// bench.sh's sink programs, of D412 and D413 (d412-d413-design.md): `clickhouse-insert`, what computed columns cost a
// ClickHouse insert a row, each column's own JSONExtract against one into a tuple, and whether a skip index holds
// under FINAL; `sink`, the batches a sink makes of E1's commits: the ClickHouse sink's parts and inserts, or the
// Elasticsearch sink's requests (the search server's, test/benchmarks/search-server).

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
 * clickhouse-insert's codec arms (CH-6, D421): the Sink's `_state_events` without codecs (LZ4) and with the Sink's;
 * and the bar the Sink's codecs are read against, a share of the insert's rows a second against LZ4.
 */
export const CODEC = { arms: ['lz4', 'wow'], bar: 0.1 };

/**
 * sink's settings: E1's rate a second and its seconds, and the scenarios: the commits spread over many aggregates,
 * over a few hot ones, and over many with half of them to one; and a backlog, the spread commits written first
 * without the sink and drained by it after (bench.sh --backlog, a rebuild of the warehouse or a catch-up).
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
    { name: 'backlog', accounts: 1000, distribution: 'uniform', backlog: true },
  ],
};

/**
 * The ceiling's backlog (CH-6, D421 ④): E1 at `rate` a second for `seconds` with the sink off, then drained by a node
 * with it, the base's build against the head's, `rounds` rounds ABBA. Its rate is the backlog's commits over the time
 * from the sink's first insert to its last, so the node's start is not in it.
 */
export const CEILING = { rate: 1000, seconds: 120, accounts: 1000, rounds: 2 };

/** The sink's tables of the benchmark's account, in the order the tables show them. */
const SINK_TABLES = [
  'transfer_account_state_events',
  'transfer_account_state',
  'transfer_account_events',
];

/**
 * The `sink` group's suites, on [arm]: the head's build on PostgreSQL. A `sink` suite's `sink` is bench.sh's --sink:
 * the head's benchmark server with the ClickHouse sink, or the search server with the Elasticsearch one.
 */
export function sinkSuites(arm, base) {
  return {
    'ch-insert': {
      group: 'sink',
      kind: 'measure',
      measure: 'clickhouse-insert',
      arms: [arm],
      answers:
        "What computed columns cost a ClickHouse insert (D412): N = 5, 20 and 50 columns read from a state's JSON text, each by its own JSONExtract or all by one into a tuple, inserted into the table and through a materialized view into its TO table, as the sink fills `_state`; and whether a skip index is used under FINAL, and still reads the newest row. The per-column or tuple choice is read against D412's bound. And what the Sink's codecs cost (CH-6): its `_state_events` without codecs and with them, each filled with the same commits, then merged.",
    },
    'sink-ch': {
      group: 'sink',
      kind: 'measure',
      measure: 'sink',
      sink: 'clickhouse',
      arms: [arm],
      answers:
        "The ClickHouse sink's parts and inserts (D413's before): one node, E1 deposits at a fixed rate over many accounts, a few hot ones, and many with one hot; then the parts each of the sink's tables got (system.part_log) and the inserts the sink sent (system.query_log). And a backlog: the spread deposits written with the sink off, then drained by a node with it (a rebuild of the warehouse, a catch-up), with the time it took.",
    },
    'sink-ch-ceiling': {
      group: 'codecs',
      kind: 'measure',
      measure: 'sink',
      sink: 'clickhouse',
      ceiling: true,
      arms: [base, arm],
      answers:
        "The ClickHouse sink's own ceiling, the base's build against the head's (CH-6, D421 ④): a backlog of E1 deposits at 1,000 a second for 120 s written with the sink off, then drained by a node with it, two rounds ABBA; the commits a second from the sink's first insert to its last.",
    },
    'sink-es': {
      group: 'sink',
      kind: 'measure',
      measure: 'sink',
      sink: 'elasticsearch',
      arms: [arm],
      answers:
        "The Elasticsearch sink's requests (D413's before): one node of the search server, its account's events on Elasticsearch, E1 deposits at a fixed rate in sink-ch's scenarios, its backlog among them; then the `_bulk`, `_refresh` and `_update_by_query` requests the sink sent (counted on the store's client) for the deposits' commits, every commit of a backlog, with the node's indexing, bulk and refresh totals as a cross-check.",
    },
  };
}

/** The `bench.sh measure` options of each run of [definition], a suite of one of SINK_PROGRAMS. */
function runsOf(definition) {
  if (definition.ceiling) {
    return [
      {
        name: 'ceiling',
        args: [
          `--accounts=${CEILING.accounts}`,
          `--rate=${CEILING.rate}`,
          `--seconds=${CEILING.seconds}`,
          '--distribution=uniform',
          '--backlog=true',
        ],
      },
    ];
  }
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
      ...(definition.sink === 'elasticsearch' ? ['--sink=elasticsearch'] : []),
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
      ...(scenario.backlog ? ['--backlog=true'] : []),
    ],
  }));
}

/**
 * Runs a suite of one of SINK_PROGRAMS into [suite], as runMeasure runs the others: once a run on its one arm, or for a
 * ceiling suite CEILING.rounds rounds of its arms, ABBA, each arm on its build ([installs]).
 */
export function runSink(
  definition,
  suite,
  { benchPath, out, cpuset, arms, installs = {}, save },
) {
  const read = definition.measure === 'sink' ? readSink : readInsert;
  const plan = definition.ceiling
    ? Array.from({ length: CEILING.rounds }, (_, round) =>
        roundOrder(arms, round).map(arm => ({ arm, round })),
      ).flat()
    : [{ arm: arms[0] }];
  for (const { arm, round } of plan) {
    for (const spec of runsOf(definition)) {
      runOne(arm, spec, round);
    }
  }

  function runOne(arm, spec, round) {
    const name = round === undefined ? spec.name : `${round + 1}-${arm.id}`;
    const dir = join(out, name);
    const status = bench(
      benchPath,
      [
        'measure',
        `--program=${definition.measure}`,
        `--backend=${arm.backend}`,
        ...(installs[arm.install]
          ? [`--install=${installs[arm.install]}`]
          : []),
        ...spec.args,
        ...(cpuset.server ? [`--server-cpus=${cpuset.server}`] : []),
        `--out=${dir}`,
      ],
      arm.props,
    );
    const run = { name, arm: arm.id, status: 'failed' };
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
    codecs: readCodecs(dir),
  };
}

/**
 * The codec arms of [dir] (CODEC.arms), from codec.jsonl and codec-merge.jsonl: per arm, the median CPU (user and all)
 * µs a row and rows a second of its inserts, and its merge's CPU µs a row, duration and the parts' bytes after it;
 * undefined when either file is missing.
 */
function readCodecs(dir) {
  const inserts = join(dir, 'codec.jsonl');
  const merges = join(dir, 'codec-merge.jsonl');
  if (!existsSync(inserts) || !existsSync(merges)) return undefined;
  const lines = file =>
    readFileSync(file, 'utf8')
      .split('\n')
      .filter(Boolean)
      .map(line => JSON.parse(line));
  const merged = new Map(lines(merges).map(merge => [merge.arm, merge]));
  const groups = new Map();
  for (const insert of lines(inserts)) {
    groups.set(insert.arm, [...(groups.get(insert.arm) ?? []), insert]);
  }
  return CODEC.arms
    .filter(arm => groups.has(arm))
    .map(arm => {
      const list = groups.get(arm);
      const median = values => quantile(values, 0.5);
      const perRow = field =>
        median(list.map(it => Number(it[field]) / Number(it.rows)));
      const merge = merged.get(arm);
      return {
        arm,
        inserts: list.length,
        userMicrosPerRow: perRow('userMicros'),
        cpuMicrosPerRow: perRow('cpuMicros'),
        rowsPerSecond: median(
          list.map(it => Number(it.rows) / (Number(it.durationMs) / 1000)),
        ),
        merge: merge && {
          partsBefore: Number(merge.partsBefore),
          userMicrosPerRow: Number(merge.userMicros) / Number(merge.rows),
          durationMs: Number(merge.durationMs),
          compressedBytes: Number(merge.compressedBytes),
          uncompressedBytes: Number(merge.uncompressedBytes),
        },
      };
    });
}

/**
 * sink's results from bench.sh's --out [dir]: the commits the load made and the sink wrote; on ClickHouse its inserts,
 * the commits each carried and the parts and rows of each of its tables, on Elasticsearch its requests by kind and
 * the deposits' commits each `_bulk` carried; undefined when sink.json is missing.
 */
export function readSink(dir) {
  const sink = readJsonIfThere(join(dir, 'sink.json'));
  if (!sink) return undefined;
  if (sink.sink === 'elasticsearch') return readElasticsearchSink(sink);
  const inserts = Number(sink.inserts?.inserts ?? 0);
  const parts = Object.fromEntries(
    (sink.parts ?? []).map(it => [
      it.table,
      { parts: Number(it.parts), rows: Number(it.rows) },
    ]),
  );
  // The query log counts the rows its materialized views wrote too: an insert's commits are the base table's rows.
  const commits = parts[SINK_TABLES[0]]?.rows ?? 0;
  // A backlog's drain, from the sink's first insert to its last: the commits it wrote a second, the node's start out.
  const windowMs =
    Number(sink.inserts?.lastMs ?? 0) - Number(sink.inserts?.firstMs ?? 0);
  return {
    sink: 'clickhouse',
    accounts: sink.accounts,
    expected: Number(sink.expected),
    written: Number(sink.written),
    inserts,
    commitsPerInsert: inserts > 0 ? commits / inserts : null,
    parts,
    drainMs: drainOf(sink),
    commitsPerSecond:
      sink.backlog === true && windowMs > 0
        ? commits / (windowMs / 1000)
        : null,
  };
}

/** A backlog's time to drain, ms; null for a run that took its commits as they came. */
const drainOf = sink => (sink.backlog === true ? Number(sink.drainMs) : null);

/** [value] ms as seconds, or a dash. */
const drain = value => (value === null ? '-' : `${num(value / 1000, 1)} s`);

/**
 * The Elasticsearch sink's sink.json [sink]: its counts are from after the seeding, so the commits they are read
 * against are the deposits' (the commits less the accounts the seeding opened); a backlog's are of every commit.
 */
function readElasticsearchSink(sink) {
  const requests = Object.fromEntries(
    Object.entries(sink.requests ?? {}).map(([kind, count]) => [
      kind,
      Number(count),
    ]),
  );
  const commits =
    sink.backlog === true
      ? Number(sink.expected)
      : Number(sink.expected) - Number(sink.accounts);
  const bulks = requests._bulk ?? 0;
  return {
    sink: 'elasticsearch',
    accounts: sink.accounts,
    expected: Number(sink.expected),
    written: Number(sink.written),
    commits,
    requests,
    commitsPerBulk: bulks > 0 ? commits / bulks : null,
    node: sink.node ?? {},
    drainMs: drainOf(sink),
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
  return [
    ...lines,
    '',
    ...verdicts,
    '',
    ...skipLines(measured.skip),
    '',
    ...codecLines(measured.codecs),
  ];
}

/**
 * The codec arms (CH-6): per arm, the insert's CPU (user) a row and rows a second, the merge's CPU a row and duration,
 * the bytes after it; then the Sink's codecs against LZ4, read against CODEC.bar.
 */
function codecLines(codecs) {
  if (!codecs?.length) return ['No codec result.'];
  const lines = [
    "The Sink's `_state_events` of the same commits: without codecs (LZ4) and with the Sink's; the inserts' median, then one OPTIMIZE FINAL of the arm's parts.",
    '',
    '| Arm | Insert CPU a row | Insert rows/s | Merge CPU a row | Merge | Compressed | Ratio |',
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: |',
    ...codecs.map(
      it =>
        `| ${it.arm} | ${micros(it.userMicrosPerRow)} | ${num(it.rowsPerSecond, 0)} | ${micros(it.merge?.userMicrosPerRow)} | ${it.merge ? `${num(it.merge.durationMs, 0)} ms` : '-'} | ${it.merge ? `${num(it.merge.compressedBytes, 0)} B` : '-'} | ${it.merge ? `×${num(it.merge.uncompressedBytes / it.merge.compressedBytes, 2)}` : '-'} |`,
    ),
  ];
  const lz4 = codecs.find(it => it.arm === 'lz4');
  const wow = codecs.find(it => it.arm === 'wow');
  if (!lz4 || !wow) return lines;
  const fall = 1 - wow.rowsPerSecond / lz4.rowsPerSecond;
  return [
    ...lines,
    '',
    `- The Sink's codecs: ${num(fall * 100, 1)}% fewer rows a second than LZ4, ${fall > CODEC.bar ? `**over** the ${num(CODEC.bar * 100, 0)}% bar` : `within the ${num(CODEC.bar * 100, 0)}% bar`}; the bytes ${num((1 - wow.merge?.compressedBytes / lz4.merge?.compressedBytes) * 100, 1)}% fewer.`,
  ];
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

/** sink's table: the ClickHouse sink's, or the Elasticsearch sink's when [sink] is `elasticsearch`. */
export function sinkTables(suite, sink = 'clickhouse', ceiling = false) {
  if (sink === 'elasticsearch') return elasticsearchSinkTable(suite);
  if (ceiling) return ceilingTable(suite);
  const lines = [
    '| Scenario | Accounts | Commits | Written | Inserts | Commits an insert | Parts (state_events, state, events) | Rows a part (state_events, state, events) | Drain |',
    '| --- | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: |',
  ];
  for (const run of suite.runs) {
    if (run.status !== 'ok') {
      lines.push(`| ${run.name} | ${run.status} | | | | | | | |`);
      continue;
    }
    const measured = run.measure;
    const parts = SINK_TABLES.map(table => measured.parts[table]);
    lines.push(
      `| ${run.name} | ${num(measured.accounts, 0)} | ${num(measured.expected, 0)} | ${num(measured.written, 0)} | ${num(measured.inserts, 0)} | ${measured.commitsPerInsert === null ? '-' : num(measured.commitsPerInsert, 1)} | ${parts.map(it => num(it?.parts ?? 0, 0)).join(', ')} | ${parts.map(it => (it?.parts ? num(it.rows / it.parts, 1) : '-')).join(', ')} | ${drain(measured.drainMs ?? null)} |`,
    );
  }
  return lines;
}

/**
 * The Elasticsearch sink's table: per scenario, the commits, those counted (the deposits', every one of a backlog)
 * and the requests the sink sent for them, `_bulk` with the commits each carried, `_update_by_query` and `_refresh` a 1,000 commits, and the node's
 * documents indexed and refreshes.
 */
function elasticsearchSinkTable(suite) {
  const lines = [
    '| Scenario | Accounts | Commits | Written | Commits counted | _bulk | Commits a _bulk | _update_by_query (a 1,000 commits) | _refresh (a 1,000 commits) | Other requests | Documents indexed (node) | Refreshes (node) | Drain |',
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |',
  ];
  const perThousand = (count, commits) =>
    `${num(count ?? 0, 0)} (${commits > 0 ? num(((count ?? 0) * 1000) / commits, 1) : '-'})`;
  for (const run of suite.runs) {
    if (run.status !== 'ok') {
      lines.push(`| ${run.name} | ${run.status} | | | | | | | | | | | |`);
      continue;
    }
    const measured = run.measure;
    const { requests, node, commits } = measured;
    lines.push(
      `| ${run.name} | ${num(measured.accounts, 0)} | ${num(measured.expected, 0)} | ${num(measured.written, 0)} | ${num(commits, 0)} | ${num(requests._bulk ?? 0, 0)} | ${measured.commitsPerBulk === null ? '-' : num(measured.commitsPerBulk, 1)} | ${perThousand(requests._update_by_query, commits)} | ${perThousand(requests._refresh, commits)} | ${num(requests.other ?? 0, 0)} | ${num(node.indexTotal ?? 0, 0)} | ${num(node.refreshTotal ?? 0, 0)} | ${drain(measured.drainMs ?? null)} |`,
    );
  }
  return lines;
}

/**
 * The anomalies of a suite of one of SINK_PROGRAMS: a run that failed, commits the sink did not write, Elasticsearch
 * requests that were not counted, a wrong FINAL.
 */
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
    if (measured.sink === 'elasticsearch' && !('_bulk' in measured.requests))
      row(
        run,
        'requests not counted',
        "no search-requests line in the node's log",
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

/**
 * The ceiling suite's table: each run's backlog and the commits a second the sink drained it at; then each arm's
 * median, and the head's against the base's (the first arm's), read against CODEC.bar.
 */
function ceilingTable(suite) {
  const lines = [
    '| Run | Commits | Written | Inserts | Commits an insert | Drain (start to written) | Commits a second (first insert to last) |',
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: |',
  ];
  const byArm = new Map();
  for (const run of suite.runs) {
    if (run.status !== 'ok') {
      lines.push(`| ${run.name} | ${run.status} | | | | | |`);
      continue;
    }
    const measured = run.measure;
    lines.push(
      `| ${run.name} | ${num(measured.expected, 0)} | ${num(measured.written, 0)} | ${num(measured.inserts, 0)} | ${measured.commitsPerInsert === null ? '-' : num(measured.commitsPerInsert, 1)} | ${drain(measured.drainMs ?? null)} | ${measured.commitsPerSecond === null ? '-' : num(measured.commitsPerSecond, 0)} |`,
    );
    if (measured.commitsPerSecond !== null) {
      byArm.set(run.arm, [
        ...(byArm.get(run.arm) ?? []),
        measured.commitsPerSecond,
      ]);
    }
  }
  const medians = [...byArm].map(([arm, rates]) => [
    arm,
    quantile(rates, 0.5),
    rates,
  ]);
  lines.push(
    '',
    ...medians.map(
      ([arm, median, rates]) =>
        `- ${arm}: ${num(median, 0)} commits a second, the median of ${rates.map(it => num(it, 0)).join(', ')}.`,
    ),
  );
  if (medians.length === 2) {
    const [[base, of], [head, at]] = medians;
    const change = at / of - 1;
    lines.push(
      `- ${head} against ${base}: ${change < 0 ? '' : '+'}${num(change * 100, 1)}%, ${-change > CODEC.bar ? `**over** the ${num(CODEC.bar * 100, 0)}% bar` : `within the ${num(CODEC.bar * 100, 0)}% bar`}.`,
    );
  }
  return lines;
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
    ...(suite.program === 'sink'
      ? sinkTables(suite, definition?.sink, definition?.ceiling === true)
      : insertTables(suite)),
    '',
  ];
  const found = anomalyTable(sinkAnomalies(suite));
  if (found.length > 0) lines.push('Anomalies:', '', ...found, '');
  if (suite.failures > 0)
    lines.push(`**${suite.failures} run(s) failed**; see the job log.`, '');
  return `${lines.join('\n')}\n`;
}
