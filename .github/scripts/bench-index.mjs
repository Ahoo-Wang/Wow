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
        "What Elasticsearch's indices cost a document (D411): the eShop catalog's server on its `search` profile, its products' snapshots and events on Elasticsearch, seeded with 100,000 products, each created and then updated; then each index merged to one segment, and its store bytes a document, Elasticsearch's indexing time a document, and the `text` and `keyword` fields its mapping holds. Run at the commit before D411's mapping by the marker and at its merge, the two read what it saves.",
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
 * index's results from bench.sh's --out [dir]: the products and commits seeded, and per index its documents, store
 * bytes and indexing figures, a document each; undefined when index.json is missing.
 */
export function readIndex(dir) {
  const figures = readJsonIfThere(join(dir, 'index.json'));
  if (!figures) return undefined;
  return {
    products: Number(figures.products),
    commits: Number(figures.commits),
    seedSeconds: Number(figures.seedSeconds),
    indices: (figures.indices ?? []).map(index => {
      const documents = Number(index.documents);
      const indexTotal = Number(index.indexTotal);
      return {
        kind: index.kind,
        index: index.index,
        documents,
        storeBytes: Number(index.storeBytes),
        bytesPerDocument:
          documents > 0 ? Number(index.storeBytes) / documents : null,
        indexTotal,
        indexTimeMillis: Number(index.indexTimeMillis),
        indexMicrosPerOperation:
          indexTotal > 0
            ? (Number(index.indexTimeMillis) * 1000) / indexTotal
            : null,
        textFields: Number(index.textFields),
        keywordFields: Number(index.keywordFields),
      };
    }),
  };
}

/** index's table: per index, its documents, store bytes a document, indexing time an operation and mapped fields. */
export function indexTable(suite) {
  const run = suite.runs.find(it => it.status === 'ok');
  if (!run) return ['No run completed.'];
  const measured = run.measure;
  return [
    `${num(measured.products, 0)} products, ${num(measured.commits, 0)} commits, seeded and indexed in ${num(measured.seedSeconds, 0)} s; each index merged to one segment.`,
    '',
    '| Index | Documents | Store | Bytes a document | Indexing operations | Indexing time | µs an operation | `text` fields | `keyword` fields |',
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |',
    ...measured.indices.map(
      it =>
        `| ${it.kind} (\`${it.index}\`) | ${num(it.documents, 0)} | ${num(it.storeBytes / 1048576, 1)} MiB | ${it.bytesPerDocument === null ? '-' : num(it.bytesPerDocument, 0)} | ${num(it.indexTotal, 0)} | ${num(it.indexTimeMillis / 1000, 1)} s | ${it.indexMicrosPerOperation === null ? '-' : num(it.indexMicrosPerOperation, 1)} | ${num(it.textFields, 0)} | ${num(it.keywordFields, 0)} |`,
    ),
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
