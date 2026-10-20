package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "book_inventory")
public class BookInventory extends BaseEntity {
    public Long productId;
    public int onHand;
    public int reserved;
}
