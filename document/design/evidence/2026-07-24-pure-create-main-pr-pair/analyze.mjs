import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const RESULT_NAME = "threads-14-pure-create-main-pr-pair.json";
const HUMAN_NAME = "threads-14-pure-create-main-pr-pair-human.txt";
const MANIFEST_NAME = "threads-14-pure-create-main-pr-pair.manifest.json";
const T_ONE_SIDED_95_DF7 = 1.894578605;
const T_TWO_SIDED_95_DF7 = 2.364624251;

const variants = {
  baseline: {
    commit: "220e0fc8652e0871aa152f085b69efd14c853de1",
    jar: "aa189666b74ce7051efc02fb0fd0bcb0e515339e4be6b64e9b9b22efd22148c9",
  },
  candidate: {
    commit: "b38afec22ae7462b581434fd7bb6c7c605b757f5",
    jar: "f9373063021d2fcb15b3d0adc7162a0b9583509b92609d606089b65dbb6d15d3",
  },
};

const pairs = ["AB", "BA", "BA", "AB", "BA", "AB", "AB", "BA"].map(
  (order, index) => ({
    id: String(index + 1).padStart(2, "0"),
    order,
  }),
);

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function assertRunSpec(manifest) {
  const spec = manifest.runSpec;
  assert(spec.suite === "pure-create-main-pr-pair", "Unexpected suite");
  assert(spec.profile === "pure-create-main-pr-pair", "Unexpected profile");
  assert(spec.threads === 14, "Unexpected JMH thread count");
  assert(JSON.stringify(spec.modes) === JSON.stringify(["thrpt"]), "Unexpected mode");
  assert(spec.warmupIterations === 2 && spec.warmupTime === "5s", "Unexpected warmup");
  assert(
    spec.measurementIterations === 3 && spec.measurementTime === "10s",
    "Unexpected measurement",
  );
  assert(spec.forks === 1, "Unexpected fork count");
  assert(
    JSON.stringify(spec.parameters) ===
      JSON.stringify({
        schedulerStrategy: "PARALLEL",
        schedulerPoolSize: "14",
        stripeCount: "896",
      }),
    "Unexpected scheduler parameters",
  );
  assert(spec.requestedProfilers.length === 0, "Profiler must be disabled");
}

function loadRun(pair, variant) {
  const directory = path.join(ROOT, `pair-${pair.id}-${pair.order.toLowerCase()}`, variant);
  const resultFile = path.join(directory, RESULT_NAME);
  const humanFile = path.join(directory, HUMAN_NAME);
  const manifestFile = path.join(directory, MANIFEST_NAME);
  const results = readJson(resultFile);
  const manifest = readJson(manifestFile);
  const expected = variants[variant];

  assert(manifest.schemaVersion === 1, `${directory}: unexpected manifest schema`);
  assert(manifest.status === "SUCCESS", `${directory}: run did not succeed`);
  assert(manifest.source.commit === expected.commit, `${directory}: unexpected commit`);
  assert(manifest.source.dirty === false, `${directory}: source is dirty`);
  assert(manifest.source.jmhJarSha256 === expected.jar, `${directory}: unexpected JMH JAR`);
  assertRunSpec(manifest);
  assert(manifest.artifacts.result.path === RESULT_NAME, `${directory}: result path mismatch`);
  assert(manifest.artifacts.human.path === HUMAN_NAME, `${directory}: human path mismatch`);
  assert(manifest.artifacts.result.rowCount === 2, `${directory}: unexpected result count`);
  assert(fs.statSync(resultFile).size === manifest.artifacts.result.size, `${directory}: result size`);
  assert(fs.statSync(humanFile).size === manifest.artifacts.human.size, `${directory}: human size`);
  assert(sha256(resultFile) === manifest.artifacts.result.sha256, `${directory}: result SHA-256`);
  assert(sha256(humanFile) === manifest.artifacts.human.sha256, `${directory}: human SHA-256`);
  assert(results.length === 2, `${directory}: expected MongoDB and Redis rows`);

  const scores = {};
  for (const backend of ["Mongo", "Redis"]) {
    const row = results.find((result) => result.benchmark.includes(`${backend}CommandWriteE2EBenchmark`));
    assert(row !== undefined, `${directory}: missing ${backend} result`);
    assert(row.benchmark.endsWith(".sendAndWaitProcessed"), `${directory}: unexpected method`);
    assert(row.mode === "thrpt" && row.threads === 14, `${directory}: unexpected JMH identity`);
    assert(row.primaryMetric.scoreUnit === "ops/s", `${directory}: unexpected score unit`);
    assert(Number.isFinite(row.primaryMetric.score) && row.primaryMetric.score > 0, `${directory}: score`);
    scores[backend] = row.primaryMetric.score;
  }

  return { manifest, scores };
}

function mean(values) {
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function summarize(rows) {
  const logRatios = rows.map((row) => row.logRatio);
  const logMean = mean(logRatios);
  const logSd = Math.sqrt(
    logRatios.reduce((sum, value) => sum + (value - logMean) ** 2, 0) /
      (logRatios.length - 1),
  );
  const standardError = logSd / Math.sqrt(logRatios.length);
  const ab = rows.filter((row) => row.order === "AB").map((row) => row.logRatio);
  const ba = rows.filter((row) => row.order === "BA").map((row) => row.logRatio);

  return {
    n: rows.length,
    baselineArithmeticMean: mean(rows.map((row) => row.baseline)),
    candidateArithmeticMean: mean(rows.map((row) => row.candidate)),
    geometricRatio: Math.exp(logMean),
    gainPct: (Math.exp(logMean) - 1) * 100,
    oneSided95LowerRatio: Math.exp(logMean - T_ONE_SIDED_95_DF7 * standardError),
    oneSided95LowerGainPct:
      (Math.exp(logMean - T_ONE_SIDED_95_DF7 * standardError) - 1) * 100,
    twoSided95Ratio: [
      Math.exp(logMean - T_TWO_SIDED_95_DF7 * standardError),
      Math.exp(logMean + T_TWO_SIDED_95_DF7 * standardError),
    ],
    twoSided95GainPct: [
      (Math.exp(logMean - T_TWO_SIDED_95_DF7 * standardError) - 1) * 100,
      (Math.exp(logMean + T_TWO_SIDED_95_DF7 * standardError) - 1) * 100,
    ],
    abGeometricRatio: Math.exp(mean(ab)),
    baGeometricRatio: Math.exp(mean(ba)),
    orderEffectPct: (Math.exp((mean(ab) - mean(ba)) / 2) - 1) * 100,
    passesTwentyPercentGate:
      Math.exp(logMean - T_ONE_SIDED_95_DF7 * standardError) > 1.2,
  };
}

const runs = [];
const rows = { Mongo: [], Redis: [] };

for (const pair of pairs) {
  const baseline = loadRun(pair, "baseline");
  const candidate = loadRun(pair, "candidate");
  const first = pair.order === "AB" ? baseline : candidate;
  const second = pair.order === "AB" ? candidate : baseline;

  assert(
    Date.parse(first.manifest.completedAt) < Date.parse(second.manifest.startedAt),
    `pair-${pair.id}: invocations overlap or order is wrong`,
  );
  runs.push(first.manifest, second.manifest);

  for (const backend of ["Mongo", "Redis"]) {
    const baselineScore = baseline.scores[backend];
    const candidateScore = candidate.scores[backend];
    const ratio = candidateScore / baselineScore;
    rows[backend].push({
      pair: pair.id,
      order: pair.order,
      baseline: baselineScore,
      candidate: candidateScore,
      ratio,
      logRatio: Math.log(ratio),
    });
  }
}

for (let index = 1; index < runs.length; index += 1) {
  assert(
    Date.parse(runs[index - 1].completedAt) < Date.parse(runs[index].startedAt),
    `formal invocation ${index + 1} overlaps its predecessor`,
  );
}

const runtimeIdentities = new Set(runs.map((manifest) => JSON.stringify(manifest.runtime)));
assert(runtimeIdentities.size === 1, "Runtime identity changed between invocations");

const csv = [
  [
    "pair",
    "order",
    "mongo_baseline_ops_s",
    "mongo_candidate_ops_s",
    "mongo_ratio",
    "mongo_gain_pct",
    "redis_baseline_ops_s",
    "redis_candidate_ops_s",
    "redis_ratio",
    "redis_gain_pct",
  ].join(","),
  ...pairs.map((pair, index) => {
    const mongo = rows.Mongo[index];
    const redis = rows.Redis[index];
    return [
      pair.id,
      pair.order,
      mongo.baseline.toFixed(6),
      mongo.candidate.toFixed(6),
      mongo.ratio.toFixed(9),
      ((mongo.ratio - 1) * 100).toFixed(6),
      redis.baseline.toFixed(6),
      redis.candidate.toFixed(6),
      redis.ratio.toFixed(9),
      ((redis.ratio - 1) * 100).toFixed(6),
    ].join(",");
  }),
].join("\n");
assert(
  fs.readFileSync(path.join(ROOT, "summary.csv"), "utf8").trim() === csv,
  "summary.csv does not match the raw JMH results",
);

const summary = {
  Mongo: summarize(rows.Mongo),
  Redis: summarize(rows.Redis),
};

console.log(
  JSON.stringify(
    {
      verified: {
        pairs: pairs.length,
        invocations: runs.length,
        artifacts: runs.length * 3,
        noOverlap: true,
        runtimeIdentities: runtimeIdentities.size,
      },
      rows,
      summary,
      combinedGatePass:
        summary.Mongo.passesTwentyPercentGate && summary.Redis.passesTwentyPercentGate,
    },
    null,
    2,
  ),
);
