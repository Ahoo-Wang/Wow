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

import { useState, type FormEvent, type ReactNode } from "react";
import { asc, filter } from "@ahoo-wang/wow-client/dsl";
import type { Snapshot } from "@ahoo-wang/wow-client";
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
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
} from "@/components/ui/empty";
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
import { useStocks, type Stock } from "@/shop/catalog";
import { ProblemAlert } from "@/shop/problems";
import { useSession } from "@/shop/session";
import { money, useLoad, useTitle, type Loaded } from "@/shop/useLoad";

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

/** What an action came to, shown where it was taken: a form or a product's row. */
interface Feedback {
  readonly place: string;
  readonly done?: string;
  readonly error?: unknown;
}

/** Runs an action and says what it came to at its place; the lists are read again after it. */
type Act = (
  place: string,
  action: () => Promise<unknown>,
  done: string,
  form?: HTMLFormElement,
) => Promise<void>;

/**
 * The employee's catalog administration: brands and categories, products
 * with their prices and publication, and each product's stock in the
 * inventory service. Each action's outcome, done or refused, is shown where
 * it was taken.
 */
export function AdminPage() {
  useTitle("Catalog admin");
  const { user, clients } = useSession();
  const [feedback, setFeedback] = useState<Feedback>();
  const [stockRevision, setStockRevision] = useState(0);
  const [lists, reload] = useLoad(
    async (signal) => {
      const all = { filter: filter.matchAll(), limit: 200 };
      const [brands, categories, products] = await Promise.all([
        clients.catalog.brand.snapshots.list(
          { ...all, sort: [asc("state.name")] },
          { signal },
        ),
        clients.catalog.category.snapshots.list(
          { ...all, sort: [asc("state.name")] },
          { signal },
        ),
        clients.catalog.product.snapshots.list(
          { ...all, sort: [asc("state.name")] },
          { signal },
        ),
      ]);
      return { brands, categories, products };
    },
    `admin:${user.id}`,
  );
  const products = lists.status === "loaded" ? lists.value.products : [];
  const stocks = useStocks(
    products.map((product) => product.aggregateId),
    stockRevision,
  );

  const act: Act = async (place, action, done, form) => {
    setFeedback(undefined);
    try {
      await action();
      form?.reset();
      setFeedback({ place, done });
    } catch (error) {
      setFeedback({ place, error });
    }
    reload();
    setStockRevision((revision) => revision + 1);
  };

  /** The outcome of the last action, when it was taken at `place`. */
  const feedbackAt = (place: string): ReactNode => {
    if (feedback?.place !== place) return null;
    return feedback.error !== undefined ? (
      <ProblemAlert error={feedback.error} service="catalog" />
    ) : (
      <p role="status" className="text-sm text-muted-foreground">
        {feedback.done}
      </p>
    );
  };

  return (
    <div className="flex flex-col gap-4">
      {user.role !== "employee" && (
        <p className="text-sm text-muted-foreground" role="note">
          Only an employee changes the catalog and the stock: the catalog and
          inventory services refuse these commands to {user.id}.
        </p>
      )}
      {lists.status === "failed" && (
        <ProblemAlert error={lists.error} service="catalog" onRetry={reload} />
      )}
      <div className="grid gap-4 lg:grid-cols-3">
        <NamedList
          kind="brand"
          title="Brands"
          records={lists.status === "loaded" ? lists.value.brands : undefined}
          create={(name, id) =>
            clients.catalog.brand.commands.createBrand({ name }, { id: id || undefined })
          }
          rename={(id, name) => clients.catalog.brand.commands.renameBrand(id, { name })}
          act={act}
          feedbackAt={feedbackAt}
        />
        <NamedList
          kind="category"
          title="Categories"
          records={lists.status === "loaded" ? lists.value.categories : undefined}
          create={(name, id) =>
            clients.catalog.category.commands.createCategory({ name }, { id: id || undefined })
          }
          rename={(id, name) =>
            clients.catalog.category.commands.renameCategory(id, { name })
          }
          act={act}
          feedbackAt={feedbackAt}
        />
        <Card>
          <CardHeader>
            <CardTitle>New product</CardTitle>
            <CardDescription>
              A draft; publish it once it has a brand and a category.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <form
              aria-label="New product"
              onSubmit={(event) => {
                const form = event.currentTarget;
                const { id, name, price, brandId, categoryId, description } =
                  fields(event);
                return act(
                  "new-product",
                  () =>
                    clients.catalog.product.commands.createProduct(
                      {
                        name,
                        price,
                        description,
                        brandId: brandId || null,
                        categoryId: categoryId || null,
                      },
                      { id: id || undefined },
                    ),
                  `Created the draft ${name}.`,
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
                <ReferenceFields
                  idPrefix="product"
                  brands={lists.status === "loaded" ? lists.value.brands : []}
                  categories={lists.status === "loaded" ? lists.value.categories : []}
                />
                <Button type="submit">Create product</Button>
              </FieldGroup>
            </form>
            {feedbackAt("new-product")}
          </CardContent>
        </Card>
      </div>
      {lists.status === "loading" && <Skeleton className="h-32 w-full" />}
      {lists.status === "loaded" && products.length === 0 && (
        <Empty>
          <EmptyHeader>
            <EmptyTitle>No products yet</EmptyTitle>
            <EmptyDescription>
              Create one above: it starts as a draft.
            </EmptyDescription>
          </EmptyHeader>
        </Empty>
      )}
      {stocks.status === "failed" && products.length > 0 && (
        <ProblemAlert
          error={stocks.error}
          service="inventory"
          onRetry={() => setStockRevision((revision) => revision + 1)}
        />
      )}
      {lists.status === "loaded" && products.length > 0 && (
        <Table aria-label="Catalog products">
          <TableHeader>
            <TableRow>
              <TableHead>Product</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Price</TableHead>
              <TableHead>Stock (available / on hand)</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {products.map((product) => (
              <ProductRow
                key={product.aggregateId}
                product={product}
                brands={lists.value.brands}
                categories={lists.value.categories}
                stocks={stocks}
                act={act}
                feedbackAt={feedbackAt}
              />
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}

/** A brand's or a category's selects of a product's form: none, or one of those listed. */
function ReferenceFields({
  idPrefix,
  brands,
  categories,
  product,
}: {
  idPrefix: string;
  brands: readonly Snapshot<BrandState>[];
  categories: readonly Snapshot<CategoryState>[];
  product?: ProductState;
}) {
  return (
    <>
      <Field>
        <FieldLabel htmlFor={`${idPrefix}-brand`}>Brand</FieldLabel>
        <select
          id={`${idPrefix}-brand`}
          name="brandId"
          className={SELECT}
          defaultValue={product?.brandId ?? ""}
        >
          <option value="">None</option>
          {brands.map((brand) => (
            <option key={brand.aggregateId} value={brand.aggregateId}>
              {brand.state.name}
            </option>
          ))}
        </select>
      </Field>
      <Field>
        <FieldLabel htmlFor={`${idPrefix}-category`}>Category</FieldLabel>
        <select
          id={`${idPrefix}-category`}
          name="categoryId"
          className={SELECT}
          defaultValue={product?.categoryId ?? ""}
        >
          <option value="">None</option>
          {categories.map((category) => (
            <option key={category.aggregateId} value={category.aggregateId}>
              {category.state.name}
            </option>
          ))}
        </select>
      </Field>
    </>
  );
}

/** The brands or the categories: each listed and renamed in place, and a new one. */
function NamedList({
  kind,
  title,
  records,
  create,
  rename,
  act,
  feedbackAt,
}: {
  kind: "brand" | "category";
  title: string;
  records: readonly Snapshot<BrandState | CategoryState>[] | undefined;
  create: (name: string, id: string) => Promise<unknown>;
  rename: (id: string, name: string) => Promise<unknown>;
  act: Act;
  feedbackAt: (place: string) => ReactNode;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {records && records.length === 0 && (
          <p className="text-sm text-muted-foreground">None yet.</p>
        )}
        {records && records.length > 0 && (
          <ul className="flex flex-col gap-2" aria-label={title}>
            {records.map((record) => (
              <li key={record.aggregateId} className="flex flex-col gap-1">
                <form
                  className="flex items-center gap-1"
                  aria-label={`Rename ${record.state.name}`}
                  onSubmit={(event) => {
                    const form = event.currentTarget;
                    const { name } = fields(event);
                    return act(
                      `${kind}:${record.aggregateId}`,
                      () => rename(record.aggregateId, name),
                      `Renamed ${record.state.name} to ${name}.`,
                      form,
                    );
                  }}
                >
                  <span className="min-w-0 flex-1 truncate text-sm">
                    {record.state.name}{" "}
                    <span className="text-xs text-muted-foreground">
                      {record.aggregateId}
                    </span>
                  </span>
                  <Input
                    name="name"
                    className="w-28"
                    aria-label={`New name of ${record.state.name}`}
                  />
                  <Button
                    type="submit"
                    size="sm"
                    variant="outline"
                    aria-label={`Rename ${record.state.name}`}
                  >
                    Rename
                  </Button>
                </form>
                {feedbackAt(`${kind}:${record.aggregateId}`)}
              </li>
            ))}
          </ul>
        )}
        <form
          aria-label={`New ${kind}`}
          onSubmit={(event) => {
            const form = event.currentTarget;
            const { id, name } = fields(event);
            return act(`new-${kind}`, () => create(name, id), `Created ${name}.`, form);
          }}
        >
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor={`${kind}-id`}>Id</FieldLabel>
              <Input id={`${kind}-id`} name="id" placeholder="Generated" />
            </Field>
            <Field>
              <FieldLabel htmlFor={`${kind}-name`}>Name</FieldLabel>
              <Input id={`${kind}-name`} name="name" />
            </Field>
            <Button type="submit">Create {kind}</Button>
          </FieldGroup>
        </form>
        {feedbackAt(`new-${kind}`)}
      </CardContent>
    </Card>
  );
}

function nameOf(
  records: readonly Snapshot<BrandState | CategoryState>[],
  id: string | null | undefined,
): string {
  if (!id) return "—";
  return records.find((record) => record.aggregateId === id)?.state.name ?? id;
}

/** A product's stock as its row shows it. */
function stockText(stocks: Loaded<ReadonlyMap<string, Stock>>, id: string): ReactNode {
  if (stocks.status === "loading") return "…";
  if (stocks.status === "failed")
    return <span title="The inventory service did not answer.">unavailable</span>;
  const stock = stocks.value.get(id) ?? { available: 0, onHand: 0 };
  return `${stock.available} / ${stock.onHand}`;
}

/**
 * One product: its price, its publication, its stock in the inventory, and,
 * opened, its name, description, brand and category, or its discontinuation.
 */
function ProductRow({
  product,
  brands,
  categories,
  stocks,
  act,
  feedbackAt,
}: {
  product: Snapshot<ProductState>;
  brands: readonly Snapshot<BrandState>[];
  categories: readonly Snapshot<CategoryState>[];
  stocks: Loaded<ReadonlyMap<string, Stock>>;
  act: Act;
  feedbackAt: (place: string) => ReactNode;
}) {
  const { clients } = useSession();
  const [editing, setEditing] = useState(false);
  const id = product.aggregateId;
  const state = product.state;
  const place = `product:${id}`;
  return (
    <>
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
              const form = event.currentTarget;
              const { price } = fields(event);
              return act(
                place,
                () => clients.catalog.product.commands.changeProductPrice(id, { price }),
                `Changed the price of ${state.name} to ${price ? money(price) : "nothing"}.`,
                form,
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
            <Button
              type="submit"
              size="sm"
              variant="outline"
              aria-label={`Change the price of ${state.name}`}
            >
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
              return act(
                place,
                () =>
                  clients.inventory.stock.commands.receiveStock(
                    { quantity: Number(quantity) },
                    { id },
                  ),
                `Received ${quantity} of ${state.name}.`,
                form,
              );
            }}
          >
            <span className="w-24" data-testid="stock">
              {stockText(stocks, id)}
            </span>
            <Input
              name="quantity"
              type="number"
              className="w-20"
              aria-label={`Quantity of ${id} received`}
            />
            <Button
              type="submit"
              size="sm"
              variant="outline"
              aria-label={`Receive stock of ${state.name}`}
            >
              Receive
            </Button>
          </form>
        </TableCell>
        <TableCell>
          <div className="flex items-center gap-1">
            {state.status === "PUBLISHED" ? (
              <Button
                size="sm"
                variant="outline"
                aria-label={`Delist ${state.name}`}
                onClick={() =>
                  act(
                    place,
                    () => clients.catalog.product.commands.delistProduct(id),
                    `Delisted ${state.name}.`,
                  )
                }
              >
                Delist
              </Button>
            ) : (
              <Button
                size="sm"
                aria-label={`Publish ${state.name}`}
                onClick={() =>
                  act(
                    place,
                    () => clients.catalog.product.commands.publishProduct(id),
                    `Published ${state.name}.`,
                  )
                }
              >
                Publish
              </Button>
            )}
            <Button
              size="sm"
              variant="ghost"
              aria-expanded={editing}
              aria-label={`Edit ${state.name}`}
              onClick={() => setEditing(!editing)}
            >
              Edit
            </Button>
          </div>
        </TableCell>
      </TableRow>
      {(editing || feedbackAt(place)) && (
        <TableRow>
          <TableCell colSpan={5} className="whitespace-normal">
            <div className="flex flex-col gap-3">
            {feedbackAt(place)}
            {editing && (
              <div className="flex flex-col gap-4 lg:flex-row">
                <form
                  className="flex-1"
                  aria-label={`Edit ${id}`}
                  onSubmit={(event) => {
                    const { name, description, brandId, categoryId } = fields(event);
                    return act(
                      place,
                      () =>
                        clients.catalog.product.commands.updateProduct(id, {
                          name,
                          description,
                          brandId: brandId || null,
                          categoryId: categoryId || null,
                        }),
                      `Saved ${name}.`,
                    );
                  }}
                >
                  <FieldGroup className="grid gap-3 sm:grid-cols-2">
                    <Field>
                      <FieldLabel htmlFor={`edit-${id}-name`}>Name</FieldLabel>
                      <Input id={`edit-${id}-name`} name="name" defaultValue={state.name} />
                    </Field>
                    <Field>
                      <FieldLabel htmlFor={`edit-${id}-description`}>
                        Description
                      </FieldLabel>
                      <Input
                        id={`edit-${id}-description`}
                        name="description"
                        defaultValue={state.description}
                      />
                    </Field>
                    <ReferenceFields
                      idPrefix={`edit-${id}`}
                      brands={brands}
                      categories={categories}
                      product={state}
                    />
                    <div>
                      <Button type="submit" aria-label={`Save ${state.name}`}>
                        Save
                      </Button>
                    </div>
                  </FieldGroup>
                </form>
                <form
                  className="flex flex-col gap-2 lg:w-64"
                  aria-label={`Discontinue ${id}`}
                  onSubmit={(event) => {
                    const { reason } = fields(event);
                    return act(
                      place,
                      () =>
                        clients.catalog.product.commands.discontinueProduct(id, {
                          reason,
                        }),
                      `Discontinued ${state.name}: it left the catalog.`,
                    );
                  }}
                >
                  <Field>
                    <FieldLabel htmlFor={`discontinue-${id}-reason`}>
                      Discontinue for good, because
                    </FieldLabel>
                    <Input id={`discontinue-${id}-reason`} name="reason" />
                  </Field>
                  <div>
                    <Button
                      type="submit"
                      variant="destructive"
                      aria-label={`Discontinue ${state.name}`}
                    >
                      Discontinue
                    </Button>
                  </div>
                </form>
              </div>
            )}
            </div>
          </TableCell>
        </TableRow>
      )}
    </>
  );
}
