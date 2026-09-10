package net.citotech.cito.gateway;

import java.net.URI;

/** Restricts credential-bearing provider requests to the reviewed provider origins. */
public final class ProviderEndpointPolicy {
    private ProviderEndpointPolicy() {}

    public static void requireOrigin(String raw, String expected) {
        try {
            URI uri = URI.create(raw);
            URI approved = URI.create(expected);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !approved.getHost().equalsIgnoreCase(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new PaymentGatewayException(
                    "Provider baseUrl must use the approved origin " + expected);
        }
    }

    public static void requireRelativePath(String path) {
        if (path == null || path.isBlank()) return;
        // Paths may contain the reference placeholder, but cannot override the origin or traverse.
        String safe = path.replace("{reference}", "reference").replace("{id}", "reference");
        try {
            URI uri = URI.create(safe);
            if (!safe.startsWith("/")
                    || safe.startsWith("//")
                    || safe.contains("\\")
                    || safe.contains("%")
                    || safe.contains("..")
                    || uri.isAbsolute()
                    || uri.getRawAuthority() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new PaymentGatewayException(
                    "Provider endpoint must be an absolute path on the approved origin");
        }
    }

    public static boolean ambiguousSubmission(int status) {
        return status == 0 || status == 408 || status == 409 || status == 429 || status >= 500;
    }
}
