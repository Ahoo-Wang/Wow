/**
 * - key: example.order.WowExampleOrderState
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "address": {
 *       "$ref": "#/components/schemas/example.order.ShippingAddress",
 *       "readOnly": true
 *     },
 *     "id": {
 *       "type": "string"
 *     },
 *     "items": {
 *       "type": "array",
 *       "items": {
 *         "$ref": "#/components/schemas/example.order.OrderItem",
 *         "readOnly": true
 *       },
 *       "readOnly": true
 *     },
 *     "paidAmount": {
 *       "type": "number"
 *     },
 *     "status": {
 *       "$ref": "#/components/schemas/example.order.OrderStatus"
 *     },
 *     "totalAmount": {
 *       "type": "number"
 *     },
 *     "payable": {
 *       "type": "number",
 *       "readOnly": true
 *     }
 *   },
 *   "required": [
 *     "id"
 *   ]
 * }
 * ```
 */
export interface WowExampleOrderState {
    readonly address: ShippingAddress;
    id: string;
    readonly items: OrderItem[];
    paidAmount: number;
    status: OrderStatus;
    totalAmount: number;
    readonly payable: number;
}

/**
 * 收货地址已修改
 * - key: example.order.AddressChanged
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "shippingAddress": {
 *       "$ref": "#/components/schemas/example.order.ShippingAddress"
 *     }
 *   },
 *   "required": [
 *     "shippingAddress"
 *   ],
 *   "title": "收货地址已修改"
 * }
 * ```
 */
export interface AddressChanged {
    shippingAddress: ShippingAddress;
}

/**
 * 修改收货地址
 * - key: example.order.ChangeAddress
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "shippingAddress": {
 *       "$ref": "#/components/schemas/example.order.ShippingAddress"
 *     }
 *   },
 *   "required": [
 *     "shippingAddress"
 *   ],
 *   "title": "修改收货地址",
 *   "description": ""
 * }
 * ```
 */
export interface ChangeAddress {
    shippingAddress: ShippingAddress;
}

/**
 * 下单
 * - key: example.order.CreateOrder
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "address": {
 *       "$ref": "#/components/schemas/example.order.ShippingAddress"
 *     },
 *     "fromCart": {
 *       "type": "boolean"
 *     },
 *     "items": {
 *       "type": "array",
 *       "items": {
 *         "$ref": "#/components/schemas/example.order.CreateOrder.Item"
 *       },
 *       "minItems": 1
 *     }
 *   },
 *   "required": [
 *     "address",
 *     "fromCart",
 *     "items"
 *   ],
 *   "title": "下单",
 *   "description": ""
 * }
 * ```
 */
export interface CreateOrder {
    address: ShippingAddress;
    fromCart: boolean;
    /**
     * - Array Constraints
     *   - minItems: 1
     */
    items: CreateOrderItem[];
}

/**
 * - key: example.order.CreateOrder.Item
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "price": {
 *       "type": "number",
 *       "exclusiveMinimum": 0
 *     },
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
 *     "price",
 *     "productId",
 *     "quantity"
 *   ]
 * }
 * ```
 */
export interface CreateOrderItem {
    /**
     * - Numeric Constraints
     *   - exclusiveMinimum: 0
     */
    price: number;
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
 * - key: example.order.OrderAggregatedFields
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
 *     "state.address",
 *     "state.address.city",
 *     "state.address.country",
 *     "state.address.detail",
 *     "state.address.district",
 *     "state.address.province",
 *     "state.id",
 *     "state.items",
 *     "state.items.id",
 *     "state.items.price",
 *     "state.items.productId",
 *     "state.items.quantity",
 *     "state.items.totalPrice",
 *     "state.paidAmount",
 *     "state.payable",
 *     "state.status",
 *     "state.totalAmount"
 *   ]
 * }
 * ```
 */
export enum OrderAggregatedFields {
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
    STATE_ADDRESS = `state.address`,
    STATE_ADDRESS_CITY = `state.address.city`,
    STATE_ADDRESS_COUNTRY = `state.address.country`,
    STATE_ADDRESS_DETAIL = `state.address.detail`,
    STATE_ADDRESS_DISTRICT = `state.address.district`,
    STATE_ADDRESS_PROVINCE = `state.address.province`,
    STATE_ID = `state.id`,
    STATE_ITEMS = `state.items`,
    STATE_ITEMS_ID = `state.items.id`,
    STATE_ITEMS_PRICE = `state.items.price`,
    STATE_ITEMS_PRODUCT_ID = `state.items.productId`,
    STATE_ITEMS_QUANTITY = `state.items.quantity`,
    STATE_ITEMS_TOTAL_PRICE = `state.items.totalPrice`,
    STATE_PAID_AMOUNT = `state.paidAmount`,
    STATE_PAYABLE = `state.payable`,
    STATE_STATUS = `state.status`,
    STATE_TOTAL_AMOUNT = `state.totalAmount`
}

/**
 * order_created
 * - key: example.order.OrderCreated
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "address": {
 *       "$ref": "#/components/schemas/example.order.ShippingAddress"
 *     },
 *     "fromCart": {
 *       "type": "boolean"
 *     },
 *     "items": {
 *       "type": "array",
 *       "items": {
 *         "$ref": "#/components/schemas/example.order.OrderItem"
 *       }
 *     },
 *     "orderId": {
 *       "type": "string"
 *     }
 *   },
 *   "required": [
 *     "address",
 *     "fromCart",
 *     "items",
 *     "orderId"
 *   ],
 *   "title": "order_created"
 * }
 * ```
 */
export interface OrderCreated {
    address: ShippingAddress;
    fromCart: boolean;
    items: OrderItem[];
    orderId: string;
}

/**
 * - key: example.order.OrderItem
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "id": {
 *       "type": "string"
 *     },
 *     "price": {
 *       "type": "number"
 *     },
 *     "productId": {
 *       "type": "string"
 *     },
 *     "quantity": {
 *       "type": "integer",
 *       "format": "int32"
 *     },
 *     "totalPrice": {
 *       "type": "number",
 *       "readOnly": true
 *     }
 *   },
 *   "required": [
 *     "id",
 *     "price",
 *     "productId",
 *     "quantity"
 *   ]
 * }
 * ```
 */
export interface OrderItem {
    id: string;
    price: number;
    productId: string;
    /** - format: int32 */
    quantity: number;
    readonly totalPrice: number;
}

/**
 * order_paid
 * - key: example.order.OrderPaid
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "amount": {
 *       "type": "number"
 *     },
 *     "paid": {
 *       "type": "boolean"
 *     }
 *   },
 *   "required": [
 *     "amount",
 *     "paid"
 *   ],
 *   "title": "order_paid"
 * }
 * ```
 */
export interface OrderPaid {
    amount: number;
    paid: boolean;
}

/**
 * order_received
 * - key: example.order.OrderReceived
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "title": "order_received"
 * }
 * ```
 */
export type OrderReceived = Record<string, any>;
/**
 * order_shipped
 * - key: example.order.OrderShipped
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "title": "order_shipped"
 * }
 * ```
 */
export type OrderShipped = Record<string, any>;

/**
 * - key: example.order.OrderStatus
 * - schema: 
 * ```json
 * {
 *   "type": "string",
 *   "enum": [
 *     "CREATED",
 *     "PAID",
 *     "SHIPPED",
 *     "RECEIVED"
 *   ]
 * }
 * ```
 */
export enum OrderStatus {
    CREATED = `CREATED`,
    PAID = `PAID`,
    SHIPPED = `SHIPPED`,
    RECEIVED = `RECEIVED`
}

/**
 * pay_order
 * - key: example.order.PayOrder
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "amount": {
 *       "type": "number",
 *       "exclusiveMinimum": 0
 *     },
 *     "paymentId": {
 *       "type": "string",
 *       "minLength": 1
 *     }
 *   },
 *   "required": [
 *     "amount",
 *     "paymentId"
 *   ],
 *   "title": "pay_order",
 *   "description": ""
 * }
 * ```
 */
export interface PayOrder {
    /**
     * - Numeric Constraints
     *   - exclusiveMinimum: 0
     */
    amount: number;
    /**
     * - String Constraints
     *   - minLength: 1
     */
    paymentId: string;
}

/**
 * 收货
 * - key: example.order.ReceiptOrder
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "title": "收货",
 *   "description": ""
 * }
 * ```
 */
export type ReceiptOrder = Record<string, any>;
/**
 * 发货
 * - key: example.order.ShipOrder
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "title": "发货",
 *   "description": ""
 * }
 * ```
 */
export type ShipOrder = Record<string, any>;

/**
 * - key: example.order.ShippingAddress
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "city": {
 *       "type": "string"
 *     },
 *     "country": {
 *       "type": "string",
 *       "minLength": 1
 *     },
 *     "detail": {
 *       "type": "string"
 *     },
 *     "district": {
 *       "type": "string"
 *     },
 *     "province": {
 *       "type": "string",
 *       "minLength": 1
 *     }
 *   },
 *   "required": [
 *     "city",
 *     "country",
 *     "detail",
 *     "district",
 *     "province"
 *   ]
 * }
 * ```
 */
export interface ShippingAddress {
    city: string;
    /**
     * - String Constraints
     *   - minLength: 1
     */
    country: string;
    detail: string;
    district: string;
    /**
     * - String Constraints
     *   - minLength: 1
     */
    province: string;
}
