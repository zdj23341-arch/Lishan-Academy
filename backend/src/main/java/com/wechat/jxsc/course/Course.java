package com.wechat.jxsc.course;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "course")
public class Course extends BaseEntity {
    public Long productId;
    public String validityType;
    public Integer validityDays;
    public int durationMinutes;
    public String teacherName;
    public long chapterRevision;
}
