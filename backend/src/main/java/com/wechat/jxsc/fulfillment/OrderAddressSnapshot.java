package com.wechat.jxsc.fulfillment;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "order_address_snapshot")
public class OrderAddressSnapshot extends BaseEntity {
    public Long orderId;
    public String nameCipher;
    public String phoneCipher;
    public String detailCipher;
    public String provinceCode;
    public String cityCode;
    public String districtCode;
}
