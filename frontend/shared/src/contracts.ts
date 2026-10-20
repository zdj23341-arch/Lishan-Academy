export type Id = string;
export type IsoTime = string;
export type MoneyFen = number;
export type ProductType = 'COURSE' | 'BOOK' | 'MEMBERSHIP';
export type OrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'COMPLETED' | 'CANCELLED';
export type FulfillmentStatus = 'NOT_REQUIRED' | 'PENDING_SHIPMENT' | 'SHIPPED' | 'RECEIVED';
export type RefundStatus =
  | 'NONE'
  | 'REQUESTED'
  | 'RETURN_REQUIRED'
  | 'RETURNING'
  | 'PROCESSING'
  | 'PARTIAL'
  | 'FULL'
  | 'REJECTED'
  | 'FAILED';
export type WithdrawalStatus =
  | 'REQUESTED'
  | 'PROCESSING'
  | 'UNKNOWN'
  | 'USER_ACTION_REQUIRED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CLOSED';
export type CommissionStatus = 'PENDING' | 'AVAILABLE' | 'PARTIALLY_REVERSED' | 'REVERSED';
export type FileFormat = 'DOC' | 'DOCX' | 'PDF' | 'JPG' | 'JPEG' | 'PNG' | 'WEBP';
export interface Page<T> {
  items: T[];
  page: number;
  pageSize: number;
  total: number;
}
export interface ApiEnvelope<T> {
  code: string;
  message: string;
  data: T;
  requestId: string;
}
export interface UserSummary {
  id: Id;
  nickname: string;
  distributor: boolean;
}
export interface AdminUserSummary {
  id: Id;
  loginName: string;
  roles?: string[];
  permissions: string[];
  passwordChangeRequired?: boolean;
}
export interface AdminLoginResult {
  accessToken: string;
  expiresAt: IsoTime;
  permissions: string[];
  passwordChangeRequired: boolean;
}
export interface LoginView {
  accessToken: string;
  expiresAt: IsoTime;
  user?: UserSummary;
  admin?: AdminUserSummary;
  permissions?: string[];
  passwordChangeRequired?: boolean;
}
export type AdminPermission =
  | 'CONTENT_EDIT'
  | 'ORDER_OPERATE'
  | 'FINANCE_OPERATE'
  | 'DISTRIBUTION_OPERATE'
  | 'ANALYTICS_READ'
  | 'AUDIT_READ'
  | 'SETTINGS_MANAGE'
  | 'EXPORT_READ';

export const PERMISSION_ALIASES: Record<string, string[]> = {
  CONTENT_EDITOR: ['CONTENT_EDIT', 'CONTENT_EDITOR'],
  ORDER_OPERATOR: ['ORDER_OPERATE', 'ORDER_OPERATOR'],
  FINANCE_OPERATOR: ['FINANCE_OPERATE', 'FINANCE_OPERATOR'],
  DISTRIBUTION_OPERATOR: ['DISTRIBUTION_OPERATE', 'DISTRIBUTION_OPERATOR'],
  ANALYST: ['ANALYTICS_READ', 'ANALYST'],
  SUPER_ADMIN: ['SUPER_ADMIN'],
  CONTENT_EDIT: ['CONTENT_EDIT', 'CONTENT_EDITOR'],
  ORDER_OPERATE: ['ORDER_OPERATE', 'ORDER_OPERATOR'],
  FINANCE_OPERATE: ['FINANCE_OPERATE', 'FINANCE_OPERATOR'],
  DISTRIBUTION_OPERATE: ['DISTRIBUTION_OPERATE', 'DISTRIBUTION_OPERATOR'],
  ANALYTICS_READ: ['ANALYTICS_READ', 'ANALYST'],
  AUDIT_READ: ['AUDIT_READ'],
  SETTINGS_MANAGE: ['SETTINGS_MANAGE', 'SUPER_ADMIN'],
  EXPORT_READ: ['EXPORT_READ'],
};

export function hasPermission(
  userPermissions: readonly string[] | undefined | null,
  required: string,
): boolean {
  if (!userPermissions || !userPermissions.length) return false;
  // Only an explicit legacy super-admin role can grant known capabilities.
  // A business permission is never evidence of that role.
  if (userPermissions.includes('SUPER_ADMIN') && Object.hasOwn(PERMISSION_ALIASES, required))
    return true;
  if (userPermissions.includes(required)) return true;
  const aliases = PERMISSION_ALIASES[required];
  if (aliases && aliases.some((alias) => userPermissions.includes(alias))) return true;
  return false;
}
export interface AssetSummary {
  id: Id;
  name: string;
  extension: string;
  mime: string;
  bytes: number;
}
export interface ProductCard {
  id: Id;
  type: ProductType;
  title: string;
  coverUrl: string | null;
  priceFen: MoneyFen;
  originalPriceFen: MoneyFen | null;
  status: 'ON_SALE' | 'OFF_SALE';
  subtitle: string | null;
  /** Course content duration in minutes; null for non-course products. */
  durationMinutes?: number | null;
}
export interface Target {
  type:
    | 'PRODUCT'
    | 'PRODUCT_LIST'
    | 'MEMBERSHIP'
    | 'PAPER_LIST'
    | 'ARTICLE'
    | 'LEARNING'
    | 'ORDER_LIST'
    | 'LOGISTICS'
    | 'MEMBERSHIP_CENTER'
    | 'COURSE_DETAIL';
  id: Id | null;
  filters: Record<string, string>;
}
export interface HomeView {
  revision: number;
  banners: { id: Id; imageUrl: string; title: string; target: Target }[];
  shortcuts: { id: Id; label: string; iconUrl: string; target: Target }[];
  sections: {
    id: Id;
    type:
      | 'PRODUCT_GRID'
      | 'BOOK_GRID'
      | 'COURSE_GRID'
      | 'PAPER_GRID'
      | 'MEMBERSHIP_ENTRY'
      | 'ARTICLE_CARD';
    title: string;
    moreTarget: Target | null;
    products: ProductCard[];
    article: { id: Id; title: string; coverUrl: string | null } | null;
    papers?: import('./content/types').PaperCard[];
  }[];
}
export interface MembershipView {
  level: 'STANDARD';
  state: 'NONE' | 'ACTIVE' | 'EXPIRED';
  startsAt: IsoTime | null;
  expiresAt: IsoTime | null;
  canDownload: boolean;
  plans: {
    productId: Id;
    durationMonths: 6 | 12;
    priceFen: MoneyFen;
    projectedExpiresAt: IsoTime;
  }[];
}
export interface OrderView {
  id: Id;
  orderNo: string;
  orderStatus: OrderStatus;
  fulfillmentStatus: FulfillmentStatus;
  refundStatus: RefundStatus;
  goodsAmountFen: MoneyFen;
  freightAmountFen: MoneyFen;
  payableAmountFen: MoneyFen;
  paidAmountFen: MoneyFen;
  refundedAmountFen: MoneyFen;
  expiresAt: IsoTime | null;
  paidAt: IsoTime | null;
  completedAt: IsoTime | null;
  item: {
    productId: Id;
    type: ProductType;
    title: string;
    coverUrl: string | null;
    quantity: number;
    unitPriceFen: MoneyFen;
  };
  allowedActions: (
    'PAY' | 'CANCEL' | 'CONFIRM_RECEIPT' | 'APPLY_AFTERSALE' | 'VIEW_LOGISTICS' | 'VIEW_CONTENT'
  )[];
}
export interface WithdrawalView {
  id: Id;
  withdrawalNo: string;
  amountFen: MoneyFen;
  status: WithdrawalStatus;
  createdAt: IsoTime;
  successAt: IsoTime | null;
  failReason: string | null;
  confirmation: null | {
    action: 'WECHAT_CONFIRM_RECEIPT';
    merchantId: string;
    appId: string;
    packageInfo: string;
  };
}
export type ContentBlock =
  { type: 'TEXT'; text: string } | { type: 'IMAGE'; assetId: Id; url: string; alt?: string };
