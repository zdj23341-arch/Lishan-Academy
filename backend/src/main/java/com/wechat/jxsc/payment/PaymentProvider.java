package com.wechat.jxsc.payment;

import java.util.Map;

/**
 * All methods run without a caller DB transaction. Real adapters verify signatures/decrypt before
 * producing events.
 */
public interface PaymentProvider {
    enum State {
        CREATED,
        PROCESSING,
        UNKNOWN,
        SUCCEEDED,
        CLOSED,
        FAILED
    }

    record Intent(
            long id,
            long orderId,
            String businessNo,
            String channel,
            long amountFen,
            String currency,
            String appId,
            String merchantId,
            String originalBusinessNo,
            String originalChannelId,
            Long originalAmountFen,
            String payer,
            String description,
            String productPath) {
        public Intent(
                long id,
                long orderId,
                String businessNo,
                String channel,
                long amountFen,
                String currency,
                String appId,
                String merchantId,
                String originalBusinessNo,
                String originalChannelId,
                Long originalAmountFen) {
            this(
                    id,
                    orderId,
                    businessNo,
                    channel,
                    amountFen,
                    currency,
                    appId,
                    merchantId,
                    originalBusinessNo,
                    originalChannelId,
                    originalAmountFen,
                    null,
                    null,
                    null);
        }

        public Intent(
                long id,
                long orderId,
                String businessNo,
                String channel,
                long amountFen,
                String currency,
                String appId,
                String merchantId) {
            this(
                    id,
                    orderId,
                    businessNo,
                    channel,
                    amountFen,
                    currency,
                    appId,
                    merchantId,
                    null,
                    null,
                    null);
        }
    }

    record Result(
            State state,
            String channelId,
            long amountFen,
            String currency,
            String appId,
            String merchantId,
            Map<String, String> params) {}

    record VerifiedEvent(
            String provider,
            String eventId,
            String resourceType,
            long transactionId,
            long orderId,
            String businessNo,
            String channelId,
            long amountFen,
            String currency,
            String appId,
            String merchantId,
            State state,
            boolean signatureValid,
            String rawPayload) {}

    String channel();

    Result prepay(Intent intent);

    Result query(Intent intent);

    Result close(Intent intent);

    Result refund(Intent intent);

    Result queryRefund(Intent intent);

    default VerifiedEvent verifyCallback(byte[] body, Map<String, String> headers) {
        throw new com.wechat.jxsc.common.ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "EXTERNAL_SERVICE_UNAVAILABLE",
                "Payment callback adapter is not configured");
    }
}
