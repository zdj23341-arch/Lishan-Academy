package com.wechat.jxsc.analytics;

import com.wechat.jxsc.common.tasks.TaskHandler;

import org.springframework.stereotype.Component;

@Component
public class ExportWorker implements TaskHandler {
    private final ExportService exports;

    public ExportWorker(ExportService exports) {
        this.exports = exports;
    }

    public boolean supports(String type) {
        return type.equals("ADMIN_EXPORT");
    }

    public void handle(String type, String key, String payload) {
        long id = Long.parseLong(key);
        try {
            exports.save(exports.generate(id));
        } catch (Exception e) {
            exports.fail(id);
        }
    }
}
