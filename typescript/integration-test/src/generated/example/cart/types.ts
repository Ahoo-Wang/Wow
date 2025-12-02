/**
 * - key: example.cart.CartData
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "items": {
 *       "type": "array",
 *       "items": {
 *         "$ref": "#/components/schemas/example.cart.CartItem"
 *       }
 *     },
 *     "size": {
 *       "type": "integer",
 *       "format": "int32",
 *       "description": "购物车数量",
 *       "readOnly": true
 *     }
 *   },
 *   "required": [
 *     "items"
 *   ]
 * }
 * ```
 */
export interface CartData {
    items: CartItem[];
    /**
     * 购物车数量
     * - format: int32
     */
    readonly size: number;
}

/**
 * 加入购物车
 * - key: example.cart.AddCartItem
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "productId": {
 *       "type": "string",
 *       "minLength": 1
 *     },
 *     "quantity": {
 *       "type": "integer",
 *       "format": "int32",
 *       "exclusiveMinimum": 0
 *     }
 *   },
 *   "required": [
 *     "productId"
 *   ],
 *   "title": "加入购物车",
 *   "description": ""
 * }
 * ```
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

/**
 * - key: example.cart.CartAggregatedFields
 * - schema: 
 * ```json
 * {
 *   "type": "string",
 *   "enum": [
 *     "",
 *     "aggregateId",
 *     "tenantId",
 *     "ownerId",
 *     "version",
 *     "eventId",
 *     "firstOperator",
 *     "operator",
 *     "firstEventTime",
 *     "eventTime",
 *     "deleted",
 *     "state",
 *     "state.id",
 *     "state.items",
 *     "state.items.productId",
 *     "state.items.quantity",
 *     "state.size"
 *   ]
 * }
 * ```
 */
export enum CartAggregatedFields {
    AGGREGATE_ID = `aggregateId`,
    TENANT_ID = `tenantId`,
    OWNER_ID = `ownerId`,
    VERSION = `version`,
    EVENT_ID = `eventId`,
    FIRST_OPERATOR = `firstOperator`,
    OPERATOR = `operator`,
    FIRST_EVENT_TIME = `firstEventTime`,
    EVENT_TIME = `eventTime`,
    DELETED = `deleted`,
    STATE = `state`,
    STATE_ID = `state.id`,
    STATE_ITEMS = `state.items`,
    STATE_ITEMS_PRODUCT_ID = `state.items.productId`,
    STATE_ITEMS_QUANTITY = `state.items.quantity`,
    STATE_SIZE = `state.size`
}

/**
 * - key: example.cart.CartItem
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "productId": {
 *       "type": "string"
 *     },
 *     "quantity": {
 *       "type": "integer",
 *       "format": "int32"
 *     }
 *   },
 *   "required": [
 *     "productId"
 *   ]
 * }
 * ```
 */
export interface CartItem {
    productId: string;
    /** - format: int32 */
    quantity: number;
}

/**
 * 商品已加入购物车
 * - key: example.cart.CartItemAdded
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "added": {
 *       "$ref": "#/components/schemas/example.cart.CartItem"
 *     }
 *   },
 *   "required": [
 *     "added"
 *   ],
 *   "title": "商品已加入购物车"
 * }
 * ```
 */
export interface CartItemAdded {
    added: CartItem;
}

/**
 * cart_item_removed
 * - key: example.cart.CartItemRemoved
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "productIds": {
 *       "type": "array",
 *       "items": {
 *         "type": "string"
 *       }
 *     }
 *   },
 *   "required": [
 *     "productIds"
 *   ],
 *   "title": "cart_item_removed"
 * }
 * ```
 */
export interface CartItemRemoved {
    productIds: string[];
}

/**
 * cart_quantity_changed
 * - key: example.cart.CartQuantityChanged
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "changed": {
 *       "$ref": "#/components/schemas/example.cart.CartItem"
 *     }
 *   },
 *   "required": [
 *     "changed"
 *   ],
 *   "title": "cart_quantity_changed"
 * }
 * ```
 */
export interface CartQuantityChanged {
    changed: CartItem;
}

/**
 * - key: example.cart.CartState
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "id": {
 *       "type": "string"
 *     },
 *     "items": {
 *       "type": "array",
 *       "items": {
 *         "$ref": "#/components/schemas/example.cart.CartItem"
 *       }
 *     },
 *     "size": {
 *       "type": "integer",
 *       "format": "int32",
 *       "description": "购物车数量",
 *       "readOnly": true
 *     }
 *   },
 *   "required": [
 *     "id"
 *   ]
 * }
 * ```
 */
export interface CartState {
    id: string;
    items: CartItem[];
    /**
     * 购物车数量
     * - format: int32
     */
    readonly size: number;
}

/**
 * 变更购买数量
 * - key: example.cart.ChangeQuantity
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "productId": {
 *       "type": "string",
 *       "minLength": 1
 *     },
 *     "quantity": {
 *       "type": "integer",
 *       "format": "int32",
 *       "exclusiveMinimum": 0
 *     }
 *   },
 *   "required": [
 *     "productId",
 *     "quantity"
 *   ],
 *   "title": "变更购买数量",
 *   "description": ""
 * }
 * ```
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
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "title": "mock_variable_command",
 *   "description": ""
 * }
 * ```
 */
export type MockVariableCommand = Record<string, any>;

/**
 * - key: example.cart.MockVariableCommand.MockEnum
 * - schema: 
 * ```json
 * {
 *   "type": "string",
 *   "enum": [
 *     "First",
 *     "Second",
 *     "Third"
 *   ]
 * }
 * ```
 */
export enum MockVariableCommandMockEnum {
    FIRST = `First`,
    SECOND = `Second`,
    THIRD = `Third`
}

/**
 * 挂载的命令
 * - key: example.cart.MountedCommand
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "title": "挂载的命令",
 *   "description": ""
 * }
 * ```
 */
export type MountedCommand = Record<string, any>;

/**
 * 删除商品
 * - key: example.cart.RemoveCartItem
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "productIds": {
 *       "type": "array",
 *       "items": {
 *         "type": "string"
 *       },
 *       "minItems": 1
 *     }
 *   },
 *   "required": [
 *     "productIds"
 *   ],
 *   "title": "删除商品",
 *   "description": ""
 * }
 * ```
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
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "title": "view_cart",
 *   "description": ""
 * }
 * ```
 */
export type ViewCart = Record<string, any>;
