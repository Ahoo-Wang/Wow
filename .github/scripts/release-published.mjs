/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { ROOT, parseVersion } from './project-version.mjs';
import { releasePlan } from './release-plan.mjs';
import { waitForFiles } from './registry-wait.mjs';

// Reads back what a release published, as anyone pulls it: anonymously, and
// changing nothing. Run after each publish of a release (image-deploy.yml's
// image-check, chart-deploy.yml's chart-check, package-deploy.yml's
// maven-check), or by hand against any published version:
//
//   node .github/scripts/release-published.mjs --version <v> [--only images|chart|maven]
//
// - images: each of IMAGES on each of IMAGE_REGISTRIES has the version's tag,
//   an index with linux/amd64 and linux/arm64, and one digest on all three.
//   The floating tags (`<x.y>`, `latest`) release-plan.mjs gives the version,
//   from the v* tags of this checkout, point at that digest; the ones it does
//   not give (all of them for a pre-release) do not.
// - chart: CHART on each of CHART_REGISTRIES at the version, its Chart.yaml
//   (the chart's config, as `helm show chart` prints it) naming that version
//   as `version` and `appVersion`.
// - maven: the POM of the BOM, of every me.ahoo.wow module it lists, of
//   DEPENDENCIES and of each plugin marker (PLUGIN_IDS), on Maven Central at
//   the version. Central serves a release some minutes after it accepts it,
//   so this waits, up to MAVEN_TIMEOUT_MS, reading every MAVEN_INTERVAL_MS.
//
// A failure names the registry, the artifact and what is missing. Every check
// is a read: re-running it publishes nothing.

/**
 * What Maven Central gets beside the modules the BOM lists: the platform every
 * module's POM imports (`:wow-dependencies`, root build.gradle.kts
 * `publicationProjects`), and the marker of each Gradle plugin id
 * (compiler/gradle-plugin's `gradlePlugin`), which `plugins { id(...) }`
 * resolves: group the id, artifact `<id>.gradle.plugin`.
 */
export const DEPENDENCIES = 'wow-dependencies';
export const PLUGIN_IDS = [
  'me.ahoo.wow',
  'me.ahoo.wow.api',
  'me.ahoo.wow.service',
];

/** The server images image-deploy.yml pushes. */
export const IMAGES = [
  'wow-ops-server',
  'wow-view-store-server',
  'wow-analytics-catalog-server',
  'wow-migrate-data',
];

/** Where image-deploy.yml pushes the images: a registry and its namespace. */
export const IMAGE_REGISTRIES = [
  { name: 'GHCR', host: 'ghcr.io', namespace: 'ahoo-wang' },
  { name: 'Docker Hub', host: 'registry-1.docker.io', namespace: 'ahoowang' },
  {
    name: 'Aliyun',
    host: 'registry.cn-shanghai.aliyuncs.com',
    namespace: 'ahoo',
  },
];

/** The platforms every image index carries. */
export const PLATFORMS = ['linux/amd64', 'linux/arm64'];

/** The Helm chart chart-deploy.yml pushes, and where (Aliyun refuses an OCI chart). */
export const CHART = 'wow-service';
export const CHART_REGISTRIES = IMAGE_REGISTRIES.slice(0, 2);

export const MAVEN_CENTRAL = 'https://repo1.maven.org/maven2';
export const GROUP = 'me.ahoo.wow';
/** Central took 10 to 20 minutes to serve a beta; an hour is the bound. */
export const MAVEN_TIMEOUT_MS = 3_600_000;
export const MAVEN_INTERVAL_MS = 30_000;

export const CHECKS = ['images', 'chart', 'maven'];

const INDEX_TYPES = [
  'application/vnd.oci.image.index.v1+json',
  'application/vnd.docker.distribution.manifest.list.v2+json',
];
const MANIFEST_TYPES = [
  'application/vnd.oci.image.manifest.v1+json',
  'application/vnd.docker.distribution.manifest.v2+json',
];
const HELM_CONFIG = 'application/vnd.cncf.helm.config.v1+json';

/** How often a read that fails on the way (no answer, or a 5xx) is made. */
const READ_ATTEMPTS = 3;
const READ_DELAY_MS = 5_000;
const READ_TIMEOUT_MS = 30_000;

export const UNCHANGED =
  'This check only reads: it changed nothing published, and re-running it publishes nothing.';

/** The arguments: `--version <v>` and an optional `--only <check>`. */
export function parseArgs(args) {
  const options = { checks: CHECKS };
  for (let i = 0; i < args.length; i++) {
    const value = args[i + 1];
    if (args[i] === '--version' && value !== undefined) {
      if (!/^\d/.test(value) || !parseVersion(value))
        throw new Error(
          `--version ${value} is not a release version (such as 10.0.0 or 10.1.0-rc.1)`,
        );
      options.version = value;
      i++;
    } else if (args[i] === '--only' && CHECKS.includes(value)) {
      options.checks = [value];
      i++;
    } else
      throw new Error(
        `unknown argument ${args[i]}: release-published.mjs --version <v> [--only ${CHECKS.join('|')}]`,
      );
  }
  if (!options.version) throw new Error('--version <v> is required');
  return options;
}

/** The parameters of a `WWW-Authenticate: Bearer` challenge. */
export function parseChallenge(header) {
  if (!header || !/^bearer\s/i.test(header)) return undefined;
  return Object.fromEntries(
    [...header.matchAll(/(\w+)="([^"]*)"/g)].map(([, key, value]) => [
      key,
      value,
    ]),
  );
}

/**
 * How long a 429's Retry-After is waited for, at most: an anonymous pull limit
 * that a minute does not lift is reported, not waited out.
 */
const RATE_LIMIT_WAIT_MS = 60_000;

/** The wait a 429's `Retry-After` (seconds or an HTTP date) asks for, bounded. */
export function retryAfterMs(header, now = Date.now()) {
  if (!header) return READ_DELAY_MS;
  const seconds = Number(header);
  const ms = Number.isFinite(seconds)
    ? seconds * 1000
    : Date.parse(header) - now;
  if (!Number.isFinite(ms)) return READ_DELAY_MS;
  return Math.min(Math.max(ms, 0), RATE_LIMIT_WAIT_MS);
}

/**
 * A read, made again (READ_ATTEMPTS in all) when it gets no answer, a 5xx, or
 * a 429: a rate limit is "not yet", waited for as its Retry-After says, up to
 * RATE_LIMIT_WAIT_MS. Any other answer is returned.
 */
export async function retryingRead(
  url,
  init,
  {
    fetch = globalThis.fetch,
    sleep = ms => new Promise(done => setTimeout(done, ms)),
  } = {},
) {
  for (let attempt = 1; ; attempt++) {
    let failure;
    let wait = READ_DELAY_MS;
    try {
      const response = await fetch(url, {
        ...init,
        cache: 'no-store',
        signal: AbortSignal.timeout(READ_TIMEOUT_MS),
      });
      if (response.status === 429) {
        const header = response.headers.get('retry-after');
        wait = retryAfterMs(header);
        failure = `answered 429: the registry's rate limit${header ? ` (Retry-After ${header})` : ''}`;
      } else if (response.status < 500) return response;
      else failure = `answered ${response.status}`;
      await response.body?.cancel();
    } catch (error) {
      failure = `could not be read: ${error.cause?.message ?? error.message}`;
    }
    if (attempt >= READ_ATTEMPTS)
      throw new Error(`${url} ${failure} (${attempt} attempts)`);
    await sleep(wait);
  }
}

/**
 * An anonymous reader of OCI registries: each repository's pull token comes
 * from the realm the registry's own 401 names, as `docker pull` gets one.
 */
export function registryReader({
  fetch = globalThis.fetch,
  sleep = ms => new Promise(done => setTimeout(done, ms)),
} = {}) {
  const tokens = new Map();

  const read = (url, init) => retryingRead(url, init, { fetch, sleep });

  async function token(challenge) {
    const { realm, service, scope } = challenge;
    const url = new URL(realm);
    if (service) url.searchParams.set('service', service);
    if (scope) url.searchParams.set('scope', scope);
    const response = await read(url.href, {});
    if (response.status !== 200)
      throw new Error(
        `${url.origin} refused an anonymous token for ${scope}: ${response.status}`,
      );
    const body = await response.json();
    return body.token ?? body.access_token;
  }

  /** A GET or HEAD of /v2/<repository>/<path>, with the repository's token. */
  async function get(host, repository, path, { method = 'GET', accept } = {}) {
    const url = `https://${host}/v2/${repository}/${path}`;
    const key = `${host}/${repository}`;
    const headers = accept ? { accept } : {};
    const auth = () =>
      tokens.has(key) ? { authorization: `Bearer ${tokens.get(key)}` } : {};
    let response = await read(url, {
      method,
      headers: { ...headers, ...auth() },
    });
    const challenge = parseChallenge(response.headers.get('www-authenticate'));
    if (response.status === 401 && challenge) {
      await response.body?.cancel();
      tokens.set(key, await token(challenge));
      response = await read(url, {
        method,
        headers: { ...headers, ...auth() },
      });
    }
    return response;
  }

  return {
    /**
     * A tag's (or digest's) manifest: `{ status, digest, mediaType, body }`;
     * HEAD reads only the digest.
     */
    async manifest(host, repository, reference, { head = false } = {}) {
      const response = await get(host, repository, `manifests/${reference}`, {
        method: head ? 'HEAD' : 'GET',
        accept: [...INDEX_TYPES, ...MANIFEST_TYPES].join(', '),
      });
      const status = response.status;
      if (status !== 200) {
        await response.body?.cancel();
        return { status };
      }
      const mediaType = response.headers.get('content-type')?.split(';')[0];
      let digest = response.headers.get('docker-content-digest') ?? undefined;
      if (head) return { status, digest, mediaType };
      const text = await response.text();
      digest ??= `sha256:${createHash('sha256').update(text).digest('hex')}`;
      return { status, digest, mediaType, body: JSON.parse(text) };
    },

    /** A blob's JSON (a chart's config), following the registry's redirect. */
    async blob(host, repository, digest) {
      const response = await get(host, repository, `blobs/${digest}`);
      if (response.status !== 200) {
        await response.body?.cancel();
        return { status: response.status };
      }
      return { status: 200, body: await response.json() };
    },
  };
}

/** Where a registry's repository is, as a problem names it. */
function at(registry, repository) {
  return `${registry.name} (${registry.host}/${repository})`;
}

/** The `os/architecture` of each image manifest an index lists. */
export function indexPlatforms(index) {
  return (index?.manifests ?? [])
    .filter(({ platform }) => platform && platform.os !== 'unknown')
    .map(({ platform }) =>
      [platform.os, platform.architecture, platform.variant]
        .filter(Boolean)
        .join('/'),
    );
}

/**
 * The images of `plan` (release-plan.mjs) on every registry. Returns the
 * `lines` read and the `problems` found.
 */
export async function checkImages(plan, reader) {
  const lines = [];
  const problems = [];
  const version = plan.version;
  const given = plan.imageTags.filter(tag => tag !== version);
  const floating = [plan.line, 'latest'];
  for (const image of IMAGES) {
    const digests = new Map();
    for (const registry of IMAGE_REGISTRIES) {
      const repository = `${registry.namespace}/${image}`;
      const where = at(registry, repository);
      const read = await reader.manifest(registry.host, repository, version);
      if (read.status === 404) {
        problems.push(`${where}: ${image}:${version} is missing (404)`);
        continue;
      }
      if (read.status !== 200) {
        problems.push(
          `${where}: ${image}:${version} could not be read (${read.status})`,
        );
        continue;
      }
      digests.set(registry.name, read.digest);
      const platforms = indexPlatforms(read.body);
      lines.push(
        `${where}:${version} ${read.digest} ${read.mediaType} [${platforms.join(', ')}]`,
      );
      if (!INDEX_TYPES.includes(read.mediaType ?? read.body?.mediaType)) {
        problems.push(
          `${where}: ${image}:${version} is a single-platform ${read.mediaType}, not an index of ${PLATFORMS.join(' and ')}`,
        );
        continue;
      }
      const absent = PLATFORMS.filter(
        platform => !platforms.includes(platform),
      );
      if (absent.length > 0)
        problems.push(
          `${where}: the index of ${image}:${version} has no ${absent.join(' or ')} (it has ${platforms.join(', ') || 'none'})`,
        );
    }
    const distinct = new Set(digests.values());
    if (distinct.size > 1)
      problems.push(
        `${image}:${version} differs between the registries: ${[...digests].map(([name, digest]) => `${name} ${digest}`).join(', ')}`,
      );
    // Floating tags are compared with the version's digest where one was read.
    const digest = digests.get(IMAGE_REGISTRIES[0].name) ?? [...distinct][0];
    if (digest === undefined || distinct.size > 1) continue;
    for (const registry of IMAGE_REGISTRIES) {
      if (!digests.has(registry.name)) continue;
      const repository = `${registry.namespace}/${image}`;
      const where = at(registry, repository);
      for (const tag of floating) {
        const read = await reader.manifest(registry.host, repository, tag, {
          head: true,
        });
        const points =
          read.status === 200 ? read.digest : `none (${read.status})`;
        lines.push(`${where}:${tag} ${points}`);
        if (given.includes(tag) && points !== digest)
          problems.push(
            `${where}: ${image}:${tag} should point at ${version} (${digest}) but points at ${points}`,
          );
        if (!given.includes(tag) && points === digest)
          problems.push(
            `${where}: ${image}:${tag} was moved to ${version} (${digest}), which ${plan.prerelease ? 'as a pre-release moves no floating tag' : `does not get ${tag}`}`,
          );
      }
    }
  }
  return { lines, problems };
}

/** The chart at `version` on every chart registry. */
export async function checkChart(version, reader) {
  const lines = [];
  const problems = [];
  for (const registry of CHART_REGISTRIES) {
    const repository = `${registry.namespace}/${CHART}`;
    const where = at(registry, repository);
    const read = await reader.manifest(registry.host, repository, version);
    if (read.status !== 200) {
      problems.push(
        `${where}: the chart ${CHART} ${version} is ${read.status === 404 ? 'missing (404)' : `unreadable (${read.status})`}`,
      );
      continue;
    }
    const config = read.body?.config;
    if (config?.mediaType !== HELM_CONFIG) {
      problems.push(
        `${where}: ${CHART}:${version} is not a Helm chart (its config is ${config?.mediaType ?? 'missing'})`,
      );
      continue;
    }
    const chart = await reader.blob(registry.host, repository, config.digest);
    if (chart.status !== 200) {
      problems.push(
        `${where}: the Chart.yaml of ${CHART} ${version} (${config.digest}) is unreadable (${chart.status})`,
      );
      continue;
    }
    const { name, version: chartVersion, appVersion } = chart.body;
    lines.push(
      `${where}:${version} ${read.digest} name ${name}, version ${chartVersion}, appVersion ${appVersion}`,
    );
    for (const [key, value, expected] of [
      ['name', name, CHART],
      ['version', chartVersion, version],
      ['appVersion', appVersion, version],
    ])
      if (value !== expected)
        problems.push(
          `${where}: the Chart.yaml of ${CHART} ${version} has ${key} ${value ?? '(none)'}, not ${expected}`,
        );
  }
  return { lines, problems };
}

/** The URL of me.ahoo.wow:artifact:version's POM on Maven Central. */
export function pomUrl(artifact, version, group = GROUP) {
  return `${MAVEN_CENTRAL}/${group.replaceAll('.', '/')}/${artifact}/${version}/${artifact}-${version}.pom`;
}

/** The `{ groupId, artifactId, version }` of each dependency a BOM manages. */
export function bomEntries(pom) {
  const management =
    /<dependencyManagement>([\s\S]*?)<\/dependencyManagement>/.exec(pom)?.[1];
  if (!management) return [];
  return [...management.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)].map(
    ([, dependency]) => {
      const field = name =>
        new RegExp(`<${name}>\\s*([^<]*?)\\s*</${name}>`).exec(dependency)?.[1];
      return {
        groupId: field('groupId'),
        artifactId: field('artifactId'),
        version: field('version'),
      };
    },
  );
}

/**
 * The POMs of the BOM and every me.ahoo.wow module it lists, on Maven
 * Central: waits for the BOM, then for the modules, both within one bound.
 */
export async function checkMaven(
  version,
  {
    timeoutMs = MAVEN_TIMEOUT_MS,
    intervalMs = MAVEN_INTERVAL_MS,
    fetch = globalThis.fetch,
    sleep = ms => new Promise(done => setTimeout(done, ms)),
    now = Date.now,
    log = console.log,
  } = {},
) {
  const lines = [];
  const problems = [];
  const started = now();
  const wait = files =>
    waitForFiles(files, {
      timeoutMs: Math.max(0, timeoutMs - (now() - started)),
      intervalMs,
      kind: 'POM',
      registry: 'Maven Central',
      fetch,
      sleep,
      log,
    });
  const bom = pomUrl('wow-bom', version);
  try {
    await wait([[`${GROUP}:wow-bom:${version}`, bom]]);
  } catch (error) {
    problems.push(error.message);
    return { lines, problems };
  }
  let response;
  try {
    response = await retryingRead(bom, {}, { fetch, sleep });
  } catch (error) {
    problems.push(`Maven Central: ${error.message}`);
    return { lines, problems };
  }
  if (response.status !== 200) {
    problems.push(
      `Maven Central: ${bom} answered ${response.status} once served`,
    );
    return { lines, problems };
  }
  const entries = bomEntries(await response.text()).filter(
    ({ groupId }) => groupId === GROUP,
  );
  if (entries.length === 0) {
    problems.push(`Maven Central: ${bom} lists no ${GROUP} module`);
    return { lines, problems };
  }
  const modules = [];
  for (const entry of entries)
    if (entry.version === version) modules.push(entry.artifactId);
    else
      problems.push(
        `Maven Central: wow-bom ${version} lists ${GROUP}:${entry.artifactId} at ${entry.version}, not ${version}`,
      );
  const markers = PLUGIN_IDS.map(id => [id, `${id}.gradle.plugin`]);
  try {
    await wait([
      ...[...modules, DEPENDENCIES].map(artifact => [
        `${GROUP}:${artifact}:${version}`,
        pomUrl(artifact, version),
      ]),
      ...markers.map(([group, artifact]) => [
        `${group}:${artifact}:${version}`,
        pomUrl(artifact, version, group),
      ]),
    ]);
    lines.push(
      `Maven Central: wow-bom ${version} and the ${modules.length} modules it lists: ${modules.join(', ')}`,
      `Maven Central: ${GROUP}:${DEPENDENCIES} ${version}`,
      `Maven Central: the plugin markers ${markers.map(([group, artifact]) => `${group}:${artifact}`).join(', ')} ${version}`,
    );
  } catch (error) {
    problems.push(error.message);
  }
  return { lines, problems };
}

/** The v* tags of this checkout, which decide the floating image tags. */
function gitTags() {
  return execFileSync('git', ['tag', '--list', 'v*'], {
    cwd: ROOT,
    encoding: 'utf8',
  })
    .split('\n')
    .filter(Boolean);
}

/** A GitHub Actions error annotation; its message keeps its line breaks. */
export function annotation(message) {
  return `::error title=release published::${message
    .replace(/%/g, '%25')
    .replace(/\r/g, '%0D')
    .replace(/\n/g, '%0A')}`;
}

/** Runs the checks `args` selects; resolves to the problems found. */
export async function run(
  args,
  {
    reader = registryReader(),
    tags = gitTags,
    maven = {},
    log = console.log,
  } = {},
) {
  const { version, checks } = parseArgs(args);
  const problems = [];
  for (const check of checks) {
    log(`== ${check} ${version}`);
    let result;
    if (check === 'images')
      result = await checkImages(
        releasePlan(`refs/tags/v${version}`, tags()),
        reader,
      );
    else if (check === 'chart') result = await checkChart(version, reader);
    else result = await checkMaven(version, { log, ...maven });
    for (const line of result.lines) log(line);
    for (const problem of result.problems) log(`FAIL ${problem}`);
    problems.push(...result.problems);
  }
  return problems;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  let problems;
  try {
    problems = await run(process.argv.slice(2));
  } catch (error) {
    problems = [error.message];
  }
  if (problems.length > 0) {
    const message = `release-published: ${problems.length} problem${problems.length === 1 ? '' : 's'}:\n${problems.map(problem => `- ${problem}`).join('\n')}\n${UNCHANGED}`;
    if (process.env.GITHUB_ACTIONS) console.log(annotation(message));
    console.error(message);
    process.exit(1);
  }
  console.log(
    `release-published: everything checked is published. ${UNCHANGED}`,
  );
}
