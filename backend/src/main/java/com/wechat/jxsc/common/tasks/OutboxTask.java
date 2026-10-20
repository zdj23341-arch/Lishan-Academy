package com.wechat.jxsc.common.tasks;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "outbox_task")
public class OutboxTask extends BaseEntity {
    public String taskType;
    public String businessKey;

    @Column(columnDefinition = "text")
    public String payloadJson;

    public String status;
    public int retryCount;
    public Instant nextRunAt;
    public Instant lockedUntil;
    public String leaseToken;
    public String lastError;
}
