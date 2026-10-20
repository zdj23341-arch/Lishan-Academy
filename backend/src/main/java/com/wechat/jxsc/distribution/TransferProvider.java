package com.wechat.jxsc.distribution;

/** Transport is invoked only after the durable dispatch marker commits. Task7 supplies REAL. */
public interface TransferProvider {
    boolean ready();

    /** Capability to recover an already dispatched intent without creating a new transfer. */
    default boolean recoveryReady() {
        return ready();
    }

    String channel();

    String appId();

    String merchantId();

    record Intent(
            long id,
            String outBillNo,
            long amountFen,
            String appId,
            String merchantId,
            String recipient,
            String channel,
            String recipientName) {
        public Intent(
                long id,
                String outBillNo,
                long amountFen,
                String appId,
                String merchantId,
                String recipient,
                String channel) {
            this(id, outBillNo, amountFen, appId, merchantId, recipient, channel, null);
        }
    }

    record Observation(
            String eventId,
            String outBillNo,
            long amountFen,
            String appId,
            String merchantId,
            String recipient,
            String channel,
            String channelBillNo,
            String state,
            String packageInfo,
            boolean verified,
            String authenticatedPayload,
            @com.fasterxml.jackson.annotation.JsonIgnore String safeFailureCode) {
        public Observation(
                String eventId,
                String outBillNo,
                long amountFen,
                String appId,
                String merchantId,
                String recipient,
                String channel,
                String channelBillNo,
                String state,
                String packageInfo,
                boolean verified,
                String authenticatedPayload) {
            this(
                    eventId,
                    outBillNo,
                    amountFen,
                    appId,
                    merchantId,
                    recipient,
                    channel,
                    channelBillNo,
                    state,
                    packageInfo,
                    verified,
                    authenticatedPayload,
                    null);
        }

        public Observation(
                String eventId,
                String outBillNo,
                long amountFen,
                String appId,
                String merchantId,
                String recipient,
                String channel,
                String channelBillNo,
                String state,
                String packageInfo,
                boolean verified) {
            this(
                    eventId,
                    outBillNo,
                    amountFen,
                    appId,
                    merchantId,
                    recipient,
                    channel,
                    channelBillNo,
                    state,
                    packageInfo,
                    verified,
                    null);
        }
    }

    Observation send(Intent intent);

    /** Pure local validation; must not perform transport or database mutations. */
    default void preflight(Intent intent) {}

    Observation query(Intent intent);

    default Observation cancel(Intent intent) {
        throw new IllegalStateException("Cancellation unavailable");
    }

    default long maximumFen() {
        return Long.MAX_VALUE;
    }
}
