package com.wechat.jxsc.commerce;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.wechat.jxsc.common.storage.StorageProvider;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.*;
import java.time.Instant;
import java.util.*;

class EntitlementProviderBoundaryIntegrationTest extends EntitlementTestSupport {
    @MockitoSpyBean StorageProvider storage;

    String authorizeFile() throws Exception {
        return authorizeFile("2026-10-10T02:00:00Z");
    }

    String authorizeFile(String paidAt) throws Exception {
        var f = upload(a, "PAPER_ATTACHMENT", "DEV.png", "image/png", png());
        var p =
                call(
                        "POST",
                        "/admin/papers",
                        a,
                        Map.of(
                                "version",
                                0,
                                "title",
                                "DEV Paper",
                                "stage",
                                "JUNIOR",
                                "grade",
                                "9",
                                "subject",
                                "CHINESE",
                                "year",
                                2026,
                                "status",
                                "PUBLISHED",
                                "contentBlocks",
                                List.of(),
                                "attachments",
                                List.of(
                                        Map.of(
                                                "assetId",
                                                f.path("id").asText(),
                                                "title",
                                                "DEV file",
                                                "sortNo",
                                                0))),
                        200);
        deliver(paid(member(6), paidAt));
        return call(
                        "POST",
                        "/papers/"
                                + p.path("id").asText()
                                + "/files/"
                                + f.path("id").asText()
                                + "/authorize",
                        b,
                        Map.of("action", "DOWNLOAD"),
                        200)
                .path("url")
                .asText();
    }

    @Test
    void storageUsesPinnedIdentityOutsideTransactionAndTransportFailureIsNotPurchaseDenial()
            throws Exception {
        String url = authorizeFile();
        var pinned =
                jdbc.queryForMap(
                        "select storage_key,sealed_version from content_capability order by id desc"
                                + " limit 1");
        doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            var object = call.getArgument(0, StorageProvider.SealedObject.class);
                            assertThat(object.key()).isEqualTo(pinned.get("storage_key"));
                            assertThat(object.version()).isEqualTo(pinned.get("sealed_version"));
                            return call.callRealMethod();
                        })
                .when(storage)
                .open(any());
        var response = mvc.perform(get(url)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(png());
        doThrow(new IOException("Unreachable provider")).when(storage).open(any());
        var failure = mvc.perform(get(url)).andReturn().getResponse();
        assertThat(failure.getStatus()).isEqualTo(503);
        assertThat(failure.getContentAsString())
                .contains("EXTERNAL_SERVICE_UNAVAILABLE")
                .doesNotContain("Unreachable provider");
    }

    @Test
    void streamedBytesCannotCrossCapabilityExpiry() throws Exception {
        String url = authorizeFile();
        doAnswer(
                        call -> {
                            InputStream actual = (InputStream) call.callRealMethod();
                            return new FilterInputStream(actual) {
                                @Override
                                public int read(byte[] b, int off, int len) throws IOException {
                                    int n = super.read(b, off, len);
                                    clock.now = Instant.parse("2026-10-10T02:02:00Z");
                                    return n;
                                }
                            };
                        })
                .when(storage)
                .open(any());
        var response = mvc.perform(get(url)).andReturn().getResponse();
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-10T02:02:00Z"));
        assertThat(response.getStatus()).isEqualTo(500);
        var error = json.readTree(response.getContentAsByteArray());
        assertThat(error.path("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(error.path("data").isNull()).isTrue();
    }

    @Test
    void refundShortenedMembershipCapsAnAlreadyIssuedBearerStream() throws Exception {
        String first = paid(member(6), "2026-04-06T02:00:00Z");
        deliver(first);
        String url = authorizeFile("2026-04-10T02:00:00.500000Z");
        refund(first, true, "shorten-" + first);
        assertThat(me().path("expiresAt").asText()).isEqualTo("2026-10-10T02:00:00.500Z");
        doAnswer(
                        call -> {
                            InputStream actual = (InputStream) call.callRealMethod();
                            return new FilterInputStream(actual) {
                                @Override
                                public int read(byte[] b, int off, int len) throws IOException {
                                    int n = super.read(b, off, len);
                                    clock.now = Instant.parse("2026-10-10T02:00:01Z");
                                    return n;
                                }
                            };
                        })
                .when(storage)
                .open(any());
        var response = mvc.perform(get(url)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(json.readTree(response.getContentAsByteArray()).path("data").isNull()).isTrue();
    }
}
