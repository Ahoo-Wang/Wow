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
 *   and answers 401 itself for a token that is invalid or has expired, as
 *   CoSec's gateway does (`application/problem+json`, `token-invalid`,
 *   `token-expired`);
 * - otherwise forwards it with the identity CoSec's gateway issues
 *   (`IdentityIssuer`, encoded as `IdentityCodec` does, format v1): the
 *   token's tenant, subject and roles, in the scope of the declared
 *   `CoSec-App-Id` and the default space, the token's `jti` and expiry.
 *
 * It decides nothing else: the suite's tokens carry their roles, so there is
 * no policy, no document and no Redis to consult.
 */

import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { createServer, request as forward } from 'node:http';

/** The header the gateway forwards the identity in (`IdentityCodec.HEADER`). */
const IDENTITY = 'cosec-identity';

/** CoSec's tenant for a credential without one (`TenantId.DEFAULT`). */
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

/** CoSec's problem types the stand-in answers with (`ProblemType`). */
const PROBLEMS = {
  'token-invalid': { title: 'Token Invalid', status: 401 },
  'token-expired': { title: 'Token Expired', status: 401 },
  'scope-rejected': { title: 'Scope Rejected', status: 403 },
};

/**
 * Whom `authorization` names: `{}` for no bearer token, `{ claims }` for a
 * token signed with `secret` that has not expired, else `{ problem }`.
 */
function authenticate(authorization, secret, now) {
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
  return { claims };
}

/**
 * The `CoSec-Identity` of `claims` in `app`: base64url, without padding, of
 * compact JSON with exactly CoSec's members in CoSec's order.
 */
function identity(claims, app, now) {
  const value = {
    v: 1,
    tenant: claims.tenantId || DEFAULT_TENANT,
    sub: claims.sub,
    scope: { app, space: '' },
    roles: [...new Set(claims.roles ?? [])].sort(),
    auth: { method: 'jwt', jti: String(claims.jti), exp: claims.exp },
    iat: now,
    rid: randomUUID(),
  };
  return Buffer.from(JSON.stringify(value)).toString('base64url');
}

/** The app `declared` names (`CoSec-App-Id`), or null when it cannot be one. */
function appOf(declared) {
  if (declared === undefined || declared === '') return '';
  const valid =
    declared.trim() !== '' &&
    declared !== '*' &&
    !declared.includes(':') &&
    declared.length <= 128;
  return valid ? declared : null;
}

function only(value) {
  return Array.isArray(value) ? value[0] : value;
}

function forwarded(headers) {
  return Object.fromEntries(
    Object.entries(headers).filter(([name]) => !HOP_BY_HOP.has(name)),
  );
}

function refuse(response, slug) {
  const { title, status } = PROBLEMS[slug];
  response.writeHead(status, { 'content-type': 'application/problem+json' });
  response.end(
    JSON.stringify({
      type: `https://cosec.ahoo.me/problems/${slug}`,
      title,
      status,
    }),
  );
}

/**
 * Listens on `127.0.0.1:port` and forwards every request to the server on
 * `127.0.0.1:upstream` as the CoSec gateway would, verifying the suite's
 * tokens with `secret`.
 */
export function startCoSecGateway({ port, upstream, secret }) {
  if (!secret)
    throw new Error("The CoSec gateway stand-in needs the suite's secret.");
  const gateway = createServer((request, response) => {
    const now = Math.floor(Date.now() / 1000);
    const { claims, problem } = authenticate(
      only(request.headers.authorization),
      secret,
      now,
    );
    if (problem) return refuse(response, problem);
    const headers = forwarded(request.headers);
    if (claims) {
      // Only a scope that resolves is decided in (`ScopeResolver`): a valid app, the default space.
      const app = appOf(only(request.headers['cosec-app-id']));
      const space = only(request.headers['cosec-space-id']);
      if (app === null || (space !== undefined && space !== ''))
        return refuse(response, 'scope-rejected');
      headers[IDENTITY] = identity(claims, app, now);
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
        response.writeHead(answer.statusCode ?? 502, forwarded(answer.headers));
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
