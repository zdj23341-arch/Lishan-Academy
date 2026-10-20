package com.wechat.jxsc.catalog;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "category")
public class Category extends BaseEntity {
    public String name;

    @Enumerated(EnumType.STRING)
    public Product.Type type;

    public Long parentId;
    public int sortNo;
    public boolean enabled;
    public String seedKey;
}
