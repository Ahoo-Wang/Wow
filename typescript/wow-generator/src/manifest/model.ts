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

import type { ServiceRouteModel } from './serviceRoutes';
import type { TextNumbersDeclaration, TextNumbersModel } from './textNumbers';

/** A property of a generated interface. */
export interface PropertyModel {
  /** As written in the interface: quoted when it is no identifier. */
  readonly name: string;
  readonly type: string;
  readonly optional: boolean;
  readonly description?: string;
}

/** A type declared for a schema of `$defs`. */
export interface TypeModel {
  /** The `$defs` key. */
  readonly key: string;
  readonly name: string;
  /** The file it is declared in, relative to the output directory. */
  readonly file: string;
  readonly description?: string;
  /** An interface's properties, or undefined for a type alias. */
  readonly properties?: readonly PropertyModel[];
  /** A type alias's type; or, for an interface, its index signature's. */
  readonly type?: string;
  /** The type names it refers to. */
  readonly references: readonly string[];
}

/** A command of an aggregate client. */
export interface CommandModel {
  readonly name: string;
  readonly method: string;
  /** Its routes, as the Manifest lists them: the client sends it by them. */
  readonly routes: readonly Route[];
  /** Its route by id, and its route without one. */
  readonly byIdPath?: { readonly path: string };
  readonly unaddressedPath?: { readonly path: string };
  /**
   * Of an aggregate whose id is its owner's, the method that sends it by no id
   * to the caller's own aggregate (`addItemOwn`); `method` then sends it by id.
   */
  readonly ownMethod?: string;
  /** The body's type name, or undefined when its schema is unknown. */
  readonly bodyType: string;
  /** The body may be left out: its schema requires nothing the method takes. */
  readonly bodyOptional: boolean;
  /**
   * The property of its body that names its aggregate (the Manifest's
   * `idProperty`): the command then has no route by id, and its method no id.
   */
  readonly idProperty?: string;
  /**
   * Its `idProperty` is computed (`readOnly` in its schema): the service
   * computes it from the body's fields, so the method takes the body without it.
   */
  readonly idComputed?: boolean;
  readonly creation: string;
  readonly mounted: boolean;
  readonly produces: readonly string[];
  /** What the command's class says it is, as the Manifest describes it. */
  readonly description?: string;
  /** Its body has exact numbers, under `textNumbers.commands` of its aggregate. */
  readonly bodyNumbers?: boolean;
}

/** A route of the Manifest: a method and a path, `{id}` the aggregate id. */
export interface Route {
  readonly method: string;
  readonly path: string;
}

/** A lifecycle command an aggregate lists. */
export interface LifecycleModel {
  /** `wow.lifecycle.DeleteAggregate`, … */
  readonly name: string;
  /** The `LifecycleCommands` member. */
  readonly member: string;
  readonly method: string;
  /** Its routes, as the Manifest lists them. */
  readonly routes: readonly Route[];
  /**
   * Of an aggregate whose id is its owner's, the method that sends it by no id
   * to the caller's own aggregate (`deleteAggregateOwn`).
   */
  readonly ownMethod?: string;
  /**
   * The body's type, or undefined for a command without one: the type
   * generated from the Manifest's `$defs` entry of the command's name when it
   * holds one (`bodyGenerated`), else `@ahoo-wang/wow-client`'s.
   */
  readonly bodyType?: string;
  /** The body's type is generated from the Manifest, not `@ahoo-wang/wow-client`'s. */
  readonly bodyGenerated?: boolean;
  /** What the Manifest's schema of the body says the command does. */
  readonly description?: string;
}

/** An aggregate and its clients. */
export interface AggregateModel {
  readonly context: string;
  readonly name: string;
  readonly description?: string;
  /** The type-name prefix: `Product`. */
  readonly typeName: string;
  /** The directory of its modules, relative to the output directory. */
  readonly directory: string;
  readonly ownership: string;
  /**
   * Where its id comes from: `assigned`, or `owner` for one whose id is its
   * owner's, whose commands and state read also go by no id to the caller's own.
   */
  readonly idSource: string;
  readonly snapshot: boolean;
  readonly stateType: string;
  readonly stateFields: readonly string[];
  readonly commands: readonly CommandModel[];
  readonly events: readonly {
    /** The name the Manifest declares: `ProductCreated`. */
    readonly name: string;
    /**
     * The event type the server names it by on the wire (08-data-protocols §2):
     * `context.aggregate.EventName`, or the declared name when it is already
     * qualified.
     */
    readonly wireName: string;
    readonly revision: number;
    readonly type: string;
  }[];
  readonly lifecycle: readonly LifecycleModel[];
  readonly queryModels: readonly string[];
  /** Its access attributes (decision D390), in the Manifest's order. */
  readonly access: readonly AccessModel[];
  /**
   * The exact numbers of its state, events (by wire name), commands and
   * access attributes; none when it has none.
   */
  readonly textNumbers?: {
    readonly state?: TextNumbersModel;
    readonly events?: Readonly<Record<string, TextNumbersModel>>;
    readonly commands?: Readonly<Record<string, TextNumbersModel>>;
    readonly access?: TextNumbersModel;
  };
}

/** An access attribute of an aggregate: its name, the TypeScript type of a value, and whether it holds several. */
export interface AccessModel {
  readonly name: string;
  /**
   * A value's type: an integer is a `Long` on the server, typed `string` and
   * read as its exact text.
   */
  readonly type: string;
  readonly multiple: boolean;
  /** Whether its values are integers, read as their exact text. */
  readonly integer: boolean;
}

/** A bounded context and what its modules declare. */
export interface ContextModel {
  readonly name: string;
  readonly description?: string;
  /** The type-name prefix: `EshopCatalog`. */
  readonly typeName: string;
  /** The constant prefix: `ESHOP_CATALOG`. */
  readonly constantName: string;
  readonly directory: string;
  readonly errors: readonly {
    readonly code: string;
    readonly status: number;
    readonly description?: string;
  }[];
  readonly subscriptions: readonly string[];
  readonly aggregates: readonly AggregateModel[];
  /** Its service routes, in the Manifest's order. */
  readonly routes: readonly ServiceRouteModel[];
  /** The marks of the recursive types declared in it, its `textNumbers.ts`. */
  readonly textNumbers: readonly TextNumbersDeclaration[];
}

/** What a Manifest generates, decided before anything is written. */
export interface ManifestModel {
  readonly service?: string;
  readonly types: readonly TypeModel[];
  readonly contexts: readonly ContextModel[];
}
