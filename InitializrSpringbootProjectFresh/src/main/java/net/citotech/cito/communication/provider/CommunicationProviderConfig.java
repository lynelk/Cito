package net.citotech.cito.communication.provider;

import net.citotech.cito.communication.sms.AfricasTalkingSmsGatewayAdapter;
import net.citotech.cito.communication.sms.LegacySettingsSmsGatewayAdapter;
import net.citotech.cito.communication.sms.SmsMobiloSmsGatewayAdapter;
import net.citotech.cito.communication.sms.TwilioSmsGatewayAdapter;
import net.citotech.cito.communication.sms.YoSmsGatewayAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** Registers SMS gateway adapters into the channel-neutral provider SPI. */
@Configuration
public class CommunicationProviderConfig {

    @Bean
    public CommunicationProviderAdapter legacySettingsCommunicationProvider(
            @Lazy LegacySettingsSmsGatewayAdapter delegate) {
        return new SmsCommunicationProviderAdapter(
                delegate, CommunicationSmsProviderCodes.LEGACY_SETTINGS);
    }

    @Bean
    public CommunicationProviderAdapter yoSmsCommunicationProvider(
            @Lazy YoSmsGatewayAdapter delegate) {
        return new SmsCommunicationProviderAdapter(
                delegate, CommunicationSmsProviderCodes.YO_SMS);
    }

    @Bean
    public CommunicationProviderAdapter africastalkingCommunicationProvider(
            @Lazy AfricasTalkingSmsGatewayAdapter delegate) {
        return new SmsCommunicationProviderAdapter(
                delegate, CommunicationSmsProviderCodes.AFRICAS_TALKING);
    }

    @Bean
    public CommunicationProviderAdapter twilioCommunicationProvider(
            @Lazy TwilioSmsGatewayAdapter delegate) {
        return new SmsCommunicationProviderAdapter(
                delegate, CommunicationSmsProviderCodes.TWILIO_SMS);
    }

    @Bean
    public CommunicationProviderAdapter smsMobiloCommunicationProvider(
            @Lazy SmsMobiloSmsGatewayAdapter delegate) {
        return new SmsCommunicationProviderAdapter(
                delegate, CommunicationSmsProviderCodes.SMSMOBILO_SMS);
    }
}
