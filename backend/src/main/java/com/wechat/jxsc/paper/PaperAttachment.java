package com.wechat.jxsc.paper;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "paper_attachment")
public class PaperAttachment extends BaseEntity {
    public Long paperId;
    public Long assetId;
    public String title;
    public int sortNo;
}
