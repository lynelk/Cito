package net.citotech.cito.gateway;

import net.citotech.cito.Common;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Tests route approved logical origins to a local server without weakening production URL policy.
 */
public final class ProviderHttpTestTransport {
    private ProviderHttpTestTransport() {}

    public static void route(String origin, String local) {
        var transport =
                new RestClientOutboundHttpExecutor(
                        new DefaultListableBeanFactory()
                                .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
                        2000,
                        2000);
        Common.setOutboundHttpExecutor(
                (method, url, body, headers) -> {
                    if (!url.startsWith(origin + "/"))
                        throw new AssertionError("Unexpected outbound test origin");
                    return transport.execute(
                            method, local + url.substring(origin.length()), body, headers);
                });
    }

    public static void reset() {
        Common.setOutboundHttpExecutor(null);
    }
}
