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
import { SearchIcon, ShoppingCartIcon } from "lucide-react";
import {
  asc,
  desc,
  filter,
  StringComparison,
  type FilterExpression,
} from "@ahoo-wang/wow-client/dsl";
import type { SnapshotRecord } from "@ahoo-wang/wow-client";
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
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
} from "@/components/ui/empty";
import { ProblemAlert } from "@/shop/problems";
import { useSession } from "@/shop/session";
import { money, useLoad } from "@/shop/useLoad";

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

/** Browses and searches the catalog's products through their snapshot queries. */
export function ShopPage() {
  const { user, clients } = useSession();
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState({ search: "", categoryId: "" });
  const [sort, setSort] = useState<ProductSort>("name");
  const [page, setPage] = useState(1);
  const [notice, setNotice] = useState<unknown>();
  const [added, setAdded] = useState<string>();
  const publishedOnly = user.role === "buyer";
  const [categories] = useLoad<SnapshotRecord<CategoryState>[]>(
    (signal) =>
      clients.catalog.category.snapshots.list(
        { filter: filter.matchAll(), sort: [asc("state.name")], limit: 100 },
        { signal },
      ),
    `categories:${user.id}`,
  );
  const [products] = useLoad(
    (signal) =>
      clients.catalog.product.snapshots.paged(
        {
          filter: productFilter(query.search, query.categoryId, publishedOnly),
          sort: [SORTS[sort]],
          pagination: { index: page, size: PAGE_SIZE },
        },
        { signal },
      ),
    JSON.stringify([user.id, query, sort, page]),
  );

  async function addToBasket(record: SnapshotRecord<ProductState>) {
    setNotice(undefined);
    setAdded(undefined);
    try {
      await clients.basket.basket.commands.addBasketItem(
        {
          productId: record.aggregateId,
          unitPrice: record.state.price,
          quantity: 1,
        },
        { id: user.id },
      );
      setAdded(record.state.name);
    } catch (error) {
      setNotice(error);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          setPage(1);
          setQuery((current) => ({ ...current, search }));
        }}
      >
        <FieldGroup className="flex-1 flex-row flex-wrap items-end gap-3">
          <Field className="min-w-48 flex-1">
            <FieldLabel htmlFor="search">Search</FieldLabel>
            <Input
              id="search"
              placeholder="Product name"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </Field>
          <Field className="w-48">
            <FieldLabel htmlFor="category">Category</FieldLabel>
            <select
              id="category"
              className="h-8 rounded-lg border border-input bg-background px-2 text-sm"
              value={query.categoryId}
              onChange={(event) => {
                setPage(1);
                setQuery((current) => ({
                  ...current,
                  categoryId: event.target.value,
                }));
              }}
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
              value={sort}
              onChange={(event) => setSort(event.target.value as ProductSort)}
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
      {notice !== undefined && <ProblemAlert error={notice} />}
      {added && (
        <p role="status" className="text-sm text-muted-foreground">
          Added {added} to your basket.
        </p>
      )}
      {products.status === "loading" && <Skeleton className="h-40 w-full" />}
      {products.status === "failed" && <ProblemAlert error={products.error} />}
      {products.status === "loaded" && products.value.list.length === 0 && (
        <Empty>
          <EmptyHeader>
            <EmptyTitle>No products</EmptyTitle>
            <EmptyDescription>
              Nothing matches the search
              {publishedOnly ? " among the published products" : ""}.
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
            {products.value.list.map((record) => (
              <Card key={record.aggregateId} data-testid="product">
                <CardHeader>
                  <CardTitle>{record.state.name}</CardTitle>
                  <CardDescription>
                    {record.state.description || record.aggregateId}
                  </CardDescription>
                </CardHeader>
                <CardContent className="flex items-center gap-2">
                  <span className="text-lg font-semibold">
                    {money(record.state.price)}
                  </span>
                  {record.state.status !== "PUBLISHED" && (
                    <Badge variant="outline">{record.state.status}</Badge>
                  )}
                </CardContent>
                {user.role === "buyer" && (
                  <CardFooter>
                    <Button
                      variant="secondary"
                      onClick={() => addToBasket(record)}
                      aria-label={`Add ${record.state.name} to the basket`}
                    >
                      <ShoppingCartIcon data-icon="inline-start" />
                      Add to basket
                    </Button>
                  </CardFooter>
                )}
              </Card>
            ))}
          </div>
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Button
              variant="outline"
              size="sm"
              disabled={page === 1}
              onClick={() => setPage(page - 1)}
            >
              Previous
            </Button>
            <span>
              Page {page}
              {products.value.total !== null &&
                ` of ${Math.max(1, Math.ceil(products.value.total / PAGE_SIZE))}` +
                  `${products.value.exact ? "" : "+"}`}
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={
                products.value.total !== null &&
                page * PAGE_SIZE >= products.value.total &&
                products.value.exact
              }
              onClick={() => setPage(page + 1)}
            >
              Next
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
