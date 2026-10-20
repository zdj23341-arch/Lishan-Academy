import type { PaperDetail, ProductDetail } from '../content/types';
import type { OrderView } from '../contracts';

/** In-memory frontend scenario bridge, never a production payment callback. */
export interface DevDigitalContent {
  projectProduct(product: ProductDetail, actor: string): ProductDetail;
  projectPaper?(paper: PaperDetail, actor: string): PaperDetail;
  paid(order: OrderView, productSnapshot: ProductDetail, actor: string): boolean;
  refunded(order: OrderView, productSnapshot: ProductDetail, actor: string): void;
}

export function combineDevDigitalContent(...handlers: DevDigitalContent[]): DevDigitalContent {
  return {
    projectProduct: (product, actor) =>
      handlers.reduce((current, handler) => handler.projectProduct(current, actor), product),
    projectPaper: (paper, actor) =>
      handlers.reduce(
        (current, handler) => handler.projectPaper?.(current, actor) ?? current,
        paper,
      ),
    paid: (order, product, actor) =>
      handlers.some((handler) => handler.paid(order, product, actor)),
    refunded: (order, product, actor) =>
      handlers.forEach((handler) => handler.refunded(order, product, actor)),
  };
}
