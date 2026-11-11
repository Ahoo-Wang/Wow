/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { join } from 'node:path';
import {
  anomalyTable,
  bench,
  labelOf,
  num,
  readJsonIfThere,
} from './bench-common.mjs';

// bench.sh's index program, of D411: what Elasticsearch's indices of the eShop catalog's products cost a document,
// the search server's IndexFigures (test/benchmarks/search-server, its install's index/) seeding the catalog's
// server on its `search` profile. Run at two commits, its figures read what mapping strings by the marker saves.

/** The programs of this module, as a `measure` suite names them. */
export const INDEX_PROGRAMS = ['index'];

/** index's settings: the products seeded, each created and then updated (two commits). */
export const INDEX = { products: 100000 };

/** The `index-es` suite, on [arm]: the head's build on MongoDB, the catalog's log store on its `search` profile. */
export function indexSuites(arm) {
  return {
    'index-es': {
      group: 'index',
      kind: 'measure',
      measure: 'index',
      arms: [arm],
      answers:
        "What Elasticsearch's indices cost a document (D411): the eShop catalog's server on its `search` profile, its products' snapshots and events on Elasticsearch, seeded with 100,000 products, each created and then updated; then each index merged to one segment, and its documents, store bytes a document, Elasticsearch's indexing time a document and the `text` fields its mapping holds. Run at the commit before D411's mapping by the marker and at its merge, the two read what it saves.",
    },
  };
}

/** Runs a suite of one of INDEX_PROGRAMS into [suite], once, on its one arm, as runMeasure runs the others. */
export function runIndex(
  definition,
  suite,
  { benchPath, out, cpuset, arms, save },
) {
  const [arm] = arms;
  const dir = join(out, 'index');
  const status = bench(
    benchPath,
    [
      'measure',
      `--program=${definition.measure}`,
      `--products=${INDEX.products}`,
      ...(cpuset.server ? [`--server-cpus=${cpuset.server}`] : []),
      `--out=${dir}`,
    ],
    arm.props,
  );
  const run = { name: 'index', arm: arm.id, status: 'failed' };
  const measured = status === 0 ? readIndex(dir) : undefined;
  if (measured) {
    run.status = 'ok';
    run.measure = measured;
  } else {
    suite.failures++;
  }
  suite.runs.push(run);
  save();
}

/**
 * index's results from bench.sh's --out [dir]: the products and commits seeded, and per index its documents, its
 * store's bytes and Elasticsearch's indexing time, a document each, and its mapping's `text` fields; undefined when
 * index.json is missing.
 */
export function readIndex(dir) {
  const figures = readJsonIfThere(join(dir, 'index.json'));
  if (!figures) return undefined;
  const perDocument = (value, documents) =>
    documents > 0 ? value / documents : null;
  return {
    products: Number(figures.products),
    commits: Number(figures.commits),
    indices: (figures.indices ?? []).map(index => {
      const documents = Number(index.documents);
      return {
        kind: index.kind,
        index: index.index,
        documents,
        bytesPerDocument: perDocument(Number(index.storeBytes), documents),
        indexMicrosPerDocument: perDocument(
          Number(index.indexTimeMillis) * 1000,
          documents,
        ),
        textFields: Number(index.textFields),
      };
    }),
  };
}

/**
 * index's table: per index, its documents, store bytes a document, indexing time a document and `text` fields; the
 * indexing time with the caution a shared runner's times take.
 */
export function indexTable(suite) {
  const run = suite.runs.find(it => it.status === 'ok');
  if (!run) return ['No run completed.'];
  const measured = run.measure;
  const value = (number, digits) =>
    number === null ? '-' : num(number, digits);
  return [
    `${num(measured.products, 0)} products, each created and then updated (${num(measured.commits, 0)} commits); each index merged to one segment.`,
    '',
    '| Index | Documents | Store bytes a document | Indexing time a document | `text` fields |',
    '| --- | ---: | ---: | ---: | ---: |',
    ...measured.indices.map(
      it =>
        `| ${it.kind} | ${num(it.documents, 0)} | ${value(it.bytesPerDocument, 0)} | ${value(it.indexMicrosPerDocument, 1)} µs | ${num(it.textFields, 0)} |`,
    ),
    '',
    "The store's bytes are the index's own, read the same way on any runner. The indexing time is Elasticsearch's (`_stats`) on a shared runner: compare it only with a run on the same runner type, as a direction rather than a figure.",
  ];
}

/** The anomalies of a suite of one of INDEX_PROGRAMS: a run that failed, an index short of its documents. */
export function indexAnomalies(suite) {
  const rows = [];
  for (const run of suite.runs) {
    const row = (kind, detail) =>
      rows.push({
        suite: labelOf(suite),
        run: run.name,
        context: suite.program,
        kind,
        detail,
      });
    if (run.status === 'failed')
      row('run failed', 'bench.sh failed; see the job log');
    if (run.status !== 'ok') continue;
    const { products, commits, indices } = run.measure;
    const expected = { snapshot: products, events: commits };
    for (const index of indices) {
      if (index.documents < expected[index.kind])
        row(
          'documents missing',
          `${index.kind}: ${index.documents} of ${expected[index.kind]}`,
        );
    }
    if (indices.length !== 2)
      row(
        'indices missing',
        `${indices.length} of the snapshot and events indices`,
      );
  }
  return rows;
}

/** The Markdown summary of a suite of one of INDEX_PROGRAMS, [definition] its SUITES entry. */
export function renderIndex(suite, definition) {
  const lines = [
    `## ${labelOf(suite)}`,
    '',
    definition?.answers ?? '',
    '',
    `Runner: ${suite.runner?.cpu ?? '?'}, ${suite.runner?.cores ?? '?'} cores.`,
    '',
    ...indexTable(suite),
    '',
  ];
  const found = anomalyTable(indexAnomalies(suite));
  if (found.length > 0) lines.push('Anomalies:', '', ...found, '');
  if (suite.failures > 0)
    lines.push(`**${suite.failures} run(s) failed**; see the job log.`, '');
  return `${lines.join('\n')}\n`;
}
