package com.forgeai.shared.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeEncryptionServiceTest {

    @Test
    void encryptAndDecryptRoundTripUsesUniqueEnvelopeDek() {
        EnvelopeEncryptionService service = new EnvelopeEncryptionService(
                "unit-test-kek-secret-32-bytes-minimum-key!",
                "kek-test-v1"
        );
        String apiKey = "sk-or-v1-9876543210abcdef1234567890abcdef";

        EnvelopeEncryptionService.EncryptedPayload p1 = service.encrypt(apiKey);
        EnvelopeEncryptionService.EncryptedPayload p2 = service.encrypt(apiKey);

        assertThat(p1.ciphertext()).isNotEqualTo(apiKey);
        assertThat(p1.ciphertext()).isNotEqualTo(p2.ciphertext());
        assertThat(p1.wrappedDek()).isNotEqualTo(p2.wrappedDek());
        assertThat(p1.kekKeyId()).isEqualTo("kek-test-v1");

        assertThat(service.decrypt(p1.ciphertext(), p1.wrappedDek())).isEqualTo(apiKey);
        assertThat(service.decrypt(p2.ciphertext(), p2.wrappedDek())).isEqualTo(apiKey);
        assertThat(EnvelopeEncryptionService.maskKeyHint(apiKey)).isEqualTo("sk-o...cdef");
    }

    @Test
    void wrongKekCannotDecryptWrappedDek() {
        EnvelopeEncryptionService writer = new EnvelopeEncryptionService("kek-one-secret", "kek-1");
        EnvelopeEncryptionService wrongReader = new EnvelopeEncryptionService("kek-two-secret", "kek-2");

        EnvelopeEncryptionService.EncryptedPayload payload = writer.encrypt("sk-or-v1-secret-value-12345");
        assertThatThrownBy(() -> wrongReader.decrypt(payload.ciphertext(), payload.wrappedDek()))
                .isInstanceOf(IllegalStateException.class);
    }
}
