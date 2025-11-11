/** - key: example.order.WowExampleOrderState */
export interface WowExampleOrderState {
  address: ShippingAddress;
  id: string;
  items: OrderItem[];
  paidAmount: number;
  status: OrderStatus;
  totalAmount: number;
  payable: number;
}

/**
 * 收货地址已修改
 * - key: example.order.AddressChanged
 */
export interface AddressChanged {
  shippingAddress: ShippingAddress;
}

/**
 * 修改收货地址
 * - key: example.order.ChangeAddress
 */
export interface ChangeAddress {
  shippingAddress: ShippingAddress;
}

/**
 * 下单
 * - key: example.order.CreateOrder
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

/** - key: example.order.CreateOrder.Item */
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

/** - key: example.order.OrderAggregatedFields */
export enum OrderAggregatedFields {
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
  STATE_ADDRESS = 'state.address',
  STATE_ADDRESS_CITY = 'state.address.city',
  STATE_ADDRESS_COUNTRY = 'state.address.country',
  STATE_ADDRESS_DETAIL = 'state.address.detail',
  STATE_ADDRESS_DISTRICT = 'state.address.district',
  STATE_ADDRESS_PROVINCE = 'state.address.province',
  STATE_ID = 'state.id',
  STATE_ITEMS = 'state.items',
  STATE_ITEMS_ID = 'state.items.id',
  STATE_ITEMS_PRICE = 'state.items.price',
  STATE_ITEMS_PRODUCT_ID = 'state.items.productId',
  STATE_ITEMS_QUANTITY = 'state.items.quantity',
  STATE_ITEMS_TOTAL_PRICE = 'state.items.totalPrice',
  STATE_PAID_AMOUNT = 'state.paidAmount',
  STATE_PAYABLE = 'state.payable',
  STATE_STATUS = 'state.status',
  STATE_TOTAL_AMOUNT = 'state.totalAmount',
}

/**
 * order_created
 * - key: example.order.OrderCreated
 */
export interface OrderCreated {
  address: ShippingAddress;
  fromCart: boolean;
  items: OrderItem[];
  orderId: string;
}

/** - key: example.order.OrderItem */
export interface OrderItem {
  id: string;
  price: number;
  productId: string;
  /** - format: int32 */
  quantity: number;
  totalPrice: number;
}

/**
 * order_paid
 * - key: example.order.OrderPaid
 */
export interface OrderPaid {
  amount: number;
  paid: boolean;
}

/**
 * order_received
 * - key: example.order.OrderReceived
 */
export type OrderReceived = Record<string, any>;
/**
 * order_shipped
 * - key: example.order.OrderShipped
 */
export type OrderShipped = Record<string, any>;

/** - key: example.order.OrderStatus */
export enum OrderStatus {
  CREATED = 'CREATED',
  PAID = 'PAID',
  SHIPPED = 'SHIPPED',
  RECEIVED = 'RECEIVED',
}

/**
 * pay_order
 * - key: example.order.PayOrder
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
 */
export type ReceiptOrder = Record<string, any>;
/**
 * 发货
 * - key: example.order.ShipOrder
 */
export type ShipOrder = Record<string, any>;

/** - key: example.order.ShippingAddress */
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
