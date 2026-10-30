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
  "the-catalog-console-e2e-secret-of-32-chars";

function part(value: object): string {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

/**
 * A CoSec access token of `sub` with `roles`, in CoSec's default tenant, as
 * the enterprise's CoSec issues it: HS256-signed, valid for `validFor` seconds
 * (an hour; a negative one gives a token that has expired).
 */
export function accessToken(
  sub: string,
  roles: string[],
  validFor: number = 3600,
  secret: string = COSEC_SECRET,
): string {
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${part({ alg: "HS256", typ: "JWT" })}.${part({
    jti: `e2e-${sub}-${now}`,
    sub,
    tenantId: "(0)",
    roles,
    iat: now,
    exp: now + validFor,
  })}`;
  const signature = createHmac("sha256", secret)
    .update(unsigned)
    .digest("base64url");
  return `${unsigned}.${signature}`;
}
