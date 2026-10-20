package com.wechat.jxsc.auth;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "user_account")
public class UserAccount extends BaseEntity {
    @Column(nullable = false, length = 200)
    String nickname;

    @Column(length = 512)
    String avatarUrl;

    @Column(columnDefinition = "text")
    String phoneCipher;

    @Column(nullable = false, length = 32)
    String status = "ACTIVE";

    protected UserAccount() {}

    UserAccount(String nickname) {
        this.nickname = nickname;
    }
}
