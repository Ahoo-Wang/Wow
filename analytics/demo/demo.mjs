#!/usr/bin/env node
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

/*
 * Publishes the demo's dataset and prints the tokens to sign in with (analytics/demo/README.md).
 *
 * What an enterprise's CoSec does — issue tokens — this script does with the demo's secret, so the demo needs no
 * CoSec: an admin token publishes `retail.order_lines` through the registry the way the console's admin page would
 * (draft from the table, revise the declaration, review each field's sensitivity, publish), shares the analyses of
 * `views.json` with the tenant (each once, by its title), and an analyst token per reading is printed for the
 * console's sign-in. Run again, it publishes the declaration again as the next version.
 *
 *   node analytics/demo/demo.mjs
 *
 * ANALYTICS_URL (http://localhost:8080) and COSEC_JWT_SECRET (the compose file's) say where and with what.
 */

import { createHmac, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const BASE = process.env.ANALYTICS_URL ?? 'http://localhost:8080';
const SECRET =
  process.env.COSEC_JWT_SECRET ??
  'wow-analytics-demo-secret-change-me-0123456789';
const TENANT = 'demo';
const DATASET = 'retail.order_lines';
const REGIONS = ['east', 'north', 'south', 'west'];
const WEEK = 7 * 24 * 60 * 60;

const base64url = value => Buffer.from(value).toString('base64url');

/** A CoSec access token: the claims its JwtTokenConverter writes, signed HS256 with the service's secret. */
function token(subject, { roles = [], regions = REGIONS } = {}) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64url(
    JSON.stringify({
      jti: randomUUID(),
      sub: subject,
      iat: now,
      exp: now + WEEK,
      roles,
      attributes: { 'abac.region': regions.join(',') },
      tenantId: TENANT,
    }),
  );
  const signature = createHmac('sha256', SECRET)
    .update(`${header}.${payload}`)
    .digest('base64url');
  return `${header}.${payload}.${signature}`;
}

const admin = token('demo-admin', { roles: ['analytics-admin'] });

async function call(method, path, body, bearer = admin) {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${bearer}`,
      'Content-Type': 'application/json',
      'Command-Wait-Stage': 'SNAPSHOT',
      'Command-Request-Id': randomUUID(),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const text = await response.text();
  return { ok: response.ok, status: response.status, text };
}

async function command(method, action, body) {
  const answer = await call(
    method,
    `/dataset-registry/dataset/${DATASET}/${action}`,
    body,
  );
  if (!answer.ok)
    throw new Error(`${action}: ${answer.status} ${answer.text}`);
}

async function waitUntil(what, ready, seconds = 120) {
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) {
    if (await ready().catch(() => false)) return;
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  throw new Error(`Timed out waiting for ${what}.`);
}

const read = async name =>
  JSON.parse(
    await readFile(new URL(`./dataset/${name}`, import.meta.url), 'utf8'),
  );

await waitUntil(
  `the service at ${BASE}`,
  async () => (await fetch(`${BASE}/actuator/health/liveness`)).ok,
);

// A dataset drafted once stays drafted: a second run revises the same draft.
const drafted = await call(
  'POST',
  `/dataset-registry/dataset/${DATASET}/draft`,
  { table: DATASET },
);
if (!drafted.ok && drafted.status !== 409 && drafted.status !== 400)
  throw new Error(`draft: ${drafted.status} ${drafted.text}`);
await command('PUT', 'revise', {
  document: await read(`${DATASET}.json`),
});
await command('PUT', 'sensitivity', await read(`${DATASET}.sensitivity.json`));
await command('POST', 'publish', {});
await waitUntil('the dataset in the directory', async () => {
  const answer = await call('GET', '/dataset');
  return (
    answer.ok &&
    JSON.parse(answer.text).some(
      entry =>
        `${entry.contextName}.${entry.name}` === DATASET,
    )
  );
});

// The analyses of views.json, shared with the tenant: an analyst opens the dataset and finds them there.
const SHARED = `/view-store/tenant/${TENANT}/owner/(shared)/view`;
const listed = await call('POST', `${SHARED}/snapshot/list`, {
  condition: { operator: 'ALL' },
  limit: 1000,
});
if (!listed.ok) throw new Error(`views: ${listed.status} ${listed.text}`);
const titles = new Set(
  JSON.parse(listed.text).map(snapshot => snapshot.state?.title),
);
for (const { title, config } of JSON.parse(
  await readFile(new URL('./views.json', import.meta.url), 'utf8'),
)) {
  if (titles.has(title)) continue;
  const created = await call('POST', SHARED, {
    definitionId: DATASET,
    title,
    config,
  });
  if (!created.ok)
    throw new Error(`view ${title}: ${created.status} ${created.text}`);
}

const tokens = {
  'Admin (publishes datasets, every region)': admin,
  'Analyst, every region': token('demo-analyst', { regions: REGIONS }),
  'Analyst, the east alone': token('demo-analyst-east', {
    regions: ['east'],
  }),
};
console.log(`\nPublished ${DATASET}. Open ${BASE}/ and sign in with a token:\n`);
for (const [who, value] of Object.entries(tokens))
  console.log(`${who}:\n${value}\n`);
