package net.citotech.cito.scheduler;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.citotech.cito.Common;
import net.citotech.cito.Model.Balance;
import net.citotech.cito.Model.Setting;
import net.citotech.cito.SendMail;
import net.citotech.cito.communication.notification.NotificationOrchestrator;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically checks gateway float balances against configured thresholds and sends an alert email
 * when any balance falls below its threshold.
 *
 * <p>Settings consumed (from the settings table): float_alert_email – recipient e-mail address
 * float_alert_mtn_min – minimum MTN float before alert float_alert_airtel_min – minimum Airtel
 * float before alert float_alert_safaricom_min – minimum Safaricom float before alert
 *
 * <p>Uses the stock/float merchant account to read current balances.
 */
@Component
@EnableScheduling
public class FloatAlertScheduler {

    private static final Logger logger = Logger.getLogger(FloatAlertScheduler.class.getName());

    @Autowired private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired private SendMail emailService;

    @Autowired private NotificationOrchestrator notifications;

    @Scheduled(fixedDelay = 1800000) // every 30 minutes
    @SchedulerLock(name = "floatAlert", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void checkFloatBalances() {
        try {
            Setting alertEmailSetting = Common.getSettings("float_alert_email", jdbcTemplate);
            String alertEmail =
                    alertEmailSetting == null ? "" : alertEmailSetting.getSetting_value().trim();

            Setting stockAccountSetting = Common.getSettings("float_stock_account", jdbcTemplate);
            if (stockAccountSetting == null) return;

            String stockAccount = stockAccountSetting.getSetting_value().trim();
            net.citotech.cito.Model.Merchant floatMerchant =
                    Common.getMerchantByAccountNumber(stockAccount, jdbcTemplate);
            if (floatMerchant == null) return;

            ArrayList<Balance> balances =
                    Common.getMerchantBalances(floatMerchant.getId() + "", jdbcTemplate);
            if (balances == null || balances.isEmpty()) return;

            StringBuilder alertBody = new StringBuilder();

            for (Balance b : balances) {
                String thresholdKey = resolveThresholdKey(b.getGateway_id());
                if (thresholdKey == null) continue;

                Setting thresholdSetting = Common.getSettings(thresholdKey, jdbcTemplate);
                if (thresholdSetting == null
                        || thresholdSetting.getSetting_value().trim().isEmpty()) continue;

                BigDecimal threshold;
                try {
                    threshold = new BigDecimal(thresholdSetting.getSetting_value().trim());
                } catch (NumberFormatException e) {
                    continue;
                }

                if (b.getAmountDecimal().compareTo(threshold) < 0) {
                    alertBody.append(
                            String.format(
                                    "%s balance is %.2f %s (threshold: %.2f)\n",
                                    b.getCode(), b.getAmount(), b.getCode(), threshold));
                }
            }

            if (alertBody.length() > 0) {
                long bucket = Instant.now().getEpochSecond() / 1800;
                notifications.record(
                        0,
                        "float-threshold:" + bucket,
                        "balance.threshold",
                        "float-monitor-" + bucket);
                if (alertEmail.isBlank()) return;
                String subject = "[Cito] Float balance alert";
                String body =
                        "The following gateway float balances are below their minimum thresholds:\n\n"
                                + alertBody.toString()
                                + "\nPlease top up to avoid transaction failures.";
                emailService.sendSimpleMessage(alertEmail, subject, body);
                logger.log(Level.WARNING, "Float alert email sent: " + alertBody.toString().trim());
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "FloatAlertScheduler error: " + e.getMessage(), e);
        }
    }

    private String resolveThresholdKey(String gatewayId) {
        if (gatewayId == null) return null;
        switch (gatewayId) {
            case "MTNMoMoPaymentGateway":
                return "float_alert_mtn_min";
            case "AirtelMoneyPaymentGateway":
                return "float_alert_airtel_min";
            case "AirtelMoneyOpenApiPaymentGateway":
                return "float_alert_airtel_min";
            case "SafariComPaymentGateway":
                return "float_alert_safaricom_min";
            default:
                return null;
        }
    }
}
