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

import { useState } from "react";
import { useSearchParams } from "react-router";
import { SearchIcon, ShoppingCartIcon } from "lucide-react";
import {
  asc,
  desc,
  filter,
  StringComparison,
  type FilterExpression,
} from "@ahoo-wang/wow-client/dsl";
import type { Snapshot } from "@ahoo-wang/wow-client";
import type {
  CategoryState,
  ProductState,
  ProductStateField,
} from "@/generated/catalog/eshop-catalog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
} from "@/components/ui/empty";
import { useStocks, type Stock } from "@/shop/catalog";
import { ProblemAlert } from "@/shop/problems";
import { useSession } from "@/shop/session";
import { money, useLoad, useTitle } from "@/shop/useLoad";

/** How the storefront sorts. */
export type ProductSort = "name" | "price-asc" | "price-desc";

/** The storefront's search, as the query's filter: published products only for a buyer. */
export function productFilter(
  search: string,
  categoryId: string,
  publishedOnly: boolean,
): FilterExpression<ProductStateField> {
  const parts: FilterExpression<ProductStateField>[] = [];
  if (publishedOnly) parts.push(filter.eq("state.status", "PUBLISHED"));
  if (categoryId) parts.push(filter.eq("state.categoryId", categoryId));
  const text = search.trim();
  if (text)
    parts.push(
      filter.contains("state.name", text, StringComparison.CASE_INSENSITIVE),
    );
  if (parts.length === 0) return filter.matchAll();
  return parts.length === 1 ? parts[0] : filter.and(parts);
}

const SORTS = {
  name: asc<ProductStateField>("state.name"),
  "price-asc": asc<ProductStateField>("state.price"),
  "price-desc": desc<ProductStateField>("state.price"),
};

const PAGE_SIZE = 12;

/**
 * The storefront's view as its address keeps it, so a link, a reload and Back
 * show the same products: `q`, `category`, `sort` and `page`.
 */
function viewOf(params: URLSearchParams) {
  const sort = params.get("sort");
  const page = Number(params.get("page"));
  return {
    search: params.get("q") ?? "",
    categoryId: params.get("category") ?? "",
    sort: (sort && sort in SORTS ? sort : "name") as ProductSort,
    page: Number.isInteger(page) && page > 1 ? page : 1,
  };
}

/** What adding a product to the basket came to, shown on its card. */
type Added = { readonly status: "adding" } | { readonly status: "added" } | {
  readonly status: "failed";
  readonly error: unknown;
};

/** A product's stock as its card says it; nothing while it is unknown. */
function StockNote({ stock }: { stock: Stock | undefined }) {
  if (!stock) return null;
  return stock.available > 0 ? (
    <span className="text-sm text-muted-foreground">{stock.available} in stock</span>
  ) : (
    <Badge variant="outline">Out of stock</Badge>
  );
}

/** Browses and searches the catalog's products through their snapshot queries. */
export function ShopPage() {
  useTitle("Shop");
  const { user, clients, basketChanged } = useSession();
  const [params, setParams] = useSearchParams();
  const view = viewOf(params);
  const [added, setAdded] = useState<Record<string, Added>>({});
  const publishedOnly = user.role === "buyer";

  /** Shows `next` in the address, a step of the history; page 1 unless it names one. */
  function show(next: Partial<ReturnType<typeof viewOf>>) {
    const merged = { ...view, page: 1, ...next };
    const query = new URLSearchParams();
    if (merged.search) query.set("q", merged.search);
    if (merged.categoryId) query.set("category", merged.categoryId);
    if (merged.sort !== "name") query.set("sort", merged.sort);
    if (merged.page > 1) query.set("page", String(merged.page));
    setParams(query);
  }

  const [categories] = useLoad<Snapshot<CategoryState>[]>(
    (signal) =>
      clients.catalog.category.snapshots.list(
        { filter: filter.matchAll(), sort: [asc("state.name")], limit: 100 },
        { signal },
      ),
    `categories:${user.id}`,
  );
  const [products, reloadProducts] = useLoad(
    (signal) =>
      clients.catalog.product.snapshots.paged(
        {
          filter: productFilter(view.search, view.categoryId, publishedOnly),
          sort: [SORTS[view.sort]],
          pagination: { index: view.page, size: PAGE_SIZE },
        },
        { signal },
      ),
    JSON.stringify([user.id, view]),
  );
  const listed = products.status === "loaded" ? products.value.list : [];
  const stocks = useStocks(listed.map((record) => record.aggregateId));
  const stockOf = (id: string) =>
    stocks.status === "loaded" ? stocks.value.get(id) ?? { available: 0, onHand: 0 } : undefined;

  async function addToBasket(record: Snapshot<ProductState>) {
    const id = record.aggregateId;
    setAdded((current) => ({ ...current, [id]: { status: "adding" } }));
    try {
      await clients.basket.basket.commands.addBasketItemOwn({
        productId: id,
        unitPrice: record.state.price,
        quantity: 1,
      });
      setAdded((current) => ({ ...current, [id]: { status: "added" } }));
      basketChanged();
    } catch (error) {
      setAdded((current) => ({ ...current, [id]: { status: "failed", error } }));
    }
  }

  const searching = Boolean(view.search.trim() || view.categoryId);
  return (
    <div className="flex flex-col gap-4">
      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          show({ search: String(new FormData(event.currentTarget).get("q") ?? "") });
        }}
      >
        <FieldGroup className="flex-1 flex-row flex-wrap items-end gap-3">
          <Field className="min-w-48 flex-1">
            <FieldLabel htmlFor="search">Search</FieldLabel>
            {/* Keyed by the address's search, so Back and Forward put their search back in the box. */}
            <Input
              key={view.search}
              id="search"
              name="q"
              placeholder="Product name"
              defaultValue={view.search}
            />
          </Field>
          <Field className="w-48">
            <FieldLabel htmlFor="category">Category</FieldLabel>
            <select
              id="category"
              className="h-8 rounded-lg border border-input bg-background px-2 text-sm"
              value={view.categoryId}
              onChange={(event) => show({ categoryId: event.target.value })}
            >
              <option value="">All categories</option>
              {categories.status === "loaded" &&
                categories.value.map((category) => (
                  <option key={category.aggregateId} value={category.aggregateId}>
                    {category.state.name}
                  </option>
                ))}
            </select>
          </Field>
          <Field className="w-48">
            <FieldLabel htmlFor="sort">Sort by</FieldLabel>
            <select
              id="sort"
              className="h-8 rounded-lg border border-input bg-background px-2 text-sm"
              value={view.sort}
              onChange={(event) =>
                show({ sort: event.target.value as ProductSort })
              }
            >
              <option value="name">Name</option>
              <option value="price-asc">Price, lowest first</option>
              <option value="price-desc">Price, highest first</option>
            </select>
          </Field>
        </FieldGroup>
        <Button type="submit">
          <SearchIcon data-icon="inline-start" />
          Search
        </Button>
      </form>
      {products.status === "loading" && <Skeleton className="h-40 w-full" />}
      {products.status === "failed" && (
        <ProblemAlert error={products.error} service="catalog" onRetry={reloadProducts} />
      )}
      {products.status === "loaded" && products.value.list.length === 0 && (
        <Empty>
          <EmptyHeader>
            <EmptyTitle>No products</EmptyTitle>
            <EmptyDescription>
              {searching
                ? `Nothing matches the search${publishedOnly ? " among the published products" : ""}.`
                : publishedOnly
                  ? "The catalog has no published products yet."
                  : "The catalog has no products yet."}
            </EmptyDescription>
          </EmptyHeader>
        </Empty>
      )}
      {products.status === "loaded" && products.value.list.length > 0 && (
        <>
          <div
            className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3"
            aria-label="Products"
          >
            {products.value.list.map((record) => {
              const stock = stockOf(record.aggregateId);
              const adding = added[record.aggregateId];
              return (
                <Card key={record.aggregateId} data-testid="product">
                  <CardHeader>
                    <CardTitle>{record.state.name}</CardTitle>
                    {record.state.description && (
                      <CardDescription>{record.state.description}</CardDescription>
                    )}
                  </CardHeader>
                  <CardContent className="flex flex-wrap items-center gap-2">
                    <span className="text-lg font-semibold">
                      {money(record.state.price)}
                    </span>
                    {record.state.status !== "PUBLISHED" && (
                      <Badge variant="outline">{record.state.status}</Badge>
                    )}
                    <StockNote stock={stock} />
                  </CardContent>
                  {user.role === "buyer" && (
                    <CardFooter className="flex flex-col items-start gap-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <Button
                          variant="secondary"
                          onClick={() => addToBasket(record)}
                          disabled={adding?.status === "adding" || stock?.available === 0}
                          aria-label={`Add ${record.state.name} to the basket`}
                        >
                          {adding?.status === "adding" ? (
                            <Spinner data-icon="inline-start" />
                          ) : (
                            <ShoppingCartIcon data-icon="inline-start" />
                          )}
                          Add to basket
                        </Button>
                        {adding?.status === "added" && (
                          <p role="status" className="text-sm text-muted-foreground">
                            Added {record.state.name} to your basket.
                          </p>
                        )}
                      </div>
                      {adding?.status === "failed" && (
                        <ProblemAlert error={adding.error} service="basket" />
                      )}
                    </CardFooter>
                  )}
                </Card>
              );
            })}
          </div>
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Button
              variant="outline"
              size="sm"
              disabled={view.page === 1}
              onClick={() => show({ page: view.page - 1 })}
            >
              Previous
            </Button>
            <span>
              Page {view.page}
              {products.value.total !== null &&
                ` of ${Math.max(1, Math.ceil(products.value.total / PAGE_SIZE))}` +
                  `${products.value.exact ? "" : "+"}`}
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={
                products.value.total !== null &&
                view.page * PAGE_SIZE >= products.value.total &&
                products.value.exact
              }
              onClick={() => show({ page: view.page + 1 })}
            >
              Next
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
