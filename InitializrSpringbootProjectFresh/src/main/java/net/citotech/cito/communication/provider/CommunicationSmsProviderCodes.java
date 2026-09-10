package net.citotech.cito.communication.provider;

/**
 * Stable SMS provider codes shared by the routing table ({@code communication_routing_rules},
 * V50), the legacy {@code smsAdaptersByCode} map, and the generic provider registry (ISO domain
 * mapping: communication/provider). Keeping them in one place prevents a routing rule from
 * referencing a code the registry does not know.
 */
public final class CommunicationSmsProviderCodes {

    public static final String LEGACY_SETTINGS = "LEGACY_SETTINGS";
    public static final String YO_SMS = "YO_SMS";
    public static final String AFRICAS_TALKING = "AFRICAS_TALKING";
    public static final String TWILIO_SMS = "TWILIO_SMS";
    public static final String SMSMOBILO_SMS = "SMSMOBILO_SMS";

    private CommunicationSmsProviderCodes() {}
}
