package com.wechat.jxsc.order;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "order_item")
public class OrderItem extends BaseEntity {
    public Long orderId;
    public Long productId;
    public int quantity;
    public long unitPriceFen;
    public long goodsPaidFen;
    public long goodsRefundedFen;
    public String type;
    public String titleSnapshot;
    public String stockState;
    public int restoredQuantity;

    @Column(columnDefinition = "text")
    public String fulfillmentSnapshotJson;
}
