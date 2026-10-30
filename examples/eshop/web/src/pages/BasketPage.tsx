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

import { useRef, useState } from "react";
import { Link } from "react-router";
import { MinusIcon, PlusIcon, Trash2Icon } from "lucide-react";
import type { BasketItem } from "@/generated/basket/eshop-basket";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import {
  Table,
  TableBody,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { nameOf, useProductNames } from "@/shop/catalog";
import { checkout, type CheckoutProgress } from "@/shop/checkout";
import { ProblemAlert } from "@/shop/problems";
import { useSession } from "@/shop/session";
import { money, useLoad, useTitle } from "@/shop/useLoad";
import { CheckoutSteps } from "./CheckoutSteps";

/** The buyer's basket: its items, their quantities, and the checkout with its progress. */
export function BasketPage() {
  useTitle("Basket");
  const { user, clients, basketChanged } = useSession();
  const [basket, reload] = useLoad(
    // A basket nobody has added to yet does not exist: it reads as empty.
    async (signal): Promise<BasketItem[]> =>
      (await clients.basket.basket.state.findOwn({ signal }))?.state.items ?? [],
    `basket:${user.id}`,
  );
  const items = basket.status === "loaded" ? basket.value : [];
  const names = useProductNames(items.map((item) => item.productId));
  const [problem, setProblem] = useState<unknown>();
  const [progress, setProgress] = useState<CheckoutProgress>();
  const [busy, setBusy] = useState(false);
  // Where the focus goes once an item is removed: the basket's title stays, the item's row does not.
  const basketTitle = useRef<HTMLDivElement>(null);

  async function act(action: () => Promise<unknown>) {
    setProblem(undefined);
    setBusy(true);
    try {
      await action();
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
      reload();
      basketChanged();
    }
  }

  /**
   * Changes an item's quantity, or removes it at 0. A control pressed while
   * another change is sent does nothing, and stays focused: it is marked
   * `aria-disabled`, not disabled, so the keyboard keeps its place.
   */
  function changeQuantity(item: BasketItem, quantity: number) {
    if (busy) return;
    if (quantity === 0) basketTitle.current?.focus();
    return act(() =>
      quantity === 0
        ? clients.basket.basket.commands.removeBasketItemOwn({
            productId: item.productId,
          })
        : clients.basket.basket.commands.changeBasketItemQuantityOwn({
            productId: item.productId,
            quantity,
          }),
    );
  }

  function checkOut() {
    if (busy) return;
    setProgress({ results: [], done: false });
    return act(async () => {
      try {
        await checkout(clients, setProgress);
      } catch (error) {
        // Refused at admission: no hop was reached.
        setProgress(undefined);
        throw error;
      }
    });
  }

  if (user.role !== "buyer")
    return (
      <Empty>
        <EmptyHeader>
          <EmptyTitle>Baskets are the buyers'</EmptyTitle>
          <EmptyDescription>Switch to a buyer to shop.</EmptyDescription>
        </EmptyHeader>
      </Empty>
    );

  return (
    <div className="flex flex-col gap-4">
      {problem !== undefined && <ProblemAlert error={problem} service="basket" />}
      {basket.status === "loading" && <Skeleton className="h-32 w-full" />}
      {basket.status === "failed" && (
        <ProblemAlert error={basket.error} service="basket" onRetry={reload} />
      )}
      {basket.status === "loaded" && (
        <Card>
          <CardHeader>
            <CardTitle ref={basketTitle} tabIndex={-1}>
              Your basket
            </CardTitle>
            <CardDescription>
              The basket of {user.id}, from the basket service.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {items.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                Your basket is empty. <Link to="/">Browse the shop</Link>.
              </p>
            ) : (
              <Table aria-label="Basket items">
                <TableHeader>
                  <TableRow>
                    <TableHead>Product</TableHead>
                    <TableHead className="hidden sm:table-cell">Unit price</TableHead>
                    <TableHead>Quantity</TableHead>
                    <TableHead className="text-right">Amount</TableHead>
                    <TableHead />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((item) => {
                    const name = nameOf(names, item.productId);
                    return (
                      <TableRow key={item.productId} data-testid="basket-item">
                        <TableCell>{name}</TableCell>
                        <TableCell className="hidden sm:table-cell">
                          {money(item.unitPrice)}
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center gap-1">
                            <Button
                              variant="outline"
                              size="icon-xs"
                              aria-label={`One ${name} less`}
                              aria-disabled={busy}
                              onClick={() =>
                                changeQuantity(item, item.quantity - 1)
                              }
                            >
                              <MinusIcon />
                            </Button>
                            <span data-testid="quantity">{item.quantity}</span>
                            <Button
                              variant="outline"
                              size="icon-xs"
                              aria-label={`One ${name} more`}
                              aria-disabled={busy}
                              onClick={() =>
                                changeQuantity(item, item.quantity + 1)
                              }
                            >
                              <PlusIcon />
                            </Button>
                          </div>
                        </TableCell>
                        <TableCell className="text-right">
                          {money(Number(item.unitPrice) * item.quantity)}
                        </TableCell>
                        <TableCell>
                          <Button
                            variant="ghost"
                            size="icon-xs"
                            aria-label={`Remove ${name}`}
                            aria-disabled={busy}
                            onClick={() => changeQuantity(item, 0)}
                          >
                            <Trash2Icon />
                          </Button>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
                <TableFooter>
                  <TableRow>
                    <TableCell colSpan={2} className="sm:hidden">
                      Total
                    </TableCell>
                    <TableCell colSpan={3} className="hidden sm:table-cell">
                      Total
                    </TableCell>
                    <TableCell className="text-right" data-testid="total">
                      {money(
                        items.reduce(
                          (sum, item) => sum + Number(item.unitPrice) * item.quantity,
                          0,
                        ),
                      )}
                    </TableCell>
                    <TableCell />
                  </TableRow>
                </TableFooter>
              </Table>
            )}
          </CardContent>
          <CardFooter className="gap-2">
            {/* Disabled on an empty basket; busy, it keeps its focus and does nothing. */}
            <Button
              onClick={checkOut}
              disabled={items.length === 0}
              aria-disabled={busy}
            >
              {busy && progress && !progress.done && (
                <Spinner data-icon="inline-start" />
              )}
              Check out
            </Button>
          </CardFooter>
        </Card>
      )}
      {progress && <CheckoutSteps progress={progress} />}
    </div>
  );
}
