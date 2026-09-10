package net.citotech.cito.communication;

import java.util.HashMap;
import java.util.Map;
import net.citotech.cito.communication.provider.CommunicationSmsProviderCodes;
import net.citotech.cito.communication.routing.ProviderRouter;
import net.citotech.cito.communication.sms.AfricasTalkingSmsGatewayAdapter;
import net.citotech.cito.communication.sms.LegacySettingsSmsGatewayAdapter;
import net.citotech.cito.communication.sms.SmsGatewayAdapter;
import net.citotech.cito.communication.sms.SmsMobiloSmsGatewayAdapter;
import net.citotech.cito.communication.sms.TwilioSmsGatewayAdapter;
import net.citotech.cito.communication.sms.YoSmsGatewayAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registration point for SMS provider adapters behind the {@link ProviderRouter} (ISO domain
 * mapping: communication/routing). The map keys are the stable {@code provider_code}s used by
 * {@code communication_routing_rules}. {@code LEGACY_SETTINGS} remains available so existing
 * deployments keep their pre-router behavior until dedicated providers are enabled.
 */
@Configuration
public class CommunicationSmsConfig {

    public static final String LEGACY_SETTINGS_CODE = CommunicationSmsProviderCodes.LEGACY_SETTINGS;

    @Bean
    public Map<String, SmsGatewayAdapter> smsAdaptersByCode(
            LegacySettingsSmsGatewayAdapter legacySettingsSmsGatewayAdapter,
            YoSmsGatewayAdapter yoSmsGatewayAdapter,
            AfricasTalkingSmsGatewayAdapter africastalkingSmsGatewayAdapter,
            TwilioSmsGatewayAdapter twilioSmsGatewayAdapter,
            SmsMobiloSmsGatewayAdapter smsMobiloSmsGatewayAdapter) {
        Map<String, SmsGatewayAdapter> adapters = new HashMap<>();
        adapters.put(CommunicationSmsProviderCodes.LEGACY_SETTINGS, legacySettingsSmsGatewayAdapter);
        adapters.put(CommunicationSmsProviderCodes.YO_SMS, yoSmsGatewayAdapter);
        adapters.put(CommunicationSmsProviderCodes.AFRICAS_TALKING, africastalkingSmsGatewayAdapter);
        adapters.put(CommunicationSmsProviderCodes.TWILIO_SMS, twilioSmsGatewayAdapter);
        adapters.put(CommunicationSmsProviderCodes.SMSMOBILO_SMS, smsMobiloSmsGatewayAdapter);
        return Map.copyOf(adapters);
    }
}
