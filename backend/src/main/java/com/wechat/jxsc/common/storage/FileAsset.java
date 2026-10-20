package com.wechat.jxsc.common.storage;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "file_asset")
public class FileAsset extends BaseEntity {
    String ownerType;
    Long ownerId;
    String purpose;
    String storageKey;
    String sealedVersion;
    String originalName;
    String extension;
    String mime;
    long expectedBytes;
    Long bytes;
    String sha256;
    String status;
    String scanStatus;
    String uploadTokenHash;
    Instant uploadExpiresAt;
    String seedKey;
    String processingToken;
    Instant processingUntil;
}
