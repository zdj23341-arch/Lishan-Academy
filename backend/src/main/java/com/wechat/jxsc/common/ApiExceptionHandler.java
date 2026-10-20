package com.wechat.jxsc.common;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({
        org.springframework.orm.ObjectOptimisticLockingFailureException.class,
        jakarta.persistence.OptimisticLockException.class
    })
    public ResponseEntity<ApiResponse<Void>> stale(
            Exception exception, HttpServletRequest request) {
        return apiException(
                new ApiException(
                        HttpStatus.CONFLICT,
                        "VERSION_CONFLICT",
                        "Content changed; reload before editing"),
                request);
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> relationshipConflict(
            Exception exception, HttpServletRequest request) {
        return apiException(
                new ApiException(
                        HttpStatus.CONFLICT,
                        "RESOURCE_CONFLICT",
                        "A unique value or referenced resource conflicts"),
                request);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> methodNotAllowed(
            Exception exception, HttpServletRequest request) {
        return apiException(
                new ApiException(
                        HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed"),
                request);
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> unsupportedMediaType(
            Exception exception, HttpServletRequest request) {
        return apiException(
                new ApiException(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "UNSUPPORTED_MEDIA_TYPE",
                        "Unsupported media type"),
                request);
    }

    @ExceptionHandler({
        org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        jakarta.validation.ConstraintViolationException.class,
        org.springframework.web.method.annotation.HandlerMethodValidationException.class
    })
    public ResponseEntity<ApiResponse<Void>> validation(
            Exception exception, HttpServletRequest request) {
        return apiException(
                new ApiException(
                        HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request fields"),
                request);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> forbidden(
            Exception exception, HttpServletRequest request) {
        return apiException(
                new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied"), request);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(
            Exception exception, HttpServletRequest request) {
        return apiException(
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found"), request);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> apiException(
            ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        new ApiResponse<>(
                                exception.getCode(),
                                exception.getMessage(),
                                null,
                                RequestIdFilter.requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpectedException(
            Exception exception, HttpServletRequest request) {
        log.error(
                "Unhandled API exception type={} requestId={}",
                exception.getClass().getName(),
                RequestIdFilter.requestId(request));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        new ApiResponse<>(
                                "INTERNAL_ERROR",
                                "An internal error occurred",
                                null,
                                RequestIdFilter.requestId(request)));
    }
}
