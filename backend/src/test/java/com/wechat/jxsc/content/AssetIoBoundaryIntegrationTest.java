package com.wechat.jxsc.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.wechat.jxsc.common.storage.StorageProvider;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

class AssetIoBoundaryIntegrationTest extends ContentHttpSupport {
    @MockitoSpyBean StorageProvider storage;

    @Test
    void storageIoRunsAfterCommitAndDirectUploadsCanComplete() throws Exception {
        doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return call.callRealMethod();
                        })
                .when(storage)
                .seal(anyString());
        doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return call.callRealMethod();
                        })
                .when(storage)
                .open(any(StorageProvider.SealedObject.class));
        String token = admin("CONTENT_EDITOR");
        byte[] bytes = png();
        var ticket =
                call(
                        "POST",
                        "/admin/files/presign",
                        token,
                        Map.of(
                                "name",
                                "image.png",
                                "mime",
                                "image/png",
                                "bytes",
                                bytes.length,
                                "purpose",
                                "PUBLIC_IMAGE"),
                        200);
        putBytes(token, ticket, bytes, 200);
        assertThat(
                        call(
                                        "POST",
                                        "/admin/files/" + ticket.path("id").asText() + "/complete",
                                        token,
                                        Map.of(),
                                        200)
                                .path("status")
                                .asText())
                .isEqualTo("READY");
        var publicResponse =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get(
                                                "/api/v1/assets/"
                                                        + ticket.path("id").asText()
                                                        + "/content"))
                        .andReturn()
                        .getResponse();
        assertThat(publicResponse.getStatus()).isEqualTo(200);
        assertThat(publicResponse.getContentAsByteArray()).isEqualTo(bytes);
        doReturn(true).when(storage).directUpload();
        var direct =
                call(
                        "POST",
                        "/admin/files/presign",
                        token,
                        Map.of(
                                "name",
                                "direct.png",
                                "mime",
                                "image/png",
                                "bytes",
                                bytes.length,
                                "purpose",
                                "PUBLIC_IMAGE"),
                        200);
        String key =
                jdbc.queryForObject(
                        "select storage_key from file_asset where id=?",
                        String.class,
                        direct.path("id").asText());
        storage.writeOnce(key, new java.io.ByteArrayInputStream(bytes), bytes.length);
        assertThat(
                        call(
                                        "POST",
                                        "/admin/files/" + direct.path("id").asText() + "/complete",
                                        token,
                                        Map.of(),
                                        200)
                                .path("status")
                                .asText())
                .isEqualTo("READY");
    }
}
