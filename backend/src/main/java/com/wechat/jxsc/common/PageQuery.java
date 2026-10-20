package com.wechat.jxsc.common;

import org.springframework.http.HttpStatus;

public record PageQuery(int page, int pageSize) {
    public PageQuery {
        if (page < 1
                || pageSize < 1
                || pageSize > 100
                || (long) (page - 1) * pageSize > Integer.MAX_VALUE)
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid pagination");
    }

    public int offset() {
        return (page - 1) * pageSize;
    }
}
