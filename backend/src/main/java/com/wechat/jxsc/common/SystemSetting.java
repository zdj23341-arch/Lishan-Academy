package com.wechat.jxsc.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "system_setting")
public class SystemSetting extends BaseEntity {
    @Column(nullable = false, unique = true, length = 128)
    private String settingKey;

    @Column(nullable = false, columnDefinition = "text")
    private String settingValue;

    protected SystemSetting() {}

    public SystemSetting(String key, String value) {
        this.settingKey = key;
        this.settingValue = value;
    }

    public String getSettingKey() { return settingKey; }
    public String getSettingValue() { return settingValue; }
    public void setSettingValue(String value) { settingValue = value; }
}
