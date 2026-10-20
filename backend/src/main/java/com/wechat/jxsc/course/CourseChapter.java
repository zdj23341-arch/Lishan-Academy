package com.wechat.jxsc.course;

import com.wechat.jxsc.common.BaseEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "course_chapter")
public class CourseChapter extends BaseEntity {
    public Long courseId;
    public String title;
    public int sortNo;
    public Long videoAssetId;
    public int durationSeconds;
    public boolean isTrial;
    public boolean enabled;
}
