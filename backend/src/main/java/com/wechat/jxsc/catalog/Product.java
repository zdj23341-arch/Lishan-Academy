package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.BaseEntity;
import com.wechat.jxsc.common.content.BodyBlocks;

import jakarta.persistence.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.*;

@Entity
@Table(name = "product")
public class Product extends BaseEntity {
    public enum Type {
        COURSE,
        BOOK,
        MEMBERSHIP
    }

    @Enumerated(EnumType.STRING)
    public Type type;

    public String title;
    public String subtitle;
    public Long coverAssetId;
    public long priceFen;
    public Long originalPriceFen;
    public String status;
    public int sortNo;
    public Integer quantityLimit;
    public String shippingSummary;
    public String seedKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "json")
    public List<BodyBlocks.Input> contentBlocks = List.of();

    @ElementCollection
    @CollectionTable(name = "product_category", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "category_id")
    public Set<Long> categoryIds = new LinkedHashSet<>();
}
