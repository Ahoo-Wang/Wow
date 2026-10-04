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

/**
 * `node scripts/eval-skills.mjs [skill…]` runs each skill's eval suite
 * (`skills/<name>/evals/<case>/prompt.md` + `graders/*.md`) with
 * `claude plugin eval`, one skill directory at a time, and prints a summary.
 *
 * Local only: every run is a real agent session on your Claude login and costs
 * money, so CI never runs it (CI checks the suites' shape in
 * `scripts/validate_wow_skills.py`). Reports land in
 * `skills/<name>/evals/results/` (git-ignored).
 *
 * Each skill runs in two passes, split by the case's tag:
 * - `activation`: does the skill load (`trigger`) or stay out (`negative`)?
 *   Run with `--ablation none`, because a no-skill arm would only repeat the
 *   question at full price; the `tool_used: Skill` grader is the score. The
 *   cases set `max_turns: 2`: the skill loads (or not) on the first turn, so
 *   more turns only pay for work nobody grades. A run that stops at its turn
 *   limit is still graded and counts as a result, not an error.
 * - `behavior`: is the answer right? Run with `--ablation with-without` by
 *   default, so the score has a no-skill baseline to beat;
 *   `SKILLS_EVAL_ABLATION=none` drops that arm (about half the cost) when
 *   comparing two versions of the skill rather than skill against none. Under
 *   `none` the `tool_used: Skill` grader counts toward the score.
 *
 * Environment:
 * - `CLAUDE_BIN`: the `claude` executable (default `claude`).
 * - `SKILLS_EVAL_MAX_COST`: per-skill `--max-cost-usd` ceiling, shared by the
 *   two passes (default 2).
 * - `SKILLS_EVAL_RUNS`: `--runs` per case, overriding each case's `runs`
 *   (default 1).
 * - `SKILLS_EVAL_CONCURRENCY`: `--concurrency`, 1 to 8 (default 1; each run is
 *   a full `claude` child on one rate limit).
 * - `SKILLS_EVAL_PASSES`: `activation`, `behavior` or both (default
 *   `activation,behavior`).
 * - `SKILLS_EVAL_MODEL`: `--model` for every case (default: the CLI's).
 * - `SKILLS_EVAL_ABLATION`: `--ablation` for the behavior pass, `with-without`
 *   or `none` (default `with-without`); the activation pass is always `none`.
 * - `SKILLS_EVAL_CASE`: `--case` name glob for both passes, to re-run only
 *   some cases (e.g. `{a07-*,b41-*}`; default: every case).
 *
 * Scores are measured, not gated: each pass runs with `--threshold 0`, so a
 * case scoring below 1 does not fail it. The script exits 1 only when a pass
 * did not finish (the cost ceiling, a lost login, an error), and 127 when the
 * `claude` executable cannot be started.
 */
import { spawnSync } from 'node:child_process';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const skillsDir = join(root, 'skills');

function positiveNumber(name, fallback, integer = false) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number(raw);
  if (
    !Number.isFinite(value) ||
    value <= 0 ||
    (integer && !Number.isInteger(value))
  ) {
    console.error(
      `${name} must be a positive ${integer ? 'integer' : 'number'}, got '${raw}'`,
    );
    process.exit(2);
  }
  return value;
}

const ABLATIONS = ['with-without', 'none'];
const ablation = process.env.SKILLS_EVAL_ABLATION || 'with-without';
if (!ABLATIONS.includes(ablation)) {
  console.error(
    `SKILLS_EVAL_ABLATION must be ${ABLATIONS.join(' or ')}, got '${ablation}'`,
  );
  process.exit(2);
}

const PASSES = {
  activation: ['--tag', 'activation', '--ablation', 'none'],
  behavior: ['--tag', 'behavior', '--ablation', ablation],
};

const claude = process.env.CLAUDE_BIN || 'claude';
const maxCost = positiveNumber('SKILLS_EVAL_MAX_COST', 2);
const runs = positiveNumber('SKILLS_EVAL_RUNS', 1, true);
const concurrency = positiveNumber('SKILLS_EVAL_CONCURRENCY', 1, true);
if (concurrency > 8) {
  console.error(
    `SKILLS_EVAL_CONCURRENCY must be 1 to 8 (claude plugin eval's range), got ${concurrency}`,
  );
  process.exit(2);
}
const model = process.env.SKILLS_EVAL_MODEL || undefined;
const caseGlob = process.env.SKILLS_EVAL_CASE || undefined;
const passes = (process.env.SKILLS_EVAL_PASSES || 'activation,behavior')
  .split(',')
  .map(pass => pass.trim());
for (const pass of passes)
  if (!PASSES[pass]) {
    console.error(
      `SKILLS_EVAL_PASSES: unknown pass '${pass}' (use ${Object.keys(PASSES).join(', ')})`,
    );
    process.exit(2);
  }

const all = readdirSync(skillsDir, { withFileTypes: true })
  .filter(
    entry =>
      entry.isDirectory() && existsSync(join(skillsDir, entry.name, 'evals')),
  )
  .map(entry => entry.name)
  .sort();
const requested = process.argv.slice(2);
const unknown = requested.filter(name => !all.includes(name));
if (unknown.length > 0) {
  console.error(
    `No eval suite for: ${unknown.join(', ')} (have: ${all.join(', ')})`,
  );
  process.exit(2);
}
const skills = requested.length > 0 ? requested : all;
const stamp = new Date().toISOString().replace(/[:.]/g, '-');

/** The `claude plugin eval --json` result of one pass, or undefined. */
function readResult(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch {
    return undefined;
  }
}

/**
 * What a pass cost: agent and judge spend of every run in both arms, or the
 * reported total when that is higher.
 */
function spend(result) {
  let runs = 0;
  for (const testCase of result?.cases ?? [])
    for (const arm of Object.values(testCase.arms ?? {}))
      for (const run of arm ?? [])
        runs += (run.costUsd ?? 0) + (run.judgeCostUsd ?? 0);
  return Math.max(runs, result?.costUsd ?? 0);
}

/**
 * A run that did not produce a gradable result. Reaching `max_turns` is not
 * one: the CLI still grades the transcript, and activation cases stop at their
 * turn limit on purpose.
 */
const runFailed = run =>
  Boolean(run.error) && !/maximum number of turns/i.test(run.error);

const graderPassed = (run, name) =>
  run.graders?.find(grader => grader.name === name)?.passed;
const ratio = (part, whole) => (whole === 0 ? undefined : part / whole);
const percent = value =>
  value === undefined ? '—' : `${Math.round(value * 100)}%`;
const mean = values =>
  values.length === 0
    ? undefined
    : values.reduce((sum, value) => sum + value, 0) / values.length;

/**
 * Trigger metrics over every with-skill run: a `trigger` case passes its
 * `skill-fired` grader, a `negative` case its `skill-not-fired` grader.
 */
function activationMetrics(result) {
  let truePositive = 0;
  let falseNegative = 0;
  let falsePositive = 0;
  let trueNegative = 0;
  let errors = 0;
  for (const testCase of result?.cases ?? [])
    for (const run of testCase.arms?.with ?? []) {
      if (runFailed(run)) {
        errors++;
        continue;
      }
      const fired = graderPassed(run, 'skill-fired');
      const silent = graderPassed(run, 'skill-not-fired');
      if (fired === true) truePositive++;
      else if (fired === false) falseNegative++;
      else if (silent === true) trueNegative++;
      else if (silent === false) falsePositive++;
    }
  return {
    truePositive,
    falseNegative,
    falsePositive,
    trueNegative,
    errors,
    recall: ratio(truePositive, truePositive + falseNegative),
    precision: ratio(truePositive, truePositive + falsePositive),
    negativesSilent: ratio(trueNegative, trueNegative + falsePositive),
  };
}

/** Behavior metrics: per-case pass rate and score with and without the skill, and how often it loaded. */
function behaviorMetrics(result) {
  const withRuns = [];
  const withoutRuns = [];
  let fired = 0;
  let errors = 0;
  for (const testCase of result?.cases ?? []) {
    for (const run of testCase.arms?.with ?? []) {
      if (runFailed(run)) errors++;
      else {
        withRuns.push(run);
        if (graderPassed(run, 'skill-fired')) fired++;
      }
    }
    for (const run of testCase.arms?.without ?? [])
      if (!runFailed(run)) withoutRuns.push(run);
  }
  return {
    cases: result?.cases?.length ?? 0,
    runs: withRuns.length,
    errors,
    passRate: mean(withRuns.map(run => (run.passed ? 1 : 0))),
    score: mean(withRuns.map(run => run.score)),
    withoutPassRate: mean(withoutRuns.map(run => (run.passed ? 1 : 0))),
    withoutScore: mean(withoutRuns.map(run => run.score)),
    loaded: ratio(fired, withRuns.length),
  };
}

const meaning = code =>
  ({
    0: 'pass',
    1: 'error',
    2: 'cost ceiling hit',
    127: 'claude not found',
  })[code] ?? 'error';

const probe = spawnSync(claude, ['--version'], { encoding: 'utf8' });
if (probe.error || probe.status !== 0) {
  console.error(
    `Cannot run ${claude} --version: ${probe.error?.message ?? probe.stderr?.trim() ?? `exit ${probe.status}`}. Set CLAUDE_BIN to the claude executable.`,
  );
  process.exit(127);
}

const results = [];
for (const skill of skills) {
  const cwd = join(skillsDir, skill);
  const outDir = join(cwd, 'evals', 'results', `summary-${stamp}`);
  mkdirSync(outDir, { recursive: true });
  const entry = { skill, cost: 0, passes: {} };
  for (const pass of passes) {
    const budget = Math.round((maxCost - entry.cost) * 100) / 100;
    if (budget <= 0) {
      entry.passes[pass] = { code: 2, skipped: true };
      continue;
    }
    console.log(
      `\n=== ${skill} · ${pass} (runs ${runs}, max $${budget}, concurrency ${concurrency})`,
    );
    const json = join(outDir, `${pass}.json`);
    const started = Date.now();
    const run = spawnSync(
      claude,
      [
        'plugin',
        'eval',
        '.',
        '--trust-plugin',
        '--no-publish',
        ...PASSES[pass],
        '--runs',
        String(runs),
        '--concurrency',
        String(concurrency),
        '--max-cost-usd',
        String(budget),
        '--threshold',
        '0',
        '--json',
        json,
        ...(model ? ['--model', model] : []),
        ...(caseGlob ? ['--case', caseGlob] : []),
      ],
      { cwd, stdio: 'inherit' },
    );
    if (run.error) {
      console.error(`  could not start ${claude}: ${run.error.message}`);
      process.exit(127);
    }
    const result = readResult(json);
    const cost = spend(result);
    entry.cost += cost;
    entry.passes[pass] = {
      code: run.error ? 127 : (run.status ?? 1),
      seconds: Math.round((Date.now() - started) / 1000),
      cost,
      partial: result?.partial ? (result.partialReason ?? true) : false,
      metrics:
        pass === 'activation'
          ? activationMetrics(result)
          : behaviorMetrics(result),
    };
  }
  writeFileSync(
    join(outDir, 'summary.json'),
    `${JSON.stringify(entry, null, 2)}\n`,
  );
  results.push(entry);
}

const rows = [
  '| Skill | Recall | Precision | Negatives silent | Behavior pass (with / without) | Score (with / without) | Loaded in behavior | Cost |',
  '| --- | --- | --- | --- | --- | --- | --- | --- |',
];
for (const { skill, cost, passes: done } of results) {
  const a = done.activation?.metrics;
  const b = done.behavior?.metrics;
  rows.push(
    `| ${skill} | ${a ? `${percent(a.recall)} (${a.truePositive}/${a.truePositive + a.falseNegative})` : '—'} | ${a ? percent(a.precision) : '—'} | ${a ? `${percent(a.negativesSilent)} (${a.trueNegative}/${a.trueNegative + a.falsePositive})` : '—'} | ${b ? `${percent(b.passRate)} / ${percent(b.withoutPassRate)}` : '—'} | ${b?.score === undefined ? '—' : `${b.score.toFixed(2)} / ${b.withoutScore === undefined ? '—' : b.withoutScore.toFixed(2)}`} | ${b ? percent(b.loaded) : '—'} | $${cost.toFixed(2)} |`,
  );
}
const total = results.reduce((sum, { cost }) => sum + cost, 0);
console.log(
  `\nSkill evals (runs ${runs}, behavior ablation ${ablation}):\n\n${rows.join('\n')}\n\nTotal cost: $${total.toFixed(2)}`,
);
for (const { skill, passes: done } of results)
  for (const [pass, { code, partial, metrics }] of Object.entries(done))
    if (code !== 0 || partial || metrics?.errors)
      console.log(
        `  ${skill} ${pass}: exit ${code} (${partial ? `stopped: ${partial}` : meaning(code)})${metrics?.errors ? `, ${metrics.errors} run errors` : ''}`,
      );
const failed = results.filter(({ passes: done }) =>
  Object.values(done).some(({ code }) => code !== 0),
);
process.exit(failed.length === 0 ? 0 : 1);
