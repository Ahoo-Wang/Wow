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

import { useState, type FormEvent } from "react";
import { asc, filter } from "@ahoo-wang/wow-client/dsl";
import { WowProblemError, type SnapshotRecord } from "@ahoo-wang/wow-client";
import type {
  BrandState,
  CategoryState,
  ProductState,
} from "@/generated/catalog/eshop-catalog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ProblemAlert } from "@/shop/problems";
import { useSession } from "@/shop/session";
import { money, useLoad } from "@/shop/useLoad";

/** The form's fields by name. */
function fields(event: FormEvent<HTMLFormElement>): Record<string, string> {
  event.preventDefault();
  const form = event.currentTarget;
  const values = Object.fromEntries(
    [...new FormData(form).entries()].map(([name, value]) => [
      name,
      String(value).trim(),
    ]),
  );
  return values;
}

const SELECT =
  "h-8 rounded-lg border border-input bg-background px-2 text-sm";

/**
 * The employee's catalog administration: brands and categories, products
 * with their prices and publication, and each product's stock in the
 * inventory service.
 */
export function AdminPage() {
  const { user, clients } = useSession();
  const [problem, setProblem] = useState<unknown>();
  const [lists, reload] = useLoad(
    async (signal) => {
      const all = { filter: filter.matchAll(), limit: 200 };
      const [brands, categories, products] = await Promise.all([
        clients.brandQueries.list(
          { ...all, sort: [asc("state.name")] },
          { signal },
        ),
        clients.categoryQueries.list(
          { ...all, sort: [asc("state.name")] },
          { signal },
        ),
        clients.productQueries.list(
          { ...all, sort: [asc("state.name")] },
          { signal },
        ),
      ]);
      return { brands, categories, products };
    },
    `admin:${user.id}`,
  );

  async function act(action: () => Promise<unknown>, form?: HTMLFormElement) {
    setProblem(undefined);
    try {
      await action();
      form?.reset();
    } catch (error) {
      setProblem(error);
    }
    reload();
  }

  return (
    <div className="flex flex-col gap-4">
      {user.role !== "employee" && (
        <p className="text-sm text-muted-foreground" role="note">
          Only an employee changes the catalog: the catalog service refuses
          these commands to {user.id}.
        </p>
      )}
      {problem !== undefined && <ProblemAlert error={problem} />}
      <div className="grid gap-4 lg:grid-cols-3">
        <Card>
          <CardHeader>
            <CardTitle>New brand</CardTitle>
          </CardHeader>
          <CardContent>
            <form
              aria-label="New brand"
              onSubmit={(event) => {
                const form = event.currentTarget;
                const { id, name } = fields(event);
                return act(
                  () => clients.brands.createBrand({ name }, { id: id || undefined }),
                  form,
                );
              }}
            >
              <FieldGroup>
                <Field>
                  <FieldLabel htmlFor="brand-id">Id</FieldLabel>
                  <Input id="brand-id" name="id" placeholder="Generated" />
                </Field>
                <Field>
                  <FieldLabel htmlFor="brand-name">Name</FieldLabel>
                  <Input id="brand-name" name="name" />
                </Field>
                <Button type="submit">Create brand</Button>
              </FieldGroup>
            </form>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>New category</CardTitle>
          </CardHeader>
          <CardContent>
            <form
              aria-label="New category"
              onSubmit={(event) => {
                const form = event.currentTarget;
                const { id, name } = fields(event);
                return act(
                  () =>
                    clients.categories.createCategory(
                      { name },
                      { id: id || undefined },
                    ),
                  form,
                );
              }}
            >
              <FieldGroup>
                <Field>
                  <FieldLabel htmlFor="category-id">Id</FieldLabel>
                  <Input id="category-id" name="id" placeholder="Generated" />
                </Field>
                <Field>
                  <FieldLabel htmlFor="category-name">Name</FieldLabel>
                  <Input id="category-name" name="name" />
                </Field>
                <Button type="submit">Create category</Button>
              </FieldGroup>
            </form>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>New product</CardTitle>
            <CardDescription>
              A draft; publish it once it has a brand and a category.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form
              aria-label="New product"
              onSubmit={(event) => {
                const form = event.currentTarget;
                const { id, name, price, brandId, categoryId, description } =
                  fields(event);
                return act(
                  () =>
                    clients.products.createProduct(
                      {
                        name,
                        price: Number(price),
                        description,
                        brandId: brandId || null,
                        categoryId: categoryId || null,
                      },
                      { id: id || undefined },
                    ),
                  form,
                );
              }}
            >
              <FieldGroup>
                <Field>
                  <FieldLabel htmlFor="product-id">Id</FieldLabel>
                  <Input id="product-id" name="id" placeholder="Generated" />
                </Field>
                <Field>
                  <FieldLabel htmlFor="product-name">Name</FieldLabel>
                  <Input id="product-name" name="name" />
                </Field>
                <Field>
                  <FieldLabel htmlFor="product-description">
                    Description
                  </FieldLabel>
                  <Input id="product-description" name="description" />
                </Field>
                <Field>
                  <FieldLabel htmlFor="product-price">Price</FieldLabel>
                  <Input
                    id="product-price"
                    name="price"
                    type="number"
                    step="0.01"
                  />
                </Field>
                <Field>
                  <FieldLabel htmlFor="product-brand">Brand</FieldLabel>
                  <select id="product-brand" name="brandId" className={SELECT}>
                    <option value="">None</option>
                    {lists.status === "loaded" &&
                      lists.value.brands.map((brand) => (
                        <option key={brand.aggregateId} value={brand.aggregateId}>
                          {brand.state.name}
                        </option>
                      ))}
                  </select>
                </Field>
                <Field>
                  <FieldLabel htmlFor="product-category">Category</FieldLabel>
                  <select
                    id="product-category"
                    name="categoryId"
                    className={SELECT}
                  >
                    <option value="">None</option>
                    {lists.status === "loaded" &&
                      lists.value.categories.map((category) => (
                        <option
                          key={category.aggregateId}
                          value={category.aggregateId}
                        >
                          {category.state.name}
                        </option>
                      ))}
                  </select>
                </Field>
                <Button type="submit">Create product</Button>
              </FieldGroup>
            </form>
          </CardContent>
        </Card>
      </div>
      {lists.status === "loading" && <Skeleton className="h-32 w-full" />}
      {lists.status === "failed" && <ProblemAlert error={lists.error} />}
      {lists.status === "loaded" && (
        <Table aria-label="Catalog products">
          <TableHeader>
            <TableRow>
              <TableHead>Product</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Price</TableHead>
              <TableHead>Stock</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {lists.value.products.map((product) => (
              <ProductRow
                key={product.aggregateId}
                product={product}
                brands={lists.value.brands}
                categories={lists.value.categories}
                act={act}
              />
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}

function nameOf(
  records: readonly SnapshotRecord<BrandState | CategoryState>[],
  id: string | null | undefined,
): string {
  if (!id) return "—";
  return records.find((record) => record.aggregateId === id)?.state.name ?? id;
}

/** One product: its price, its publication, and its stock in the inventory. */
function ProductRow({
  product,
  brands,
  categories,
  act,
}: {
  product: SnapshotRecord<ProductState>;
  brands: readonly SnapshotRecord<BrandState>[];
  categories: readonly SnapshotRecord<CategoryState>[];
  act: (action: () => Promise<unknown>, form?: HTMLFormElement) => Promise<void>;
}) {
  const { clients } = useSession();
  const id = product.aggregateId;
  const [stock, reloadStock] = useLoad(
    (signal) =>
      clients.stockStates.get(id, { signal }).then(
        (view) => view.state,
        (error: unknown) => {
          // No stock received yet.
          if (error instanceof WowProblemError && error.status === 404)
            return { available: 0, onHand: 0 };
          throw error;
        },
      ),
    `stock:${id}:${product.version}`,
  );
  const state = product.state;
  return (
    <TableRow data-testid="admin-product" data-product={id}>
      <TableCell>
        <div className="font-medium">{state.name}</div>
        <div className="text-xs text-muted-foreground">
          {id} · {nameOf(brands, state.brandId)} ·{" "}
          {nameOf(categories, state.categoryId)}
        </div>
      </TableCell>
      <TableCell>
        <Badge variant={state.status === "PUBLISHED" ? "default" : "outline"}>
          {state.status}
        </Badge>
      </TableCell>
      <TableCell>
        <form
          className="flex items-center gap-1"
          aria-label={`Price of ${id}`}
          onSubmit={(event) => {
            const { price } = fields(event);
            return act(() =>
              clients.products.changeProductPrice(id, { price: Number(price) }),
            );
          }}
        >
          <span className="w-16">{money(state.price)}</span>
          <Input
            name="price"
            type="number"
            step="0.01"
            className="w-24"
            aria-label={`New price of ${id}`}
          />
          <Button type="submit" size="sm" variant="outline">
            Change
          </Button>
        </form>
      </TableCell>
      <TableCell>
        <form
          className="flex items-center gap-1"
          aria-label={`Stock of ${id}`}
          onSubmit={(event) => {
            const form = event.currentTarget;
            const { quantity } = fields(event);
            return act(async () => {
              await clients.stocks.receiveStock(
                { quantity: Number(quantity) },
                { id },
              );
              reloadStock();
            }, form);
          }}
        >
          <span className="w-20" data-testid="stock">
            {stock.status === "loaded"
              ? `${stock.value.available} / ${stock.value.onHand}`
              : stock.status === "failed"
                ? "?"
                : "…"}
          </span>
          <Input
            name="quantity"
            type="number"
            className="w-20"
            aria-label={`Quantity of ${id} received`}
          />
          <Button type="submit" size="sm" variant="outline">
            Receive
          </Button>
        </form>
      </TableCell>
      <TableCell>
        {state.status === "PUBLISHED" ? (
          <Button
            size="sm"
            variant="outline"
            onClick={() => act(() => clients.products.delistProduct(id))}
          >
            Delist
          </Button>
        ) : (
          <Button
            size="sm"
            onClick={() => act(() => clients.products.publishProduct(id))}
          >
            Publish
          </Button>
        )}
      </TableCell>
    </TableRow>
  );
}
