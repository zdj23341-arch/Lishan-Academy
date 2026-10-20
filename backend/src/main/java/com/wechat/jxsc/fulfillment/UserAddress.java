package com.wechat.jxsc.fulfillment;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "user_address")
public class UserAddress extends BaseEntity {
    public Long userId;
    public String nameCipher;
    public String phoneCipher;
    public String detailCipher;
    public String provinceCode;
    public String cityCode;
    public String districtCode;
    public boolean isDefault;
    public boolean deleted;
}
