package net.citotech.cito.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regulator reporting (compliance roadmap: "regulator-facing reports should be scheduled exports
 * sourced from the normalized ledger after the ledger roadmap lands").
 *
 * <p>{@link #generateDailyCashFlow(LocalDate)} aggregates one day of {@code
 * merchant_transactions_log} into a BoU daily cash-flow report (per tx_type/currency/status rows
 * plus per-currency totals) and upserts it into {@code regulator_reports} as {@code report_json};
 * the CSV export is derived from that stored JSON at download time so an operator sees exactly the
 * numbers that were generated. The report contains no PII - only counts and amounts. {@link
 * #piiInventory()} exposes the in-app PII data-class catalog (metadata only, no personal data).
 */
@Service
public class RegulatorReportingService {

    private static final String REPORT_TYPE_BOU_DAILY_CASH_FLOW = "BOU_DAILY_CASH_FLOW";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RegulatorReportingService(
            NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Aggregates and stores the BoU daily cash-flow report for {@code reportDate}. Idempotent:
     * re-running the same date replaces the stored run (the unique report_type/report_date key
     * upserts). Returns the stored report row.
     */
    @Transactional
    public Map<String, Object> generateDailyCashFlow(LocalDate reportDate) {
        List<Row> rows = aggregate(reportDate);
        Map<String, Object> report = buildReport(reportDate, rows);
        String json = toJson(report);
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("report_type", REPORT_TYPE_BOU_DAILY_CASH_FLOW);
        p.addValue("report_date", Date.valueOf(reportDate));
        p.addValue("row_count", rows.size());
        p.addValue("total_amount", totalAmount(rows));
        p.addValue("report_json", json);
        jdbcTemplate.update(
                "INSERT INTO regulator_reports "
                        + "(report_type, report_date, report_status, row_count, total_amount, report_json) "
                        + "VALUES (:report_type, :report_date, 'GENERATED', :row_count, :total_amount, :report_json) "
                        + "ON DUPLICATE KEY UPDATE report_status='GENERATED', row_count=VALUES(row_count), "
                        + "total_amount=VALUES(total_amount), report_json=VALUES(report_json), error_message=NULL",
                p);
        return fetchReport(REPORT_TYPE_BOU_DAILY_CASH_FLOW, reportDate);
    }

    public Map<String, Object> fetchReport(String reportType, LocalDate reportDate) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("report_type", blank(reportType) ? REPORT_TYPE_BOU_DAILY_CASH_FLOW : reportType);
        p.addValue("report_date", Date.valueOf(reportDate));
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT id, report_type, report_date, report_status, row_count, total_amount, "
                                + "report_json, error_message, created_at, updated_at "
                                + "FROM regulator_reports WHERE report_type=:report_type AND report_date=:report_date",
                        p);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listReports(String reportType, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("report_type", blank(reportType) ? REPORT_TYPE_BOU_DAILY_CASH_FLOW : reportType);
        p.addValue("limit", Math.max(1, Math.min(limit, 200)));
        return jdbcTemplate.queryForList(
                "SELECT id, report_type, report_date, report_status, row_count, total_amount, "
                        + "created_at, updated_at FROM regulator_reports WHERE report_type=:report_type "
                        + "ORDER BY report_date DESC LIMIT :limit",
                p);
    }

    /**
     * Renders the stored report_json as a CSV (header + one row per tx_type/currency/status group).
     */
    public String toCsv(Map<String, Object> report) {
        if (report == null || report.get("report_json") == null) {
            throw new PaymentGatewayException("Report not found or empty");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body =
                    objectMapper.readValue(String.valueOf(report.get("report_json")), Map.class);
            StringBuilder csv = new StringBuilder();
            csv.append("tx_type,currency,status,tx_count,total_amount\n");
            List<?> rows = body.get("rows") instanceof List<?> list ? list : List.of();
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                csv.append(cell(row.get("txType")))
                        .append(',')
                        .append(cell(row.get("currency")))
                        .append(',')
                        .append(cell(row.get("status")))
                        .append(',')
                        .append(cell(row.get("txCount")))
                        .append(',')
                        .append(cell(row.get("totalAmount")))
                        .append('\n');
            }
            return csv.toString();
        } catch (Exception e) {
            throw new PaymentGatewayException("Unable to render report CSV: " + e.getMessage());
        }
    }

    /** In-app PII data-class inventory: metadata only, no personal data is ever stored here. */
    public List<Map<String, Object>> piiInventory() {
        return jdbcTemplate.queryForList(
                "SELECT id, data_class, storage_location, retention_days, masking_status, notes, created_at "
                        + "FROM pii_inventory_entries ORDER BY data_class",
                new MapSqlParameterSource());
    }

    private List<Row> aggregate(LocalDate reportDate) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("rangeStart", Timestamp.valueOf(reportDate.atStartOfDay()));
        p.addValue("rangeEnd", Timestamp.valueOf(reportDate.plusDays(1).atStartOfDay()));
        return jdbcTemplate.query(
                "SELECT tx_type, currency, status, COUNT(*) AS tx_count, "
                        + "COALESCE(SUM(original_amount), 0) AS total_amount "
                        + "FROM merchant_production_transactions "
                        + "WHERE created_on >= :rangeStart AND created_on < :rangeEnd "
                        + "GROUP BY tx_type, currency, status ORDER BY tx_type, currency, status",
                p,
                (rs, rowNum) ->
                        new Row(
                                rs.getString("tx_type"),
                                rs.getString("currency"),
                                rs.getString("status"),
                                rs.getInt("tx_count"),
                                rs.getBigDecimal("total_amount")));
    }

    private Map<String, Object> buildReport(LocalDate reportDate, List<Row> rows) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportDate", reportDate.toString());
        report.put("generatedAt", Instant.now().toString());
        report.put("reportVersion", "1");
        List<Map<String, Object>> rowMaps = new ArrayList<>();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Row row : rows) {
            Map<String, Object> rowMap = new LinkedHashMap<>();
            rowMap.put("txType", row.txType());
            rowMap.put("currency", row.currency());
            rowMap.put("status", row.status());
            rowMap.put("txCount", row.txCount());
            rowMap.put("totalAmount", row.totalAmount());
            rowMaps.add(rowMap);
            String key = row.currency() + ":" + row.txType();
            totals.merge(key, row.totalAmount(), BigDecimal::add);
        }
        report.put("rows", rowMaps);
        report.put("totalsByCurrencyAndType", totals);
        return report;
    }

    private String toJson(Map<String, Object> report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            throw new PaymentGatewayException("Unable to serialize regulator report");
        }
    }

    private BigDecimal totalAmount(List<Row> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (Row row : rows) {
            total = total.add(row.totalAmount());
        }
        return total;
    }

    private String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        return text.contains(",") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record Row(
            String txType, String currency, String status, int txCount, BigDecimal totalAmount) {}
}
