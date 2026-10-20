package com.wechat.jxsc.paper;

import com.wechat.jxsc.common.BaseEntity;
import com.wechat.jxsc.common.content.BodyBlocks;

import jakarta.persistence.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.*;

@Entity
@Table(name = "paper")
public class Paper extends BaseEntity {
    public long attachmentRevision;
    public String title;
    public String stage;
    public String schoolStage;
    public String grade;
    public String subject;
    public String paperType;
    public Integer year;
    public String summary;
    public String status;
    public String visibility;
    public Long coverAssetId;
    public String seedKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "description_json", columnDefinition = "json")
    public List<BodyBlocks.Input> contentBlocks = List.of();
}
