package com.wechat.jxsc.payment;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "callback_inbox")
public class CallbackInbox extends BaseEntity {
    public String provider;
    public String eventId;
    public String resourceType;
    public boolean signatureValid;

    @Column(columnDefinition = "text")
    public String payloadEncrypted;

    public String payloadHash;
    public String status;
    public Instant processedAt;
    public int retryCount;
    public String errorCode;
}
