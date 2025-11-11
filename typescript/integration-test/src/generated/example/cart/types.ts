/** - key: example.cart.CartData */
export interface CartData {
  items: CartItem[];
}

/**
 * 加入购物车
 * 加入购物车
 * - key: example.cart.AddCartItem
 */
export interface AddCartItem {
  /**
   * - String Constraints
   *   - minLength: 1
   */
  productId: string;
  /**
   * - format: int32
   * - Numeric Constraints
   *   - exclusiveMinimum: 0
   */
  quantity: number;
}

/** - key: example.cart.CartAggregatedFields */
export enum CartAggregatedFields {
  AGGREGATE_ID = 'aggregateId',
  TENANT_ID = 'tenantId',
  OWNER_ID = 'ownerId',
  VERSION = 'version',
  EVENT_ID = 'eventId',
  FIRST_OPERATOR = 'firstOperator',
  OPERATOR = 'operator',
  FIRST_EVENT_TIME = 'firstEventTime',
  EVENT_TIME = 'eventTime',
  DELETED = 'deleted',
  STATE = 'state',
  STATE_ID = 'state.id',
  STATE_ITEMS = 'state.items',
  STATE_ITEMS_PRODUCT_ID = 'state.items.productId',
  STATE_ITEMS_QUANTITY = 'state.items.quantity',
}

/** - key: example.cart.CartItem */
export interface CartItem {
  productId: string;
  /** - format: int32 */
  quantity: number;
}

/**
 * 商品已加入购物车
 * - key: example.cart.CartItemAdded
 */
export interface CartItemAdded {
  added: CartItem;
}

/**
 * cart_item_removed
 * - key: example.cart.CartItemRemoved
 */
export interface CartItemRemoved {
  productIds: string[];
}

/**
 * cart_quantity_changed
 * - key: example.cart.CartQuantityChanged
 */
export interface CartQuantityChanged {
  changed: CartItem;
}

/** - key: example.cart.CartState */
export interface CartState {
  id: string;
  items: CartItem[];
}

/**
 * 变更购买数量
 * - key: example.cart.ChangeQuantity
 */
export interface ChangeQuantity {
  /**
   * - String Constraints
   *   - minLength: 1
   */
  productId: string;
  /**
   * - format: int32
   * - Numeric Constraints
   *   - exclusiveMinimum: 0
   */
  quantity: number;
}

/**
 * mock_variable_command
 * - key: example.cart.MockVariableCommand
 */
export type MockVariableCommand = Record<string, any>;

/** - key: example.cart.MockVariableCommand.MockEnum */
export enum MockVariableCommandMockEnum {
  FIRST = 'First',
  SECOND = 'Second',
  THIRD = 'Third',
}

/**
 * 挂载的命令
 * - key: example.cart.MountedCommand
 */
export type MountedCommand = Record<string, any>;

/**
 * 删除商品
 * - key: example.cart.RemoveCartItem
 */
export interface RemoveCartItem {
  /**
   * - Array Constraints
   *   - minItems: 1
   */
  productIds: string[];
}

/**
 * view_cart
 * - key: example.cart.ViewCart
 */
export type ViewCart = Record<string, any>;
