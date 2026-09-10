package net.citotech.cito.reconciliation;

/**
 * Airtel OpenAPI statement parser (audit O1). See {@link MtnStatementParser}'s javadoc for why
 * column aliases currently match the shared defaults.
 */
public class AirtelOpenApiStatementParser extends AbstractTabularStatementParser {
    public AirtelOpenApiStatementParser() {
        super("AIRTEL_OPENAPI", "airtel_open_api");
    }
}
