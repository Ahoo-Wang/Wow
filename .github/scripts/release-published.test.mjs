/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { releasePlan } from './release-plan.mjs';
import {
  IMAGES,
  IMAGE_REGISTRIES,
  bomEntries,
  checkChart,
  checkImages,
  checkMaven,
  parseArgs,
  parseChallenge,
  pomUrl,
  registryReader,
  run,
} from './release-published.mjs';

const BETA = '10.0.0-beta.21';
const STABLE = '10.0.0';
const INDEX = 'application/vnd.oci.image.index.v1+json';
const MANIFEST = 'application/vnd.oci.image.manifest.v1+json';

const digestOf = text =>
  `sha256:${createHash('sha256').update(text).digest('hex')}`;

/** An image index of `platforms`, its JSON text different for each `seed`. */
function index(seed, platforms = ['linux/amd64', 'linux/arm64']) {
  return JSON.stringify({
    schemaVersion: 2,
    mediaType: INDEX,
    manifests: platforms.map(platform => {
      const [os, architecture] = platform.split('/');
      return {
        mediaType: MANIFEST,
        digest: digestOf(`${seed} ${platform}`),
        platform: { os, architecture },
      };
    }),
  });
}

/**
 * Registries as anonymous pulls see them: every /v2/ read without a token
 * answers 401 with a Bearer challenge whose realm hands one out. `content`
 * maps `host/repository:reference` to `{ type, text }`, and
 * `host/repository@digest` to a blob's JSON text.
 */
function registries(content) {
  const reads = [];
  const fetch = async (url, init = {}) => {
    const { host, pathname, searchParams } = new URL(url);
    if (pathname === '/token') {
      reads.push(`token ${searchParams.get('scope')}`);
      return response(200, JSON.stringify({ token: `t-${host}` }));
    }
    const [, repository, kind, reference] =
      /^\/v2\/(.+)\/(manifests|blobs)\/(.+)$/.exec(pathname);
    if (init.headers?.authorization !== `Bearer t-auth.${host}`)
      return response(401, '', {
        'www-authenticate': `Bearer realm="https://auth.${host}/token",service="${host}",scope="repository:${repository}:pull"`,
      });
    reads.push(`${init.method ?? 'GET'} ${host}/${repository} ${reference}`);
    const key = `${host}/${repository}${kind === 'blobs' ? '@' : ':'}${reference}`;
    const found = content[key];
    if (found === undefined) return response(404, '');
    if (kind === 'blobs') return response(200, found);
    return response(200, init.method === 'HEAD' ? '' : found.text, {
      'content-type': found.type,
      'docker-content-digest': digestOf(found.text),
    });
  };
  return { fetch, reads };
}

function response(status, text, headers = {}) {
  return {
    status,
    statusText: { 200: 'OK', 401: 'Unauthorized', 404: 'Not Found' }[status] ?? '',
    headers: { get: name => headers[name.toLowerCase()] ?? null },
    body: { cancel: async () => {} },
    text: async () => text,
    json: async () => JSON.parse(text),
  };
}

/** Every image of `version` on every registry, each image's index the same everywhere. */
function published(version, { floating = [] } = {}) {
  const content = {};
  for (const image of IMAGES)
    for (const { host, namespace } of IMAGE_REGISTRIES)
      for (const tag of [version, ...floating])
        content[`${host}/${namespace}/${image}:${tag}`] = {
          type: INDEX,
          text: index(`${image} ${version}`),
        };
  return content;
}

const reader = fetch => registryReader({ fetch, sleep: async () => {} });

test('parseArgs takes --version and one --only, and refuses anything else', () => {
  assert.deepEqual(parseArgs(['--version', BETA]), {
    version: BETA,
    checks: ['images', 'chart', 'maven'],
  });
  assert.deepEqual(parseArgs(['--version', BETA, '--only', 'chart']), {
    version: BETA,
    checks: ['chart'],
  });
  assert.throws(() => parseArgs([]), /--version <v> is required/);
  assert.throws(
    () => parseArgs(['--version', 'v10.0.0']),
    /v10.0.0 is not a release version/,
  );
  assert.throws(
    () => parseArgs(['--version', BETA, '--only', 'npm']),
    /unknown argument --only/,
  );
});

test("parseChallenge reads a Bearer challenge's realm, service and scope", () => {
  assert.deepEqual(
    parseChallenge(
      'Bearer realm="https://auth.docker.io/token",service="registry.docker.io",scope="repository:ahoowang/wow-ops-server:pull"',
    ),
    {
      realm: 'https://auth.docker.io/token',
      service: 'registry.docker.io',
      scope: 'repository:ahoowang/wow-ops-server:pull',
    },
  );
  assert.equal(parseChallenge('Basic realm="x"'), undefined);
  assert.equal(parseChallenge(null), undefined);
});

test('a pre-release published everywhere, its floating tags untouched, passes', async () => {
  const content = published(BETA);
  // `latest` still on an older digest: a pre-release does not move it.
  for (const { host, namespace } of IMAGE_REGISTRIES)
    content[`${host}/${namespace}/wow-view-store-server:latest`] = {
      type: INDEX,
      text: index('older'),
    };
  const { fetch, reads } = registries(content);
  const { lines, problems } = await checkImages(
    releasePlan(`refs/tags/v${BETA}`, ['v9.2.0', `v${BETA}`]),
    reader(fetch),
  );
  assert.deepEqual(problems, []);
  assert.equal(lines.length, IMAGES.length * 3 * 3);
  // The token is asked once per repository, then reused.
  assert.equal(reads.filter(read => read.startsWith('token')).length, 12);
  assert.ok(
    reads.includes(`HEAD ghcr.io/ahoo-wang/wow-ops-server latest`),
    'the floating tags are read by HEAD',
  );
});

test('a missing tag names the registry, the repository and the version', async () => {
  const content = published(BETA);
  delete content[`ghcr.io/ahoo-wang/wow-ops-server:${BETA}`];
  delete content[`registry-1.docker.io/ahoowang/wow-ops-server:${BETA}`];
  const { fetch } = registries(content);
  const { problems } = await checkImages(
    releasePlan(`refs/tags/v${BETA}`, []),
    reader(fetch),
  );
  assert.deepEqual(problems, [
    `GHCR (ghcr.io/ahoo-wang/wow-ops-server): wow-ops-server:${BETA} is missing (404)`,
    `Docker Hub (registry-1.docker.io/ahoowang/wow-ops-server): wow-ops-server:${BETA} is missing (404)`,
  ]);
});

test('a single-platform manifest, or an index without arm64, is refused', async () => {
  const content = published(BETA);
  content[`ghcr.io/ahoo-wang/wow-migrate-data:${BETA}`] = {
    type: MANIFEST,
    text: JSON.stringify({ schemaVersion: 2, mediaType: MANIFEST }),
  };
  content[`registry.cn-shanghai.aliyuncs.com/ahoo/wow-ops-server:${BETA}`] = {
    type: INDEX,
    text: index('wow-ops-server', ['linux/amd64']),
  };
  const { fetch } = registries(content);
  const { problems } = await checkImages(
    releasePlan(`refs/tags/v${BETA}`, []),
    reader(fetch),
  );
  assert.equal(problems.length, 4);
  assert.equal(
    problems[0],
    `Aliyun (registry.cn-shanghai.aliyuncs.com/ahoo/wow-ops-server): the index of wow-ops-server:${BETA} has no linux/arm64 (it has linux/amd64)`,
  );
  // Each also differs from the other registries' index.
  assert.match(problems[1], /^wow-ops-server:10.0.0-beta.21 differs between/);
  assert.equal(
    problems[2],
    `GHCR (ghcr.io/ahoo-wang/wow-migrate-data): wow-migrate-data:${BETA} is a single-platform ${MANIFEST}, not an index of linux/amd64 and linux/arm64`,
  );
  assert.match(problems[3], /^wow-migrate-data:10.0.0-beta.21 differs between/);
});

test('digests that differ between the registries are named, registry by registry', async () => {
  const content = published(BETA);
  const rebuilt = index('rebuilt');
  content[`registry-1.docker.io/ahoowang/wow-view-store-server:${BETA}`] = {
    type: INDEX,
    text: rebuilt,
  };
  const { fetch } = registries(content);
  const { problems } = await checkImages(
    releasePlan(`refs/tags/v${BETA}`, []),
    reader(fetch),
  );
  const built = digestOf(index(`wow-view-store-server ${BETA}`));
  assert.deepEqual(problems, [
    `wow-view-store-server:${BETA} differs between the registries: GHCR ${built}, Docker Hub ${digestOf(rebuilt)}, Aliyun ${built}`,
  ]);
});

test("a pre-release's floating tag moved to it is refused", async () => {
  const content = published(BETA);
  content[`registry.cn-shanghai.aliyuncs.com/ahoo/wow-ops-server:latest`] =
    content[`registry.cn-shanghai.aliyuncs.com/ahoo/wow-ops-server:${BETA}`];
  const { fetch } = registries(content);
  const { problems } = await checkImages(
    releasePlan(`refs/tags/v${BETA}`, []),
    reader(fetch),
  );
  const digest = digestOf(index(`wow-ops-server ${BETA}`));
  assert.deepEqual(problems, [
    `Aliyun (registry.cn-shanghai.aliyuncs.com/ahoo/wow-ops-server): wow-ops-server:latest was moved to ${BETA} (${digest}), which as a pre-release moves no floating tag`,
  ]);
});

test("a stable latest-line release's 10.0 and latest point at it; one left behind is refused", async () => {
  const content = published(STABLE, { floating: ['10.0', 'latest'] });
  content[`ghcr.io/ahoo-wang/wow-ops-server:latest`] = {
    type: INDEX,
    text: index('9.2.0'),
  };
  delete content[`ghcr.io/ahoo-wang/wow-ops-server:10.0`];
  const { fetch } = registries(content);
  const plan = releasePlan(`refs/tags/v${STABLE}`, ['v9.2.0', `v${STABLE}`]);
  assert.deepEqual(plan.imageTags, [STABLE, '10.0', 'latest']);
  const { problems } = await checkImages(plan, reader(fetch));
  const digest = digestOf(index(`wow-ops-server ${STABLE}`));
  assert.deepEqual(problems, [
    `GHCR (ghcr.io/ahoo-wang/wow-ops-server): wow-ops-server:10.0 should point at ${STABLE} (${digest}) but points at none (404)`,
    `GHCR (ghcr.io/ahoo-wang/wow-ops-server): wow-ops-server:latest should point at ${STABLE} (${digest}) but points at ${digestOf(index('9.2.0'))}`,
  ]);
});

test('a patch to an older line moves neither its line nor latest', async () => {
  const content = published('10.0.3', { floating: ['latest'] });
  const { fetch } = registries(content);
  // 10.0.4 exists, and 10.1.0 is the latest line.
  const plan = releasePlan('refs/tags/v10.0.3', [
    'v10.0.3',
    'v10.0.4',
    'v10.1.0',
  ]);
  assert.deepEqual(plan.imageTags, ['10.0.3']);
  const { problems } = await checkImages(plan, reader(fetch));
  assert.equal(problems.length, IMAGES.length * 3);
  assert.match(
    problems[0],
    /wow-ops-server:latest was moved to 10.0.3 \(sha256:\w+\), which does not get latest$/,
  );
});

/** wow-service's OCI manifest and Chart.yaml config at `version` on GHCR and Docker Hub. */
function charts(version, chartYaml = {}) {
  const config = JSON.stringify({
    name: 'wow-service',
    version,
    appVersion: version,
    ...chartYaml,
  });
  const manifest = JSON.stringify({
    schemaVersion: 2,
    config: {
      mediaType: 'application/vnd.cncf.helm.config.v1+json',
      digest: digestOf(config),
    },
    layers: [],
  });
  return {
    [`ghcr.io/ahoo-wang/wow-service:${version}`]: {
      type: MANIFEST,
      text: manifest,
    },
    [`ghcr.io/ahoo-wang/wow-service@${digestOf(config)}`]: config,
    [`registry-1.docker.io/ahoowang/wow-service:${version}`]: {
      type: MANIFEST,
      text: manifest,
    },
    [`registry-1.docker.io/ahoowang/wow-service@${digestOf(config)}`]: config,
  };
}

test('the chart on both registries, its Chart.yaml at the version, passes', async () => {
  const { fetch } = registries(charts(BETA));
  const { lines, problems } = await checkChart(BETA, reader(fetch));
  assert.deepEqual(problems, []);
  assert.match(
    lines[0],
    /^GHCR \(ghcr.io\/ahoo-wang\/wow-service\):10.0.0-beta.21 sha256:\w+ name wow-service, version 10.0.0-beta.21, appVersion 10.0.0-beta.21$/,
  );
});

test('a chart whose appVersion differs, or that is missing, is refused', async () => {
  const content = charts(BETA, { appVersion: '10.0.0-beta.20' });
  for (const key of Object.keys(content))
    if (key.startsWith('registry-1.docker.io')) delete content[key];
  const { fetch } = registries(content);
  const { problems } = await checkChart(BETA, reader(fetch));
  assert.deepEqual(problems, [
    `GHCR (ghcr.io/ahoo-wang/wow-service): the Chart.yaml of wow-service ${BETA} has appVersion 10.0.0-beta.20, not ${BETA}`,
    `Docker Hub (registry-1.docker.io/ahoowang/wow-service): the chart wow-service ${BETA} is missing (404)`,
  ]);
});

test('an image pushed under the chart name is not taken for the chart', async () => {
  const { fetch } = registries({
    [`ghcr.io/ahoo-wang/wow-service:${BETA}`]: {
      type: INDEX,
      text: index('x'),
    },
    ...Object.fromEntries(
      Object.entries(charts(BETA)).filter(([key]) =>
        key.startsWith('registry-1'),
      ),
    ),
  });
  const { problems } = await checkChart(BETA, reader(fetch));
  assert.deepEqual(problems, [
    `GHCR (ghcr.io/ahoo-wang/wow-service): wow-service:${BETA} is not a Helm chart (its config is missing)`,
  ]);
});

test('a read with no answer or a 5xx is made again, up to three times', async () => {
  let reads = 0;
  const slept = [];
  const fetch = async () => {
    reads++;
    if (reads === 1)
      throw new Error('fetch failed', { cause: { message: 'ECONNRESET' } });
    if (reads === 2) return response(503, '');
    return response(404, '');
  };
  const read = await registryReader({
    fetch,
    sleep: async ms => slept.push(ms),
  }).manifest('ghcr.io', 'ahoo-wang/wow-ops-server', BETA);
  assert.deepEqual(read, { status: 404 });
  assert.deepEqual(slept, [5_000, 5_000]);

  const down = registryReader({
    fetch: async () => response(502, ''),
    sleep: async () => {},
  });
  await assert.rejects(
    down.manifest('ghcr.io', 'ahoo-wang/wow-ops-server', BETA),
    {
      message: `https://ghcr.io/v2/ahoo-wang/wow-ops-server/manifests/${BETA} answered 502 (3 attempts)`,
    },
  );
});

const BOM = `<project>
  <groupId>me.ahoo.wow</groupId>
  <artifactId>wow-bom</artifactId>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>me.ahoo.wow</groupId>
        <artifactId>wow-api</artifactId>
        <version>${BETA}</version>
      </dependency>
      <dependency>
        <groupId>me.ahoo.wow</groupId>
        <artifactId>wow-kernel</artifactId>
        <version>${BETA}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>`;

test('bomEntries reads the dependencies a BOM manages', () => {
  assert.deepEqual(bomEntries(BOM), [
    { groupId: 'me.ahoo.wow', artifactId: 'wow-api', version: BETA },
    { groupId: 'me.ahoo.wow', artifactId: 'wow-kernel', version: BETA },
  ]);
  assert.deepEqual(bomEntries('<project/>'), []);
});

/**
 * Maven Central on a fake clock: each URL answers 404 until its time (ms
 * after the start) has come, then 200; the BOM's body is BOM. `sleep` moves
 * the clock.
 */
function central(times) {
  let clock = 0;
  const reads = {};
  const fetch = async url => {
    reads[url] = (reads[url] ?? 0) + 1;
    const due = times[url];
    if (due === undefined || clock < due) return response(404, '');
    return response(200, url.endsWith('wow-bom-10.0.0-beta.21.pom') ? BOM : '');
  };
  return {
    reads,
    options: {
      fetch,
      sleep: async ms => {
        clock += ms;
      },
      now: () => clock,
      log: () => {},
    },
    clock: () => clock,
  };
}

test("Maven Central's POMs appearing within the bound pass", async () => {
  const { options, clock } = central({
    [pomUrl('wow-bom', BETA)]: 600_000,
    [pomUrl('wow-api', BETA)]: 600_000,
    [pomUrl('wow-kernel', BETA)]: 900_000,
  });
  const { lines, problems } = await checkMaven(BETA, options);
  assert.deepEqual(problems, []);
  assert.deepEqual(lines, [
    `Maven Central: wow-bom ${BETA} and the 2 modules it lists: wow-api, wow-kernel`,
  ]);
  assert.equal(clock(), 900_000);
});

test('a POM that never appears fails at the hour, read every 30 seconds', async () => {
  const { options, reads, clock } = central({
    [pomUrl('wow-bom', BETA)]: 1_200_000,
    [pomUrl('wow-api', BETA)]: 1_200_000,
  });
  const { problems } = await checkMaven(BETA, options);
  // The BOM took 20 minutes, which the modules' wait does not get again.
  assert.equal(clock(), 3_600_000);
  assert.deepEqual(problems, [
    `Maven Central does not serve the POMs of me.ahoo.wow:wow-kernel:${BETA} after 2400s (81 reads 30s apart)` +
      `\n  me.ahoo.wow:wow-kernel:${BETA}: ${pomUrl('wow-kernel', BETA)} answered 404 Not Found`,
  ]);
  assert.equal(reads[pomUrl('wow-kernel', BETA)], 81);
  assert.equal(reads[pomUrl('wow-api', BETA)], 1);
});

test('a BOM that never appears fails at the hour and reads no module', async () => {
  const { options, reads } = central({});
  const { problems } = await checkMaven(BETA, options);
  assert.deepEqual(problems, [
    `Maven Central does not serve the POMs of me.ahoo.wow:wow-bom:${BETA} after 3600s (121 reads 30s apart)` +
      `\n  me.ahoo.wow:wow-bom:${BETA}: ${pomUrl('wow-bom', BETA)} answered 404 Not Found`,
  ]);
  assert.deepEqual(Object.keys(reads), [pomUrl('wow-bom', BETA)]);
});

test('run checks only what --only names and returns every problem', async () => {
  const { fetch } = registries(charts(BETA, { version: '10.0.0-beta.20' }));
  const logs = [];
  const problems = await run(['--version', BETA, '--only', 'chart'], {
    reader: reader(fetch),
    tags: () => assert.fail('the chart check needs no tags'),
    log: line => logs.push(line),
  });
  assert.equal(problems.length, 2);
  assert.equal(logs[0], `== chart ${BETA}`);
  assert.ok(logs.some(line => line.startsWith('FAIL GHCR')));
});
