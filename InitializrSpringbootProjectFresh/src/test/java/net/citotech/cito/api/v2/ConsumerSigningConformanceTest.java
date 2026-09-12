package net.citotech.cito.api.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.developer.reference.ApiAccessBillingService;
import net.citotech.cito.security.CanonicalRequestSigner;
import net.citotech.cito.security.ReplayProtectionService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

/** Shared SDK corpus against the actual released verifier, not a replacement implementation. */
class ConsumerSigningConformanceTest {
    @Test
    void consumerVectorsMatchQueryCanonicalizationAndSignatureVerification() throws Exception {
        JSONArray vectors =
                new JSONArray(Files.readString(Path.of("../sdk/tests/signing-vectors.json")));
        V2RequestSecurityService security =
                new V2RequestSecurityService(
                        mock(NamedParameterJdbcTemplate.class),
                        mock(ReplayProtectionService.class),
                        mock(ApiAccessBillingService.class));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var key = generator.generateKeyPair();
        Merchant merchant = new Merchant();
        merchant.setPublic_key(Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
        for (int index = 0; index < vectors.length(); index++) {
            JSONObject vector = vectors.getJSONObject(index);
            MockHttpServletRequest request = new MockHttpServletRequest();
            JSONArray pairs = vector.getJSONArray("query");
            for (int entry = 0; entry < pairs.length(); entry++) {
                JSONArray pair = pairs.getJSONArray(entry);
                request.addParameter(pair.getString(0), pair.getString(1));
            }
            String query = ReflectionTestUtils.invokeMethod(security, "canonicalQuery", request);
            assertEquals(vector.getString("canonicalQuery"), query, vector.getString("name"));
            String canonical =
                    CanonicalRequestSigner.canonicalize(
                            vector.getString("method"),
                            vector.getString("path"),
                            query,
                            vector.getString("timestamp"),
                            vector.getString("nonce"),
                            vector.getString("body"));
            assertEquals(vector.getString("canonical"), canonical, vector.getString("name"));
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key.getPrivate());
            signer.update(canonical.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(signer.sign());
            assertTrue(CanonicalRequestSigner.verify(merchant, canonical, signature));
            assertFalse(CanonicalRequestSigner.verify(merchant, canonical + "tampered", signature));
        }
    }
}
