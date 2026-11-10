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


import { WowProblemError } from "@ahoo-wang/wow-client";
import { describe, expect, it } from "vitest";
import { ALICE, fakeServices, problemAnswer } from "@/test/fakeShop";
import { shopClients } from "./clients";

const MUG = {
  id: "mug",
  name: "Wow mug",
  description: "Half a litre",
  price: 8.5,
  brandId: "wow",
  categoryId: "mugs",
};

/** The catalog's storefront routes, through the client generated from its Manifest. */
describe("the storefront's service routes", () => {
  const { transport, received } = fakeServices([
    ["GET", /\/listings\/mug$/, () => ({ json: MUG })],
    ["GET", /\/listings\/draft$/, () => ({ noContent: true })],
    [
      "GET",
      /\/categories\/mugs\/listings\?limit=0$/,
      () =>
        problemAnswer(
          400,
          "Bad Request",
          "eshop-catalog.limit-out-of-range",
        ),
    ],
  ]);
  const { catalog } = shopClients(ALICE, transport);

  it("answers a published product's listing, and undefined for none", async () => {
    // region listing
    const mug = await catalog.routes.listing("mug");
    const draft = await catalog.routes.listing("draft");
    // endregion
    expect(mug).toEqual({ ...MUG, price: "8.5" });
    expect(draft).toBeUndefined();
    expect(received.map((request) => request.path)).toEqual([
      "/eshop-catalog/listings/mug",
      "/eshop-catalog/listings/draft",
    ]);
  });

  it("rejects a refused call with its problem and code", async () => {
    // region refusal
    const refused = catalog.routes.categoryListings("mugs", { limit: 0 });
    await expect(refused).rejects.toMatchObject({
      status: 400,
      code: "eshop-catalog.limit-out-of-range",
    });
    // endregion
    await expect(refused).rejects.toBeInstanceOf(WowProblemError);
  });
});
