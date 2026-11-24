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

import { createHmac } from "node:crypto";

/**
 * The secret the suite signs its tokens with, which the CoSec gateway's
 * stand-in (`e2e/cosec-gateway.mjs`) verifies them with. The `cosec` server
 * holds none: it reads the identity the gateway forwards.
 */
export const COSEC_SECRET =
  "the-ops-console-e2e-secret-of-32-characters";

/**
 * The console's app: the one the suite's tokens are issued for (their `aud`),
 * and the app of the console's route at the gateway's stand-in
 * (`cosec-app-id`), which takes no token for another app or for none.
 */
export const APP = "ops-console";

/**
 * The `jti` the gateway's stand-in holds as revoked, as CoSec's gateway holds
 * a token whose session was logged out: it answers it 401 `token-revoked`.
 */
export const REVOKED_JTI = "e2e-revoked";

/** How a token of {@link accessToken} differs from a valid one of the console's. */
export interface TokenOptions {
  /** Seconds it is valid for: an hour; a negative one gives a token that has expired. */
  validFor?: number;
  /** The secret it is signed with. */
  secret?: string;
  /** The app it is issued for, {@link APP}; null for a token of no app. */
  app?: string | null;
  /** Its id, unique by default; {@link REVOKED_JTI} for one the gateway revoked. */
  jti?: string;
}

function part(value: object): string {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

/**
 * A CoSec access token of `sub` with `roles`, in CoSec's default tenant, as
 * the enterprise's CoSec issues it for an app: HS256-signed, valid for an
 * hour, for the console's app, unless `options` says otherwise.
 */
export function accessToken(
  sub: string,
  roles: string[],
  { validFor = 3600, secret = COSEC_SECRET, app = APP, jti }: TokenOptions = {},
): string {
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${part({ alg: "HS256", typ: "JWT" })}.${part({
    jti: jti ?? `e2e-${sub}-${now}`,
    sub,
    tenantId: "(0)",
    roles,
    ...(app === null ? {} : { aud: app }),
    iat: now,
    exp: now + validFor,
  })}`;
  const signature = createHmac("sha256", secret)
    .update(unsigned)
    .digest("base64url");
  return `${unsigned}.${signature}`;
}
