package net.citotech.cito.api.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.citotech.cito.Model.Balance;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.PaymentOrchestrationService;
import net.citotech.cito.api.v2.dto.AccountValidationRequest;
import net.citotech.cito.api.v2.dto.ApiErrorResponse;
import net.citotech.cito.api.v2.dto.PaymentChannelResponse;
import net.citotech.cito.api.v2.dto.PaymentRequest;
import net.citotech.cito.api.v2.dto.PaymentResult;
import net.citotech.cito.api.v2.dto.PaymentStatusResponse;
import net.citotech.cito.api.v2.dto.StatementExportResponse;
import net.citotech.cito.export.TabularExportService;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.merchant.MerchantEnvironmentService;
import net.citotech.cito.payout.PayoutControlService;
import net.citotech.cito.payout.PayoutControlService.PayoutEvaluation;
import net.citotech.cito.sandbox.SandboxProductionGuardService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v2")
public class PaymentsV2Controller {
    private final PaymentOrchestrationService paymentOrchestrationService;
    private final AdapterNativePaymentService adapterNativePaymentService;
    private final PaymentStatusService paymentStatusService;
    private final V2RequestSecurityService securityService;
    private final IdempotencyService idempotencyService;
    private final AccountValidationService accountValidationService;
    private final MerchantStatementExportService statementExportService;
    private final PayoutControlService payoutControlService;
    private final MerchantEnvironmentService environmentService;
    private final SandboxProductionGuardService productionGuard;
    private final ObjectMapper objectMapper;

    public PaymentsV2Controller(
            PaymentOrchestrationService paymentOrchestrationService,
            AdapterNativePaymentService adapterNativePaymentService,
            PaymentStatusService paymentStatusService,
            V2RequestSecurityService securityService,
            IdempotencyService idempotencyService,
            AccountValidationService accountValidationService,
            MerchantStatementExportService statementExportService,
            PayoutControlService payoutControlService,
            MerchantEnvironmentService environmentService,
            SandboxProductionGuardService productionGuard,
            ObjectMapper objectMapper) {
        this.paymentOrchestrationService = paymentOrchestrationService;
        this.adapterNativePaymentService = adapterNativePaymentService;
        this.paymentStatusService = paymentStatusService;
        this.securityService = securityService;
        this.idempotencyService = idempotencyService;
        this.accountValidationService = accountValidationService;
        this.statementExportService = statementExportService;
        this.payoutControlService = payoutControlService;
        this.environmentService = environmentService;
        this.productionGuard = productionGuard;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/payments/collect")
    public ResponseEntity<?> collect(@RequestBody String body, HttpServletRequest servletRequest) {
        try {
            PaymentRequest request = objectMapper.readValue(body, PaymentRequest.class);
            Merchant merchant =
                    securityService.verify(servletRequest, body, request.getMerchantNumber());
            String environment = resolveCompatibilityEnvironment(servletRequest, request);
            String idempotencyKey = servletRequest.getHeader("X-CPay-Idempotency-Key");
            String idempotencyBody = bodyWithEnvironment(body, environment);
            Optional<PaymentResult> existing =
                    idempotencyService.findExisting(
                            request.getMerchantNumber(), idempotencyKey, idempotencyBody);
            if (existing.isPresent()) {
                return ResponseEntity.ok(existing.get());
            }

            PaymentResult result;
            if (MerchantEnvironmentService.SANDBOX.equals(environment)) {
                result = adapterNativePaymentService.collect(request, merchant, environment);
            } else {
                productionGuard.reserveProductionExecution(
                        merchant, environment, "COLLECT", request.getReference());
                result =
                        paymentOrchestrationService.collect(
                                request, merchant, servletRequest.getRemoteAddr());
            }
            idempotencyService.record(
                    request.getMerchantNumber(), idempotencyKey, idempotencyBody, result);
            return ResponseEntity.accepted().body(result);
        } catch (V2RequestSecurityException e) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
        } catch (IllegalStateException e) {
            return error(HttpStatus.FORBIDDEN, "PRODUCTION_CAPABILITY_NOT_ENABLED", e.getMessage());
        } catch (PaymentGatewayException e) {
            return error(HttpStatus.BAD_REQUEST, "PAYMENT_REJECTED", e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid collect request");
        }
    }

    @PostMapping(path = "/payments/payout")
    public ResponseEntity<?> payout(@RequestBody String body, HttpServletRequest servletRequest) {
        try {
            PaymentRequest request = objectMapper.readValue(body, PaymentRequest.class);
            Merchant merchant =
                    securityService.verify(servletRequest, body, request.getMerchantNumber());
            String environment = resolveCompatibilityEnvironment(servletRequest, request);
            String idempotencyKey = servletRequest.getHeader("X-CPay-Idempotency-Key");
            String idempotencyBody = bodyWithEnvironment(body, environment);
            Optional<PaymentResult> existing =
                    idempotencyService.findExisting(
                            request.getMerchantNumber(), idempotencyKey, idempotencyBody);
            if (existing.isPresent()) {
                return ResponseEntity.ok(existing.get());
            }

            if (MerchantEnvironmentService.SANDBOX.equals(environment)) {
                PaymentResult sandboxResult =
                        adapterNativePaymentService.payout(request, merchant, environment);
                idempotencyService.record(
                        request.getMerchantNumber(),
                        idempotencyKey,
                        idempotencyBody,
                        sandboxResult);
                return ResponseEntity.accepted().body(sandboxResult);
            }

            productionGuard.reserveProductionExecution(
                    merchant, environment, "PAYOUT", request.getReference());
            if (request.getChannel() == null
                    || request.getChannel().isBlank()
                    || net.citotech.cito.gateway.MobileMoneyExecutionService.managed(
                            request.getChannel())) {
                PaymentResult managed =
                        paymentOrchestrationService.payout(
                                request, merchant, servletRequest.getRemoteAddr());
                idempotencyService.record(
                        request.getMerchantNumber(), idempotencyKey, idempotencyBody, managed);
                return ResponseEntity.accepted().body(managed);
            }
            PayoutEvaluation control = payoutControlService.evaluate(request, merchant, "system");
            if (control.isApprovalRequired()) {
                PaymentResult pending = new PaymentResult();
                pending.setReference(request.getReference());
                pending.setStatus("APPROVAL_PENDING");
                pending.setChannel(request.getChannel());
                pending.setCurrency(request.getCurrency());
                pending.setMessage(
                        "Payout requires maker-checker approval: " + control.reasonCode());
                idempotencyService.record(
                        request.getMerchantNumber(), idempotencyKey, idempotencyBody, pending);
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(pending);
            }
            PaymentResult result =
                    paymentOrchestrationService.payout(
                            request, merchant, servletRequest.getRemoteAddr());
            idempotencyService.record(
                    request.getMerchantNumber(), idempotencyKey, idempotencyBody, result);
            return ResponseEntity.accepted().body(result);
        } catch (V2RequestSecurityException e) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
        } catch (IllegalStateException e) {
            return error(HttpStatus.FORBIDDEN, "PRODUCTION_CAPABILITY_NOT_ENABLED", e.getMessage());
        } catch (PaymentGatewayException e) {
            return error(HttpStatus.BAD_REQUEST, "PAYMENT_REJECTED", e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid payout request");
        }
    }

    @GetMapping(path = "/channels")
    public List<PaymentChannelResponse> channels() {
        return paymentOrchestrationService.listChannels();
    }

    @GetMapping(path = "/balances")
    public ResponseEntity<?> balances(
            @RequestParam("merchantNumber") String merchantNumber,
            HttpServletRequest servletRequest) {
        try {
            Merchant merchant = securityService.verify(servletRequest, "", merchantNumber);
            List<Balance> balances = paymentOrchestrationService.balances(merchantNumber, merchant);
            return ResponseEntity.ok(balances);
        } catch (V2RequestSecurityException e) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
        } catch (PaymentGatewayException e) {
            return error(HttpStatus.BAD_REQUEST, "BALANCE_REJECTED", e.getMessage());
        }
    }

    @GetMapping(path = "/payments/{reference}")
    public ResponseEntity<?> status(
            @PathVariable("reference") String reference,
            @RequestParam("merchantNumber") String merchantNumber,
            HttpServletRequest servletRequest) {
        try {
            Merchant merchant = securityService.verify(servletRequest, "", merchantNumber);
            PaymentStatusResponse status = paymentStatusService.getStatus(merchant, reference);
            return ResponseEntity.ok(status);
        } catch (V2RequestSecurityException e) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
        } catch (PaymentGatewayException e) {
            return error(HttpStatus.BAD_REQUEST, "STATUS_REJECTED", e.getMessage());
        }
    }

    @PostMapping(path = "/accounts/validate")
    public ResponseEntity<?> validateAccount(
            @RequestBody String body, HttpServletRequest servletRequest) {
        try {
            AccountValidationRequest request =
                    objectMapper.readValue(body, AccountValidationRequest.class);
            Merchant merchant =
                    securityService.verify(servletRequest, body, request.getMerchantNumber());
            return ResponseEntity.ok(accountValidationService.validate(request, merchant));
        } catch (V2RequestSecurityException e) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
        } catch (PaymentGatewayException e) {
            return error(HttpStatus.BAD_REQUEST, "ACCOUNT_VALIDATION_REJECTED", e.getMessage());
        } catch (Exception e) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "Invalid account validation request");
        }
    }

    @GetMapping(path = "/statements")
    public ResponseEntity<?> statements(
            @RequestParam("merchantNumber") String merchantNumber,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            @RequestParam(value = "format", defaultValue = "json") String format,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            HttpServletRequest servletRequest) {
        try {
            Merchant merchant = securityService.verify(servletRequest, "", merchantNumber);
            StatementExportResponse export =
                    statementExportService.export(
                            merchant, merchantNumber, startDate, endDate, limit, cursor);
            if ("csv".equalsIgnoreCase(format)) {
                String filename =
                        "cpay-statement-"
                                + merchantNumber
                                + "-"
                                + startDate
                                + "-to-"
                                + endDate
                                + ".csv";
                return ResponseEntity.ok()
                        .header(
                                HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .contentType(
                                MediaType.parseMediaType(TabularExportService.CSV_CONTENT_TYPE))
                        .body(statementExportService.toCsv(export));
            }
            if ("xlsx".equalsIgnoreCase(format)) {
                String filename =
                        "cpay-statement-"
                                + merchantNumber
                                + "-"
                                + startDate
                                + "-to-"
                                + endDate
                                + ".xlsx";
                return ResponseEntity.ok()
                        .header(
                                HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .contentType(
                                MediaType.parseMediaType(TabularExportService.XLSX_CONTENT_TYPE))
                        .body(statementExportService.toXlsx(export));
            }
            return ResponseEntity.ok(export);
        } catch (V2RequestSecurityException e) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
        } catch (PaymentGatewayException e) {
            return error(HttpStatus.BAD_REQUEST, "STATEMENT_EXPORT_REJECTED", e.getMessage());
        }
    }

    /**
     * Compatibility v2 historically executed production behavior when no environment was supplied.
     * Preserve that contract. Sandbox is selected only when the caller explicitly declares it in
     * the environment header or request metadata.
     */
    private String resolveCompatibilityEnvironment(
            HttpServletRequest servletRequest, PaymentRequest request) {
        String headerEnvironment = servletRequest.getHeader("X-CPay-Environment");
        String bodyEnvironment =
                request == null || request.getMetadata() == null
                        ? null
                        : request.getMetadata().get("environment");
        if (blank(headerEnvironment) && blank(bodyEnvironment)) {
            return MerchantEnvironmentService.PRODUCTION;
        }
        return environmentService.resolveRequestEnvironment(headerEnvironment, bodyEnvironment);
    }

    private String bodyWithEnvironment(String body, String environment) {
        return (body == null ? "" : body) + "\n#cpay-environment=" + environment;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(code, message, UUID.randomUUID().toString()));
    }
}
