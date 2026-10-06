/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { appendFileSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// The steps of benchmark-ab.yml, the A/B JMH comparison of two refs:
//
//   node .github/scripts/benchmark-ab.mjs inputs   (resolve job)
//   node .github/scripts/benchmark-ab.mjs plan     (plan job)
//   node .github/scripts/benchmark-ab.mjs report   (report job)
//
// `inputs` validates the dispatch inputs (or, for the `benchmark-ab` label,
// derives the include patterns from the benchmark classes the pull request
// changed) and turns them into JMH arguments. `plan` splits the benchmarks the
// two JMH jars list into one matrix job per class or method. `report` pools the
// JSON of every interleaved fork per side and writes the comparison table.

// Forks per side (`rounds`): each round runs one fork of base and one of head
// on the same runner, alternating which goes first (ABBA) so drift over the job
// cancels as well as runner-to-runner variance.
export const PROFILES = {
  quick: {
    rounds: 3,
    warmupIterations: 2,
    warmupTime: '2s',
    iterations: 3,
    iterationTime: '2s',
  },
  gate: {
    rounds: 8,
    warmupIterations: 3,
    warmupTime: '3s',
    iterations: 5,
    iterationTime: '3s',
  },
};

export const JVM_ARGS = '-Xmx2g -Xms2g -XX:+UseG1GC -XX:+AlwaysPreTouch';

// Benchmarks that need Redis, MongoDB, Elasticsearch or Kafka; a runner has
// none of them.
const SERVICE_BENCHMARK = /^me\.ahoo\.wow\.benchmark\.infrastructure\./;
const JMH_SOURCE =
  /^wow-benchmarks\/src\/jmh\/(?:kotlin|java)\/(.+Benchmark)\.(?:kt|java)$/;
const PARAM = /^[A-Za-z_$][\w$]*=[^\s;]+$/;

const escapeRegex = text => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const words = text =>
  (text ?? '')
    .split(/[\s,;]+/)
    .map(word => word.trim())
    .filter(Boolean);

/** Include patterns for the JMH classes a pull request changed. */
export function touchedPatterns(paths) {
  const patterns = new Set();
  for (const path of paths) {
    const match = JMH_SOURCE.exec(path.trim());
    if (match)
      patterns.add(`^${escapeRegex(match[1].replaceAll('/', '.'))}\\.`);
  }
  return [...patterns].sort();
}

/**
 * The validated run settings. `include` is a JMH regex or a list of them
 * (comma or whitespace separated: JMH runs their union); `params` overrides
 * `@Param` values as `name=v1,v2` entries separated by `;` or whitespace.
 */
export function resolveInputs({
  include,
  exclude = '',
  profile = 'quick',
  params = '',
  threads = '',
  split = 'class',
  threshold = '3',
  changedPaths,
}) {
  const settings = PROFILES[profile];
  if (!settings) throw new Error(`Unknown profile: ${profile}`);
  if (!['class', 'method'].includes(split))
    throw new Error(`Unknown split: ${split}`);
  const noise = Number(threshold);
  if (!Number.isFinite(noise) || noise < 0)
    throw new Error(`Threshold must be a non-negative number: ${threshold}`);
  const patterns = changedPaths
    ? touchedPatterns(changedPaths)
    : words(include);
  for (const pattern of patterns)
    if (pattern.startsWith('-'))
      throw new Error(`An include pattern cannot start with '-': ${pattern}`);
  if (exclude.trim().startsWith('-'))
    throw new Error(`The exclude pattern cannot start with '-': ${exclude}`);
  const paramArgs = (params ?? '')
    .split(/[\s;]+/)
    .filter(Boolean)
    .flatMap(param => {
      if (!PARAM.test(param))
        throw new Error(`A param override must be name=v1,v2: ${param}`);
      return ['-p', param];
    });
  const threadCounts = words(threads);
  for (const count of threadCounts)
    if (!/^[1-9]\d*$/.test(count))
      throw new Error(`Threads must be positive integers: ${threads}`);
  return {
    run: patterns.length > 0,
    patterns,
    exclude: exclude.trim(),
    split,
    threshold: noise,
    rounds: settings.rounds,
    jmhArgs: [
      '-wi',
      String(settings.warmupIterations),
      '-w',
      settings.warmupTime,
      '-i',
      String(settings.iterations),
      '-r',
      settings.iterationTime,
      ...paramArgs,
    ],
    // `default` keeps the benchmark's own @Threads (JMH's default is 1).
    threads: threadCounts.length ? threadCounts : ['default'],
  };
}

/** The benchmark names of `java -jar benchmarks.jar -l` output. */
export function parseBenchmarkList(output) {
  return output
    .split('\n')
    .map(line => line.trim())
    .filter(line => /^[\w$]+(?:\.[\w$]+)+$/.test(line));
}

/**
 * One matrix job per class (or per method), over the benchmarks either jar
 * lists. A benchmark only one side has still runs there; the report marks it.
 */
export function planMatrix(baseNames, headNames, split = 'class') {
  const names = [...new Set([...baseNames, ...headNames])].sort();
  const skipped = names.filter(name => SERVICE_BENCHMARK.test(name));
  const groups = new Map();
  for (const name of names) {
    if (SERVICE_BENCHMARK.test(name)) continue;
    const key =
      split === 'method' ? name : name.slice(0, name.lastIndexOf('.'));
    groups.set(key, true);
  }
  const keys = [...groups.keys()];
  const shortName = key => {
    const parts = key.split('.');
    return split === 'method' ? parts.slice(-2).join('.') : parts.at(-1);
  };
  const counts = new Map();
  for (const key of keys)
    counts.set(shortName(key), (counts.get(shortName(key)) ?? 0) + 1);
  const include = keys.map(key => {
    const label = counts.get(shortName(key)) > 1 ? key : shortName(key);
    return {
      id: label.replace(/[^\w.-]/g, '_'),
      label,
      regex: `^${escapeRegex(key)}${split === 'method' ? '$' : '\\.'}`,
    };
  });
  return {
    matrix: { include },
    skipped,
    onlyBase: baseNames.filter(name => !headNames.includes(name)),
    onlyHead: headNames.filter(name => !baseNames.includes(name)),
  };
}

// ---- Statistics: the 99.9% confidence half-width JMH reports as `±`. ----

function logGamma(x) {
  const g = [
    676.5203681218851, -1259.1392167224028, 771.3234287776531,
    -176.6150291621406, 12.507343278686905, -0.13857109526572012,
    9.984369578019572e-6, 1.5056327351493116e-7,
  ];
  if (x < 0.5)
    return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1 - x);
  let sum = 0.9999999999998099;
  const shifted = x - 1;
  g.forEach((coefficient, index) => {
    sum += coefficient / (shifted + index + 1);
  });
  const t = shifted + g.length - 0.5;
  return (
    0.5 * Math.log(2 * Math.PI) +
    (shifted + 0.5) * Math.log(t) -
    t +
    Math.log(sum)
  );
}

// Continued fraction of the regularized incomplete beta function.
function betaFraction(a, b, x) {
  const tiny = 1e-300;
  let c = 1;
  let d = 1 - ((a + b) * x) / (a + 1);
  d = 1 / (Math.abs(d) < tiny ? tiny : d);
  let h = d;
  for (let m = 1; m <= 300; m++) {
    const m2 = 2 * m;
    for (const numerator of [
      (m * (b - m) * x) / ((a + m2 - 1) * (a + m2)),
      (-(a + m) * (a + b + m) * x) / ((a + m2) * (a + m2 + 1)),
    ]) {
      d = 1 + numerator * d;
      d = 1 / (Math.abs(d) < tiny ? tiny : d);
      c = 1 + numerator / c;
      if (Math.abs(c) < tiny) c = tiny;
      h *= d * c;
    }
    if (Math.abs(d * c - 1) < 1e-15) break;
  }
  return h;
}

function incompleteBeta(x, a, b) {
  if (x <= 0) return 0;
  if (x >= 1) return 1;
  const front = Math.exp(
    logGamma(a + b) -
      logGamma(a) -
      logGamma(b) +
      a * Math.log(x) +
      b * Math.log(1 - x),
  );
  return x < (a + 1) / (a + b + 2)
    ? (front * betaFraction(a, b, x)) / a
    : 1 - (front * betaFraction(b, a, 1 - x)) / b;
}

function studentCdf(t, df) {
  const tail = 0.5 * incompleteBeta(df / (df + t * t), df / 2, 0.5);
  return t > 0 ? 1 - tail : tail;
}

/** The Student t quantile, as JMH's commons-math TDistribution computes it. */
export function tQuantile(p, df) {
  let low = 0;
  let high = 1;
  while (studentCdf(high, df) < p) high *= 2;
  for (let step = 0; step < 200; step++) {
    const middle = (low + high) / 2;
    if (studentCdf(middle, df) < p) low = middle;
    else high = middle;
  }
  return (low + high) / 2;
}

/** Mean and JMH's error (99.9% CI half-width) of pooled iteration scores. */
export function summarize(values) {
  const n = values.length;
  const mean = values.reduce((sum, value) => sum + value, 0) / n;
  if (n < 2) return { n, mean, error: NaN };
  const variance =
    values.reduce((sum, value) => sum + (value - mean) ** 2, 0) / (n - 1);
  return {
    n,
    mean,
    error: (tQuantile(0.9995, n - 1) * Math.sqrt(variance)) / Math.sqrt(n),
  };
}

// ---- Results ----

function* jsonFiles(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) yield* jsonFiles(path);
    else if (entry.name.endsWith('.json')) yield { name: entry.name, path };
  }
}

const paramsText = params =>
  Object.entries(params ?? {})
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => `${key}=${value}`)
    .join(', ');

/**
 * Pools JMH JSON records per side and benchmark/mode/threads/params. Files are
 * `base-*.json` and `head-*.json` (one fork each); `meta.json` is a job's
 * wall time.
 */
export function collect(files) {
  const rows = new Map();
  const jobs = [];
  for (const { name, records } of files) {
    if (name === 'meta.json') {
      jobs.push(records);
      continue;
    }
    const side = /^(base|head)-/.exec(name)?.[1];
    if (!side) continue;
    for (const record of records) {
      const params = paramsText(record.params);
      const key = [record.benchmark, record.mode, record.threads, params].join(
        '\u0000',
      );
      const row = rows.get(key) ?? {
        benchmark: record.benchmark,
        mode: record.mode,
        threads: record.threads,
        params,
        unit: record.primaryMetric.scoreUnit,
        base: { score: [], alloc: [], gcTime: [] },
        head: { score: [], alloc: [], gcTime: [] },
      };
      const pool = row[side];
      const secondary = record.secondaryMetrics ?? {};
      pool.score.push(...record.primaryMetric.rawData.flat());
      pool.alloc.push(
        ...(secondary['gc.alloc.rate.norm']?.rawData?.flat() ?? []),
      );
      pool.gcTime.push(...(secondary['gc.time']?.rawData?.flat() ?? []));
      rows.set(key, row);
    }
  }
  return { rows: [...rows.values()], jobs };
}

export function readResults(dir) {
  return collect(
    [...jsonFiles(dir)].map(({ name, path }) => ({
      name,
      records: JSON.parse(readFileSync(path, 'utf8')),
    })),
  );
}

const mean = values =>
  values.length
    ? values.reduce((sum, value) => sum + value, 0) / values.length
    : NaN;

/**
 * Δ% of head against base, and the verdict: `noise` when the two error bars
 * overlap or |Δ| is under the threshold, else `faster` or `slower` (higher is
 * better for throughput, lower for time modes).
 */
export function compare(row, threshold) {
  const base = summarize(row.base.score);
  const head = summarize(row.head.score);
  const delta = ((head.mean - base.mean) / base.mean) * 100;
  const overlap =
    !Number.isFinite(base.error) ||
    !Number.isFinite(head.error) ||
    Math.abs(head.mean - base.mean) <= base.error + head.error;
  const higherIsBetter = row.mode === 'thrpt';
  let verdict = 'noise';
  if (!overlap && Math.abs(delta) >= threshold)
    verdict = delta > 0 === higherIsBetter ? 'faster' : 'slower';
  return {
    base,
    head,
    delta,
    verdict,
    alloc: [mean(row.base.alloc), mean(row.head.alloc)],
    gcTime: [mean(row.base.gcTime), mean(row.head.gcTime)],
  };
}

function decimals(value) {
  // At least four significant digits.
  const magnitude = Math.abs(value);
  if (magnitude >= 1000) return 0;
  if (magnitude >= 100) return 1;
  if (magnitude >= 10) return 2;
  if (magnitude >= 1) return 3;
  return 4;
}

const format = (value, digits = decimals(value)) =>
  Number.isFinite(value)
    ? value.toLocaleString('en-US', {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
      })
    : 'n/a';

const scoreText = ({ mean: value, error }, unit) =>
  `${format(value)} ± ${format(error, decimals(value))} ${unit}`;

const pairText = ([base, head], digits) =>
  Number.isFinite(base) || Number.isFinite(head)
    ? `${format(base, digits)} → ${format(head, digits)}`
    : '-';

const cell = text => String(text).replaceAll('|', '\\|');

const duration = seconds =>
  `${Math.floor(seconds / 60)}m ${String(Math.round(seconds % 60)).padStart(2, '0')}s`;

/** The Markdown report: a header, the table and how to read it. */
export function renderReport({ rows, jobs }, meta) {
  const threshold = meta.threshold;
  const lines = [`## Benchmark A/B: ${meta.profile}`, ''];
  lines.push(
    `Base \`${meta.baseRef}\` (${meta.baseSha.slice(0, 10)}) against head \`${meta.headRef}\` (${meta.headSha.slice(0, 10)}); ` +
      `${meta.rounds} interleaved forks per side on the same runner, noise threshold ±${threshold}%.`,
  );
  if (meta.runUrl) lines.push('', `Run: ${meta.runUrl}`);
  if (meta.runResult && meta.runResult !== 'success')
    lines.push(
      '',
      `> [!WARNING]\n> Some benchmark jobs did not succeed (${meta.runResult}); their rows are missing.`,
    );
  if (!rows.length) {
    lines.push('', 'No JMH results were produced.');
    return `${lines.join('\n')}\n`;
  }
  const sorted = [...rows].sort(
    (left, right) =>
      left.benchmark.localeCompare(right.benchmark) ||
      left.threads - right.threads ||
      left.params.localeCompare(right.params),
  );
  const table = [
    '| Benchmark | Params | Base | Head | Δ | Verdict | B/op base → head | GC ms/iter base → head |',
    '| --- | --- | --- | ---: | ---: | --- | ---: | ---: |',
  ];
  const counts = { faster: 0, slower: 0, noise: 0, missing: 0 };
  for (const row of sorted) {
    const name = row.benchmark.split('.').slice(-2).join('.');
    const params = [
      row.params,
      row.threads === 1 ? '' : `threads=${row.threads}`,
      row.mode === 'thrpt' ? '' : `mode=${row.mode}`,
    ]
      .filter(Boolean)
      .join(', ');
    if (!row.base.score.length || !row.head.score.length) {
      counts.missing++;
      const only = row.base.score.length ? 'base' : 'head';
      const result = summarize(row[only].score);
      table.push(
        `| ${cell(name)} | ${cell(params || '-')} | ${only === 'base' ? scoreText(result, row.unit) : '-'} | ${only === 'head' ? scoreText(result, row.unit) : '-'} | - | only in ${only} | - | - |`,
      );
      continue;
    }
    const result = compare(row, threshold);
    counts[result.verdict]++;
    const verdict =
      result.verdict === 'noise' ? 'noise' : `**${result.verdict}**`;
    table.push(
      `| ${cell(name)} | ${cell(params || '-')} | ${scoreText(result.base, row.unit)} | ${scoreText(result.head, row.unit)} | ${result.delta >= 0 ? '+' : ''}${format(result.delta, 1)}% | ${verdict} | ${pairText(result.alloc, 0)} | ${pairText(result.gcTime, 1)} |`,
    );
  }
  lines.push(
    '',
    `**${counts.slower} slower, ${counts.faster} faster, ${counts.noise} within noise**` +
      (counts.missing ? `, ${counts.missing} on one side only` : '') +
      '.',
    '',
    ...table,
  );
  if (jobs.length) {
    const seconds = jobs.map(job => job.seconds);
    lines.push(
      '',
      `Benchmark time per job: longest ${duration(Math.max(...seconds))} (${jobs.find(job => job.seconds === Math.max(...seconds)).label}), ${jobs.length} ${jobs.length === 1 ? 'job' : 'jobs'}, ${duration(seconds.reduce((sum, value) => sum + value, 0))} in total.`,
    );
  }
  lines.push(
    '',
    '`±` is the 99.9% confidence half-width over every measured iteration of every fork of that side, as JMH computes it. ' +
      `A row is flagged only when the two intervals do not overlap and |Δ| ≥ ${threshold}%; ` +
      '`B/op` (`gc.alloc.rate.norm`) and `GC ms/iter` (`gc.time`) come from `-prof gc`.',
  );
  return `${lines.join('\n')}\n`;
}

function output(values) {
  const { GITHUB_OUTPUT } = process.env;
  const text = Object.entries(values)
    .map(
      ([key, value]) =>
        `${key}=${typeof value === 'string' ? value : JSON.stringify(value)}`,
    )
    .join('\n');
  console.log(text);
  if (GITHUB_OUTPUT) appendFileSync(GITHUB_OUTPUT, `${text}\n`);
}

function summary(markdown) {
  const { GITHUB_STEP_SUMMARY } = process.env;
  if (GITHUB_STEP_SUMMARY) appendFileSync(GITHUB_STEP_SUMMARY, markdown);
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const env = process.env;
  const command = process.argv[2];
  if (command === 'inputs') {
    const settings = resolveInputs({
      include: env.INCLUDE,
      exclude: env.EXCLUDE,
      profile: env.PROFILE || 'quick',
      params: env.PARAMS,
      threads: env.THREADS,
      split: env.SPLIT || 'class',
      threshold: env.THRESHOLD || '3',
      changedPaths: env.CHANGED_FILES
        ? readFileSync(env.CHANGED_FILES, 'utf8').split('\n')
        : undefined,
    });
    output({
      run: String(settings.run),
      patterns: settings.patterns,
      exclude: settings.exclude,
      split: settings.split,
      threshold: String(settings.threshold),
      rounds: String(settings.rounds),
      'jmh-args': settings.jmhArgs,
      threads: settings.threads,
      'jvm-args': JVM_ARGS,
    });
    if (!settings.run)
      summary(
        'No JMH benchmark class changed in this pull request. Dispatch Benchmark A/B with an include pattern to compare others.\n',
      );
  } else if (command === 'plan') {
    const read = path => parseBenchmarkList(readFileSync(path, 'utf8'));
    const plan = planMatrix(
      read(env.BASE_LIST),
      read(env.HEAD_LIST),
      env.SPLIT || 'class',
    );
    if (!plan.matrix.include.length)
      throw new Error('No benchmark matches the include patterns.');
    output({ matrix: plan.matrix });
    const notes = [
      `Planned ${plan.matrix.include.length} benchmark jobs.`,
      ...(plan.skipped.length
        ? [`Skipped (they need external services): ${plan.skipped.join(', ')}`]
        : []),
      ...(plan.onlyBase.length
        ? [`Only in base: ${plan.onlyBase.join(', ')}`]
        : []),
      ...(plan.onlyHead.length
        ? [`Only in head: ${plan.onlyHead.join(', ')}`]
        : []),
    ];
    summary(`${notes.join('\n\n')}\n`);
  } else if (command === 'report') {
    const markdown = renderReport(readResults(env.RESULTS_DIR), {
      profile: env.PROFILE,
      baseRef: env.BASE_REF,
      baseSha: env.BASE_SHA,
      headRef: env.HEAD_REF,
      headSha: env.HEAD_SHA,
      rounds: env.ROUNDS,
      threshold: Number(env.THRESHOLD),
      runUrl: env.RUN_URL,
      runResult: env.RUN_RESULT,
    });
    process.stdout.write(markdown);
  } else {
    throw new Error(`Unknown command: ${command}`);
  }
}
