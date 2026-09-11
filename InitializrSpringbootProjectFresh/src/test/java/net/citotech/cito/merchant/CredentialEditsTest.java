package net.citotech.cito.merchant;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CredentialEditsTest {
    @Test
    void ordinaryEditsPreserveSecretAndPublicConfiguration() {
        var previous =
                Map.<String, Object>of(
                        "clientSecret",
                        "original-secret",
                        "country",
                        "UG",
                        "publicKey",
                        "BEGIN PUBLIC KEY material",
                        "tokenPath",
                        "/auth/oauth2/token");
        var mask = CredentialEdits.mask(previous);
        assertThat(mask)
                .containsEntry("clientSecret", "****")
                .containsEntry("country", "UG")
                .containsEntry("publicKey", previous.get("publicKey"))
                .containsEntry("tokenPath", previous.get("tokenPath"));
        assertThat(CredentialEdits.merge(previous, mask, List.of())).isEqualTo(previous);
        assertThat(CredentialEdits.merge(previous, Map.of("clientSecret", ""), List.of()))
                .isEqualTo(previous);
    }

    @Test
    void explicitReplacementAndClearAreDistinct() {
        var previous = Map.<String, Object>of("apiPin", "old-pin", "publicKey", "public");
        assertThat(CredentialEdits.merge(previous, Map.of("apiPin", "new-pin"), List.of()))
                .containsEntry("apiPin", "new-pin");
        assertThat(CredentialEdits.merge(previous, Map.of(), List.of("apiPin", "publicKey")))
                .isEmpty();
    }

    @Test
    void newMaskedSecretIsRejected() {
        assertThatThrownBy(
                        () ->
                                CredentialEdits.merge(
                                        Map.of(), Map.of("apiKey", "ab****cd"), List.of()))
                .hasMessageContaining("Masked");
    }
}
