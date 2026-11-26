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
 * The CoSec gateway in front of the catalog server's `cosec` profile, as far
 * as the end-to-end suite needs one: the server runs in CoSec's gateway mode, so
 * it reads the caller from the `CoSec-Identity` header the gateway forwards
 * and ignores the bearer token. For each request this stand-in:
 *
 * - strips any `CoSec-Identity` the client sent;
 * - without a bearer token, forwards the request with no identity
 *   (anonymous: the server decides, and answers 401);
 * - with one, verifies it (HS256 with the suite's secret, `e2e/cosec.ts`)
 *   and answers 401 itself for a token that is invalid, has expired or was
 *   revoked (its `jti` is one the suite revoked), as CoSec's gateway does
 *   (`application/problem+json`, `token-invalid`, `token-expired`,
 *   `token-revoked`): the CoSec client refreshes only the second, and removes
 *   the stored pair on the first and the third;
 * - decides the request in an app as CoSec 8 does: the token's own (its
 *   `aud`), and on a route that names one (`cosec-app-id`, here `app`) only
 *   that app, so a token for another app, or for none, is refused 403
 *   `scope-rejected`. No client chooses it: `CoSec-App-Id` is not read;
 * - answers every request with a `CoSec-Request-Id`: the client's when it is
 *   1 to 64 of `A-Z a-z 0-9 . _ -`, else one of its own, and a refusal's body
 *   holds CoSec's members in CoSec's order (`type`, `title`, `status`,
 *   `code`, `requestId`), as CoSec's Clients page describes them;
 * - otherwise forwards it with the identity CoSec's gateway forwards (format
 *   v1, as Web integration › Forwarding the identity describes it): the
 *   token's tenant, subject and roles, in that app and the default space, no
 *   memberships, whose credential it is (the token's `cosec.kind`), its
 *   `jti`, expiry and issue time, and the request's id, as CoSec
 *   8.0.0's gateway forwards it.
 *
 * It decides nothing else: the suite's tokens carry their roles, so there is
 * no policy, no document and no Redis to consult.
 */

import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { createServer, request as forward } from 'node:http';

/** The header the gateway forwards the identity in. */
const IDENTITY = 'cosec-identity';

/** CoSec's default tenant, for a credential without one. */
const DEFAULT_TENANT = '(0)';

/** Headers of one connection, never forwarded; nor is a client's identity. */
const HOP_BY_HOP = new Set([
  'connection',
  'host',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  IDENTITY,
]);

/** CoSec's problem types the stand-in answers with. */
const PROBLEMS = {
  'token-invalid': { title: 'Token Invalid', status: 401 },
  'token-expired': { title: 'Token Expired', status: 401 },
  'token-revoked': { title: 'Token Revoked', status: 401 },
  'scope-rejected': { title: 'Scope Rejected', status: 403 },
};

/** The header CoSec answers every request with, and reads a client's from. */
const REQUEST_ID = 'cosec-request-id';

/** A request id CoSec keeps: 1 to 64 of `A-Z a-z 0-9 . _ -`. */
const REQUEST_ID_FORMAT = /^[A-Za-z0-9._-]{1,64}$/;

/**
 * Whom `authorization` names: `{}` for no bearer token, `{ claims }` for a
 * token signed with `secret` that has not expired and whose `jti` is not in
 * `revoked`, else `{ problem }`.
 */
function authenticate(authorization, secret, revoked, now) {
  const token = /^Bearer (.+)$/.exec(authorization ?? '')?.[1];
  if (!token) return {};
  const [header, payload, signature, ...rest] = token.split('.');
  if (!signature || rest.length > 0) return { problem: 'token-invalid' };
  const expected = createHmac('sha256', secret)
    .update(`${header}.${payload}`)
    .digest();
  const given = Buffer.from(signature, 'base64url');
  if (given.length !== expected.length || !timingSafeEqual(given, expected))
    return { problem: 'token-invalid' };
  let claims;
  try {
    claims = JSON.parse(Buffer.from(payload, 'base64url').toString());
  } catch {
    return { problem: 'token-invalid' };
  }
  if (!claims.sub || !claims.jti || typeof claims.sub !== 'string')
    return { problem: 'token-invalid' };
  if (!Number.isInteger(claims.exp) || claims.exp <= now)
    return { problem: 'token-expired' };
  if (revoked.has(String(claims.jti))) return { problem: 'token-revoked' };
  const audience = Array.isArray(claims.aud)
    ? claims.aud.length === 1
      ? claims.aud[0]
      : null
    : (claims.aud ?? '');
  // One app, a string or an array of one string: anything else is no token.
  if (typeof audience !== 'string') return { problem: 'token-invalid' };
  return { claims, audience };
}

/**
 * The `CoSec-Identity` of `claims` in `app` for the request `requestId`:
 * base64url, without padding, of compact JSON with exactly the members CoSec
 * 8.0.0's gateway forwards, in its order (whose credential it is and
 * the token's issue time in `auth`, no memberships, the request's id as
 * `rid`).
 */
function identity(claims, app, now, requestId) {
  const value = {
    v: 1,
    tenant: claims.tenantId || DEFAULT_TENANT,
    sub: claims.sub,
    scope: { app, space: '' },
    roles: [...new Set(claims.roles ?? [])].sort(),
    memberships: { spaces: {}, attachments: [] },
    auth: {
      method: 'jwt',
      kind: kindOf(claims),
      jti: String(claims.jti),
      exp: claims.exp,
      iat: claims.iat,
    },
    iat: now,
    rid: requestId,
  };
  return Buffer.from(JSON.stringify(value)).toString('base64url');
}

/**
 * Whose the token is, as CoSec's verifier reads its `cosec.kind` claim: a
 * person's only when it says exactly `person`, else a workload's.
 */
function kindOf(claims) {
  return claims['cosec.kind'] === 'person' ? 'person' : 'workload';
}

/** Whether `app` can be an app id: not blank or `*`, one path segment, no `:`. */
function isAppId(app) {
  return (
    app.trim() !== '' &&
    app !== '*' &&
    !app.includes(':') &&
    !app.includes('/') &&
    app.length <= 128
  );
}

/**
 * The app a request with a token for `audience` (empty for none) is decided
 * in on a route of `route` (empty for none), or null when the scope does not
 * resolve: a route's app takes only its own tokens, and a token's app must be
 * one.
 */
function appOf(audience, route) {
  if (audience !== '' && !isAppId(audience)) return null;
  if (route === '') return audience;
  return audience === route ? route : null;
}

function only(value) {
  return Array.isArray(value) ? value[0] : value;
}

function forwarded(headers) {
  return Object.fromEntries(
    Object.entries(headers).filter(([name]) => !HOP_BY_HOP.has(name)),
  );
}

function refuse(response, slug, requestId) {
  const { title, status } = PROBLEMS[slug];
  response.writeHead(status, {
    'content-type': 'application/problem+json',
    [REQUEST_ID]: requestId,
  });
  response.end(
    JSON.stringify({
      type: `https://cosec.ahoo.me/problems/${slug}`,
      title,
      status,
      code: slug.toUpperCase().replaceAll('-', '_'),
      requestId,
    }),
  );
}

/**
 * Listens on `127.0.0.1:port` and forwards every request to the server on
 * `127.0.0.1:upstream` as the CoSec gateway would on a route whose app is
 * `app` (none when empty), verifying the suite's tokens with `secret` and
 * refusing those whose `jti` is in `revoked`.
 */
export function startCoSecGateway({ port, upstream, secret, app = '', revoked = [] }) {
  if (!secret)
    throw new Error("The CoSec gateway stand-in needs the suite's secret.");
  if (app !== '' && !isAppId(app))
    throw new Error(`The route's app [${app}] cannot be an app id.`);
  const revokedIds = new Set(revoked);
  const gateway = createServer((request, response) => {
    const now = Math.floor(Date.now() / 1000);
    const sent = only(request.headers[REQUEST_ID]);
    const requestId =
      sent !== undefined && REQUEST_ID_FORMAT.test(sent) ? sent : randomUUID();
    const { claims, audience, problem } = authenticate(
      only(request.headers.authorization),
      secret,
      revokedIds,
      now,
    );
    if (problem) return refuse(response, problem, requestId);
    const headers = { ...forwarded(request.headers), [REQUEST_ID]: requestId };
    if (claims) {
      // Only a scope that resolves is decided in: the token's app, the route's if it names one, the default space.
      const scope = appOf(audience, app);
      const space = only(request.headers['cosec-space-id']);
      if (scope === null || (space !== undefined && space !== ''))
        return refuse(response, 'scope-rejected', requestId);
      headers[IDENTITY] = identity(claims, scope, now, requestId);
    }
    const proxied = forward(
      {
        host: '127.0.0.1',
        port: upstream,
        method: request.method,
        path: request.url,
        headers,
      },
      answer => {
        response.writeHead(answer.statusCode ?? 502, {
          ...forwarded(answer.headers),
          [REQUEST_ID]: requestId,
        });
        answer.pipe(response);
      },
    );
    proxied.on('error', () => {
      if (!response.headersSent) response.writeHead(502);
      response.end();
    });
    // A client that goes away (a closed event stream) closes the request upstream too.
    response.on('close', () => proxied.destroy());
    request.pipe(proxied);
  });
  gateway.listen(Number(port), '127.0.0.1');
  return gateway;
}
