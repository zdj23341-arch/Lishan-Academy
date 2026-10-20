package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.wechat.jxsc.common.ApiException;
import com.wechat.jxsc.payment.PaymentProvider;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

class CommerceProviderBoundaryIntegrationTest extends CommerceTestSupport {
    @MockitoBean PaymentProvider provider;

    PaymentProvider.Result result(
            PaymentProvider.Intent i, PaymentProvider.State state, long amount) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return new PaymentProvider.Result(
                state,
                "DEV-provider-" + i.businessNo(),
                amount,
                "CNY",
                "DEV",
                "DEV-MERCHANT",
                Map.of());
    }

    @Test
    void mismatchedProviderCloseCannotReleaseReservation() throws Exception {
        when(provider.channel()).thenReturn("DEV");
        when(provider.prepay(any()))
                .thenAnswer(
                        x -> {
                            var i = x.getArgument(0, PaymentProvider.Intent.class);
                            return result(i, PaymentProvider.State.PROCESSING, i.amountFen());
                        });
        when(provider.query(any()))
                .thenAnswer(
                        x -> {
                            var i = x.getArgument(0, PaymentProvider.Intent.class);
                            return result(i, PaymentProvider.State.CLOSED, i.amountFen() + 1);
                        });
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String p = book(a, 1, 2000),
                id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        write("/payments/wechat/prepay", b, Map.of("orderId", id));
        write("POST", "/orders/" + id + "/cancel", b, Map.of(), UUID.randomUUID().toString(), 409);
        assertThat(
                        jdbc.queryForObject(
                                "select reserved from book_inventory where product_id=?",
                                Integer.class,
                                p))
                .isEqualTo(1);
    }

    @Test
    void prepayTimeoutKeepsOneDurableBusinessNumberAndCallsOutsideTransactions() throws Exception {
        when(provider.channel()).thenReturn("DEV");
        List<String> calls = new ArrayList<>();
        when(provider.prepay(any()))
                .thenAnswer(
                        x -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            calls.add(x.getArgument(0, PaymentProvider.Intent.class).businessNo());
                            throw new ApiException(
                                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                                    "EXTERNAL_SERVICE_UNAVAILABLE",
                                    "Provider timeout");
                        });
        String a = admin("SUPER_ADMIN"), b = buyer("buyer");
        String p = book(a, 1, 2000),
                id = order(b, quote(b, p, address(b).path("id").asText(), 1)).path("id").asText();
        for (int n = 0; n < 2; n++)
            write(
                    "POST",
                    "/payments/wechat/prepay",
                    b,
                    Map.of("orderId", id),
                    UUID.randomUUID().toString(),
                    503);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).isEqualTo(calls.get(1));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment_transaction where order_id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select state from payment_transaction where order_id=?",
                                String.class,
                                id))
                .isEqualTo("UNKNOWN");
    }
}
