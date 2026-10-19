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
 * An access token of CoSec's shape carrying `claims`, expiring at `exp`
 * (seconds; an hour from now by default). Unsigned: the console only reads a
 * token, the ops service verifies it.
 */
export function accessToken(
  claims: Record<string, unknown>,
  exp: number = Math.floor(Date.now() / 1000) + 3600,
): string {
  const part = (value: object) =>
    btoa(JSON.stringify(value))
      .replace(/=+$/, "")
      .replace(/\+/g, "-")
      .replace(/\//g, "_");
  const payload = { jti: "t-1", iat: Math.floor(Date.now() / 1000), exp };
  return `${part({ alg: "HS256", typ: "JWT" })}.${part({ ...payload, ...claims })}.signature`;
}
