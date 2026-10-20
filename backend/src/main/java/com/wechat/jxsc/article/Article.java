package com.wechat.jxsc.article;

import com.wechat.jxsc.common.BaseEntity;
import com.wechat.jxsc.common.content.BodyBlocks;

import jakarta.persistence.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "article")
public class Article extends BaseEntity {
    public String title;
    @Column(length = 100)
    public String authorName;
    @Column(length = 80)
    public String categoryName;
    public Long coverAssetId;
    public String status;
    public Instant publishedAt;
    public String seedKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body_json", columnDefinition = "json")
    public List<BodyBlocks.Input> contentBlocks = List.of();
}
