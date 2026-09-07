package net.citotech.cito;

import static net.citotech.cito.Common.recordStatementTx;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import net.citotech.cito.Model.*;
import net.citotech.cito.async.ManagedAsyncTasks;
import net.citotech.cito.security.ColumnAllowlist;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * @author josephtabajjwa
 */
@RestController
@RequestMapping(
        path = "/transactions",
        produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
public class TransactionsLogController {
    private static final long MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_UPLOAD_ROWS = 5000;
    private static final DateTimeFormatter SMS_SEND_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired NamedParameterJdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private net.citotech.cito.ledger.DoubleEntryLedgerService ledgerService;
    @Autowired private BatchPayoutFundingGuard batchPayoutFundingGuard;

    @Autowired
    private net.citotech.cito.ledger.LegacyLedgerPostingService legacyLedgerPostingService;

    @Autowired(required = false)
    net.citotech.cito.security.MerchantMfaService merchantMfaService;

    @PostMapping(path = "/getTransactions")
    public String getTransactions(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            User sessionUser;

            if (session.getAttribute("user") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (User) session.getAttribute("user");
            // Get the first details

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("ACCESS_TRANSACTION_LOG", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            JSONObject sObject = new JSONObject(requestBody);
            String pageSize = Common.jsonText(sObject, "pageSize", "");
            String currentPage = Common.jsonText(sObject, "currentPage", "");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");

            String sqlSelect = "SELECT *  FROM " + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG + " ";

            // HANDLE SEARCH PARAMETERS
            if (!searchValue.isNull("category") && !searchValue.isNull("value")) {

                String category = searchValue.getString("category");
                String value = searchValue.getString("value");
                if (!category.equals("all") && !value.isEmpty()) {
                    String safeCategory = ColumnAllowlist.validate(category);
                    sqlSelect += " WHERE " + safeCategory + " LIKE :" + safeCategory + " ";
                    parameters.addValue(safeCategory, "%" + value + "%");
                }
            }

            sqlSelect += " ORDER BY id DESC ";

            if (pageSize != null && !pageSize.isEmpty()) {
                int _limit = Math.max(1, Math.min(Integer.parseInt(pageSize.trim()), 1000));
                sqlSelect += " LIMIT " + _limit;
            }

            RowMapper<Transaction> rm = Common.getTransactionRowMapper();

            // ResultSet rs;
            List<Transaction> listUsers = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");
            JSONArray admins_array = new JSONArray();
            for (Transaction us : listUsers) {
                JSONObject u_p_ = new JSONObject();
                u_p_.put("id", us.getId());
                Merchant merchant = Common.getMerchantById(us.getMerchant_id(), jdbcTemplate);
                u_p_.put("merchant_number", merchant.getAccount_number());
                u_p_.put("merchant_name", merchant.getName());
                u_p_.put("gateway_id", us.getGateway_id());
                u_p_.put("charges", us.getCharges());
                u_p_.put("charges_formatted", Common.numberFormat(us.getCharges()));
                u_p_.put("status", us.getStatus());
                u_p_.put("original_amount", us.getOriginal_amount());
                u_p_.put("original_amount_formatted", Common.numberFormat(us.getOriginal_amount()));
                u_p_.put("charging_method", us.getCharging_method());
                u_p_.put("created_on", us.getCreated_on());
                u_p_.put("updaed_on", us.getUpdated_on());
                u_p_.put("tx_request_trace", us.getTx_request_trace());
                u_p_.put("tx_update_trace", us.getTx_update_trace());
                u_p_.put("tx_description", us.getTx_description());
                u_p_.put("tx_merchant_description", us.getTx_merchant_description());
                u_p_.put("tx_unique_id", us.getTx_unique_id());
                u_p_.put("tx_gateway_ref", us.getTx_gateway_ref());
                u_p_.put("tx_merchant_ref", us.getTx_merchant_ref());
                u_p_.put("payer_number", us.getPayer_number());
                u_p_.put("tx_type", us.getTx_type());
                u_p_.put("callback_trace", us.getCallback_trace());

                admins_array.put(u_p_);
            }
            resJson.put("data", admins_array);

            return resJson.toString();

        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/getMerchantTransactions")
    public String getMerchantTransactions(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            MerchantUser sessionUser;

            if (session.getAttribute("merchantUser") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (MerchantUser) session.getAttribute("merchantUser");
            // Get the first details

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("ACCESS_TRANSACTION_LOG", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            JSONObject sObject = new JSONObject(requestBody);

            JSONObject sObjectRules = sObject.getJSONObject("search_rules");
            String start_date = sObjectRules.getString("start_date");
            String end_date = sObjectRules.getString("end_date");
            String status = sObjectRules.getString("status");
            String tx_type = sObjectRules.getString("tx_type");

            int pageSize = sObject.getInt("pageSize");
            int currentPage = sObject.isNull("currentPage") ? 0 : sObject.getInt("currentPage");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");

            parameters.addValue("merchant_id", sessionUser.getMerchant_id());
            String sqlSelect =
                    "SELECT *  FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + " WHERE merchant_id = :merchant_id";

            String sqlSelectTotal =
                    "SELECT count(*) as total  "
                            + " FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + " WHERE merchant_id = :merchant_id";

            // HANDLE SEARCH PARAMETERS
            if (!searchValue.isNull("category") && !searchValue.isNull("value")) {

                String category = searchValue.getString("category");
                String value = searchValue.getString("value");
                if (!category.equals("all") && !value.isEmpty()) {
                    String safeCategory = ColumnAllowlist.validate(category);
                    sqlSelect += " AND " + safeCategory + " LIKE :" + safeCategory + " ";
                    parameters.addValue(safeCategory, "%" + value + "%");
                }
            }

            if (!start_date.isEmpty() && !end_date.isEmpty()) {
                sqlSelect += " AND (created_on BETWEEN :start_date AND :end_date) ";
                // Audit F6: bind a native java.sql.Timestamp, not the raw request string - a
                // malformed date now fails fast with a clear parse error instead of silently
                // reaching the database as an unvalidated string.
                try {
                    parameters.addValue(
                            "start_date", java.sql.Timestamp.valueOf(start_date + " 00:00:00"));
                    parameters.addValue(
                            "end_date", java.sql.Timestamp.valueOf(end_date + " 23:59:59"));
                } catch (IllegalArgumentException ex) {
                    return GeneralException.getError(
                            "101", "start_date/end_date must use YYYY-MM-DD.");
                }
            } else {
                LocalDateTime dt = LocalDateTime.now();
                java.sql.Timestamp end_date_ = java.sql.Timestamp.valueOf(dt);
                LocalDateTime last3Months = dt.minusMonths(3);
                java.sql.Timestamp start_date_ = java.sql.Timestamp.valueOf(last3Months);

                sqlSelect += " AND (created_on BETWEEN :start_date AND :end_date) ";
                parameters.addValue("start_date", start_date_);
                parameters.addValue("end_date", end_date_);
            }

            if (!status.isEmpty()) {
                sqlSelect += " AND status =:status ";
                parameters.addValue("status", status);
            }

            if (!tx_type.isEmpty()) {
                sqlSelect += " AND tx_type =:tx_type ";
                parameters.addValue("tx_type", tx_type);
            }

            sqlSelect += " ORDER BY id DESC ";

            /*if (pageSize != 0) {
                sqlSelect += " LIMIT "+(pageSize * currentPage)+", "+pageSize+" ";
            }*/

            RowMapper<Long> rmTotal =
                    new RowMapper<Long>() {
                        public Long mapRow(ResultSet rs, int rowNum) throws SQLException {
                            Long t = rs.getLong("total");
                            return t;
                        }
                    };
            List<Long> listLong = jdbcTemplate.query(sqlSelectTotal, parameters, rmTotal);

            RowMapper<Transaction> rm = Common.getTransactionRowMapper();

            // ResultSet rs;
            List<Transaction> listUsers = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");
            resJson.put("total", listLong.get(0));

            JSONArray admins_array = new JSONArray();
            for (Transaction us : listUsers) {
                JSONObject u_p_ = new JSONObject();
                u_p_.put("id", us.getId());
                Merchant merchant = Common.getMerchantById(us.getMerchant_id(), jdbcTemplate);
                u_p_.put("merchant_number", merchant.getAccount_number());
                u_p_.put("merchant_name", merchant.getName());
                u_p_.put("gateway_id", us.getGateway_id());
                u_p_.put("charges", us.getCharges());
                u_p_.put("charges_formatted", Common.numberFormat(us.getCharges()));
                u_p_.put("status", us.getStatus());
                u_p_.put("original_amount", us.getOriginal_amount());
                u_p_.put("original_amount_formatted", Common.numberFormat(us.getOriginal_amount()));
                u_p_.put("charging_method", us.getCharging_method());
                u_p_.put("created_on", us.getCreated_on());
                u_p_.put("updaed_on", us.getUpdated_on());
                u_p_.put("tx_request_trace", "" /*us.getTx_request_trace()*/);
                u_p_.put("tx_update_trace", "" /*us.getTx_update_trace()*/);
                u_p_.put("tx_description", us.getTx_description());
                u_p_.put("tx_merchant_description", us.getTx_merchant_description());
                u_p_.put("tx_unique_id", us.getTx_unique_id());
                u_p_.put("tx_gateway_ref", us.getTx_gateway_ref());
                u_p_.put("tx_merchant_ref", us.getTx_merchant_ref());
                u_p_.put("payer_number", us.getPayer_number());
                u_p_.put("tx_type", us.getTx_type());
                u_p_.put("callback_trace", us.getCallback_trace());

                admins_array.put(u_p_);
            }
            resJson.put("data", admins_array);

            return resJson.toString();

        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    private List<Beneficiary> getBatchBeneficiaries(long batch_id) {
        String sqlSelect =
                "SELECT b.batch_id, b.name as beneficiary_name, b.account,  b.amount as"
                    + " beneficiary_amount, b.account_type, b.id as beneficiary_long_id,  b.status"
                    + " as beneficiary_status, b.id as benficiary_id, t.*   FROM `"
                        + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_BENEFICIARIES
                        + "` AS b "
                        + " LEFT JOIN `"
                        + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                        + "` AS t "
                        + " ON b.id = t.beneficiary_id "
                        + " WHERE b.batch_id = :batch_id ORDER BY b.id";

        RowMapper<Beneficiary> rm =
                new RowMapper<Beneficiary>() {
                    public Beneficiary mapRow(ResultSet rs, int rowNum) throws SQLException {
                        Beneficiary b = new Beneficiary();
                        b.setId(rs.getLong("beneficiary_long_id"));
                        b.setName(rs.getString("beneficiary_name"));
                        b.setStatus(rs.getString("beneficiary_status"));
                        b.setAccount(rs.getString("account"));
                        b.setAmount(rs.getDouble("beneficiary_amount"));
                        b.setAccount_type(rs.getString("account_type"));
                        Transaction t = new Transaction();
                        if (rs.getString("gateway_id") == null) {
                            t = null;
                        } else {
                            t = Common.getTransactionRowMapper().mapRow(rs, rowNum);
                        }

                        b.setTransaction(t);
                        return b;
                    }
                };
        List<Beneficiary> blist =
                jdbcTemplate.query(sqlSelect, new MapSqlParameterSource("batch_id", batch_id), rm);
        return blist;
    }

    @PostMapping(path = "/getMerchantPayments")
    public String getMerchantPayments(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            MerchantUser sessionUser;

            if (session.getAttribute("merchantUser") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (MerchantUser) session.getAttribute("merchantUser");
            // Get the first details

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("ACCESS_TRANSACTION_LOG", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            JSONObject sObject = new JSONObject(requestBody);
            int pageSize = sObject.getInt("pageSize");
            int currentPage = sObject.isNull("currentPage") ? 0 : sObject.getInt("currentPage");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");

            parameters.addValue("merchant_id", sessionUser.getMerchant_id());
            String sqlSelect =
                    "SELECT *  FROM "
                            + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_LOG
                            + " "
                            + " WHERE merchant_id = :merchant_id";

            String sqlSelectTotal =
                    "SELECT count(*) as total  "
                            + " FROM "
                            + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_LOG
                            + " "
                            + " WHERE merchant_id = :merchant_id";

            // HANDLE SEARCH PARAMETERS
            if (!searchValue.isNull("category") && !searchValue.isNull("value")) {

                String category = searchValue.getString("category");
                String value = searchValue.getString("value");
                if (!category.equals("all") && !value.isEmpty()) {
                    String safeCategory = ColumnAllowlist.validate(category);
                    sqlSelect += " AND " + safeCategory + " LIKE :" + safeCategory + " ";
                    parameters.addValue(safeCategory, "%" + value + "%");
                }
            }

            sqlSelect += " ORDER BY id DESC ";

            if (pageSize != 0) {
                sqlSelect += " LIMIT :offset, :page_size ";
                parameters.addValue("offset", currentPage * pageSize);
                parameters.addValue("page_size", pageSize);
            }

            RowMapper<Payment> rm =
                    new RowMapper<Payment>() {
                        public Payment mapRow(ResultSet rs, int rowNum) throws SQLException {
                            Payment t = new Payment();
                            t.setId(rs.getLong("id"));
                            t.setName(rs.getString("name"));
                            t.setPaymentId(rs.getString("batch_id"));
                            t.setDescription(rs.getString("tx_description"));
                            t.setStatus(rs.getString("status"));
                            t.setCreated_on(rs.getString("created_on"));
                            t.setMerchant_id(rs.getLong("merchant_id"));
                            t.setTotal_amount(rs.getDouble("total_amount"));
                            t.setTotal_charges(rs.getDouble("total_charges"));
                            t.setCreated_by(rs.getString("created_by"));
                            t.setBeneficiaries(getBatchBeneficiaries(t.getId()));
                            return t;
                        }
                    };

            RowMapper<String> rmTotal =
                    new RowMapper<String>() {
                        public String mapRow(ResultSet rs, int rowNum) throws SQLException {
                            String t = rs.getString("total");
                            return t;
                        }
                    };
            // First get total
            List<String> totalList = jdbcTemplate.query(sqlSelectTotal, parameters, rmTotal);

            // ResultSet rs;
            List<Payment> plist = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");
            resJson.put("total", totalList.get(0));

            JSONArray admins_array = new JSONArray();
            for (Payment ps : plist) {
                JSONObject up = new JSONObject();
                up.put("id", ps.getId());
                up.put("name", ps.getName());
                up.put("tx_description", ps.getDescription());
                up.put("batch_id", ps.getPaymentId());
                up.put("status", ps.getStatus());
                up.put("total_amount", ps.getTotal_amount());
                up.put("total_charges", ps.getTotal_charges());
                up.put("created_by", ps.getCreated_by());
                up.put("created_on", ps.getCreated_on());

                // Get Beneficiaries
                int total_paid = 0;
                JSONArray jbeneficiaries = new JSONArray();
                for (Beneficiary b : ps.getBeneficiaries()) {
                    Transaction us = b.getTransaction();
                    JSONObject u_p_ = new JSONObject();

                    // Merchant merchant = Common.getMerchantById(us.getMerchant_id(),
                    // jdbcTemplate);
                    u_p_.put("name", b.getName());
                    u_p_.put("amount", b.getAmount());
                    u_p_.put("account", b.getAccount());
                    u_p_.put("account_type", b.getAccount_type());
                    u_p_.put("beneficiary_status", b.getStatus());
                    if (b.getStatus().equals(Transaction.BATCH_PAYMENT_PAID)) {
                        total_paid += 1;
                    }
                    u_p_.put("merchant_number", sessionUser.getMerchant_number());
                    u_p_.put("merchant_name", sessionUser.getMerchant_name());
                    u_p_.put("gateway_id", us == null ? "" : us.getGateway_id());
                    u_p_.put("charges", us == null ? "" : us.getCharges());
                    u_p_.put(
                            "charges_formatted",
                            us == null ? "" : Common.numberFormat(us.getCharges()));
                    u_p_.put("status", us == null ? "" : us.getStatus());
                    u_p_.put("original_amount", us == null ? "" : us.getOriginal_amount());
                    u_p_.put(
                            "original_amount_formatted",
                            us == null ? "" : Common.numberFormat(us.getOriginal_amount()));
                    u_p_.put("charging_method", us == null ? "" : us.getCharging_method());
                    u_p_.put("created_on", us == null ? "" : us.getCreated_on());
                    u_p_.put("updaed_on", us == null ? "" : us.getUpdated_on());
                    u_p_.put("tx_request_trace", us == null ? "" : us.getTx_request_trace());
                    u_p_.put("tx_update_trace", us == null ? "" : us.getTx_update_trace());
                    u_p_.put("tx_description", us == null ? "" : us.getTx_description());
                    u_p_.put(
                            "tx_merchant_description",
                            us == null ? "" : us.getTx_merchant_description());
                    u_p_.put("tx_unique_id", us == null ? "" : us.getTx_unique_id());
                    u_p_.put("tx_gateway_ref", us == null ? "" : us.getTx_gateway_ref());
                    u_p_.put("tx_merchant_ref", us == null ? "" : us.getTx_merchant_ref());
                    u_p_.put("payer_number", us == null ? "" : us.getPayer_number());
                    u_p_.put("tx_type", us == null ? "" : us.getTx_type());
                    u_p_.put("callback_trace", us == null ? "" : us.getCallback_trace());

                    jbeneficiaries.put(u_p_);
                }
                up.put("total_beneficiaries", ps.getBeneficiaries().size());
                up.put("total_paid", total_paid);
                up.put("beneficiaries", jbeneficiaries);
                admins_array.put(up);
            }
            resJson.put("data", admins_array);

            return resJson.toString();

        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/getMerchantSms")
    public String getMerchantSms(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            MerchantUser sessionUser;

            if (session.getAttribute("merchantUser") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (MerchantUser) session.getAttribute("merchantUser");
            // Get the first details

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("ACCESS_SMS_LOG", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            JSONObject sObject = new JSONObject(requestBody);

            JSONObject sObjectRules = sObject.getJSONObject("search_rules");
            String start_date = sObjectRules.getString("start_date");
            String end_date = sObjectRules.getString("end_date");
            String status = sObjectRules.getString("status");

            String pageSize = Common.jsonText(sObject, "pageSize", "");
            String currentPage = Common.jsonText(sObject, "currentPage", "");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");

            parameters.addValue("merchant_id", sessionUser.getMerchant_id());
            String sqlSelect =
                    "SELECT *  FROM "
                            + Common.DB_TABLE_MERCHANT_SMS
                            + " "
                            + " WHERE merchant_id = :merchant_id";

            // HANDLE SEARCH PARAMETERS
            if (!searchValue.isNull("category") && !searchValue.isNull("value")) {

                String category = searchValue.getString("category");
                String value = searchValue.getString("value");
                if (!value.equals("all") && !category.isEmpty() && !value.isEmpty()) {
                    String safeCategory = ColumnAllowlist.validate(category);
                    sqlSelect += " AND " + safeCategory + " LIKE :" + safeCategory + " ";
                    parameters.addValue(safeCategory, "%" + value + "%");
                }
            }

            if (!start_date.isEmpty() && !end_date.isEmpty()) {
                sqlSelect += " AND (created_on BETWEEN :start_date AND :end_date) ";
                // Audit F6: bind a native java.sql.Timestamp, not the raw request string - a
                // malformed date now fails fast with a clear parse error instead of silently
                // reaching the database as an unvalidated string.
                try {
                    parameters.addValue(
                            "start_date", java.sql.Timestamp.valueOf(start_date + " 00:00:00"));
                    parameters.addValue(
                            "end_date", java.sql.Timestamp.valueOf(end_date + " 23:59:59"));
                } catch (IllegalArgumentException ex) {
                    return GeneralException.getError(
                            "101", "start_date/end_date must use YYYY-MM-DD.");
                }
            } else {
                LocalDateTime dt = LocalDateTime.now();
                java.sql.Timestamp end_date_ = java.sql.Timestamp.valueOf(dt);
                LocalDateTime last3Months = dt.minusMonths(3);
                java.sql.Timestamp start_date_ = java.sql.Timestamp.valueOf(last3Months);

                sqlSelect += " AND (created_on BETWEEN :start_date AND :end_date) ";
                parameters.addValue("start_date", start_date_);
                parameters.addValue("end_date", end_date_);
            }

            if (!status.isEmpty()) {
                sqlSelect += " AND status =:status ";
                parameters.addValue("status", status);
            }

            sqlSelect += " ORDER BY id DESC ";

            if (pageSize != null && !pageSize.isEmpty()) {
                int _limit = Math.max(1, Math.min(Integer.parseInt(pageSize.trim()), 1000));
                sqlSelect += " LIMIT " + _limit;
            }

            RowMapper<MerchantSms> rm =
                    new RowMapper<MerchantSms>() {
                        public MerchantSms mapRow(ResultSet rs, int rowNum) throws SQLException {
                            MerchantSms t = new MerchantSms();
                            t.setId(BigInteger.valueOf(rs.getLong("id")));
                            t.setContent(rs.getString("content"));
                            t.setGw_response(rs.getString("gw_response"));
                            t.setRecipients(rs.getString("recipients"));
                            t.setStatus(rs.getString("status"));
                            t.setCreated_on(rs.getString("created_on"));
                            t.setCharge(rs.getDouble("charge"));
                            t.setCost(rs.getDouble("cost"));
                            t.setTrace(rs.getString("trace"));
                            t.setTotal_recipients(rs.getInt("total_recipients"));
                            t.setMerchant_id(BigInteger.valueOf(rs.getLong("merchant_id")));
                            t.setTotal_amount(rs.getDouble("total_amount"));
                            t.setSend_time(rs.getString("send_time"));
                            return t;
                        }
                    };

            // ResultSet rs;
            List<MerchantSms> plist = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");
            // Obtain balances
            ArrayList<Balance> balances =
                    Common.getMerchantBalances(sessionUser.getMerchant_id() + "", jdbcTemplate);

            JSONArray balArray = new JSONArray();
            for (Balance b : balances) {
                JSONObject jBalance = new JSONObject();
                jBalance.put("amount", b.getAmount());
                String[] bal_type = b.getBalance_type();
                jBalance.put("balance_type", bal_type[0]);
                jBalance.put("code", b.getCode());
                jBalance.put("gateway_id", b.getGateway_id());
                balArray.put(jBalance);
            }
            resJson.put("balances", balArray);

            JSONArray admins_array = new JSONArray();
            for (MerchantSms ps : plist) {
                JSONObject up = new JSONObject();
                up.put("id", ps.getId());
                up.put("content", ps.getContent());
                up.put("merchant_id", ps.getMerchant_id());
                String[] recipients = ps.getRecipients().split(",");
                JSONArray reps = new JSONArray();
                for (int i = 0; i < recipients.length; i++) {
                    JSONObject jObjec = new JSONObject();
                    jObjec.put("msisdn", recipients[i]);
                    jObjec.put("status", ps.getStatus());
                    jObjec.put("delete", false);
                    reps.put(jObjec);
                }
                up.put("recipients", reps);
                up.put("recipients_string", ps.getRecipients());
                up.put("status", ps.getStatus());
                up.put("charge", ps.getCharge());
                up.put("cost", ps.getCost());
                up.put("trace", ps.getTrace());
                up.put("gw_response", ps.getGw_response());
                up.put("created_on", ps.getCreated_on());
                up.put("total_recipients", ps.getTotal_recipients());
                up.put("send_time", ps.getSend_time());
                up.put("total_amount", ps.getTotal_amount());

                admins_array.put(up);
            }
            resJson.put("data", admins_array);

            return resJson.toString();

        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/getDashboardDetailsPayinsVsPayouts")
    public String getDashboardDetailsPayinsVsPayouts(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            User sessionUser;

            if (session.getAttribute("user") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (User) session.getAttribute("user");
            // Get the first details

            // Check permissions
            /*if (!Common.isUserAllowedAccessToThis("ACCESS_TRANSACTION_LOG", sessionUser)) {
                return GeneralException
                    .getError("110", GeneralException.ERRORS_110);
            }*/

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            /*JSONObject sObject = new JSONObject(requestBody);
            String pageSize = sObject.getString("pageSize");
            String currentPage = sObject.isNull("currentPage") ? "" : sObject.getString("currentPage");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");*/

            LocalDateTime now = LocalDateTime.now();
            // Audit F6: bound as a native java.sql.Timestamp via a bind parameter, not a
            // Java-formatted string spliced into the SQL text - the previous
            // "BETWEEN '"+start_date+"'" form worked only by luck (MySQL happening to coerce a
            // well-formed literal) and had no protection against a malformed/misformatted value
            // silently mis-comparing instead of erroring.
            java.sql.Timestamp end_date = java.sql.Timestamp.valueOf(now);
            LocalDateTime start_ = now.minusMonths(6);
            java.sql.Timestamp start_date = java.sql.Timestamp.valueOf(start_);
            parameters.addValue("start_date", start_date);
            parameters.addValue("end_date", end_date);

            String sqlSelect =
                    "SELECT "
                            + " (SELECT COUNT(*) "
                            + "     FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE "
                            + "         tx_type='"
                            + Transaction.TX_TYPE_PAYIN
                            + "' AND              created_on BETWEEN :start_date AND :end_date) AS"
                            + " payins, (SELECT COUNT(*)      FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE "
                            + "         tx_type='"
                            + Transaction.TX_TYPE_PAYOUT
                            + "' AND              created_on BETWEEN :start_date AND :end_date) AS"
                            + " payouts FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " ";

            RowMapper<JSONObject> rm =
                    new RowMapper<JSONObject>() {
                        public JSONObject mapRow(ResultSet rs, int rowNum) throws SQLException {

                            try {
                                JSONObject data = new JSONObject();
                                JSONArray labels = new JSONArray();
                                labels.put("Total Payins");
                                labels.put("Total Payouts");
                                data.put("labels", labels);

                                JSONArray datasets = new JSONArray();
                                JSONObject dataset = new JSONObject();
                                dataset.put("label", "Transactions in last 6 months");

                                JSONArray datasets_color = new JSONArray();
                                datasets_color.put("#3e95cd");
                                datasets_color.put("#8e5ea2");

                                dataset.put("backgroundColor", datasets_color);

                                JSONArray datasets_data = new JSONArray();
                                datasets_data.put(rs.getInt("payins"));
                                datasets_data.put(rs.getInt("payouts"));

                                dataset.put("data", datasets_data);
                                datasets.put(dataset);

                                data.put("datasets", datasets);

                                // Add options
                                JSONObject options = new JSONObject();
                                JSONObject legend = new JSONObject();
                                legend.put("display", true);
                                options.put("legend", legend);

                                JSONObject title = new JSONObject();
                                title.put("display", true);
                                title.put("text", "Payins Vs Payouts");
                                options.put("options", title);

                                JSONObject chartData = new JSONObject();
                                chartData.put("type", "bar");
                                chartData.put("options", options);
                                chartData.put("data", data);

                                return chartData;
                            } catch (JSONException ex) {
                                Logger.getLogger(TransactionsLogController.class.getName())
                                        .log(Level.SEVERE, ex.getMessage(), ex);
                            }
                            return null;
                        }
                    };

            // ResultSet rs;
            List<JSONObject> listr = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");

            for (JSONObject us : listr) {
                resJson.put("chartData", us);
            }

            return resJson.toString();

        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/getDashboardDetailsPayinsVsPayoutsMerchant")
    public String getDashboardDetailsPayinsVsPayoutsMerchant(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            MerchantUser sessionUser;

            if (session.getAttribute("merchantUser") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (MerchantUser) session.getAttribute("merchantUser");
            // Get the first details

            // Check permissions
            /*if (!Common.isUserAllowedAccessToThis("ACCESS_TRANSACTION_LOG", sessionUser)) {
                return GeneralException
                    .getError("110", GeneralException.ERRORS_110);
            }*/

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            /*JSONObject sObject = new JSONObject(requestBody);
            String pageSize = sObject.getString("pageSize");
            String currentPage = sObject.isNull("currentPage") ? "" : sObject.getString("currentPage");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");*/

            LocalDateTime now = LocalDateTime.now();
            // Audit F6: bound as a native java.sql.Timestamp via a bind parameter, not a
            // Java-formatted string spliced into the SQL text - the previous
            // "BETWEEN '"+start_date+"'" form worked only by luck (MySQL happening to coerce a
            // well-formed literal) and had no protection against a malformed/misformatted value
            // silently mis-comparing instead of erroring.
            java.sql.Timestamp end_date = java.sql.Timestamp.valueOf(now);
            LocalDateTime start_ = now.minusMonths(6);
            java.sql.Timestamp start_date = java.sql.Timestamp.valueOf(start_);
            parameters.addValue("start_date", start_date);
            parameters.addValue("end_date", end_date);

            String sqlSelect =
                    "SELECT "
                            + " (SELECT COUNT(*) "
                            + "     FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE merchant_id='"
                            + sessionUser.getMerchant_id()
                            + "' "
                            + "     AND tx_type='"
                            + Transaction.TX_TYPE_PAYIN
                            + "'          AND              created_on BETWEEN :start_date AND"
                            + " :end_date) AS payins, (SELECT COUNT(*)      FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE merchant_id='"
                            + sessionUser.getMerchant_id()
                            + "' AND "
                            + "         tx_type='"
                            + Transaction.TX_TYPE_PAYOUT
                            + "' AND              created_on BETWEEN :start_date AND :end_date) AS"
                            + " payouts FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " ";

            RowMapper<JSONObject> rm =
                    new RowMapper<JSONObject>() {
                        public JSONObject mapRow(ResultSet rs, int rowNum) throws SQLException {

                            try {
                                JSONObject data = new JSONObject();
                                JSONArray labels = new JSONArray();
                                labels.put("Total Payins");
                                labels.put("Total Payouts");
                                data.put("labels", labels);

                                JSONArray datasets = new JSONArray();
                                JSONObject dataset = new JSONObject();
                                dataset.put("label", "Transactions in last 6 months");

                                JSONArray datasets_color = new JSONArray();
                                datasets_color.put("#3e95cd");
                                datasets_color.put("#8e5ea2");

                                dataset.put("backgroundColor", datasets_color);

                                JSONArray datasets_data = new JSONArray();
                                datasets_data.put(rs.getInt("payins"));
                                datasets_data.put(rs.getInt("payouts"));

                                dataset.put("data", datasets_data);
                                datasets.put(dataset);

                                data.put("datasets", datasets);

                                // Add options
                                JSONObject options = new JSONObject();
                                JSONObject legend = new JSONObject();
                                legend.put("display", true);
                                options.put("legend", legend);

                                JSONObject title = new JSONObject();
                                title.put("display", true);
                                title.put("text", "Payins Vs Payouts");
                                options.put("options", title);

                                JSONObject chartData = new JSONObject();
                                chartData.put("type", "bar");
                                chartData.put("options", options);
                                chartData.put("data", data);

                                return chartData;
                            } catch (JSONException ex) {
                                Logger.getLogger(TransactionsLogController.class.getName())
                                        .log(Level.SEVERE, ex.getMessage(), ex);
                            }
                            return null;
                        }
                    };

            // ResultSet rs;
            List<JSONObject> listr = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");

            for (JSONObject us : listr) {
                resJson.put("chartData", us);
            }

            return resJson.toString();

        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/getDashboardDetailsTxPerGateway")
    public String getDashboardDetailsTxPerGateway(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            User sessionUser;

            if (session.getAttribute("user") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (User) session.getAttribute("user");
            // Get the first details

            // Check permissions
            /*if (!Common.isUserAllowedAccessToThis("ACCESS_TRANSACTION_LOG", sessionUser)) {
                return GeneralException
                    .getError("110", GeneralException.ERRORS_110);
            }*/

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            /*JSONObject sObject = new JSONObject(requestBody);
            String pageSize = sObject.getString("pageSize");
            String currentPage = sObject.isNull("currentPage") ? "" : sObject.getString("currentPage");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");*/

            LocalDateTime now = LocalDateTime.now();
            // Audit F6: bound as a native java.sql.Timestamp via a bind parameter, not a
            // Java-formatted string spliced into the SQL text - the previous
            // "BETWEEN '"+start_date+"'" form worked only by luck (MySQL happening to coerce a
            // well-formed literal) and had no protection against a malformed/misformatted value
            // silently mis-comparing instead of erroring.
            java.sql.Timestamp end_date = java.sql.Timestamp.valueOf(now);
            LocalDateTime start_ = now.minusMonths(6);
            java.sql.Timestamp start_date = java.sql.Timestamp.valueOf(start_);
            parameters.addValue("start_date", start_date);
            parameters.addValue("end_date", end_date);

            String sqlSelect =
                    "SELECT "
                            + " (SELECT COUNT(*) "
                            + "     FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE "
                            + "         gateway_id='MTNMoMoPaymentGateway' AND "
                            + "             created_on BETWEEN :start_date AND :end_date) AS mtnmm,"
                            + " (SELECT COUNT(*) "
                            + "     FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + "      WHERE          gateway_id='AirtelMMPaymentGateway' AND        "
                            + "      created_on BETWEEN :start_date AND :end_date) AS airtelmm FROM"
                            + " "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " ";

            RowMapper<JSONObject> rm =
                    new RowMapper<JSONObject>() {
                        public JSONObject mapRow(ResultSet rs, int rowNum) throws SQLException {

                            try {
                                JSONObject data = new JSONObject();
                                JSONArray labels = new JSONArray();
                                labels.put("Transactions on MTN MM");
                                labels.put("Transactions on Airtel MM");
                                data.put("labels", labels);

                                JSONArray datasets = new JSONArray();
                                JSONObject dataset = new JSONObject();
                                dataset.put("label", "Transactions in last 6 months");

                                JSONArray datasets_color = new JSONArray();
                                datasets_color.put("#3e95cd");
                                datasets_color.put("#8e5ea2");

                                dataset.put("backgroundColor", datasets_color);

                                JSONArray datasets_data = new JSONArray();
                                datasets_data.put(rs.getInt("mtnmm"));
                                datasets_data.put(rs.getInt("airtelmm"));

                                dataset.put("data", datasets_data);
                                datasets.put(dataset);

                                data.put("datasets", datasets);

                                // Add options
                                JSONObject options = new JSONObject();
                                JSONObject legend = new JSONObject();
                                legend.put("display", true);
                                options.put("legend", legend);

                                JSONObject title = new JSONObject();
                                title.put("display", true);
                                title.put("text", "Gateways");
                                options.put("options", title);

                                JSONObject chartData = new JSONObject();
                                chartData.put("type", "bar");
                                chartData.put("options", options);
                                chartData.put("data", data);

                                return chartData;
                            } catch (JSONException ex) {
                                Logger.getLogger(TransactionsLogController.class.getName())
                                        .log(Level.SEVERE, ex.getMessage(), ex);
                            }
                            return null;
                        }
                    };

            // ResultSet rs;
            List<JSONObject> listr = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");

            for (JSONObject us : listr) {
                resJson.put("chartData", us);
            }

            return resJson.toString();

        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/getDashboardDetailsNetworkBalances")
    public String getDashboardDetailsNetworkBalances(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            User sessionUser;

            if (session.getAttribute("user") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (User) session.getAttribute("user");

            JSONObject data = new JSONObject();
            JSONArray labels = new JSONArray();
            labels.put("MTN Collections");
            labels.put("MTN Disbursements");
            labels.put("Airtel Collections");
            labels.put("Airtel Disbursements");
            data.put("labels", labels);

            JSONArray datasets = new JSONArray();
            JSONObject dataset = new JSONObject();
            dataset.put("label", "Current Network Balances");

            JSONArray datasets_color = new JSONArray();
            datasets_color.put("#3e95cd");
            datasets_color.put("#8e5ea2");
            datasets_color.put("#5e92a2");
            datasets_color.put("#009e2d");

            dataset.put("backgroundColor", datasets_color);

            // Now get network balances disbursement and collections
            JSONArray datasets_data = new JSONArray();

            DoPayGateway gw = new DoPayGateway();

            double[] balances = gw.runPayGatewayNetworkBalances(jdbcTemplate);
            for (int iB = 0; iB < balances.length; iB++) {
                datasets_data.put(balances[iB]);
            }

            dataset.put("data", datasets_data);
            datasets.put(dataset);

            data.put("datasets", datasets);

            // Add options
            JSONObject options = new JSONObject();
            JSONObject legend = new JSONObject();
            legend.put("display", true);
            options.put("legend", legend);

            JSONObject title = new JSONObject();
            title.put("display", true);
            title.put("text", "Gateways");
            options.put("options", title);

            JSONObject chartData = new JSONObject();
            chartData.put("type", "bar");
            chartData.put("options", options);
            chartData.put("data", data);

            // ResultSet rs;
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");
            resJson.put("chartData", chartData);
            return resJson.toString();

        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/getDashboardDetailsTxPerGatewayMerchant")
    public String getDashboardDetailsTxPerGatewayMerchant(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            MerchantUser sessionUser;

            if (session.getAttribute("merchantUser") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (MerchantUser) session.getAttribute("merchantUser");
            // Get the first details

            // Check permissions
            /*if (!Common.isUserAllowedAccessToThis("ACCESS_TRANSACTION_LOG", sessionUser)) {
                return GeneralException
                    .getError("110", GeneralException.ERRORS_110);
            }*/

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            /*JSONObject sObject = new JSONObject(requestBody);
            String pageSize = sObject.getString("pageSize");
            String currentPage = sObject.isNull("currentPage") ? "" : sObject.getString("currentPage");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");*/

            LocalDateTime now = LocalDateTime.now();
            // Audit F6: bound as a native java.sql.Timestamp via a bind parameter, not a
            // Java-formatted string spliced into the SQL text - the previous
            // "BETWEEN '"+start_date+"'" form worked only by luck (MySQL happening to coerce a
            // well-formed literal) and had no protection against a malformed/misformatted value
            // silently mis-comparing instead of erroring.
            java.sql.Timestamp end_date = java.sql.Timestamp.valueOf(now);
            LocalDateTime start_ = now.minusMonths(6);
            java.sql.Timestamp start_date = java.sql.Timestamp.valueOf(start_);
            parameters.addValue("start_date", start_date);
            parameters.addValue("end_date", end_date);

            String sqlSelect =
                    "SELECT "
                            + " (SELECT COUNT(*) "
                            + "     FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE merchant_id='"
                            + sessionUser.getMerchant_id()
                            + "' AND "
                            + "         gateway_id='MTNMoMoPaymentGateway' AND "
                            + "             created_on BETWEEN :start_date AND :end_date) AS mtnmm,"
                            + " (SELECT COUNT(*) "
                            + "     FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE merchant_id='"
                            + sessionUser.getMerchant_id()
                            + "' AND          gateway_id='AirtelMMPaymentGateway' AND             "
                            + " created_on BETWEEN :start_date AND :end_date) AS airtelmm FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " ";

            RowMapper<JSONObject> rm =
                    new RowMapper<JSONObject>() {
                        public JSONObject mapRow(ResultSet rs, int rowNum) throws SQLException {

                            try {
                                JSONObject data = new JSONObject();
                                JSONArray labels = new JSONArray();
                                labels.put("Transactions on MTN MM");
                                labels.put("Transactions on Airtel MM");
                                data.put("labels", labels);

                                JSONArray datasets = new JSONArray();
                                JSONObject dataset = new JSONObject();
                                dataset.put("label", "Transactions in last 6 months");

                                JSONArray datasets_color = new JSONArray();
                                datasets_color.put("#3e95cd");
                                datasets_color.put("#8e5ea2");

                                dataset.put("backgroundColor", datasets_color);

                                JSONArray datasets_data = new JSONArray();
                                datasets_data.put(rs.getInt("mtnmm"));
                                datasets_data.put(rs.getInt("airtelmm"));

                                dataset.put("data", datasets_data);
                                datasets.put(dataset);

                                data.put("datasets", datasets);

                                // Add options
                                JSONObject options = new JSONObject();
                                JSONObject legend = new JSONObject();
                                legend.put("display", true);
                                options.put("legend", legend);

                                JSONObject title = new JSONObject();
                                title.put("display", true);
                                title.put("text", "Gateways");
                                options.put("options", title);

                                JSONObject chartData = new JSONObject();
                                chartData.put("type", "bar");
                                chartData.put("options", options);
                                chartData.put("data", data);

                                return chartData;
                            } catch (JSONException ex) {
                                Logger.getLogger(TransactionsLogController.class.getName())
                                        .log(Level.SEVERE, ex.getMessage(), ex);
                            }
                            return null;
                        }
                    };

            // ResultSet rs;
            List<JSONObject> listr = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");

            for (JSONObject us : listr) {
                resJson.put("chartData", us);
            }

            return resJson.toString();

        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/getDashboardDetailsTransactionTypes")
    public String getDashboardDetailsTransactionTypes(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            User sessionUser;

            if (session.getAttribute("user") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (User) session.getAttribute("user");
            // Get the first details

            // Check permissions
            /*if (!Common.isUserAllowedAccessToThis("ACCESS_TRANSACTION_LOG", sessionUser)) {
                return GeneralException
                    .getError("110", GeneralException.ERRORS_110);
            }*/

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            /*JSONObject sObject = new JSONObject(requestBody);
            String pageSize = sObject.getString("pageSize");
            String currentPage = sObject.isNull("currentPage") ? "" : sObject.getString("currentPage");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");*/

            LocalDateTime now = LocalDateTime.now();
            // Audit F6: bound as a native java.sql.Timestamp via a bind parameter, not a
            // Java-formatted string spliced into the SQL text - the previous
            // "BETWEEN '"+start_date+"'" form worked only by luck (MySQL happening to coerce a
            // well-formed literal) and had no protection against a malformed/misformatted value
            // silently mis-comparing instead of erroring.
            java.sql.Timestamp end_date = java.sql.Timestamp.valueOf(now);
            LocalDateTime start_ = now.minusMonths(6);
            java.sql.Timestamp start_date = java.sql.Timestamp.valueOf(start_);
            parameters.addValue("start_date", start_date);
            parameters.addValue("end_date", end_date);

            String sqlSelect =
                    "SELECT "
                            + " (SELECT COUNT(*) "
                            + "     FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + "      WHERE          status='SUCCESSFUL' AND              created_on"
                            + " BETWEEN :start_date AND :end_date) AS successful, (SELECT COUNT(*) "
                            + "     FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + "      WHERE          status='FAILED' AND              created_on"
                            + " BETWEEN :start_date AND :end_date) AS failed, (SELECT COUNT(*)     "
                            + " FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + "      WHERE          status='PENDING' AND              created_on"
                            + " BETWEEN :start_date AND :end_date) AS pending, (SELECT COUNT(*)    "
                            + "  FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + "      WHERE          status='UNDETERMINED' AND             "
                            + " created_on BETWEEN :start_date AND :end_date) AS undetermined FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " ";

            RowMapper<JSONObject> rm =
                    new RowMapper<JSONObject>() {
                        public JSONObject mapRow(ResultSet rs, int rowNum) throws SQLException {

                            try {
                                JSONObject data = new JSONObject();
                                JSONArray labels = new JSONArray();
                                labels.put("Total Successful");
                                labels.put("Total Failed");
                                labels.put("Total Pending");
                                labels.put("Total Undetermined");
                                data.put("labels", labels);

                                JSONArray datasets = new JSONArray();
                                JSONObject dataset = new JSONObject();
                                dataset.put("label", "Transactions in last 6 months");

                                JSONArray datasets_color = new JSONArray();
                                datasets_color.put("#3e95cd");
                                datasets_color.put("#eb4034");
                                datasets_color.put("#8e5ea2");
                                datasets_color.put("#f5c011");

                                dataset.put("backgroundColor", datasets_color);

                                JSONArray datasets_data = new JSONArray();
                                datasets_data.put(rs.getInt("successful"));
                                datasets_data.put(rs.getInt("failed"));
                                datasets_data.put(rs.getInt("pending"));
                                datasets_data.put(rs.getInt("undetermined"));

                                dataset.put("data", datasets_data);
                                datasets.put(dataset);

                                data.put("datasets", datasets);

                                // Add options
                                JSONObject options = new JSONObject();
                                JSONObject legend = new JSONObject();
                                legend.put("display", true);
                                options.put("legend", legend);

                                JSONObject title = new JSONObject();
                                title.put("display", true);
                                title.put("text", "Transactions");
                                options.put("options", title);

                                JSONObject chartData = new JSONObject();
                                chartData.put("type", "doughnut");
                                chartData.put("options", options);
                                chartData.put("data", data);

                                return chartData;
                            } catch (JSONException ex) {
                                Logger.getLogger(TransactionsLogController.class.getName())
                                        .log(Level.SEVERE, ex.getMessage(), ex);
                            }
                            return null;
                        }
                    };

            // ResultSet rs;
            List<JSONObject> listr = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");

            for (JSONObject us : listr) {
                resJson.put("chartData", us);
            }

            return resJson.toString();

        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/getDashboardDetailsTransactionTypesMerchant")
    public String getDashboardDetailsTransactionTypesMerchant(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            MerchantUser sessionUser;

            if (session.getAttribute("merchantUser") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (MerchantUser) session.getAttribute("merchantUser");
            // Get the first details

            // Check permissions
            /*if (!Common.isUserAllowedAccessToThis("ACCESS_TRANSACTION_LOG", sessionUser)) {
                return GeneralException
                    .getError("110", GeneralException.ERRORS_110);
            }*/

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            /*JSONObject sObject = new JSONObject(requestBody);
            String pageSize = sObject.getString("pageSize");
            String currentPage = sObject.isNull("currentPage") ? "" : sObject.getString("currentPage");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");*/

            LocalDateTime now = LocalDateTime.now();
            // Audit F6: bound as a native java.sql.Timestamp via a bind parameter, not a
            // Java-formatted string spliced into the SQL text - the previous
            // "BETWEEN '"+start_date+"'" form worked only by luck (MySQL happening to coerce a
            // well-formed literal) and had no protection against a malformed/misformatted value
            // silently mis-comparing instead of erroring.
            java.sql.Timestamp end_date = java.sql.Timestamp.valueOf(now);
            LocalDateTime start_ = now.minusMonths(6);
            java.sql.Timestamp start_date = java.sql.Timestamp.valueOf(start_);
            parameters.addValue("start_date", start_date);
            parameters.addValue("end_date", end_date);

            String sqlSelect =
                    "SELECT "
                            + " (SELECT COUNT(*) "
                            + "     FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE merchant_id='"
                            + sessionUser.getMerchant_id()
                            + "' AND          status='SUCCESSFUL' AND              created_on"
                            + " BETWEEN :start_date AND :end_date) AS successful, (SELECT COUNT(*) "
                            + "     FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE merchant_id='"
                            + sessionUser.getMerchant_id()
                            + "' AND          status='FAILED' AND              created_on BETWEEN"
                            + " :start_date AND :end_date) AS failed, (SELECT COUNT(*)      FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE merchant_id='"
                            + sessionUser.getMerchant_id()
                            + "' AND          status='PENDING' AND              created_on BETWEEN"
                            + " :start_date AND :end_date) AS pending, (SELECT COUNT(*)      FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE merchant_id='"
                            + sessionUser.getMerchant_id()
                            + "' AND          status='UNDETERMINED' AND              created_on"
                            + " BETWEEN :start_date AND :end_date) AS undetermined FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " ";

            RowMapper<JSONObject> rm =
                    new RowMapper<JSONObject>() {
                        public JSONObject mapRow(ResultSet rs, int rowNum) throws SQLException {

                            try {
                                JSONObject data = new JSONObject();
                                JSONArray labels = new JSONArray();
                                labels.put("Total Successful");
                                labels.put("Total Failed");
                                labels.put("Total Pending");
                                labels.put("Total Undetermined");
                                data.put("labels", labels);

                                JSONArray datasets = new JSONArray();
                                JSONObject dataset = new JSONObject();
                                dataset.put("label", "Transactions in last 6 months");

                                JSONArray datasets_color = new JSONArray();
                                datasets_color.put("#3e95cd");
                                datasets_color.put("#eb4034");
                                datasets_color.put("#8e5ea2");
                                datasets_color.put("#f5c011");

                                dataset.put("backgroundColor", datasets_color);

                                JSONArray datasets_data = new JSONArray();
                                datasets_data.put(rs.getInt("successful"));
                                datasets_data.put(rs.getInt("failed"));
                                datasets_data.put(rs.getInt("pending"));
                                datasets_data.put(rs.getInt("undetermined"));

                                dataset.put("data", datasets_data);
                                datasets.put(dataset);

                                data.put("datasets", datasets);

                                // Add options
                                JSONObject options = new JSONObject();
                                JSONObject legend = new JSONObject();
                                legend.put("display", true);
                                options.put("legend", legend);

                                JSONObject title = new JSONObject();
                                title.put("display", true);
                                title.put("text", "Transactions");
                                options.put("options", title);

                                JSONObject chartData = new JSONObject();
                                chartData.put("type", "doughnut");
                                chartData.put("options", options);
                                chartData.put("data", data);

                                return chartData;
                            } catch (JSONException ex) {
                                Logger.getLogger(TransactionsLogController.class.getName())
                                        .log(Level.SEVERE, ex.getMessage(), ex);
                            }
                            return null;
                        }
                    };

            // ResultSet rs;
            List<JSONObject> listr = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");

            for (JSONObject us : listr) {
                resJson.put("chartData", us);
            }

            return resJson.toString();

        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/getDashboardDetailsTxVolumes")
    public String getDashboardDetailsTxVolumes(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            User sessionUser;

            if (session.getAttribute("user") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (User) session.getAttribute("user");
            // Get the first details

            // Check permissions
            /*if (!Common.isUserAllowedAccessToThis("ACCESS_TRANSACTION_LOG", sessionUser)) {
                return GeneralException
                    .getError("110", GeneralException.ERRORS_110);
            }*/

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            /*JSONObject sObject = new JSONObject(requestBody);
            String pageSize = sObject.getString("pageSize");
            String currentPage = sObject.isNull("currentPage") ? "" : sObject.getString("currentPage");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");*/

            LocalDateTime now = LocalDateTime.now();
            // Audit F6: bound as a native java.sql.Timestamp via a bind parameter, not a
            // Java-formatted string spliced into the SQL text - the previous
            // "BETWEEN '"+start_date+"'" form worked only by luck (MySQL happening to coerce a
            // well-formed literal) and had no protection against a malformed/misformatted value
            // silently mis-comparing instead of erroring.
            java.sql.Timestamp end_date = java.sql.Timestamp.valueOf(now);
            LocalDateTime start_ = now.minusMonths(6);
            java.sql.Timestamp start_date = java.sql.Timestamp.valueOf(start_);
            parameters.addValue("start_date", start_date);
            parameters.addValue("end_date", end_date);

            String sqlSelect =
                    "SELECT "
                            + " (SELECT SUM(original_amount) "
                            + "     FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + "      WHERE          status='SUCCESSFUL' AND              created_on"
                            + " BETWEEN :start_date AND :end_date) AS successful, (SELECT"
                            + " SUM(original_amount)      FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + "      WHERE          status='FAILED' AND              created_on"
                            + " BETWEEN :start_date AND :end_date) AS failed, (SELECT"
                            + " SUM(original_amount)      FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + "      WHERE          status='PENDING' AND              created_on"
                            + " BETWEEN :start_date AND :end_date) AS pending, (SELECT"
                            + " SUM(original_amount)      FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + "      WHERE          status='UNDETERMINED' AND             "
                            + " created_on BETWEEN :start_date AND :end_date) AS undetermined FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " ";

            RowMapper<JSONObject> rm =
                    new RowMapper<JSONObject>() {
                        public JSONObject mapRow(ResultSet rs, int rowNum) throws SQLException {

                            try {
                                JSONObject data = new JSONObject();
                                JSONArray labels = new JSONArray();
                                labels.put("Total Successful");
                                labels.put("Total Failed");
                                labels.put("Total Pending");
                                labels.put("Total Undetermined");
                                data.put("labels", labels);

                                JSONArray datasets = new JSONArray();
                                JSONObject dataset = new JSONObject();
                                dataset.put("label", "Amounts in last 6 months");

                                JSONArray datasets_color = new JSONArray();
                                datasets_color.put("#3e95cd");
                                datasets_color.put("#eb4034");
                                datasets_color.put("#8e5ea2");
                                datasets_color.put("#f5c011");

                                dataset.put("backgroundColor", datasets_color);

                                JSONArray datasets_data = new JSONArray();
                                datasets_data.put(rs.getInt("successful"));
                                datasets_data.put(rs.getInt("failed"));
                                datasets_data.put(rs.getInt("pending"));
                                datasets_data.put(rs.getInt("undetermined"));

                                dataset.put("data", datasets_data);
                                datasets.put(dataset);

                                data.put("datasets", datasets);

                                // Add options
                                JSONObject options = new JSONObject();
                                JSONObject legend = new JSONObject();
                                legend.put("display", true);
                                options.put("legend", legend);

                                JSONObject title = new JSONObject();
                                title.put("display", true);
                                title.put("text", "Transactions");
                                options.put("options", title);

                                JSONObject chartData = new JSONObject();
                                chartData.put("type", "bar");
                                chartData.put("options", options);
                                chartData.put("data", data);

                                return chartData;
                            } catch (JSONException ex) {
                                Logger.getLogger(TransactionsLogController.class.getName())
                                        .log(Level.SEVERE, ex.getMessage(), ex);
                            }
                            return null;
                        }
                    };

            // ResultSet rs;
            List<JSONObject> listr = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");

            for (JSONObject us : listr) {
                resJson.put("chartData", us);
            }

            return resJson.toString();

        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/getDashboardDetailsTxVolumesMerchant")
    public String getDashboardDetailsTxVolumesMerchant(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            MerchantUser sessionUser;

            if (session.getAttribute("merchantUser") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }

            sessionUser = (MerchantUser) session.getAttribute("merchantUser");
            // Get the first details

            // Check permissions
            /*if (!Common.isUserAllowedAccessToThis("ACCESS_TRANSACTION_LOG", sessionUser)) {
                return GeneralException
                    .getError("110", GeneralException.ERRORS_110);
            }*/

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            /*JSONObject sObject = new JSONObject(requestBody);
            String pageSize = sObject.getString("pageSize");
            String currentPage = sObject.isNull("currentPage") ? "" : sObject.getString("currentPage");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");*/

            LocalDateTime now = LocalDateTime.now();
            // Audit F6: bound as a native java.sql.Timestamp via a bind parameter, not a
            // Java-formatted string spliced into the SQL text - the previous
            // "BETWEEN '"+start_date+"'" form worked only by luck (MySQL happening to coerce a
            // well-formed literal) and had no protection against a malformed/misformatted value
            // silently mis-comparing instead of erroring.
            java.sql.Timestamp end_date = java.sql.Timestamp.valueOf(now);
            LocalDateTime start_ = now.minusMonths(6);
            java.sql.Timestamp start_date = java.sql.Timestamp.valueOf(start_);
            parameters.addValue("start_date", start_date);
            parameters.addValue("end_date", end_date);

            String sqlSelect =
                    "SELECT "
                            + " (SELECT SUM(original_amount) "
                            + "     FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE merchant_id='"
                            + sessionUser.getMerchant_id()
                            + "' AND          status='SUCCESSFUL' AND              created_on"
                            + " BETWEEN :start_date AND :end_date) AS successful, (SELECT"
                            + " SUM(original_amount)      FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE merchant_id='"
                            + sessionUser.getMerchant_id()
                            + "' AND          status='FAILED' AND              created_on BETWEEN"
                            + " :start_date AND :end_date) AS failed, (SELECT SUM(original_amount) "
                            + "     FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE merchant_id='"
                            + sessionUser.getMerchant_id()
                            + "' AND          status='PENDING' AND              created_on BETWEEN"
                            + " :start_date AND :end_date) AS pending, (SELECT SUM(original_amount)"
                            + "      FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + "     WHERE  merchant_id='"
                            + sessionUser.getMerchant_id()
                            + "' AND          status='UNDETERMINED' AND              created_on"
                            + " BETWEEN :start_date AND :end_date) AS undetermined FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " ";

            RowMapper<JSONObject> rm =
                    new RowMapper<JSONObject>() {
                        public JSONObject mapRow(ResultSet rs, int rowNum) throws SQLException {

                            try {
                                JSONObject data = new JSONObject();
                                JSONArray labels = new JSONArray();
                                labels.put("Total Successful");
                                labels.put("Total Failed");
                                labels.put("Total Pending");
                                labels.put("Total Undetermined");
                                data.put("labels", labels);

                                JSONArray datasets = new JSONArray();
                                JSONObject dataset = new JSONObject();
                                dataset.put("label", "Amounts in last 6 months");

                                JSONArray datasets_color = new JSONArray();
                                datasets_color.put("#3e95cd");
                                datasets_color.put("#eb4034");
                                datasets_color.put("#8e5ea2");
                                datasets_color.put("#f5c011");

                                dataset.put("backgroundColor", datasets_color);

                                JSONArray datasets_data = new JSONArray();
                                datasets_data.put(rs.getInt("successful"));
                                datasets_data.put(rs.getInt("failed"));
                                datasets_data.put(rs.getInt("pending"));
                                datasets_data.put(rs.getInt("undetermined"));

                                dataset.put("data", datasets_data);
                                datasets.put(dataset);

                                data.put("datasets", datasets);

                                // Add options
                                JSONObject options = new JSONObject();
                                JSONObject legend = new JSONObject();
                                legend.put("display", true);
                                options.put("legend", legend);

                                JSONObject title = new JSONObject();
                                title.put("display", true);
                                title.put("text", "Transactions");
                                options.put("options", title);

                                JSONObject chartData = new JSONObject();
                                chartData.put("type", "bar");
                                chartData.put("options", options);
                                chartData.put("data", data);

                                return chartData;
                            } catch (JSONException ex) {
                                Logger.getLogger(TransactionsLogController.class.getName())
                                        .log(Level.SEVERE, ex.getMessage(), ex);
                            }
                            return null;
                        }
                    };

            // ResultSet rs;
            List<JSONObject> listr = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");

            for (JSONObject us : listr) {
                resJson.put("chartData", us);
            }

            return resJson.toString();

        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    // Audit G1: distributed lock (net.citotech.cito.config.SchedulerLockConfig) backed by the
    // `shedlock` DB table (V29__shedlock.sql) - this method runs real payout/status-check ledger
    // postings, so it must only be triggered by the scheduler and must not expose a direct HTTP
    // entry point that could be called repeatedly. lockAtMostFor=15m is a generous crash-recovery
    // ceiling:
    // this loop processes up to 100 PENDING/UNDETERMINED transactions per run, each doing one
    // synchronous gateway status-check HTTP call (worst case ~90s per call given
    // Common.HTTP_REQUEST_TIMEOUT_MILLISECONDS=30s connect /
    // HTTP_REQUEST_READTIMEOUT_MILLISECONDS=60s
    // read), so 15 minutes safely covers a normal run while still releasing the lock well before
    // an actually-crashed instance would otherwise starve the queue indefinitely. lockAtLeastFor=1m
    // matches the 60s fixedDelay so a fast run can't have its lock immediately re-acquired by the
    // same instance's next tick (or grabbed by another instance a moment later due to minor clock
    // skew) before the configured interval has actually elapsed.
    //
    // The pre-existing local java.nio.channels.FileLock below (guarding
    // Common.CLASS_PATH_CHECK_TX_LOCK) is kept as a defense-in-depth layer rather than removed:
    // ShedLock is now the authoritative cross-instance guard (this method won't even be entered
    // concurrently once the lock is held, since @SchedulerLock's AOP advice wraps the whole call),
    // so the file lock becomes largely redundant under normal operation, but leaving it costs
    // nothing and still catches same-JVM overlap if ShedLock's DB lock were ever unavailable.
    // Rewriting this method's dozen mid-method `lock.release(); writer.close();` early-return
    // points to remove it would be a much larger, higher-risk change to this money-movement path
    // for no real benefit, so it stays as-is.
    @Scheduled(fixedDelay = 60000, initialDelay = 1000)
    @SchedulerLock(name = "testCheckstatusCron", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public String testCheckstatusCron(/*@RequestBody String requestBody,
            HttpServletRequest request, HttpServletResponse response*/ ) {
        // Set the response header

        String filePath = lockfiledirectory + Common.CLASS_PATH_CHECK_TX_LOCK;
        Logger.getLogger(TransactionsLogController.class.getName())
                .log(Level.FINE, "LockFile " + filePath);

        try {

            RandomAccessFile writer = new RandomAccessFile(filePath, "rw");

            File lfile = new File(filePath);
            if (lfile.createNewFile()) {
                Logger.getLogger(TransactionsLogController.class.getName())
                        .log(Level.FINE, "File " + filePath + " has been created.");
            }

            FileLock lock = writer.getChannel().lock();
            writer.write("Am handling lock!".getBytes());

            // First check if stock|revenew|suspense accounts were configured transaction
            Setting getStockAccount = Common.getSettings("float_stock_account", jdbcTemplate);
            if (getStockAccount == null || getStockAccount.getSetting_value().isEmpty()) {
                // release lock
                lock.release();
                writer.close();
                return GeneralException.getError("112", GeneralException.ERRORS_112);
            }

            Setting getRevenueAccount = Common.getSettings("revenue_account", jdbcTemplate);
            if (getRevenueAccount == null || getStockAccount.getSetting_value().isEmpty()) {
                // release lock
                lock.release();
                writer.close();
                return GeneralException.getError("117", GeneralException.ERRORS_117);
            }

            Setting getSuspenseAccount = Common.getSettings("suspense_account", jdbcTemplate);
            if (getSuspenseAccount == null || getStockAccount.getSetting_value().isEmpty()) {
                // release lock
                lock.release();
                writer.close();
                return GeneralException.getError("127", GeneralException.ERRORS_127);
            }

            // Now get Stock account
            String stock_account_number = getStockAccount.getSetting_value().trim();
            Merchant float_stock_account =
                    Common.getMerchantByAccountNumber(stock_account_number, jdbcTemplate);

            // Now get Revenue account
            String revenue_account_number = getRevenueAccount.getSetting_value().trim();
            Merchant revenue_stock_account =
                    Common.getMerchantByAccountNumber(revenue_account_number, jdbcTemplate);

            // suspense_account
            String suspense_account_number = getSuspenseAccount.getSetting_value().trim();
            Merchant suspense_stock_account =
                    Common.getMerchantByAccountNumber(suspense_account_number, jdbcTemplate);

            MapSqlParameterSource parameters = new MapSqlParameterSource();
            String sqlSelect =
                    "SELECT *  FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + " WHERE status IN ('PENDING','UNDETERMINED') LIMIT 100 FOR UPDATE";

            String sql_update =
                    " UPDATE "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " "
                            + " SET status=:status, tx_update_trace=:tx_update_trace, "
                            + " tx_gateway_ref=:tx_gateway_ref ";
            RowMapper<Transaction> rm = Common.getTransactionRowMapper();
            // ResultSet rs;
            List<Transaction> pendingTransactions = jdbcTemplate.query(sqlSelect, parameters, rm);

            Logger.getLogger(TransactionsLogController.class.getName())
                    .log(Level.FINE, "Checking status for " + pendingTransactions.size() + " TXs");

            for (Transaction tx : pendingTransactions) {
                // First check for the status of this transaction

                DoPayGateway gwChargingDetails = new DoPayGateway();

                String tx_type = "";
                if (tx.getTx_type().equals(Transaction.TX_TYPE_PAYIN)) {
                    tx_type = "collection";
                } else {
                    tx_type = "disbursement";
                }

                String txRef =
                        tx.getGateway_id().equals(SafariComPaymentGateway.getGatewayId())
                                ? tx.getTx_gateway_ref()
                                : tx.getTx_unique_id();

                if (tx.getGateway_id().equals(SafariComPaymentGateway.getGatewayId())
                        && tx.getTx_type().equals("PAYOUT")) {
                    txRef = tx.getSafaricomRequestReference();
                    // For now let's not check Tx status for Safaricom PAYOUT
                    continue;
                }

                Logger.getLogger(AuthenticationController.class.getName())
                        .log(
                                Level.SEVERE,
                                "SAFARICOM REFERENCE ID: " + tx.getSafaricomRequestReference(),
                                "");

                GateWayResponse txUpdatedDetails =
                        gwChargingDetails.runPayGatewayDoCheckStatus(
                                jdbcTemplate,
                                tx.getGateway_id(),
                                txRef,
                                tx_type,
                                Long.parseLong(tx.getMerchant_id()));

                if (txUpdatedDetails != null) {

                    if (txUpdatedDetails.getTransactionStatus().isEmpty()) {
                        Logger.getLogger(TransactionsLogController.class.getName())
                                .log(
                                        Level.SEVERE,
                                        "Empty Tx Status: " + txUpdatedDetails.getRequestTrace(),
                                        "");
                        continue;
                    }

                    MapSqlParameterSource parameters_ = new MapSqlParameterSource();
                    tx.setTx_update_trace(txUpdatedDetails.getRequestTrace());
                    tx.setStatus(txUpdatedDetails.getTransactionStatus());
                    tx.setTx_gateway_ref(txUpdatedDetails.getNetworkId());

                    final String sql_update_final = sql_update + " WHERE id=:id";
                    parameters_.addValue("id", tx.getId());
                    parameters_.addValue("tx_update_trace", tx.getTx_update_trace());
                    parameters_.addValue("status", tx.getStatus());
                    parameters_.addValue("tx_gateway_ref", tx.getTx_gateway_ref());

                    TransactionTemplate template = new TransactionTemplate(transactionManager);
                    String result =
                            template.execute(
                                    new TransactionCallback<String>() {
                                        @Override
                                        public String doInTransaction(TransactionStatus status) {
                                            try {
                                                jdbcTemplate.update(sql_update_final, parameters_);
                                                return "success";
                                            } catch (Exception e) {
                                                // transactionManager.rollback(status);
                                                status.setRollbackOnly();
                                                Logger.getLogger(
                                                                AuthenticationController.class
                                                                        .getName())
                                                        .log(
                                                                Level.SEVERE,
                                                                "INTERNAL ERROR: " + e.getMessage(),
                                                                "");
                                                return GeneralException.getError(
                                                        "102", GeneralException.ERRORS_102);
                                            }
                                        }
                                    });

                    if (result.equals("success")) {
                        Merchant merchant =
                                Common.getMerchantById(tx.getMerchant_id(), jdbcTemplate);

                        // If the transaction SUCCEEDED, then CREDIT THE CUSTOMER'S ACCOUNT
                        if (txUpdatedDetails.getTransactionStatus().equals("SUCCESSFUL")) {

                            // Send callback request on another thread
                            if (!tx.getCallback_url().isEmpty()) {

                                TxCallback txCallback = new TxCallback(tx, merchant);
                                txCallback.start(jdbcTemplate, transactionManager);
                            }

                            // Record this transaction
                            String[] bType = Balance.getBalanceTypeByGatewayId(tx.getGateway_id());
                            String balance_type = bType[0];

                            Statement newTx = new Statement();

                            // Record the charge and update stock and revenue account
                            if (tx.getTx_type().equals(Transaction.TX_TYPE_PAYIN)) {
                                // Credit this customer's account.
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setAmount(tx.getOriginal_amount());
                                newTx.setGateway_id(tx.getGateway_id());
                                newTx.setNarritive(tx.getTx_type());
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setMerchant_id(Long.parseLong(tx.getMerchant_id()));
                                newTx.setDescription(tx.getTx_description());
                                newTx.setRecorded_by("SYSTEM");
                                newTx.setTx_type("CR");

                                result = recordStatementTx(newTx, balance_type);
                                if (!result.equals("success")) {
                                    // release lock
                                    lock.release();
                                    writer.close();
                                    return result;
                                }

                                newTx = new Statement();
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setAmount(tx.getCharges());
                                newTx.setGateway_id(tx.getGateway_id());

                                newTx.setNarritive(Transaction.TX_TYPE_PAYIN_CHARGE);
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setMerchant_id(merchant.getId());
                                newTx.setDescription(tx.getTx_description());
                                newTx.setRecorded_by("SYSTEM");
                                newTx.setTx_type("DR");

                                result = recordStatementTx(newTx, balance_type);
                                if (!result.equals("success")) {
                                    // release lock
                                    lock.release();
                                    writer.close();
                                    return result;
                                }

                                // Now record this revenue account.
                                newTx = new Statement();
                                newTx.setAmount(tx.getCharges());
                                newTx.setGateway_id(tx.getGateway_id());

                                newTx.setNarritive(Transaction.TX_TYPE_PAYIN_REVENUE);
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setMerchant_id(revenue_stock_account.getId());
                                newTx.setDescription(tx.getTx_description());
                                newTx.setRecorded_by("SYSTEM");
                                newTx.setTx_type("CR");
                                result = recordStatementTx(newTx, balance_type);

                                if (!result.equals("success")) {
                                    // release lock
                                    lock.release();
                                    writer.close();
                                    return result;
                                }

                                // Now increase stock account.
                                newTx = new Statement();
                                newTx.setAmount(tx.getOriginal_amount());
                                newTx.setGateway_id(tx.getGateway_id());

                                newTx.setNarritive(Transaction.TX_TYPE_PAYIN);
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setMerchant_id(float_stock_account.getId());
                                newTx.setDescription(tx.getTx_description());
                                newTx.setRecorded_by("SYSTEM");
                                newTx.setTx_type("CR");
                                result = recordStatementTx(newTx, balance_type);

                                if (!result.equals("success")) {
                                    // release lock
                                    lock.release();
                                    writer.close();
                                    return result;
                                }
                            } else if (tx.getTx_type().equals(Transaction.TX_TYPE_PAYOUT)) {
                                // Record a settlement transaction for Payout
                                newTx = new Statement();
                                newTx.setAmount(tx.getOriginal_amount());
                                newTx.setGateway_id(tx.getGateway_id());

                                newTx.setNarritive(Transaction.TX_TYPE_PAYOUT_SETTLEMENT);
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setMerchant_id(suspense_stock_account.getId());
                                newTx.setDescription(tx.getTx_description());
                                newTx.setRecorded_by("SYSTEM");
                                newTx.setTx_type("DR");
                                result = recordStatementTx(newTx, balance_type);

                                if (!result.equals("success")) {
                                    // release lock
                                    lock.release();
                                    writer.close();
                                    return result;
                                }

                                // Record a settlement transaction for Payout charge
                                newTx = new Statement();
                                newTx.setAmount(tx.getCharges());
                                newTx.setGateway_id(tx.getGateway_id());

                                newTx.setNarritive(Transaction.TX_TYPE_PAYOUT_CHARGE_SETTLEMENT);
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setMerchant_id(suspense_stock_account.getId());
                                newTx.setDescription(tx.getTx_description());
                                newTx.setRecorded_by("SYSTEM");
                                newTx.setTx_type("DR");
                                result = recordStatementTx(newTx, balance_type);

                                if (!result.equals("success")) {
                                    // release lock
                                    lock.release();
                                    writer.close();
                                    return result;
                                }

                                // Record Revenue to revenue account
                                newTx = new Statement();
                                newTx.setAmount(tx.getCharges());
                                newTx.setGateway_id(tx.getGateway_id());

                                newTx.setNarritive(Transaction.TX_TYPE_PAYOUT_REVENUE);
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setMerchant_id(revenue_stock_account.getId());
                                newTx.setDescription(tx.getTx_description());
                                newTx.setRecorded_by("SYSTEM");
                                newTx.setTx_type("CR");
                                result = recordStatementTx(newTx, balance_type);

                                if (!result.equals("success")) {
                                    // release lock
                                    lock.release();
                                    writer.close();
                                    return result;
                                }
                            }
                        } else if (txUpdatedDetails.getTransactionStatus().equals("FAILED")) {

                            // Send callback request asynchronously
                            if (!tx.getCallback_url().isEmpty()) {
                                ManagedAsyncTasks.run(
                                        "txController-failed-callback-" + tx.getId(),
                                        () -> {
                                            String amountToSign = tx.getOriginal_amount() + "";
                                            String signedData =
                                                    tx.getPayer_number()
                                                            + amountToSign
                                                            + tx.getCreated_on()
                                                            + tx.getTx_merchant_ref()
                                                            + tx.getStatus()
                                                            + tx.getTx_merchant_description()
                                                            + tx.getTx_gateway_ref();

                                            /*
                                            String signedData = tx.getPayer_number()+tx.getOriginal_amount()
                                                    +tx.getCreated_on()+tx.getTx_merchant_ref()+tx.getStatus()
                                                    +tx.getTx_merchant_description()+tx.getTx_gateway_ref();
                                            */

                                            if (merchant.getPublic_key() == null
                                                    || merchant.getPublic_key().isEmpty()) {
                                                return;
                                            }
                                            try {
                                                // Now verify signature.
                                                Signature sign =
                                                        Signature.getInstance("SHA256withRSA");
                                                String base64_private_key =
                                                        merchant.getPrivate_key();
                                                base64_private_key =
                                                        base64_private_key.replace(
                                                                "-----BEGIN PRIVATE KEY-----\n",
                                                                "");
                                                String base64_cleaned =
                                                        base64_private_key.replace(
                                                                "\n-----END PRIVATE KEY-----\n",
                                                                "");

                                                PrivateKey privateKey =
                                                        Common.getPrivateKeyFromBase64String(
                                                                base64_cleaned);
                                                sign.initSign(privateKey);
                                                sign.update(signedData.getBytes());
                                                byte[] digitalSignature = sign.sign();
                                                JSONObject jObject = new JSONObject();
                                                jObject.put("amount", amountToSign);
                                                jObject.put("payer_number", tx.getPayer_number());
                                                jObject.put("reference", tx.getTx_merchant_ref());
                                                jObject.put("network_ref", tx.getTx_gateway_ref());
                                                jObject.put("status", tx.getStatus());
                                                jObject.put(
                                                        "description",
                                                        tx.getTx_merchant_description());
                                                jObject.put("completed_on", tx.getUpdated_on());
                                                jObject.put("created_on", tx.getCreated_on());
                                                jObject.put(
                                                        "signature",
                                                        Base64.getEncoder()
                                                                .encodeToString(digitalSignature));
                                                String requestData = jObject.toString();
                                                String url = tx.getCallback_url();
                                                // Now make the callback request.
                                                Map<String, String> headers = new HashMap<>();
                                                headers.put("Content-Type", "application/json");

                                                HttpRequestResponse rs =
                                                        Common.doHttpRequest(
                                                                "POST", url, requestData, headers);
                                                if (rs != null) {
                                                    String failedCbTraceSql =
                                                            sql_update
                                                                    + ", callback_trace=:callback_trace"
                                                                    + "  WHERE id=:id";
                                                    MapSqlParameterSource failedCbTraceParams =
                                                            new MapSqlParameterSource();
                                                    failedCbTraceParams.addValue("id", tx.getId());
                                                    failedCbTraceParams.addValue(
                                                            "tx_update_trace",
                                                            tx.getTx_update_trace());
                                                    failedCbTraceParams.addValue(
                                                            "status", tx.getStatus());
                                                    failedCbTraceParams.addValue(
                                                            "tx_gateway_ref",
                                                            tx.getTx_gateway_ref());
                                                    failedCbTraceParams.addValue(
                                                            "callback_trace", rs.toString());

                                                    // Now update the trace of this transaction.
                                                    String failedCbTraceResult =
                                                            template.execute(
                                                                    new TransactionCallback<
                                                                            String>() {
                                                                        @Override
                                                                        public String
                                                                                doInTransaction(
                                                                                        TransactionStatus
                                                                                                status) {
                                                                            try {
                                                                                jdbcTemplate.update(
                                                                                        failedCbTraceSql,
                                                                                        failedCbTraceParams);
                                                                                return "success";
                                                                            } catch (Exception e) {
                                                                                // transactionManager.rollback(status);
                                                                                status
                                                                                        .setRollbackOnly();
                                                                                Logger.getLogger(
                                                                                                AuthenticationController
                                                                                                        .class
                                                                                                        .getName())
                                                                                        .log(
                                                                                                Level
                                                                                                        .SEVERE,
                                                                                                "INTERNAL"
                                                                                                    + " ERROR:"
                                                                                                    + " "
                                                                                                        + e
                                                                                                                .getMessage(),
                                                                                                "");
                                                                                return GeneralException
                                                                                        .getError(
                                                                                                "102",
                                                                                                GeneralException
                                                                                                        .ERRORS_102);
                                                                            }
                                                                        }
                                                                    });
                                                    Logger.getLogger(
                                                                    TransactionsLogController.class
                                                                            .getName())
                                                            .log(
                                                                    Level.SEVERE,
                                                                    "Callback Results: "
                                                                            + failedCbTraceResult,
                                                                    "");
                                                }

                                            } catch (NoSuchAlgorithmException ex) {
                                                Logger.getLogger(
                                                                TransactionsLogController.class
                                                                        .getName())
                                                        .log(Level.SEVERE, null, ex);
                                            } catch (InvalidKeyException ex) {
                                                Logger.getLogger(
                                                                TransactionsLogController.class
                                                                        .getName())
                                                        .log(Level.SEVERE, null, ex);
                                            } catch (SignatureException ex) {
                                                Logger.getLogger(
                                                                TransactionsLogController.class
                                                                        .getName())
                                                        .log(Level.SEVERE, null, ex);
                                            } catch (JSONException ex) {
                                                Logger.getLogger(
                                                                TransactionsLogController.class
                                                                        .getName())
                                                        .log(Level.SEVERE, null, ex);
                                            }
                                        });
                            }

                            // If it's a payout, reverse the money.
                            Statement newTx = new Statement();
                            String[] bType = Balance.getBalanceTypeByGatewayId(tx.getGateway_id());
                            String balance_type = bType[0];
                            if (tx.getTx_type().equals(Transaction.TX_TYPE_PAYOUT)) {
                                // Dr the amount
                                newTx = new Statement();
                                newTx.setAmount(tx.getOriginal_amount());
                                newTx.setGateway_id(tx.getGateway_id());

                                newTx.setNarritive(Transaction.TX_TYPE_PAYOUT_REVERSAL);
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setMerchant_id(suspense_stock_account.getId());
                                newTx.setDescription(tx.getTx_description());
                                newTx.setRecorded_by("SYSTEM");
                                newTx.setTx_type("DR");
                                result = recordStatementTx(newTx, balance_type);

                                if (!result.equals("success")) {
                                    // release lock
                                    lock.release();
                                    writer.close();
                                    return result;
                                }

                                // DR the charge reversal
                                newTx = new Statement();
                                newTx.setAmount(tx.getCharges());
                                newTx.setGateway_id(tx.getGateway_id());

                                newTx.setNarritive(Transaction.TX_TYPE_PAYOUT_CHARGE_REVERSAL);
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setMerchant_id(suspense_stock_account.getId());
                                newTx.setDescription(tx.getTx_description());
                                newTx.setRecorded_by("SYSTEM");
                                newTx.setTx_type("DR");
                                result = recordStatementTx(newTx, balance_type);

                                if (!result.equals("success")) {
                                    // release lock
                                    lock.release();
                                    writer.close();
                                    return result;
                                }

                                // CR the amount back to customer's account
                                newTx = new Statement();
                                newTx.setAmount(tx.getOriginal_amount());
                                newTx.setGateway_id(tx.getGateway_id());

                                newTx.setNarritive(Transaction.TX_TYPE_PAYOUT_REVERSAL);
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setMerchant_id(merchant.getId());
                                newTx.setDescription(tx.getTx_description());
                                newTx.setRecorded_by("SYSTEM");
                                newTx.setTx_type("CR");
                                result = recordStatementTx(newTx, balance_type);

                                if (!result.equals("success")) {
                                    // release lock
                                    lock.release();
                                    writer.close();
                                    return result;
                                }

                                // CR the charge back on customer's account
                                newTx = new Statement();
                                newTx.setAmount(tx.getCharges());
                                newTx.setGateway_id(tx.getGateway_id());

                                newTx.setNarritive(Transaction.TX_TYPE_PAYOUT_CHARGE_REVERSAL);
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setMerchant_id(merchant.getId());
                                newTx.setDescription(tx.getTx_description());
                                newTx.setRecorded_by("SYSTEM");
                                newTx.setTx_type("CR");
                                result = recordStatementTx(newTx, balance_type);

                                if (!result.equals("success")) {
                                    // release lock
                                    lock.release();
                                    writer.close();
                                    return result;
                                }

                                // Restore the float account
                                newTx = new Statement();
                                newTx.setAmount(tx.getOriginal_amount());
                                newTx.setGateway_id(tx.getGateway_id());

                                newTx.setNarritive(Transaction.TX_TYPE_PAYOUT_REVERSAL);
                                newTx.setTransactions_log_id(tx.getId());
                                newTx.setMerchant_id(float_stock_account.getId());
                                newTx.setDescription(tx.getTx_description());
                                newTx.setRecorded_by("SYSTEM");
                                newTx.setTx_type("CR");
                                result = recordStatementTx(newTx, balance_type);

                                if (!result.equals("success")) {
                                    // release lock
                                    lock.release();
                                    writer.close();
                                    return result;
                                }
                            }
                        }
                        continue;
                    } else {
                        // release lock
                        lock.release();
                        // close the file
                        writer.close();
                        return result;
                    }
                }
            }

            // release lock
            lock.release();
            // close the file
            writer.close();
        } catch (IOException ex) {
            Logger.getLogger(TransactionsLogController.class.getName())
                    .log(
                            Level.SEVERE,
                            "HANDLING_INITIAL_PROCESS IOException:" + ex.getMessage(),
                            ex);
            return GeneralException.getError("107", GeneralException.ERRORS_107);
        } catch (java.nio.channels.OverlappingFileLockException ex) {
            Logger.getLogger(AuthenticationController.class.getName())
                    .log(
                            Level.SEVERE,
                            "HANDLING_INITIAL_PROCESS OverlappingFileLockException: "
                                    + ex.getMessage(),
                            "");

            return "OverlappingFileLockException";
        }

        // Execution successfully.
        return GeneralSuccessResponse.getMessage("000", GeneralSuccessResponse.SUCCESS_000);
    }

    public String recordAfterTx() {
        return "";
    }

    /*
     * API to add a new admin to the database
     */
    @PostMapping(path = "/recordTransaction")
    public String recordTransaction(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        try {

            if (!isLoggedIn(request)) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            HttpSession session = request.getSession();

            User sessionUser = (User) session.getAttribute("user");

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("CREDIT_MERCHANT", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            JSONObject sObject = new JSONObject(requestBody);
            Double amount = sObject.getDouble("amount");
            String description = sObject.getString("description");
            Long merchant_id = sObject.getLong("merchant_id");
            String balance_type = sObject.getString("balance_type");
            String tx_type = sObject.getString("tx_type");
            // First check if stock account was configured transaction
            if (tx_type.equals("FLOAT STOCK CREDIT") || tx_type.equals("FLOAT STOCK DEBIT")) {
                Setting getStockAccount = Common.getSettings("float_stock_account", jdbcTemplate);
                if (getStockAccount == null || getStockAccount.getSetting_value().isEmpty()) {
                    return GeneralException.getError("112", GeneralException.ERRORS_112);
                }

                // Check if this the right stock account
                Merchant merchant = Common.getMerchantById(merchant_id + "", jdbcTemplate);
                if (merchant == null) {
                    return GeneralException.getError(
                            "109",
                            String.format(GeneralException.ERRORS_109, "Merchant", merchant_id));
                }

                // Now check that this is float account that should be credited or debited
                String stock_account_number = getStockAccount.getSetting_value().trim();
                if (!merchant.getAccount_number().equals(stock_account_number)) {
                    return GeneralException.getError("113", GeneralException.ERRORS_113);
                }
            }

            // Get this merchant by id.
            Statement newTx = new Statement();

            newTx.setDescription(description);
            newTx.setAmount(amount);
            if (balance_type.equals("mtnmm_balance")) {
                newTx.setGateway_id("MTNMoMoPaymentGateway");
            } else if (balance_type.equals("airtelmm_balance")) {
                String use_open_api =
                        Common.getSettings("gw_airtelmoney_use_open_api", jdbcTemplate)
                                .getSetting_value();
                if (use_open_api.equals("yes")) {
                    newTx.setGateway_id("AirtelMoneyOpenApiPaymentGateway");
                } else {
                    newTx.setGateway_id("AirtelMoneyPaymentGateway");
                }
            } else if (balance_type.equals("safaricom_balance")) {
                newTx.setGateway_id("SafariComPaymentGateway");
            } else if (balance_type.equals("sms_balance")) {
                newTx.setGateway_id("SmsGateway");
            }
            newTx.setNarritive(tx_type);
            // String tx_id = Common.generateUuid();
            newTx.setTransactions_log_id(0);
            newTx.setMerchant_id(merchant_id);
            newTx.setDescription(description);
            newTx.setRecorded_by(sessionUser.getEmail() + " - " + sessionUser.getName());
            String type = "";
            ArrayList<String> cr_txs = Transaction.getCreditTxTypes();
            if (cr_txs.contains(tx_type)) {
                type = "CR";
                newTx.setTx_type(type);
            } else {
                type = "DR";
                newTx.setTx_type(type);
            }

            String result =
                    Common.recordStatementTx(newTx, balance_type, jdbcTemplate, transactionManager);

            if (result.equals("success")) {
                Setting getStockAccount = Common.getSettings("float_stock_account", jdbcTemplate);
                String stock_account_number = getStockAccount.getSetting_value().trim();
                Merchant float_stock_account =
                        Common.getMerchantByAccountNumber(stock_account_number, jdbcTemplate);

                // Depending on the type increase or reduce the stock account.
                if (tx_type.equals(Transaction.TX_TYPE_FLOAT_CREDIT)
                        || tx_type.equals(Transaction.TX_TYPE_FLOAT_DEDBIT)) {

                    if (newTx.getTx_type().equals("CR")) {
                        Statement newTxStatement = new Statement();
                        newTxStatement.setAmount(amount);
                        newTxStatement.setGateway_id(newTx.getGateway_id());
                        newTxStatement.setNarritive(Transaction.TX_TYPE_FLOAT_STOCK_CREDIT);
                        newTxStatement.setTransactions_log_id(newTx.getId());
                        newTxStatement.setMerchant_id(float_stock_account.getId());
                        newTxStatement.setDescription(description);
                        newTxStatement.setRecorded_by(
                                sessionUser.getEmail() + " - " + sessionUser.getName());
                        newTxStatement.setTx_type("CR");

                        result =
                                Common.recordStatementTx(
                                        newTxStatement,
                                        balance_type,
                                        jdbcTemplate,
                                        transactionManager);
                        if (!result.equals("success")) {
                            return result;
                        }

                    } else {
                        Statement newTxStatement = new Statement();
                        newTxStatement.setAmount(amount);
                        newTxStatement.setGateway_id(newTx.getGateway_id());
                        newTxStatement.setNarritive(Transaction.TX_TYPE_FLOAT_STOCK_DEBIT);
                        newTxStatement.setTransactions_log_id(newTx.getId());
                        newTxStatement.setMerchant_id(float_stock_account.getId());
                        newTxStatement.setDescription(description);
                        newTxStatement.setRecorded_by(
                                sessionUser.getEmail() + " - " + sessionUser.getName());
                        newTxStatement.setTx_type("DR");

                        result =
                                Common.recordStatementTx(
                                        newTxStatement,
                                        balance_type,
                                        jdbcTemplate,
                                        transactionManager);
                        if (!result.equals("success")) {
                            return result;
                        }
                    }
                }

                return GeneralSuccessResponse.getMessage("000", GeneralSuccessResponse.SUCCESS_000);

            } else {
                return result;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            Logger.getLogger(AuthenticationController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to add a new admin to the database
     */
    @PostMapping(path = "/buySms")
    public String buySms(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        try {

            if (!isMerchantUserLoggedIn(request)) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            HttpSession session = request.getSession();

            MerchantUser sessionUser = (MerchantUser) session.getAttribute("merchantUser");

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("SEND_SMS", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            JSONObject sObject = new JSONObject(requestBody);
            Double amount = sObject.getDouble("amount");
            Long merchant_id = sessionUser.getMerchant_id();
            String balance_type = sObject.getString("balance_type");
            String tx_type = "SMS PURCHASE";

            // First get the SMS revenue account
            Setting getSmsRevenueAccount = Common.getSettings("sms_revenue_account", jdbcTemplate);
            if (getSmsRevenueAccount == null || getSmsRevenueAccount.getSetting_value().isEmpty()) {
                return GeneralException.getError("133", GeneralException.ERRORS_133);
            }

            String sms_revenue_account = getSmsRevenueAccount.getSetting_value();
            Merchant smsRevenueMerchantAccount =
                    Common.getMerchantByAccountNumber(sms_revenue_account, jdbcTemplate);
            if (smsRevenueMerchantAccount == null) {
                return GeneralException.getError("133", GeneralException.ERRORS_133);
            }

            // Check the merchant has enough account balance.

            ArrayList<Balance> balances =
                    Common.getMerchantBalances(sessionUser.getMerchant_id() + "", jdbcTemplate);

            // You can't use SMS balance to buy SMS.
            if (balance_type.equals("sms_balance")) {
                return GeneralException.getError("134", GeneralException.ERRORS_134);
            }
            String gateway_id = "";
            for (Balance b : balances) {
                String[] bal_type = b.getBalance_type();

                if (bal_type[0].equals(balance_type)) {
                    // Then check the balance amount is enough
                    if (b.getAmount() < amount) {
                        return GeneralException.getError(
                                "111",
                                String.format(
                                        GeneralException.ERRORS_111, b.getAmount(), bal_type[1]));
                    }
                    gateway_id = b.getGateway_id();
                    break;
                }
            }

            final String gateway_id_final = gateway_id;
            final String balance_type_final = balance_type;

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            String result =
                    template.execute(
                            new TransactionCallback<String>() {
                                @Override
                                public String doInTransaction(TransactionStatus status) {
                                    String result = "";
                                    try {
                                        // First debit this merchant's account on this balance

                                        Statement newTxStatement = new Statement();
                                        newTxStatement.setAmount(amount);
                                        newTxStatement.setGateway_id(gateway_id_final);
                                        newTxStatement.setNarritive(
                                                Transaction.TX_TYPE_SMS_PURCHASE);
                                        // newTxStatement.setTransactions_log_id(newTx.getId());
                                        newTxStatement.setMerchant_id(merchant_id);
                                        newTxStatement.setDescription("SMS Credit Purchase");
                                        newTxStatement.setRecorded_by("SYSTEM");
                                        newTxStatement.setTx_type("DR");

                                        result =
                                                Common.recordStatementTxWithoutTransaction(
                                                        newTxStatement,
                                                        balance_type_final,
                                                        jdbcTemplate,
                                                        transactionManager,
                                                        status);
                                        if (!result.equals("success")) {
                                            return result;
                                        }

                                        // Credit the merchant's SMS account
                                        newTxStatement = new Statement();
                                        newTxStatement.setAmount(amount);
                                        newTxStatement.setGateway_id(SmsGateway.getGatewayId());
                                        newTxStatement.setNarritive(
                                                Transaction.TX_TYPE_SMS_PURCHASE);
                                        // newTxStatement.setTransactions_log_id(newTx.getId());
                                        newTxStatement.setMerchant_id(merchant_id);
                                        newTxStatement.setDescription("SMS Credit Purchase");
                                        newTxStatement.setRecorded_by("SYSTEM");
                                        newTxStatement.setTx_type("CR");

                                        result =
                                                Common.recordStatementTxWithoutTransaction(
                                                        newTxStatement,
                                                        SmsGateway.BALANCE_TYPE,
                                                        jdbcTemplate,
                                                        transactionManager,
                                                        status);
                                        if (!result.equals("success")) {
                                            return result;
                                        }

                                        // Record the SMS revenue in the SMS revenue collection
                                        // account
                                        // Credit the merchant's SMS account
                                        newTxStatement = new Statement();
                                        newTxStatement.setAmount(amount);
                                        newTxStatement.setGateway_id(gateway_id_final);
                                        newTxStatement.setNarritive(
                                                Transaction.TX_TYPE_SMS_PURCHASE);
                                        // newTxStatement.setTransactions_log_id(newTx.getId());
                                        newTxStatement.setMerchant_id(
                                                smsRevenueMerchantAccount.getId());
                                        newTxStatement.setDescription("SMS Credit Purchase");
                                        newTxStatement.setRecorded_by("SYSTEM");
                                        newTxStatement.setTx_type("CR");

                                        result =
                                                Common.recordStatementTxWithoutTransaction(
                                                        newTxStatement,
                                                        balance_type_final,
                                                        jdbcTemplate,
                                                        transactionManager,
                                                        status);
                                        if (!result.equals("success")) {
                                            return result;
                                        }

                                        return "success";
                                    } catch (Exception e) {
                                        // transactionManager.rollback(status);
                                        status.setRollbackOnly();
                                        Logger.getLogger(AuthenticationController.class.getName())
                                                .log(
                                                        Level.SEVERE,
                                                        "INTERNAL ERROR - SAVING SMS TX: "
                                                                + e.getMessage(),
                                                        "");
                                        return GeneralException.getError(
                                                "102", GeneralException.ERRORS_102);
                                    }
                                }
                            });

            if (result.equals("success")) {

                return GeneralSuccessResponse.getMessage("000", GeneralSuccessResponse.SUCCESS_000);

            } else {
                return result;
            }
        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * @Param String balance_type: This is the balance type, check Common.
     * @Param Statement tx : This is the statement transaction.
     * Returns success | JSON String with errors.
     */
    public String recordStatementTx(Statement tx, String balance_type) {

        // Balance query
        String balanceSql =
                "SELECT * FROM "
                        + Common.DB_TABLE_MERCHANT_STATEMENT
                        + " WHERE merchant_id = :merchant_id "
                        + " ORDER BY id DESC LIMIT 1 "
                        + " FOR UPDATE";

        MapSqlParameterSource parametersBalanceSql = new MapSqlParameterSource();
        parametersBalanceSql.addValue("merchant_id", tx.getMerchant_id());

        // Now add the user to database
        String sql =
                "INSERT INTO "
                        + Common.DB_TABLE_MERCHANT_STATEMENT
                        + " "
                        + " SET `merchant_id`=:merchant_id,"
                        + " `gateway_id`=:gateway_id, "
                        + " `description`=:description,"
                        + " `recorded_by`=:recorded_by,"
                        + " `amount`=:amount,"
                        + " `tx_type`=:tx_type,"
                        + " `narrative`=:narrative,"
                        + " `airtelmm_balance`=:airtelmm_balance,"
                        + " `mtnmm_balance`=:mtnmm_balance";

        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (tx.getTransactions_log_id() > 0) {
            sql += ", transactions_log_id=:transactions_log_id ";
            parameters.addValue("transactions_log_id", tx.getTransactions_log_id());
        }
        parameters.addValue("merchant_id", tx.getMerchant_id());
        parameters.addValue("gateway_id", tx.getGateway_id());
        parameters.addValue("description", tx.getDescription());
        parameters.addValue("amount", tx.getAmount());
        parameters.addValue("tx_type", tx.getTx_type());
        parameters.addValue("narrative", tx.getNarritive());
        parameters.addValue("recorded_by", tx.getRecorded_by());

        final String sql_final = sql;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        String result =
                template.execute(
                        new TransactionCallback<String>() {
                            @Override
                            public String doInTransaction(TransactionStatus status) {
                                try {

                                    RowMapper<Statement> rm_b =
                                            new RowMapper<Statement>() {
                                                public Statement mapRow(ResultSet rs, int rowNum)
                                                        throws SQLException {
                                                    Statement t = new Statement();
                                                    t.setId(rs.getLong("id"));
                                                    t.setAmount(rs.getDouble("amount"));
                                                    t.setAirtelmm_balance(
                                                            rs.getDouble("airtelmm_balance"));
                                                    t.setMtnmm_balance(
                                                            rs.getDouble("mtnmm_balance"));
                                                    t.setCreated_on(rs.getString("created_on"));
                                                    t.setUpdated_on(rs.getString("updated_on"));
                                                    t.setGateway_id(rs.getString("gateway_id"));
                                                    t.setDescription(rs.getString("description"));
                                                    t.setMerchant_id(rs.getLong("merchant_id"));
                                                    t.setNarritive(rs.getString("narrative"));
                                                    t.setTransactions_log_id(
                                                            rs.getLong("transactions_log_id"));
                                                    t.setTx_type(rs.getString("tx_type"));
                                                    return t;
                                                }
                                            };

                                    List<Statement> balanceList =
                                            jdbcTemplate.query(
                                                    balanceSql, parametersBalanceSql, rm_b);
                                    Balance mtn_balance;
                                    Balance airtel_balance;

                                    if (balanceList.size() > 0) {
                                        Statement s = balanceList.get(0);
                                        mtn_balance =
                                                new Balance(
                                                        GatewayBalanceType.MTN_MOMO.label(),
                                                        s.getMtnmm_balance(),
                                                        MTNMoMoPaymentGateway.getGatewayId());
                                        mtn_balance.setBaseCurrency(
                                                GatewayBalanceType.MTN_MOMO.currencyCode());

                                        airtel_balance =
                                                new Balance(
                                                        GatewayBalanceType.AIRTEL_MONEY.label(),
                                                        s.getAirtelmm_balance(),
                                                        AirtelMoneyPaymentGateway.getGatewayId());
                                        airtel_balance.setBaseCurrency(
                                                GatewayBalanceType.AIRTEL_MONEY.currencyCode());
                                    } else {
                                        mtn_balance =
                                                new Balance(
                                                        GatewayBalanceType.MTN_MOMO.label(),
                                                        0.00,
                                                        MTNMoMoPaymentGateway.getGatewayId());
                                        mtn_balance.setBaseCurrency(
                                                GatewayBalanceType.MTN_MOMO.currencyCode());

                                        airtel_balance =
                                                new Balance(
                                                        GatewayBalanceType.AIRTEL_MONEY.label(),
                                                        0.00,
                                                        AirtelMoneyPaymentGateway.getGatewayId());
                                        airtel_balance.setBaseCurrency(
                                                GatewayBalanceType.AIRTEL_MONEY.currencyCode());
                                    }

                                    // New balance
                                    if (tx.getTx_type().contains("CR")) {
                                        if (balance_type.equals("mtnmm_balance")) {
                                            Double nBalance =
                                                    tx.getAmount() + mtn_balance.getAmount();
                                            parameters.addValue("mtnmm_balance", nBalance);
                                            parameters.addValue(
                                                    "airtelmm_balance", airtel_balance.getAmount());
                                        }
                                        if (balance_type.equals("airtelmm_balance")) {
                                            Double nBalance =
                                                    tx.getAmount() + airtel_balance.getAmount();
                                            parameters.addValue(
                                                    "mtnmm_balance", mtn_balance.getAmount());
                                            parameters.addValue("airtelmm_balance", nBalance);
                                        }
                                    } else {
                                        if (balance_type.equals("mtnmm_balance")) {
                                            // Check if there is enough balance for this transaction
                                            if (tx.getAmount() > mtn_balance.getAmount()) {
                                                status.setRollbackOnly();
                                                return GeneralException.getError(
                                                        "111",
                                                        String.format(
                                                                GeneralException.ERRORS_111,
                                                                mtn_balance.getAmount(),
                                                                mtn_balance.getCode()));
                                            }
                                            Double nBalance =
                                                    mtn_balance.getAmount() - tx.getAmount();
                                            parameters.addValue("mtnmm_balance", nBalance);
                                            parameters.addValue(
                                                    "airtelmm_balance", airtel_balance.getAmount());
                                        }

                                        if (balance_type.equals("airtelmm_balance")) {
                                            if (tx.getAmount() > airtel_balance.getAmount()) {
                                                status.setRollbackOnly();
                                                return GeneralException.getError(
                                                        "111",
                                                        String.format(
                                                                GeneralException.ERRORS_111,
                                                                airtel_balance.getAmount(),
                                                                airtel_balance.getCode()));
                                            }
                                            Double nBalance =
                                                    airtel_balance.getAmount() - tx.getAmount();
                                            parameters.addValue(
                                                    "mtnmm_balance", mtn_balance.getAmount());
                                            parameters.addValue("airtelmm_balance", nBalance);
                                        }
                                        // More balances
                                    }

                                    KeyHolder keyHolder = new GeneratedKeyHolder();
                                    // long userId;
                                    jdbcTemplate.update(sql_final, parameters, keyHolder);
                                    // Now insert privileges
                                    BigInteger statementId = (BigInteger) keyHolder.getKey();

                                    return "success";
                                } catch (Exception e) {
                                    // transactionManager.rollback(status);
                                    status.setRollbackOnly();
                                    return GeneralException.getError(
                                            "102", GeneralException.ERRORS_102);
                                }
                            }
                        });
        return result;
    }

    /*
     * Checks if user is still logged in
     *
     * @Param request This is the serverlet request.
     *
     * Returns true if still logged in or false otherwise.
     */

    public Boolean isLoggedIn(HttpServletRequest request) {

        // First set session variable
        HttpSession session = request.getSession();

        // Check if still logged in
        User sessionUser;
        // sessionUser = (User) session.getAttribute("user");

        if (session.getAttribute("user") == null) {
            return false;
        } else {
            return true;
        }
    }

    public Boolean isMerchantUserLoggedIn(HttpServletRequest request) {

        // First set session variable
        HttpSession session = request.getSession();

        // Check if still logged in
        MerchantUser sessionUser;
        // sessionUser = (User) session.getAttribute("user");

        if (session.getAttribute("merchantUser") == null) {
            return false;
        } else {
            return true;
        }
    }

    @PostMapping(path = "/getMerchantStatement")
    public String getMerchantStatement(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            User sessionUser;

            if (session.getAttribute("user") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (User) session.getAttribute("user");
            // Get the first details

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("ACCESS_TRANSACTION_LOG", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            JSONObject sObject = new JSONObject(requestBody);

            BigInteger merchant_id = sObject.getBigInteger("merchant_id");
            String pageSize = Common.jsonText(sObject, "pageSize", "");
            String currentPage = Common.jsonText(sObject, "currentPage", "");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");

            parameters.addValue("merchant_id", merchant_id);

            String sqlSelect =
                    "SELECT s.*, "
                            + " (SELECT payer_number FROM `"
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + "` "
                            + " WHERE id=s.transactions_log_id LIMIT 1) AS payer_number "
                            + "  FROM "
                            + Common.DB_TABLE_MERCHANT_STATEMENT
                            + " AS s "
                            + "WHERE merchant_id = :merchant_id";

            // HANDLE SEARCH PARAMETERS
            if (!searchValue.isNull("category") && !searchValue.isNull("value")) {

                String category = searchValue.getString("category");
                String value = searchValue.getString("value");
                if (!value.equals("all") && !category.isEmpty() && !value.isEmpty()) {
                    String safeCategory = ColumnAllowlist.validate(category);
                    sqlSelect += " AND " + safeCategory + " LIKE :" + safeCategory + " ";
                    parameters.addValue(safeCategory, "%" + value + "%");
                }
            }

            if (!sObject.isNull("search_rules")) {
                JSONObject jo = sObject.getJSONObject("search_rules");
                String start_date = jo.getString("start_date");
                String end_date = jo.getString("end_date");
                if (!start_date.isEmpty() && !end_date.isEmpty()) {
                    start_date += " 00:00:00";
                    end_date += " 23:59:59";
                    sqlSelect += " AND (created_on BETWEEN :start_date AND :end_date ) ";
                    parameters.addValue("start_date", start_date);
                    parameters.addValue("end_date", end_date);
                }
            }

            sqlSelect += " ORDER BY id DESC ";

            if (pageSize != null && !pageSize.isEmpty()) {
                int _limit = Math.max(1, Math.min(Integer.parseInt(pageSize.trim()), 1000));
                sqlSelect += " LIMIT " + _limit;
            }

            RowMapper<Statement> rm =
                    new RowMapper<Statement>() {
                        public Statement mapRow(ResultSet rs, int rowNum) throws SQLException {
                            Statement t = new Statement();
                            t.setId(rs.getLong("id"));
                            BigDecimal bd =
                                    new BigDecimal(rs.getDouble("amount"))
                                            .setScale(2, RoundingMode.HALF_UP);
                            t.setAmount(bd.doubleValue());
                            t.setTransactions_log_id(rs.getLong("transactions_log_id"));
                            t.setGateway_id(rs.getString("gateway_id"));
                            t.setCreated_on(rs.getString("created_on"));
                            t.setUpdated_on(rs.getString("updated_on"));
                            t.setAirtelmm_balance(rs.getDouble("airtelmm_balance"));
                            t.setMtnmm_balance(rs.getDouble("mtnmm_balance"));
                            t.setSafaricom_balance(rs.getDouble("safaricom_balance"));
                            t.setSms_balance(rs.getDouble("sms_balance"));
                            t.setDescription(rs.getString("description"));
                            t.setTx_type(rs.getString("tx_type"));
                            t.setNarritive(rs.getString("narrative"));
                            t.setPayer_number(rs.getString("payer_number"));
                            return t;
                        }
                    };

            // ResultSet rs;
            List<Statement> listS = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");
            JSONArray admins_array = new JSONArray();
            for (Statement us : listS) {
                JSONObject u_p_ = new JSONObject();
                u_p_.put("id", us.getId());
                u_p_.put("gateway_id", us.getGateway_id());
                u_p_.put("merchant_id", us.getMerchant_id());
                u_p_.put("transactions_log_id", us.getTransactions_log_id());
                u_p_.put("created_on", us.getCreated_on());
                u_p_.put("updaed_on", us.getUpdated_on());
                u_p_.put("description", us.getDescription());
                u_p_.put("amount", us.getAmount());
                u_p_.put("mtnmm_balance", us.getMtnmm_balance());
                u_p_.put("airtelmm_balance", us.getAirtelmm_balance());
                u_p_.put("safaricom_balance", us.getSafaricom_balance());
                u_p_.put("sms_balance", us.getSms_balance());
                u_p_.put("tx_type", us.getTx_type());
                u_p_.put("narrative", us.getNarritive());
                u_p_.put("payer_number", us.getPayer_number());
                String mtnmm_currency_code = MTNMoMoPaymentGateway.getGatewayCurrencyCode();
                String airtelmm_currency_code = "AirtelMM";

                String balances_string =
                        mtnmm_currency_code
                                + " "
                                + Common.numberFormat(us.getMtnmm_balance())
                                + " | "
                                + airtelmm_currency_code
                                + " "
                                + Common.numberFormat(us.getAirtelmm_balance())
                                + " | "
                                + SafariComPaymentGateway.getGatewayCurrencyCode()
                                + " "
                                + Common.numberFormat(us.getSafaricom_balance())
                                + " | "
                                + SmsGateway.gateway_currency_code
                                + " "
                                + Common.numberFormat(us.getSms_balance());

                u_p_.put("balances", balances_string);

                admins_array.put(u_p_);
            }
            resJson.put("data", admins_array);

            // Construct balances presentation string
            ArrayList<Balance> balances =
                    Common.getMerchantBalances(merchant_id + "", jdbcTemplate);
            String balance_string = "";
            for (Balance b : balances) {
                balance_string += b.getCode() + " " + Common.numberFormat(b.getAmount()) + " | ";
            }
            if (!balance_string.isEmpty()) {
                balance_string = balance_string.substring(0, (balance_string.length() - 2));
            }
            resJson.put("balances", balance_string);

            return resJson.toString();
        } catch (JSONException ex) {
            ex.printStackTrace();
            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/getMerchantStatementByMerchant")
    public String getMerchantStatementByMerchant(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        // First set session variable
        HttpSession session = request.getSession();
        try {
            // Check if still logged in
            MerchantUser sessionUser;

            if (session.getAttribute("merchantUser") == null) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            sessionUser = (MerchantUser) session.getAttribute("merchantUser");
            // Get the first details

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("ACCESS_TRANSACTION_LOG", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            // Obtain search fields
            JSONObject sObject = new JSONObject(requestBody);

            long merchant_id = sessionUser.getMerchant_id();
            int pageSize = sObject.getInt("pageSize");
            int currentPage = sObject.isNull("currentPage") ? 0 : sObject.getInt("currentPage");
            JSONObject searchValue = sObject.getJSONObject("searchingValue");

            parameters.addValue("merchant_id", merchant_id);

            String sqlSelect =
                    "SELECT s.*, "
                            + " (SELECT payer_number FROM `"
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + "` "
                            + " WHERE id=s.transactions_log_id LIMIT 1) AS payer_number "
                            + "  FROM `"
                            + Common.DB_TABLE_MERCHANT_STATEMENT
                            + "` AS s "
                            + " WHERE merchant_id = :merchant_id";

            String sqlSelectTotal =
                    "SELECT count(*) as total  "
                            + " FROM "
                            + Common.DB_TABLE_MERCHANT_STATEMENT
                            + " "
                            + " WHERE merchant_id = :merchant_id";

            // HANDLE SEARCH PARAMETERS
            if (!searchValue.isNull("category") && !searchValue.isNull("value")) {

                String category = searchValue.getString("category");
                String value = searchValue.getString("value");
                if (!category.equals("all") && !value.isEmpty()) {
                    String safeCategory = ColumnAllowlist.validate(category);
                    sqlSelect += " AND " + safeCategory + " LIKE :" + safeCategory + " ";
                    parameters.addValue(safeCategory, "%" + value + "%");
                }
            }

            if (!sObject.isNull("search_rules")) {
                JSONObject jo = sObject.getJSONObject("search_rules");
                String start_date = jo.getString("start_date");
                String end_date = jo.getString("end_date");
                if (!start_date.isEmpty() && !end_date.isEmpty()) {
                    start_date += " 00:00:00";
                    end_date += " 23:59:59";
                    sqlSelect += " AND (created_on BETWEEN :start_date AND :end_date ) ";
                    sqlSelectTotal += " AND (created_on BETWEEN :start_date AND :end_date ) ";

                    parameters.addValue("start_date", start_date);
                    parameters.addValue("end_date", end_date);
                }
            } else {
                LocalDateTime dt = LocalDateTime.now();
                String end_date_ = dt.format(Common.getDateTimeFormater());
                LocalDateTime last3Months = dt.minusMonths(4);
                String start_date_ = last3Months.format(Common.getDateTimeFormater());

                sqlSelect += " AND (created_on BETWEEN :start_date AND :end_date) ";
                parameters.addValue("start_date", start_date_);
                parameters.addValue("end_date", end_date_);
            }
            sqlSelect += " ORDER BY id DESC ";

            /*if (pageSize != 0) {
                sqlSelect += " LIMIT "+(currentPage * pageSize)+", "+pageSize+" ";
            }*/

            // Get total records
            RowMapper<String> rmTotal =
                    new RowMapper<String>() {
                        public String mapRow(ResultSet rs, int rowNum) throws SQLException {
                            String t = rs.getString("total");
                            return t;
                        }
                    };
            List<String> listTotal = jdbcTemplate.query(sqlSelectTotal, parameters, rmTotal);

            RowMapper<Statement> rm =
                    new RowMapper<Statement>() {
                        public Statement mapRow(ResultSet rs, int rowNum) throws SQLException {
                            Statement t = new Statement();
                            t.setId(rs.getLong("id"));
                            BigDecimal bd =
                                    new BigDecimal(rs.getDouble("amount"))
                                            .setScale(2, RoundingMode.HALF_UP);
                            t.setAmount(bd.doubleValue());
                            t.setTransactions_log_id(rs.getLong("transactions_log_id"));
                            t.setGateway_id(rs.getString("gateway_id"));
                            t.setCreated_on(rs.getString("created_on"));
                            t.setUpdated_on(rs.getString("updated_on"));
                            t.setAirtelmm_balance(rs.getDouble("airtelmm_balance"));
                            t.setMtnmm_balance(rs.getDouble("mtnmm_balance"));
                            t.setSafaricom_balance(rs.getDouble("safaricom_balance"));
                            t.setDescription(rs.getString("description"));
                            t.setTx_type(rs.getString("tx_type"));
                            t.setNarritive(rs.getString("narrative"));
                            t.setSms_balance(rs.getDouble("sms_balance"));
                            t.setPayer_number(rs.getString("payer_number"));
                            return t;
                        }
                    };

            // ResultSet rs;
            List<Statement> listS = jdbcTemplate.query(sqlSelect, parameters, rm);
            JSONObject resJson = new JSONObject();
            resJson.put("code", "000");
            resJson.put("message", "true");
            resJson.put("total", listTotal.get(0));

            JSONArray admins_array = new JSONArray();
            for (Statement us : listS) {
                JSONObject u_p_ = new JSONObject();
                u_p_.put("id", us.getId());
                u_p_.put("gateway_id", us.getGateway_id());
                u_p_.put("merchant_id", us.getMerchant_id());
                u_p_.put("transactions_log_id", us.getTransactions_log_id());
                u_p_.put("created_on", us.getCreated_on());
                u_p_.put("updaed_on", us.getUpdated_on());
                u_p_.put("description", us.getDescription());
                u_p_.put(
                        "amount",
                        us.getTx_type().equals("CR") ? us.getAmount() * 1 : us.getAmount() * -1);
                u_p_.put("mtnmm_balance", us.getMtnmm_balance());
                u_p_.put("airtelmm_balance", us.getAirtelmm_balance());
                u_p_.put("safaricom_balance", us.getSafaricom_balance());
                u_p_.put("sms_balance", us.getSms_balance());
                u_p_.put("tx_type", us.getTx_type());
                u_p_.put("narrative", us.getNarritive());
                u_p_.put("payer_number", us.getPayer_number());
                String mtnmm_currency_code = MTNMoMoPaymentGateway.getGatewayCurrencyCode();
                String airtelmm_currency_code = "AirtelMM";

                String balances_string =
                        mtnmm_currency_code
                                + " "
                                + Common.numberFormat(us.getMtnmm_balance())
                                + " | "
                                + airtelmm_currency_code
                                + " "
                                + Common.numberFormat(us.getAirtelmm_balance())
                                + " | "
                                + SafariComPaymentGateway.getGatewayCurrencyCode()
                                + " "
                                + Common.numberFormat(us.getSafaricom_balance())
                                + " | "
                                + SmsGateway.gateway_currency_code
                                + " "
                                + Common.numberFormat(us.getSms_balance());

                u_p_.put("balances", balances_string);

                admins_array.put(u_p_);
            }
            resJson.put("data", admins_array);

            // Construct balances presentation string
            ArrayList<Balance> balances =
                    Common.getMerchantBalances(merchant_id + "", jdbcTemplate);
            String balance_string = "";
            for (Balance b : balances) {
                balance_string += b.getCode() + " " + Common.numberFormat(b.getAmount()) + " | ";
            }
            if (!balance_string.isEmpty()) {
                balance_string = balance_string.substring(0, (balance_string.length() - 2));
            }
            resJson.put("balances", balance_string);

            return resJson.toString();
        } catch (JSONException ex) {

            Logger.getLogger(TransactionsLogController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/testMtnTokens")
    public String testMtnTokens(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {

        MTNMoMoPaymentGateway gw = new MTNMoMoPaymentGateway();
        MTNMoMoPaymentGateway.Token t = gw.getToken();

        if (t != null) {
            return t.toString();
        } else {
            return "No Token returned. See the logs";
        }
    }

    @PostMapping(path = "/testMtnPayIn")
    public String testMtnPayIn(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException, JSONException {

        JSONObject sO = new JSONObject(requestBody);
        DoPayGateway gw = new DoPayGateway();

        String ref = Common.generateUuid();
        String narrative = sO.getString("narrative");
        String msisdn = sO.getString("payer");

        GateWayResponse pResponse =
                gw.runPayGatewayDoPayIn(
                        jdbcTemplate, msisdn, sO.getDouble("amount"), ref, narrative, null);

        if (pResponse != null) {
            String res = pResponse.getRequestTrace();

            return res;
        } else {
            return "No gateway for " + msisdn;
        }
        // String ref = Common.generateUuid();
    }

    @PostMapping(path = "/testMtnPayOut")
    public String testMtnPayOut(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException, JSONException {

        JSONObject sO = new JSONObject(requestBody);
        DoPayGateway gw = new DoPayGateway();

        String ref = Common.generateUuid();
        String narrative = sO.getString("narrative");

        GateWayResponse pResponse =
                gw.runPayGatewayDoPayOut(
                        jdbcTemplate,
                        sO.getString("payer"),
                        sO.getDouble("amount"),
                        ref,
                        narrative,
                        null);

        if (pResponse != null) {
            String res = pResponse.getRequestTrace();
            return res;
        } else {
            return "Gateway request failed";
        }
        // String ref = Common.generateUuid();
    }

    @PostMapping(path = "/testMtnPayInCheckStatus")
    public String testMtnPayInCheckStatus(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException, JSONException {

        JSONObject sO = new JSONObject(requestBody);

        DoPayGateway gw = new DoPayGateway();
        String ref = sO.getString("tx_id");
        String tx_type = sO.getString("tx_type");

        GateWayResponse pResponse =
                gw.runPayGatewayDoCheckStatus(
                        jdbcTemplate, "MTNMoMoPaymentGateway", ref, tx_type, null);

        if (pResponse != null) {
            String res = pResponse.getRequestTrace();
            res +=
                    "\n\n==========\n\n"
                            + "Transaction Status: "
                            + pResponse.getTransactionStatus()
                            + "\n\n"
                            + "NetworkID: "
                            + pResponse.getNetworkId()
                            + "\n\n"
                            + "Message: "
                            + pResponse.getMessage()
                            + "\n\n"
                            + "***************************\n\n";
            return res;
        } else {
            return "Gateway request failed";
        }
        // String ref = Common.generateUuid();
    }

    private Payment getPaymentById(long id) {

        String sqlSelect =
                "SELECT *  FROM "
                        + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_LOG
                        + " "
                        + " WHERE id = :id";

        RowMapper<Payment> rm =
                new RowMapper<Payment>() {
                    public Payment mapRow(ResultSet rs, int rowNum) throws SQLException {
                        Payment t = new Payment();
                        t.setId(rs.getLong("id"));
                        t.setName(rs.getString("name"));
                        t.setPaymentId(rs.getString("batch_id"));
                        t.setDescription(rs.getString("tx_description"));
                        t.setStatus(rs.getString("status"));
                        t.setCreated_on(rs.getString("created_on"));
                        t.setMerchant_id(rs.getLong("merchant_id"));
                        t.setTotal_amount(rs.getDouble("total_amount"));
                        t.setTotal_charges(rs.getDouble("total_charges"));
                        t.setCreated_by(rs.getString("created_by"));
                        t.setBeneficiaries(getBatchBeneficiaries(t.getId()));
                        return t;
                    }
                };

        List<Payment> blist =
                jdbcTemplate.query(sqlSelect, new MapSqlParameterSource("id", id), rm);
        if (blist.size() > 0) {
            return blist.get(0);
        } else {
            return null;
        }
    }

    /*
     * API to create a PayIn
     */
    @PostMapping(path = "/addPayInTransaction")
    public String addPayInTransaction(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        try {

            if (!isMerchantUserLoggedIn(request)) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            HttpSession session = request.getSession();

            MerchantUser sessionUser = (MerchantUser) session.getAttribute("merchantUser");

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("CREATE_BATCH_TX", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            JSONObject sObject = new JSONObject(requestBody);

            String created_by = sessionUser.getName() + " - " + sessionUser.getEmail();
            String tx_description = sObject.getString("tx_description");
            String account = sObject.getString("account");
            String amount_ = sObject.getString("amount");
            Double amount;

            try {
                amount = Double.parseDouble(amount_);
            } catch (NumberFormatException e) {
                return GeneralException.getError(
                        "123", String.format(GeneralException.ERRORS_123, amount_));
            }

            String merchant_number = sessionUser.getMerchant_number();

            // Get this merchant
            Merchant merchant = Common.getMerchantByAccountNumber(merchant_number, jdbcTemplate);
            if (merchant == null) {
                return GeneralException.getError(
                        "109",
                        String.format(GeneralException.ERRORS_109, "Merchant", merchant_number));
            }

            // First check if stock account was configured transaction
            Setting getStockAccount = Common.getSettings("float_stock_account", jdbcTemplate);
            Setting getRevenueAccount = Common.getSettings("revenue_account", jdbcTemplate);
            if (getStockAccount == null || getStockAccount.getSetting_value().isEmpty()) {
                return GeneralException.getError("112", GeneralException.ERRORS_112);
            }

            if (getRevenueAccount == null || getRevenueAccount.getSetting_value().isEmpty()) {
                return GeneralException.getError("117", GeneralException.ERRORS_117);
            }

            // If it's stock account, this operation is not permitted
            String stock_account_number = getStockAccount.getSetting_value().trim();
            if (merchant.getAccount_number().equals(stock_account_number)) {
                return GeneralException.getError("113", GeneralException.ERRORS_113);
            }

            // First determine the gateway by msisdn
            String gateway_id = DoPayGateway.getGatewayIdByMsisdn(account, jdbcTemplate);
            if (gateway_id == null) {
                return GeneralException.getError(
                        "118", String.format(GeneralException.ERRORS_118, account));
            }

            // Get this merchant by id.
            Transaction newTx = new Transaction();
            newTx.setGateway_id(gateway_id);
            newTx.setOriginal_amount(amount);
            newTx.setPayer_number(account);
            newTx.setStatus("PENDING");
            newTx.setMerchant_id(merchant.getId() + "");
            newTx.setTx_description(merchant.getShort_name());
            newTx.setTx_merchant_description(tx_description);
            newTx.setTx_type(Transaction.TX_TYPE_PAYIN);
            String tx_id = Common.generateUuid();
            if (gateway_id.equals(AirtelMoneyPaymentGateway.gateway_id)
                    || gateway_id.equals(AirtelMoneyOpenApiPaymentGateway.gateway_id)) {
                tx_id = tx_id.substring(0, 20);
            }
            newTx.setTx_unique_id(tx_id);
            newTx.setTx_merchant_ref(tx_id);
            newTx.setCallback_url("");

            // First get the charging method
            GatewayChargeDetails gwChargingDetails =
                    DoPayGateway.getGatewayChargeDetailsById(
                            jdbcTemplate, gateway_id, merchant.getId());
            newTx.setCharging_method(gwChargingDetails.getCustomerInboundChargeMethod());
            Double charges = DoPayGateway.getCustomerInboundCharges(amount, gwChargingDetails);
            Double tx_cost = DoPayGateway.getCostOfInboundCharges(amount, gwChargingDetails);
            newTx.setCharges(charges);
            newTx.setTx_cost(tx_cost);
            newTx.setTx_request_trace("");
            newTx.setTx_update_trace("");
            newTx.setTx_gateway_ref("");

            String result = Common.doPayIn(newTx, merchant, jdbcTemplate, transactionManager);

            // Audit A1/B1: this portal-initiated legacy call site never wrote to the double-entry
            // ledger either - post the same entries the v2 orchestration path posts, for parity.
            legacyLedgerPostingService.postPaymentEntries(
                    Transaction.TX_TYPE_PAYIN, gateway_id, merchant, newTx, amount, charges);

            return result;
        } catch (Exception ex) {
            ex.printStackTrace();
            Logger.getLogger(AuthenticationController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to create bulk payment
     */
    @PostMapping(path = "/addPayment")
    public String addPayment(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        try {

            if (!isMerchantUserLoggedIn(request)) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            HttpSession session = request.getSession();

            MerchantUser sessionUser = (MerchantUser) session.getAttribute("merchantUser");

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("CREATE_BATCH_TX", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            JSONObject sObject = new JSONObject(requestBody);

            String created_by = sessionUser.getName() + " - " + sessionUser.getEmail();
            String tx_description = sObject.getString("tx_description");
            String name = sObject.getString("name");

            Payment newPayment = new Payment();
            newPayment.setCreated_by(created_by);
            newPayment.setName(name);
            newPayment.setDescription(tx_description);
            newPayment.setMerchant_id(sessionUser.getMerchant_id());
            String payment_unique_id = Common.generateUuid();
            newPayment.setPaymentId(payment_unique_id);
            newPayment.setStatus(Transaction.BATCH_PAYMENTS_PENDING);

            JSONArray beneficiaries = sObject.getJSONArray("beneficiaries");

            // Now add the user to database
            String sql =
                    "INSERT INTO "
                            + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_LOG
                            + " "
                            + " SET `name`=:name,"
                            + " `tx_description`=:tx_description, "
                            + " `merchant_id`=:merchant_id, "
                            + " `created_by`=:created_by,"
                            + " `status`=:status,"
                            + " `total_amount`=:total_amount,"
                            + " `total_charges`=:total_charges,"
                            + " `batch_id`=:batch_id";

            String sqlBeneficiary =
                    "INSERT INTO "
                            + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_BENEFICIARIES
                            + " "
                            + " SET `batch_id`=:batch_id,"
                            + " `name`=:name, "
                            + " `account`=:account, "
                            + " `amount`=:amount, "
                            + " `account_type`=:account_type, "
                            + " `status`=:status ";

            // Getting total amount
            double total_amount = 0.00;
            double total_charges = 0.00;

            List<Beneficiary> pBeneficiaries = new ArrayList<>();
            for (int i = 0; i < beneficiaries.length(); i++) {
                String account = "";
                String account_type = "";
                String beneficiary_name = "";

                JSONObject jObject = beneficiaries.getJSONObject(i);
                beneficiary_name = jObject.getString("name");
                double amount = jObject.getDouble("amount");
                total_amount += amount;
                account = jObject.getString("account");
                account_type = jObject.getString("account_type");
                String gateway_id = DoPayGateway.getGatewayIdByMsisdn(account, jdbcTemplate);
                // Also check whether the phone number given is supported.
                if (account_type.toLowerCase().equals("phone")) {
                    if (gateway_id == null) {
                        return GeneralException.getError(
                                "118", String.format(GeneralException.ERRORS_118, account));
                    }
                }

                GatewayChargeDetails gwChargingDetails =
                        DoPayGateway.getGatewayChargeDetailsById(
                                jdbcTemplate, gateway_id, sessionUser.getMerchant_id());
                Double charges = DoPayGateway.getCustomerInboundCharges(amount, gwChargingDetails);
                total_charges += charges;
                Beneficiary b = new Beneficiary();
                b.setAccount(account);
                b.setAmount(amount);
                b.setStatus(Transaction.BATCH_PAYMENT_UNPAID);
                b.setName(beneficiary_name);
                b.setAccount_type(account_type);
                pBeneficiaries.add(b);
            }

            newPayment.setTotal_amount(total_amount);
            newPayment.setTotal_charges(total_charges);
            newPayment.setBeneficiaries(pBeneficiaries);

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            parameters.addValue("merchant_id", sessionUser.getMerchant_id());
            parameters.addValue("created_by", created_by);
            parameters.addValue("status", newPayment.getStatus());
            parameters.addValue("total_amount", newPayment.getTotal_amount());
            parameters.addValue("total_charges", newPayment.getTotal_charges());
            parameters.addValue("name", name);
            parameters.addValue("tx_description", newPayment.getDescription());
            parameters.addValue("batch_id", newPayment.getPaymentId());
            final String sql_ = sql;

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            String result =
                    template.execute(
                            new TransactionCallback<String>() {
                                @Override
                                public String doInTransaction(TransactionStatus status) {
                                    try {

                                        KeyHolder keyHolder = new GeneratedKeyHolder();
                                        // long userId;
                                        jdbcTemplate.update(sql_, parameters, keyHolder);
                                        // Now insert privileges
                                        BigInteger batchId = (BigInteger) keyHolder.getKey();

                                        KeyHolder keyHolderBeneficiary = new GeneratedKeyHolder();
                                        MapSqlParameterSource privParams;

                                        String sqlCheck =
                                                "SELECT count(*) as found "
                                                        + "FROM `"
                                                        + Common
                                                                .DB_TABLE_MERCHANT_BATCH_TRANSACTION_BENEFICIARIES
                                                        + "` "
                                                        + " WHERE batch_id = '"
                                                        + batchId
                                                        + "' AND account=:account";

                                        RowMapper<Integer> rm_ =
                                                new RowMapper<Integer>() {
                                                    public Integer mapRow(ResultSet rs, int rowNum)
                                                            throws SQLException {
                                                        int r = rs.getInt("found");
                                                        return r;
                                                    }
                                                };

                                        MapSqlParameterSource checkParams;
                                        for (Beneficiary b : newPayment.getBeneficiaries()) {
                                            keyHolderBeneficiary = new GeneratedKeyHolder();
                                            checkParams = new MapSqlParameterSource();
                                            checkParams.addValue("account", b.getAccount());

                                            // Check if this beneficiary was already added
                                            List<Integer> listBens =
                                                    jdbcTemplate.query(sqlCheck, checkParams, rm_);
                                            if (listBens.get(0) > 0) {
                                                status.setRollbackOnly();
                                                return GeneralException.getError(
                                                        "129",
                                                        String.format(
                                                                GeneralException.ERRORS_129,
                                                                b.getAccount()));
                                            }

                                            privParams = new MapSqlParameterSource();
                                            privParams.addValue("batch_id", batchId);
                                            privParams.addValue("name", b.getName());
                                            privParams.addValue("amount", b.getAmount());
                                            privParams.addValue("account", b.getAccount());
                                            privParams.addValue("status", b.getStatus());
                                            privParams.addValue(
                                                    "account_type", b.getAccount_type());

                                            // privParams.addValue("name",
                                            // privilege.getString("name"));
                                            long privId =
                                                    jdbcTemplate.update(
                                                            sqlBeneficiary,
                                                            privParams,
                                                            keyHolderBeneficiary);

                                            BigInteger beneficiaryId =
                                                    (BigInteger) keyHolderBeneficiary.getKey();
                                        }

                                        // Now insert auditTrail
                                        String actionInsert =
                                                Common.recordMerchantAction(
                                                        sessionUser,
                                                        "Added new payment "
                                                                + newPayment.toString(),
                                                        jdbcTemplate);

                                        // If it failed to execute the statement to record this
                                        // action
                                        if (!actionInsert.equals("success")) {
                                            status.setRollbackOnly();
                                            return actionInsert;
                                        }

                                        // TransactionManager.commit(status);
                                        return "success";
                                    } catch (Exception e) {
                                        // transactionManager.rollback(status);
                                        status.setRollbackOnly();
                                        return GeneralException.getError(
                                                "102", GeneralException.ERRORS_102);
                                    }
                                }
                            });

            if (result.equals("success")) {

                return GeneralSuccessResponse.getMessage("000", GeneralSuccessResponse.SUCCESS_000);
            } else {
                return result;
            }
        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to create bulk payment
     */
    @PostMapping(path = "/saveSms")
    public String saveSms(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        try {

            if (!isMerchantUserLoggedIn(request)) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            HttpSession session = request.getSession();

            MerchantUser sessionUser = (MerchantUser) session.getAttribute("merchantUser");

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("SEND_SMS", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            JSONObject sObject = new JSONObject(requestBody);

            String created_by = sessionUser.getName() + " - " + sessionUser.getEmail();
            String content = sObject.getString("content");
            Boolean multiple = sObject.isNull("multiple") ? false : true;
            String send_time = sObject.getString("send_time");

            // Keep the external timestamp format unchanged, but compare with java.time.
            LocalDateTime requestedSendTime = LocalDateTime.parse(send_time, SMS_SEND_TIME_FORMAT);
            if (LocalDateTime.now().isAfter(requestedSendTime)) {
                return GeneralException.getError("135", GeneralException.ERRORS_135);
            }

            MerchantSms newSms = new MerchantSms();
            newSms.setCreated_by(created_by);
            newSms.setContent(content);
            newSms.setMerchant_id(BigInteger.valueOf(sessionUser.getMerchant_id()));
            newSms.setSend_time(send_time);
            newSms.setGw_response("");
            newSms.setStatus("PENDING");
            newSms.setTrace("");

            // Now get recipients
            int total_recipients = 0;
            JSONArray rec_array = sObject.getJSONArray("recipients");
            total_recipients = rec_array.length();
            SmsGateway smsgw = new SmsGateway(jdbcTemplate);
            newSms.setSmsgw(smsgw.getGatewayName());

            double charge = smsgw.getCharge(sessionUser.getMerchant_id());
            newSms.setCharge(charge);
            newSms.setCost(smsgw.getCost());
            final double total_amount = (charge * total_recipients);

            // Get sms balance
            ArrayList<Balance> balances =
                    Common.getMerchantBalances(sessionUser.getMerchant_id() + "", jdbcTemplate);
            // Check whether the user has enough funds
            for (Balance b : balances) {
                if (b.getGateway_id().equals(SmsGateway.getGatewayId())) {
                    if (b.getAmount() < total_amount) {
                        return GeneralException.getError(
                                "111",
                                String.format(
                                        GeneralException.ERRORS_111, b.getAmount(), "SMS Account"));
                    }
                }
            }

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            String result =
                    template.execute(
                            new TransactionCallback<String>() {
                                @Override
                                public String doInTransaction(TransactionStatus status) {
                                    try {
                                        // Now add the user to database
                                        String sql =
                                                "INSERT INTO "
                                                        + Common.DB_TABLE_MERCHANT_SMS
                                                        + " "
                                                        + " SET `merchant_id`=:merchant_id,"
                                                        + " `cost`=:cost, "
                                                        + " `charge`=:charge, "
                                                        + " `created_by`=:created_by,"
                                                        + " `status`=:status,"
                                                        + " `total_recipients`=:total_recipients,"
                                                        + " `content`=:content,"
                                                        + " `gw_response`=:gw_response,"
                                                        + " `smsgw`=:smsgw,"
                                                        + " `trace`=:trace,"
                                                        + " `send_time`=:send_time,"
                                                        + " `total_amount`=:total_amount,"
                                                        + " `recipients`=:recipients";
                                        MapSqlParameterSource parameters =
                                                new MapSqlParameterSource();
                                        if (!multiple) {
                                            String recipient_string = "";
                                            for (int i = 0; i < rec_array.length(); i++) {
                                                JSONObject rObject = rec_array.getJSONObject(i);
                                                recipient_string +=
                                                        rObject.getString("phone") + ",";
                                            }
                                            newSms.setRecipients(recipient_string);
                                            newSms.setTotal_recipients(rec_array.length());
                                            newSms.setTotal_amount(total_amount);

                                            MerchantSms newSms_ = newSms;
                                            parameters.addValue(
                                                    "merchant_id", newSms_.getMerchant_id());
                                            parameters.addValue("created_by", created_by);
                                            parameters.addValue("status", newSms_.getStatus());
                                            parameters.addValue(
                                                    "total_amount", newSms_.getTotal_amount());
                                            parameters.addValue("charge", newSms_.getCharge());
                                            parameters.addValue("cost", newSms_.getCost());
                                            parameters.addValue(
                                                    "total_recipients",
                                                    newSms_.getTotal_recipients());
                                            parameters.addValue("trace", newSms_.getTrace());
                                            parameters.addValue("content", newSms_.getContent());
                                            parameters.addValue(
                                                    "gw_response", newSms_.getGw_response());
                                            parameters.addValue("smsgw", newSms_.getSmsgw());
                                            parameters.addValue(
                                                    "send_time", newSms_.getSend_time());
                                            parameters.addValue(
                                                    "recipients", newSms_.getRecipients());
                                            // Now save the SMS
                                            KeyHolder keyHolder = new GeneratedKeyHolder();
                                            // long userId;
                                            jdbcTemplate.update(sql, parameters, keyHolder);
                                            // Now insert privileges
                                            BigInteger smsId = (BigInteger) keyHolder.getKey();
                                        } else {
                                            ArrayList<MerchantSms> multSmsList = new ArrayList<>();
                                            for (int i = 0; i < rec_array.length(); i++) {
                                                JSONObject rObject = rec_array.getJSONObject(i);
                                                MerchantSms newSms_ = newSms;
                                                newSms_.setContent(rObject.getString("content"));
                                                newSms_.setRecipients(rObject.getString("phone"));
                                                newSms_.setTotal_recipients(1);
                                                newSms_.setTotal_amount(charge);
                                                multSmsList.add(newSms_);

                                                parameters.addValue(
                                                        "merchant_id", newSms_.getMerchant_id());
                                                parameters.addValue("created_by", created_by);
                                                parameters.addValue("status", newSms_.getStatus());
                                                parameters.addValue(
                                                        "total_amount", newSms_.getTotal_amount());
                                                parameters.addValue("charge", newSms_.getCharge());
                                                parameters.addValue("cost", newSms_.getCost());
                                                parameters.addValue(
                                                        "total_recipients",
                                                        newSms_.getTotal_recipients());
                                                parameters.addValue("trace", newSms_.getTrace());
                                                parameters.addValue(
                                                        "content", newSms_.getContent());
                                                parameters.addValue(
                                                        "gw_response", newSms_.getGw_response());
                                                parameters.addValue("smsgw", newSms_.getSmsgw());
                                                parameters.addValue(
                                                        "send_time", newSms_.getSend_time());
                                                parameters.addValue(
                                                        "recipients", newSms_.getRecipients());

                                                KeyHolder keyHolder = new GeneratedKeyHolder();
                                                // long userId;
                                                jdbcTemplate.update(sql, parameters, keyHolder);
                                                // Now insert privileges
                                                BigInteger smsId = (BigInteger) keyHolder.getKey();
                                            }
                                            // Now save these SMSs.
                                        }

                                        // Now insert auditTrail
                                        String actionInsert =
                                                Common.recordMerchantAction(
                                                        sessionUser,
                                                        "Created SMS: " + content,
                                                        jdbcTemplate);

                                        // If it failed to execute the statement to record this
                                        // action
                                        if (!actionInsert.equals("success")) {
                                            status.setRollbackOnly();
                                            return actionInsert;
                                        }

                                        // TransactionManager.commit(status);
                                        return "success";
                                    } catch (Exception e) {
                                        // transactionManager.rollback(status);
                                        status.setRollbackOnly();
                                        return GeneralException.getError(
                                                "102", GeneralException.ERRORS_102);
                                    }
                                }
                            });

            if (result.equals("success")) {

                return GeneralSuccessResponse.getMessage("000", GeneralSuccessResponse.SUCCESS_000);
            } else {
                return result;
            }
        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to create bulk payment
     */
    @PostMapping(path = "/cancelSms")
    public String cancelSms(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {

            if (!isMerchantUserLoggedIn(request)) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            HttpSession session = request.getSession();

            MerchantUser sessionUser = (MerchantUser) session.getAttribute("merchantUser");

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("SEND_SMS", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            JSONArray sArrayObject = new JSONArray(requestBody);

            String created_by = sessionUser.getName() + " - " + sessionUser.getEmail();

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            String result =
                    template.execute(
                            new TransactionCallback<String>() {
                                @Override
                                public String doInTransaction(TransactionStatus status) {
                                    try {
                                        // Now add the user to database
                                        String sql =
                                                "UPDATE "
                                                        + Common.DB_TABLE_MERCHANT_SMS
                                                        + " "
                                                        + " SET `status`=:status"
                                                        + " WHERE id=:id AND status='PENDING' ";

                                        MapSqlParameterSource parameters;
                                        for (int i = 0; i < sArrayObject.length(); i++) {
                                            JSONObject jObject = sArrayObject.getJSONObject(i);
                                            parameters = new MapSqlParameterSource();
                                            parameters.addValue("status", "CANCELLED");
                                            parameters.addValue("id", jObject.getLong("id"));

                                            jdbcTemplate.update(sql, parameters);
                                        }

                                        // Now insert auditTrail
                                        String actionInsert =
                                                Common.recordMerchantAction(
                                                        sessionUser,
                                                        "Cancelled SMSs: ",
                                                        jdbcTemplate);

                                        // If it failed to execute the statement to record this
                                        // action
                                        if (!actionInsert.equals("success")) {
                                            status.setRollbackOnly();
                                            return actionInsert;
                                        }

                                        // TransactionManager.commit(status);
                                        return "success";
                                    } catch (Exception e) {
                                        // transactionManager.rollback(status);
                                        status.setRollbackOnly();
                                        return GeneralException.getError(
                                                "102", GeneralException.ERRORS_102);
                                    }
                                }
                            });

            if (result.equals("success")) {

                return GeneralSuccessResponse.getMessage("000", GeneralSuccessResponse.SUCCESS_000);
            } else {
                return result;
            }
        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to create bulk payment
     */
    @PostMapping(path = "/editPayment")
    public String editPayment(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header
        try {

            if (!isMerchantUserLoggedIn(request)) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            HttpSession session = request.getSession();

            MerchantUser sessionUser = (MerchantUser) session.getAttribute("merchantUser");

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("CREATE_BATCH_TX", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            JSONObject sObject = new JSONObject(requestBody);

            String created_by = sessionUser.getName() + " - " + sessionUser.getEmail();
            String tx_description = sObject.getString("tx_description");
            String name = sObject.getString("name");
            long id = sObject.getLong("id");

            // Check if this payement exists
            Payment payment = getPaymentById(id);
            if (payment == null) {
                return GeneralException.getError(
                        "108", String.format(GeneralException.ERRORS_108, "Payment ", name));
            }

            // If payment is already in an uneditable state
            if (!payment.getStatus().equals(Transaction.BATCH_PAYMENTS_PENDING)) {
                return GeneralException.getError(
                        "128", String.format(GeneralException.ERRORS_128, payment.getStatus()));
            }

            Payment newPayment = new Payment();
            newPayment.setId(id);
            newPayment.setCreated_by(created_by);
            newPayment.setName(name);
            newPayment.setDescription(tx_description);
            newPayment.setMerchant_id(sessionUser.getMerchant_id());
            String payment_unique_id = Common.generateUuid();
            newPayment.setPaymentId(payment_unique_id);
            newPayment.setStatus(Transaction.BATCH_PAYMENTS_PENDING);

            JSONArray beneficiaries = sObject.getJSONArray("beneficiaries");

            // Now add the user to database
            String sql =
                    "UPDATE "
                            + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_LOG
                            + " "
                            + " SET `name`=:name,"
                            + " `tx_description`=:tx_description, "
                            + " `merchant_id`=:merchant_id, "
                            + " `created_by`=:created_by,"
                            + " `status`=:status,"
                            + " `total_amount`=:total_amount,"
                            + " `total_charges`=:total_charges,"
                            + " `batch_id`=:batch_id"
                            + " WHERE id=:id";

            String sqlDeleteBeneficiary =
                    "DELETE FROM "
                            + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_BENEFICIARIES
                            + " "
                            + " WHERE batch_id=:batch_id ";
            MapSqlParameterSource deleteParams = new MapSqlParameterSource();
            deleteParams.addValue("batch_id", id);

            String sqlBeneficiary =
                    "INSERT INTO "
                            + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_BENEFICIARIES
                            + " "
                            + " SET `batch_id`=:batch_id,"
                            + " `name`=:name, "
                            + " `account`=:account, "
                            + " `amount`=:amount, "
                            + " `account_type`=:account_type, "
                            + " `status`=:status ";

            // Getting total amount
            double total_amount = 0.00;
            double total_charges = 0.00;

            List<Beneficiary> pBeneficiaries = new ArrayList<>();
            for (int i = 0; i < beneficiaries.length(); i++) {
                String account = "";
                String account_type = "";
                String beneficiary_name = "";

                JSONObject jObject = beneficiaries.getJSONObject(i);
                beneficiary_name = jObject.getString("name");
                double amount = jObject.getDouble("amount");
                total_amount += amount;
                account = jObject.getString("account");
                account_type = jObject.getString("account_type");
                String gateway_id = DoPayGateway.getGatewayIdByMsisdn(account, jdbcTemplate);
                // Also check whether the phone number given is supported.
                if (account_type.toLowerCase().equals("phone")) {
                    if (gateway_id == null) {
                        return GeneralException.getError(
                                "118", String.format(GeneralException.ERRORS_118, account));
                    }
                }

                GatewayChargeDetails gwChargingDetails =
                        DoPayGateway.getGatewayChargeDetailsById(
                                jdbcTemplate, gateway_id, sessionUser.getMerchant_id());
                Double charges = DoPayGateway.getCustomerInboundCharges(amount, gwChargingDetails);
                total_charges += charges;
                Beneficiary b = new Beneficiary();
                b.setAccount(account);
                b.setAmount(amount);
                b.setStatus(Transaction.BATCH_PAYMENT_UNPAID);
                b.setName(beneficiary_name);
                b.setAccount_type(account_type);
                pBeneficiaries.add(b);
            }

            newPayment.setTotal_amount(total_amount);
            newPayment.setTotal_charges(total_charges);
            newPayment.setBeneficiaries(pBeneficiaries);

            MapSqlParameterSource parameters = new MapSqlParameterSource();

            parameters.addValue("merchant_id", sessionUser.getMerchant_id());
            parameters.addValue("created_by", created_by);
            parameters.addValue("status", newPayment.getStatus());
            parameters.addValue("total_amount", newPayment.getTotal_amount());
            parameters.addValue("total_charges", newPayment.getTotal_charges());
            parameters.addValue("name", name);
            parameters.addValue("tx_description", newPayment.getDescription());
            parameters.addValue("batch_id", newPayment.getPaymentId());
            parameters.addValue("id", newPayment.getId());

            final String sql_ = sql;

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            String result =
                    template.execute(
                            new TransactionCallback<String>() {
                                @Override
                                public String doInTransaction(TransactionStatus status) {
                                    try {

                                        // Update Bulk payments
                                        jdbcTemplate.update(sql_, parameters);

                                        // First delete existing beneficiaries
                                        jdbcTemplate.update(sqlDeleteBeneficiary, deleteParams);

                                        KeyHolder keyHolderBeneficiary = new GeneratedKeyHolder();
                                        MapSqlParameterSource privParams;
                                        for (Beneficiary b : newPayment.getBeneficiaries()) {
                                            keyHolderBeneficiary = new GeneratedKeyHolder();

                                            privParams = new MapSqlParameterSource();
                                            privParams.addValue("batch_id", id);
                                            privParams.addValue("name", b.getName());
                                            privParams.addValue("amount", b.getAmount());
                                            privParams.addValue("account", b.getAccount());
                                            privParams.addValue("status", b.getStatus());
                                            privParams.addValue(
                                                    "account_type", b.getAccount_type());

                                            // privParams.addValue("name",
                                            // privilege.getString("name"));
                                            long privId =
                                                    jdbcTemplate.update(
                                                            sqlBeneficiary,
                                                            privParams,
                                                            keyHolderBeneficiary);

                                            BigInteger beneficiaryId =
                                                    (BigInteger) keyHolderBeneficiary.getKey();
                                        }

                                        // Now insert auditTrail
                                        String actionInsert =
                                                Common.recordMerchantAction(
                                                        sessionUser,
                                                        "Updated payment " + newPayment.toString(),
                                                        jdbcTemplate);

                                        // If it failed to execute the statement to record this
                                        // action
                                        if (!actionInsert.equals("success")) {
                                            status.setRollbackOnly();
                                            return actionInsert;
                                        }

                                        // TransactionManager.commit(status);
                                        return "success";
                                    } catch (Exception e) {
                                        // transactionManager.rollback(status);
                                        status.setRollbackOnly();
                                        return GeneralException.getError(
                                                "102", GeneralException.ERRORS_102);
                                    }
                                }
                            });

            if (result.equals("success")) {

                return GeneralSuccessResponse.getMessage("000", GeneralSuccessResponse.SUCCESS_000);
            } else {
                return result;
            }
        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to start a payment
     */
    @PostMapping(path = "/startPayment")
    public String startPayment(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header
        try {

            if (!isMerchantUserLoggedIn(request)) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            HttpSession session = request.getSession();

            MerchantUser sessionUser = (MerchantUser) session.getAttribute("merchantUser");

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("CREATE_BATCH_TX", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            JSONObject sObject = new JSONObject(requestBody);

            String created_by = sessionUser.getName() + " - " + sessionUser.getEmail();
            long id = sObject.getLong("id");

            // Check if this payement exists
            Payment payment = getPaymentById(id);
            if (payment == null) {
                return GeneralException.getError(
                        "108", String.format(GeneralException.ERRORS_108, "Payment ", id));
            }

            // If payment is already in an uneditable state
            String pStatus = payment.getStatus();
            if (pStatus.equals(Transaction.BATCH_PAYMENTS_DONE)
                    || pStatus.equals(Transaction.BATCH_PAYMENTS_STOPPED)) {
                return GeneralException.getError(
                        "128", String.format(GeneralException.ERRORS_128, payment.getStatus()));
            }

            boolean willStartOrResumeDisbursement =
                    !pStatus.equals(Transaction.BATCH_PAYMENTS_PROCESSING);
            if (pStatus.equals(Transaction.BATCH_PAYMENTS_PROCESSING)) {
                payment.setStatus(Transaction.BATCH_PAYMENTS_PAUSED);
            } else if (pStatus.equals(Transaction.BATCH_PAYMENTS_PAUSED)) {
                payment.setStatus(Transaction.BATCH_PAYMENTS_PROCESSING);
            } else {
                payment.setStatus(Transaction.BATCH_PAYMENTS_PROCESSING);
            }

            // Audit E2: step-up MFA for high-value payouts. This is the point where a logged-in
            // human explicitly starts/resumes disbursement - paymentsPayCron processes the batch
            // asynchronously afterward with nobody present to prompt, so this is the only place a
            // fresh step-up challenge can meaningfully happen. Pausing an in-flight batch doesn't
            // disburse anything, so it's not gated.
            if (willStartOrResumeDisbursement) {
                String stepUpError = requireStepUpMfaIfOverThreshold(sessionUser, payment, sObject);
                if (stepUpError != null) {
                    return stepUpError;
                }
            }

            // Now add the user to database
            String sql =
                    "UPDATE "
                            + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_LOG
                            + " "
                            + " SET `status`=:status"
                            + " WHERE id=:id";

            MapSqlParameterSource parameters = new MapSqlParameterSource();
            parameters.addValue("id", id);
            parameters.addValue("status", payment.getStatus());

            final String sql_ = sql;

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            String result =
                    template.execute(
                            new TransactionCallback<String>() {
                                @Override
                                public String doInTransaction(TransactionStatus status) {
                                    try {

                                        // Update Bulk payments
                                        jdbcTemplate.update(sql_, parameters);

                                        // Now insert auditTrail
                                        String actionInsert =
                                                Common.recordMerchantAction(
                                                        sessionUser,
                                                        "Started payment " + payment.toString(),
                                                        jdbcTemplate);

                                        // If it failed to execute the statement to record this
                                        // action
                                        if (!actionInsert.equals("success")) {
                                            status.setRollbackOnly();
                                            return actionInsert;
                                        }

                                        // TransactionManager.commit(status);
                                        return "success";
                                    } catch (Exception e) {
                                        // transactionManager.rollback(status);
                                        status.setRollbackOnly();
                                        return GeneralException.getError(
                                                "102", GeneralException.ERRORS_102);
                                    }
                                }
                            });

            if (result.equals("success")) {

                return GeneralSuccessResponse.getMessage("000", GeneralSuccessResponse.SUCCESS_000);
            } else {
                return result;
            }
        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /**
     * Audit E2: returns null when this batch is allowed to start/resume disbursing, or an error
     * response string when it's blocked. Only enforced above cpay.step_up_mfa.payout_threshold
     * (setting name: step_up_mfa_payout_threshold; unset/<=0 disables the gate entirely). A
     * merchant user without MFA enabled is blocked outright (fail closed) rather than silently
     * allowed through - the whole point of a step-up control is to require it for this specific
     * action, not to depend on whichever login-time MFA choice the user already made.
     */
    String requireStepUpMfaIfOverThreshold(
            MerchantUser sessionUser, Payment payment, JSONObject requestBody) {
        Setting thresholdSetting = Common.getSettings("step_up_mfa_payout_threshold", jdbcTemplate);
        double threshold = parseStepUpThreshold(thresholdSetting);
        if (threshold <= 0 || payment.getTotal_amount() < threshold) {
            return null;
        }
        if (merchantMfaService == null || !merchantMfaService.isEnabled(sessionUser.getId())) {
            return GeneralException.getError("149", GeneralException.ERRORS_149);
        }
        String mfaCode = requestBody.optString("mfa_code", "");
        if (mfaCode.trim().isEmpty()
                || !merchantMfaService.verifyCode(sessionUser.getId(), mfaCode.trim())) {
            JSONObject resJson = new JSONObject();
            resJson.put("code", "STEP_UP_MFA_REQUIRED");
            resJson.put(
                    "message",
                    "This payout's total ("
                            + payment.getTotal_amount()
                            + ") exceeds the step-up threshold. Provide a fresh mfa_code to"
                            + " continue.");
            return resJson.toString();
        }
        return null;
    }

    private double parseStepUpThreshold(Setting setting) {
        if (setting == null
                || setting.getSetting_value() == null
                || setting.getSetting_value().trim().isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(setting.getSetting_value().trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /*
     * API to start a payment
     */
    @PostMapping(path = "/stopPayment")
    public String stopPayment(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header
        try {

            if (!isMerchantUserLoggedIn(request)) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            HttpSession session = request.getSession();

            MerchantUser sessionUser = (MerchantUser) session.getAttribute("merchantUser");

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("CREATE_BATCH_TX", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            JSONObject sObject = new JSONObject(requestBody);

            String created_by = sessionUser.getName() + " - " + sessionUser.getEmail();
            long id = sObject.getLong("id");

            // Check if this payement exists
            Payment payment = getPaymentById(id);
            if (payment == null) {
                return GeneralException.getError(
                        "108", String.format(GeneralException.ERRORS_108, "Payment ", id));
            }

            // If payment is already in an uneditable state
            String pStatus = payment.getStatus();
            if (pStatus.equals(Transaction.BATCH_PAYMENTS_DONE)
                    || pStatus.equals(Transaction.BATCH_PAYMENTS_STOPPED)) {
                return GeneralException.getError(
                        "128", String.format(GeneralException.ERRORS_128, payment.getStatus()));
            }

            payment.setStatus(Transaction.BATCH_PAYMENTS_STOPPED);

            // Now add the user to database
            String sql =
                    "UPDATE "
                            + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_LOG
                            + " "
                            + " SET `status`=:status"
                            + " WHERE id=:id";

            MapSqlParameterSource parameters = new MapSqlParameterSource();
            parameters.addValue("id", id);
            parameters.addValue("status", payment.getStatus());

            final String sql_ = sql;

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            String result =
                    template.execute(
                            new TransactionCallback<String>() {
                                @Override
                                public String doInTransaction(TransactionStatus status) {
                                    try {

                                        // Update Bulk payments
                                        jdbcTemplate.update(sql_, parameters);

                                        // Now insert auditTrail
                                        String actionInsert =
                                                Common.recordMerchantAction(
                                                        sessionUser,
                                                        "Stopped payment " + payment.toString(),
                                                        jdbcTemplate);

                                        // If it failed to execute the statement to record this
                                        // action
                                        if (!actionInsert.equals("success")) {
                                            status.setRollbackOnly();
                                            return actionInsert;
                                        }

                                        // TransactionManager.commit(status);
                                        return "success";
                                    } catch (Exception e) {
                                        // transactionManager.rollback(status);
                                        status.setRollbackOnly();
                                        return GeneralException.getError(
                                                "102", GeneralException.ERRORS_102);
                                    }
                                }
                            });

            if (result.equals("success")) {

                return GeneralSuccessResponse.getMessage("000", GeneralSuccessResponse.SUCCESS_000);
            } else {
                return result;
            }
        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @Value("${custom.lockfiledirectory}")
    private String lockfiledirectory;

    // Audit G1: distributed lock (net.citotech.cito.config.SchedulerLockConfig) backed by the
    // `shedlock` DB table (V29__shedlock.sql) - like testCheckstatusCron() above, this method is
    // scheduler-only and actually disburses money (Common.doPayOut per beneficiary), so two
    // instances running it concurrently would mean
    // the same batch's beneficiaries could be paid out twice. lockAtMostFor=20m: this loop pulls up
    // to 20 PROCESSING batches per run, each with up to ~31 new beneficiary payouts triggered
    // (ben_count capped at >30), so worst case ~620 synchronous gateway payout calls in one run;
    // 20 minutes is a generous-but-bounded crash-recovery ceiling above realistic run time, chosen
    // over an unbounded/very-long value so a genuinely crashed instance doesn't leave the payout
    // queue stuck for hours. lockAtLeastFor=30s matches the 30s fixedDelay, for the same
    // same-instance-immediate-reacquisition / clock-skew reason as testCheckstatusCron().
    //
    // The pre-existing local java.nio.channels.FileLock below (guarding
    // Common.CLASS_PATH_PAYMENTS_CRON_TX_LOCK) is kept as defense-in-depth, not removed - see the
    // longer note above testCheckstatusCron() for the reasoning (ShedLock is now the authoritative
    // cross-instance guard; the file lock is redundant under normal operation but harmless to keep,
    // and removing it would mean reworking several early-return points in this money-movement path
    // for no real benefit).
    @Scheduled(fixedDelay = 30000, initialDelay = 1000)
    @SchedulerLock(name = "paymentsPayCron", lockAtMostFor = "PT20M", lockAtLeastFor = "PT30S")
    public String paymentsPayCron() {
        // Set the response header

        String filePath = lockfiledirectory + Common.CLASS_PATH_PAYMENTS_CRON_TX_LOCK;
        Logger.getLogger(TransactionsLogController.class.getName())
                .log(Level.FINE, "LockFile " + filePath);
        try {

            File lfile = new File(filePath);
            if (lfile.createNewFile()) {
                Logger.getLogger(TransactionsLogController.class.getName())
                        .log(Level.FINE, "File " + filePath + " has been created.");
            }

            RandomAccessFile writer = new RandomAccessFile(lfile, "rw");
            FileLock lock = writer.getChannel().lock();

            writer.write("Am handling lock!".getBytes());

            /*try {
                TimeUnit.SECONDS.sleep(20);
            } catch (InterruptedException ex) {
                Logger.getLogger(TransactionsLogController.class.getName())
                        .log(Level.SEVERE, ex.getMessage(), ex);
            }*/

            MapSqlParameterSource parameters = new MapSqlParameterSource();
            String sqlSelectPayments =
                    "SELECT *  FROM "
                            + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_LOG
                            + " "
                            + " WHERE status IN('PROCESSING') LIMIT 20 FOR UPDATE";

            RowMapper<Payment> rm =
                    new RowMapper<Payment>() {
                        public Payment mapRow(ResultSet rs, int rowNum) throws SQLException {
                            Payment t = new Payment();
                            t.setId(rs.getLong("id"));
                            t.setName(rs.getString("name"));
                            t.setPaymentId(rs.getString("batch_id"));
                            t.setDescription(rs.getString("tx_description"));
                            t.setStatus(rs.getString("status"));
                            t.setCreated_on(rs.getString("created_on"));
                            t.setMerchant_id(rs.getLong("merchant_id"));
                            t.setTotal_amount(rs.getDouble("total_amount"));
                            t.setTotal_charges(rs.getDouble("total_charges"));
                            t.setCreated_by(rs.getString("created_by"));
                            t.setBeneficiaries(getBatchBeneficiaries(t.getId()));
                            return t;
                        }
                    };

            List<Payment> pendingPayments = jdbcTemplate.query(sqlSelectPayments, parameters, rm);

            TransactionTemplate template;
            String result;

            // For each payment execute individual beneficiary payment if not yet.
            for (Payment p : pendingPayments) {

                template = new TransactionTemplate(transactionManager);
                template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                result =
                        template.execute(
                                new TransactionCallback<String>() {
                                    @Override
                                    public String doInTransaction(TransactionStatus status) {
                                        try {
                                            // Lock the batch and atomically reserve the complete
                                            // upcoming slice before the first external provider
                                            // call. A later beneficiary can no longer roll an
                                            // earlier successful provider payout back merely
                                            // because the aggregate balance was insufficient.
                                            if (!batchPayoutFundingGuard.reserveProcessingSlice(
                                                    p.getId(), p.getMerchant_id())) {
                                                return "success";
                                            }

                                            // First check if the payment was already started for
                                            // each beneficiary
                                            int ben_count = 0;
                                            int completelly_processed = 0;
                                            for (Beneficiary b : p.getBeneficiaries()) {

                                                Transaction t =
                                                        Common.getTxByBatchIdBeneficiaryId(
                                                                p.getId(), b.getId(), jdbcTemplate);

                                                if (t == null
                                                        && !Transaction.BATCH_PAYMENT_UNPAID.equals(
                                                                b.getStatus())) {
                                                    if (Transaction.BATCH_PAYMENT_PAID.equals(
                                                                    b.getStatus())
                                                            || Transaction.BATCH_PAYMENT_FAILED
                                                                    .equals(b.getStatus())) {
                                                        completelly_processed += 1;
                                                    }
                                                    continue;
                                                }

                                                if (t == null) {

                                                    // Limit payments per batch.
                                                    if (ben_count > 30) {
                                                        continue;
                                                    }

                                                    Merchant merchant =
                                                            Common.getMerchantById(
                                                                    p.getMerchant_id() + "",
                                                                    jdbcTemplate);

                                                    String gateway_id =
                                                            DoPayGateway.getGatewayIdByMsisdn(
                                                                    b.getAccount(), jdbcTemplate);

                                                    if (gateway_id == null) {
                                                        String e =
                                                                String.format(
                                                                        GeneralException.ERRORS_118,
                                                                        b.getAccount());

                                                        String sqlUpdateBen =
                                                                "UPDATE "
                                                                        + " "
                                                                        + Common
                                                                                .DB_TABLE_MERCHANT_BATCH_TRANSACTION_BENEFICIARIES
                                                                        + "  SET `reason`=:reason,"
                                                                        + " status=:status  WHERE"
                                                                        + " id=:id";

                                                        MapSqlParameterSource updateBenparams =
                                                                new MapSqlParameterSource();
                                                        updateBenparams.addValue("id", b.getId());
                                                        updateBenparams.addValue("reason", e);
                                                        updateBenparams.addValue(
                                                                "status",
                                                                Transaction.BATCH_PAYMENT_FAILED);

                                                        jdbcTemplate.update(
                                                                sqlUpdateBen, updateBenparams);
                                                        continue;
                                                    }

                                                    Transaction newTx = new Transaction();
                                                    newTx.setGateway_id(gateway_id);
                                                    newTx.setOriginal_amount(b.getAmount());
                                                    newTx.setPayer_number(b.getAccount());
                                                    newTx.setStatus("PENDING");
                                                    newTx.setMerchant_id(p.getMerchant_id() + "");
                                                    newTx.setTx_description(
                                                            merchant.getShort_name());
                                                    String bDescription =
                                                            "Batch "
                                                                    + p.getPaymentId()
                                                                    + ": "
                                                                    + b.getAccount();
                                                    newTx.setTx_merchant_description(bDescription);
                                                    newTx.setTx_type(Transaction.TX_TYPE_PAYOUT);
                                                    String stablePayoutReference =
                                                            BatchPayoutFundingGuard.sourceReference(
                                                                    p.getId(), b.getId());
                                                    String tx_id =
                                                            UUID.nameUUIDFromBytes(
                                                                            stablePayoutReference
                                                                                    .getBytes(
                                                                                            StandardCharsets
                                                                                                    .UTF_8))
                                                                    .toString();
                                                    if (gateway_id.equals(
                                                                    AirtelMoneyPaymentGateway
                                                                            .gateway_id)
                                                            || gateway_id.equals(
                                                                    AirtelMoneyOpenApiPaymentGateway
                                                                            .gateway_id)) {
                                                        tx_id = tx_id.substring(0, 15);
                                                    }
                                                    newTx.setTx_unique_id(tx_id);
                                                    newTx.setTx_merchant_ref(stablePayoutReference);
                                                    newTx.setCallback_url("");
                                                    newTx.setOriginate_ip("localhost");
                                                    // First get the charging method
                                                    GatewayChargeDetails gwChargingDetails =
                                                            DoPayGateway
                                                                    .getGatewayChargeDetailsById(
                                                                            jdbcTemplate,
                                                                            gateway_id,
                                                                            p.getMerchant_id());
                                                    newTx.setCharging_method(
                                                            gwChargingDetails
                                                                    .getCustomerOutboundChargeMethod());
                                                    Double charges =
                                                            DoPayGateway.getCustomerOutboundCharges(
                                                                    b.getAmount(),
                                                                    gwChargingDetails);
                                                    Double tx_cost =
                                                            DoPayGateway.getCostOfOutboundCharges(
                                                                    b.getAmount(),
                                                                    gwChargingDetails);

                                                    // First check if there is enough balance.
                                                    ArrayList<Balance> balances =
                                                            Common.getMerchantBalances(
                                                                    p.getMerchant_id() + "",
                                                                    jdbcTemplate);
                                                    String insufficient_b_error = "";
                                                    for (Balance bal : balances) {
                                                        if (bal.getGateway_id()
                                                                .equals(gateway_id)) {
                                                            if ((charges + b.getAmount())
                                                                    > bal.getAmount()) {

                                                                insufficient_b_error =
                                                                        String.format(
                                                                                GeneralException
                                                                                        .ERRORS_111,
                                                                                bal.getAmount(),
                                                                                bal.getCode());
                                                            }
                                                        }
                                                    }
                                                    if (!insufficient_b_error.isEmpty()) {
                                                        // Now update the transaction to database
                                                        String sqlUpdateBen =
                                                                "UPDATE "
                                                                        + " "
                                                                        + Common
                                                                                .DB_TABLE_MERCHANT_BATCH_TRANSACTION_BENEFICIARIES
                                                                        + " "
                                                                        + " SET `reason`=:reason "
                                                                        + " WHERE id=:id";

                                                        MapSqlParameterSource updateBenparams =
                                                                new MapSqlParameterSource();
                                                        updateBenparams.addValue("id", b.getId());
                                                        updateBenparams.addValue(
                                                                "reason", insufficient_b_error);

                                                        jdbcTemplate.update(
                                                                sqlUpdateBen, updateBenparams);
                                                        Logger.getLogger(
                                                                        AuthenticationController
                                                                                .class
                                                                                .getName())
                                                                .log(
                                                                        Level.SEVERE,
                                                                        "INSUFFICIENT BALANCE: "
                                                                                + insufficient_b_error,
                                                                        "");
                                                        continue;
                                                    }

                                                    newTx.setBeneficiary_id(b.getId());
                                                    newTx.setMerchant_batch_transactions_log_id(
                                                            p.getId());

                                                    newTx.setCharges(charges);
                                                    newTx.setTx_cost(tx_cost);
                                                    newTx.setTx_request_trace("");
                                                    newTx.setTx_update_trace("");
                                                    newTx.setTx_gateway_ref("");

                                                    // The complete slice was reserved atomically by
                                                    // BatchPayoutFundingGuard before entering this
                                                    // loop. Capture or release this beneficiary's
                                                    // stable reservation after the provider result.
                                                    String batchReservationReference =
                                                            BatchPayoutFundingGuard
                                                                    .reservationReference(
                                                                            p.getId(), b.getId());

                                                    String resultPay;
                                                    try {
                                                        resultPay =
                                                                Common.doPayOut(
                                                                        newTx,
                                                                        merchant,
                                                                        jdbcTemplate,
                                                                        transactionManager);
                                                    } catch (RuntimeException payoutEx) {
                                                        ledgerService.releaseReservation(
                                                                batchReservationReference);
                                                        throw payoutEx;
                                                    }

                                                    Logger.getLogger(
                                                                    AuthenticationController.class
                                                                            .getName())
                                                            .log(
                                                                    Level.SEVERE,
                                                                    "PAY RESULTS: " + resultPay,
                                                                    "");

                                                    // Audit A1/B1: reserve/capture/release above
                                                    // only manage the
                                                    // temporary funds-hold - they never wrote the
                                                    // permanent
                                                    // double-entry transaction (debit/credit
                                                    // account entries).
                                                    // Post it here, unconditionally like the v2
                                                    // orchestration path
                                                    // does, keyed by tx_unique_id so it can never
                                                    // double-post.
                                                    legacyLedgerPostingService.postPaymentEntries(
                                                            Transaction.TX_TYPE_PAYOUT,
                                                            gateway_id,
                                                            merchant,
                                                            newTx,
                                                            b.getAmount(),
                                                            charges);

                                                    // Now update this particular beneficiary
                                                    JSONObject rObject = new JSONObject(resultPay);
                                                    boolean payoutSucceeded =
                                                            rObject.getString("state").equals("OK")
                                                                    && rObject.getString("code")
                                                                            .equals("000");
                                                    if (payoutSucceeded) {
                                                        ledgerService.captureReservation(
                                                                batchReservationReference);
                                                    } else {
                                                        ledgerService.releaseReservation(
                                                                batchReservationReference);
                                                    }
                                                    if (payoutSucceeded) {

                                                        String sqlUpdateBen =
                                                                "UPDATE "
                                                                        + " "
                                                                        + Common
                                                                                .DB_TABLE_MERCHANT_BATCH_TRANSACTION_BENEFICIARIES
                                                                        + " "
                                                                        + " SET `status`=:status"
                                                                        + " WHERE id=:id";

                                                        MapSqlParameterSource updateBenparams =
                                                                new MapSqlParameterSource();
                                                        updateBenparams.addValue("id", b.getId());
                                                        updateBenparams.addValue(
                                                                "status",
                                                                Transaction
                                                                        .BATCH_PAYMENT_INPROGRESS);
                                                        updateBenparams.addValue(
                                                                "reason",
                                                                rObject.getString("message"));
                                                        jdbcTemplate.update(
                                                                sqlUpdateBen, updateBenparams);

                                                    } else {
                                                        String sqlUpdateBen =
                                                                "UPDATE "
                                                                        + " "
                                                                        + Common
                                                                                .DB_TABLE_MERCHANT_BATCH_TRANSACTION_BENEFICIARIES
                                                                        + "  SET `status`=:status,"
                                                                        + " reason=:reason WHERE"
                                                                        + " id=:id";

                                                        MapSqlParameterSource updateBenparams =
                                                                new MapSqlParameterSource();
                                                        updateBenparams.addValue("id", b.getId());
                                                        updateBenparams.addValue(
                                                                "status",
                                                                Transaction.BATCH_PAYMENT_FAILED);
                                                        updateBenparams.addValue(
                                                                "reason",
                                                                rObject.getString("message"));
                                                        jdbcTemplate.update(
                                                                sqlUpdateBen, updateBenparams);
                                                    }

                                                    // Increment the counter for those processed.
                                                    ben_count++;
                                                } else {
                                                    // If tx already exists, then update instead
                                                    if (t.getStatus().equals("SUCCESSFUL")) {
                                                        String sqlUpdateBen =
                                                                "UPDATE "
                                                                        + " "
                                                                        + Common
                                                                                .DB_TABLE_MERCHANT_BATCH_TRANSACTION_BENEFICIARIES
                                                                        + "  SET `status`=:status,"
                                                                        + " reason=:reason WHERE"
                                                                        + " id=:id";

                                                        MapSqlParameterSource updateBenparams =
                                                                new MapSqlParameterSource();
                                                        updateBenparams.addValue("id", b.getId());
                                                        updateBenparams.addValue(
                                                                "status",
                                                                Transaction.BATCH_PAYMENT_PAID);
                                                        updateBenparams.addValue("reason", "");
                                                        jdbcTemplate.update(
                                                                sqlUpdateBen, updateBenparams);
                                                        completelly_processed += 1;
                                                    } else if (t.getStatus().equals("FAILED")) {
                                                        String sqlUpdateBen =
                                                                "UPDATE "
                                                                        + " "
                                                                        + Common
                                                                                .DB_TABLE_MERCHANT_BATCH_TRANSACTION_BENEFICIARIES
                                                                        + "  SET `status`=:status,"
                                                                        + " reason=:reason WHERE"
                                                                        + " id=:id";

                                                        MapSqlParameterSource updateBenparams =
                                                                new MapSqlParameterSource();
                                                        updateBenparams.addValue("id", b.getId());
                                                        updateBenparams.addValue(
                                                                "status",
                                                                Transaction.BATCH_PAYMENT_FAILED);
                                                        updateBenparams.addValue(
                                                                "reason", "Gateway error.");
                                                        jdbcTemplate.update(
                                                                sqlUpdateBen, updateBenparams);
                                                        completelly_processed += 1;
                                                    }
                                                }
                                            }

                                            // If all are processed, then update this payment
                                            if (p.getBeneficiaries().size()
                                                    == completelly_processed) {
                                                String sqlUpdatePaym =
                                                        "UPDATE "
                                                                + " "
                                                                + Common
                                                                        .DB_TABLE_MERCHANT_BATCH_TRANSACTION_LOG
                                                                + " "
                                                                + " SET `status`=:status"
                                                                + " WHERE id=:id";

                                                MapSqlParameterSource updatePayparams =
                                                        new MapSqlParameterSource();
                                                updatePayparams.addValue("id", p.getId());
                                                updatePayparams.addValue(
                                                        "status", Transaction.BATCH_PAYMENTS_DONE);

                                                jdbcTemplate.update(sqlUpdatePaym, updatePayparams);
                                            }

                                            return "success";
                                        } catch (Exception e) {
                                            Logger.getLogger(
                                                            TransactionsLogController.class
                                                                    .getName())
                                                    .log(Level.SEVERE, e.getMessage(), e);
                                            e.printStackTrace();
                                            // transactionManager.rollback(status);
                                            status.setRollbackOnly();
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.SEVERE,
                                                            "INTERNAL ERROR - BATCH PROCESSING TX: "
                                                                    + e.getMessage()
                                                                    + " "
                                                                    + e.getStackTrace(),
                                                            "");
                                            return GeneralException.getError(
                                                    "102", GeneralException.ERRORS_102);
                                        }
                                    }
                                });

                if (result.equals("success")) {
                    continue;
                } else {
                    break;
                }
            }

            // release lock
            lock.release();
            // close the file
            writer.close();

            Logger.getLogger(TransactionsLogController.class.getName())
                    .log(Level.FINE, "Payments cron completed");

        } catch (IOException ex) {
            Logger.getLogger(AuthenticationController.class.getName())
                    .log(Level.SEVERE, "PAYMENTS CRON IOException: " + ex.getMessage(), "");

            return GeneralException.ERRORS_102;
        } catch (java.nio.channels.OverlappingFileLockException ex) {
            Logger.getLogger(AuthenticationController.class.getName())
                    .log(
                            Level.SEVERE,
                            "PAYMENTS CRON OverlappingFileLockException: " + ex.getMessage(),
                            "");

            return "OverlappingFileLockException";
        }

        // Execution successfully.
        return GeneralSuccessResponse.getMessage("000", GeneralSuccessResponse.SUCCESS_000);
    }

    /*
     * API to start a payment
     */
    @PostMapping(path = "/testParseXml")
    public String testParseXml(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + "<AutoCreate><Response>";
        xml += "<Status>ERROR</Status>";
        xml += "<StatusCode>500</StatusCode>";
        xml += "<TransactionStatus>FAILED</TransactionStatus>";
        xml += "<ErrorMessageCode>THIS IS AN ERROR FROM GATEWAY</ErrorMessageCode>";
        xml += "<ErrorMessage>CHECK OTHER FIELDS FOR ERRORS</ErrorMessage>";
        xml += "<TransactionReference>120010010212</TransactionReference>";
        xml += "</Response></AutoCreate>";
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbFactory.setXIncludeAware(false);
            dbFactory.setExpandEntityReferences(false);
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(new InputSource(new StringReader(xml)));
            doc.getDocumentElement().normalize();
            NodeList nList = doc.getElementsByTagName("Response");

            String return_string = "";
            for (int temp = 0; temp < nList.getLength(); temp++) {
                Node nNode = nList.item(temp);
                if (nNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element eElement = (Element) nNode;
                    String status =
                            eElement.getElementsByTagName("Status").item(0).getTextContent();
                    String txstatus =
                            eElement.getElementsByTagName("TransactionStatus")
                                    .item(0)
                                    .getTextContent();
                    return_string += " Transaction Status: " + txstatus;
                }
            }
            return return_string;
        } catch (Exception e) {
            return GeneralException.ERRORS_102;
        }
    }

    /*
     * API to start a payment
     */
    @PostMapping(path = "/resolveTransaction")
    public String resolveTransaction(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header
        try {

            if (!isLoggedIn(request)) {
                return GeneralException.getError("107", GeneralException.ERRORS_107);
            }
            HttpSession session = request.getSession();

            User sessionUser = (User) session.getAttribute("user");

            // Check permissions
            if (!Common.isUserAllowedAccessToThis("RESOLVE_TRANSACTIONS", sessionUser)) {
                return GeneralException.getError("110", GeneralException.ERRORS_110);
            }

            JSONObject sObject = new JSONObject(requestBody);

            String created_by = sessionUser.getName() + " - " + sessionUser.getEmail();
            long transactionId = sObject.getLong("id");
            String resolve_to_status = sObject.getString("resolve_status");
            String tx_gateway_ref = sObject.getString("tx_gateway_ref");

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            String result =
                    template.execute(
                            new TransactionCallback<String>() {
                                @Override
                                public String doInTransaction(TransactionStatus status) {

                                    // Check if this transaction exists
                                    String sqlSelect =
                                            "SELECT *  FROM "
                                                    + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                                    + " ";
                                    sqlSelect += " WHERE id = :id FOR UPDATE";

                                    RowMapper<Transaction> rm = Common.getTransactionRowMapper();
                                    List<Transaction> listTxs =
                                            jdbcTemplate.query(
                                                    sqlSelect,
                                                    new MapSqlParameterSource("id", transactionId),
                                                    rm);
                                    if (listTxs.size() < 1) {
                                        return GeneralException.getError(
                                                "109",
                                                String.format(
                                                        GeneralException.ERRORS_109,
                                                        "Transaction"));
                                    }

                                    Setting getStockAccount =
                                            Common.getSettings("float_stock_account", jdbcTemplate);
                                    Setting getRevenueAccount =
                                            Common.getSettings("revenue_account", jdbcTemplate);
                                    Setting getSuspenseAccount =
                                            Common.getSettings("suspense_account", jdbcTemplate);

                                    String stock_account_number =
                                            getStockAccount.getSetting_value().trim();
                                    String suspense_account_number =
                                            getSuspenseAccount.getSetting_value().trim();
                                    String revenue_account_number =
                                            getRevenueAccount.getSetting_value().trim();

                                    Merchant float_stock_account =
                                            Common.getMerchantByAccountNumber(
                                                    stock_account_number, jdbcTemplate);

                                    Merchant suspense_stock_account =
                                            Common.getMerchantByAccountNumber(
                                                    suspense_account_number, jdbcTemplate);

                                    Merchant revenue_stock_account =
                                            Common.getMerchantByAccountNumber(
                                                    revenue_account_number, jdbcTemplate);

                                    Transaction newTx = listTxs.get(0);
                                    if (newTx.getStatus().equals("SUCCESSFUL")
                                            || newTx.getStatus().equals("FAILED")) {
                                        return GeneralException.getError(
                                                "130",
                                                String.format(
                                                        GeneralException.ERRORS_130,
                                                        newTx.getStatus()));
                                    }

                                    String[] bType =
                                            Balance.getBalanceTypeByGatewayId(
                                                    newTx.getGateway_id());
                                    String balance_type = bType[0];

                                    Merchant merchant =
                                            Common.getMerchantById(
                                                    newTx.getMerchant_id(), jdbcTemplate);

                                    String res_string = "";
                                    String sqlUpdateTx =
                                            "UPDATE "
                                                    + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                                    + " ";
                                    sqlUpdateTx +=
                                            " SET status=:status, "
                                                    + " tx_gateway_ref=:tx_gateway_ref, "
                                                    + " resolved_by=:resolved_by "
                                                    + "  WHERE id =:id ";
                                    MapSqlParameterSource updateParam = new MapSqlParameterSource();
                                    updateParam.addValue("status", resolve_to_status);
                                    updateParam.addValue("tx_gateway_ref", tx_gateway_ref);
                                    updateParam.addValue("id", transactionId);
                                    updateParam.addValue("resolved_by", created_by);

                                    // First update this tx status
                                    jdbcTemplate.update(sqlUpdateTx, updateParam);

                                    if (resolve_to_status.equals("SUCCESSFUL")) {
                                        if (newTx.getTx_type().equals(Transaction.TX_TYPE_PAYIN)) {
                                            // Credit this customer's account.
                                            Statement newTxS = new Statement();
                                            newTxS.setAmount(newTx.getOriginal_amount());
                                            newTxS.setGateway_id(newTx.getGateway_id());
                                            newTxS.setNarritive(newTx.getTx_type());
                                            newTxS.setTransactions_log_id(newTx.getId());
                                            newTxS.setMerchant_id(
                                                    Long.parseLong(newTx.getMerchant_id()));
                                            newTxS.setDescription(newTx.getTx_description());
                                            newTxS.setRecorded_by("SYSTEM");
                                            newTxS.setTx_type("CR");

                                            res_string =
                                                    Common.recordStatementTxWithoutTransaction(
                                                            newTxS,
                                                            balance_type,
                                                            jdbcTemplate,
                                                            transactionManager,
                                                            status);
                                            if (!res_string.equals("success")) {
                                                return res_string;
                                            }

                                            newTxS = new Statement();
                                            newTxS.setAmount(newTx.getCharges());
                                            newTxS.setGateway_id(newTx.getGateway_id());

                                            newTxS.setNarritive(Transaction.TX_TYPE_PAYIN_CHARGE);
                                            newTxS.setTransactions_log_id(newTx.getId());
                                            newTxS.setMerchant_id(merchant.getId());
                                            newTxS.setDescription(newTx.getTx_description());
                                            newTxS.setRecorded_by("SYSTEM");
                                            newTxS.setTx_type("DR");

                                            res_string =
                                                    Common.recordStatementTx(
                                                            newTxS,
                                                            balance_type,
                                                            jdbcTemplate,
                                                            transactionManager);
                                            if (!res_string.equals("success")) {
                                                return res_string;
                                            }

                                            // Now record this revenue account.
                                            newTxS = new Statement();
                                            newTxS.setAmount(newTx.getCharges());
                                            newTxS.setGateway_id(newTx.getGateway_id());

                                            newTxS.setNarritive(Transaction.TX_TYPE_PAYIN_REVENUE);
                                            newTxS.setTransactions_log_id(newTx.getId());
                                            newTxS.setMerchant_id(revenue_stock_account.getId());
                                            newTxS.setDescription(newTx.getTx_description());
                                            newTxS.setRecorded_by("SYSTEM");
                                            newTxS.setTx_type("CR");

                                            res_string =
                                                    Common.recordStatementTxWithoutTransaction(
                                                            newTxS,
                                                            balance_type,
                                                            jdbcTemplate,
                                                            transactionManager,
                                                            status);
                                            if (!res_string.equals("success")) {
                                                return res_string;
                                            }

                                            // Now increase stock account.
                                            newTxS = new Statement();
                                            newTxS.setAmount(newTx.getOriginal_amount());
                                            newTxS.setGateway_id(newTx.getGateway_id());

                                            newTxS.setNarritive(Transaction.TX_TYPE_PAYIN);
                                            newTxS.setTransactions_log_id(newTx.getId());
                                            newTxS.setMerchant_id(float_stock_account.getId());
                                            newTxS.setDescription(newTx.getTx_description());
                                            newTxS.setRecorded_by("SYSTEM");
                                            newTxS.setTx_type("CR");

                                            res_string =
                                                    Common.recordStatementTxWithoutTransaction(
                                                            newTxS,
                                                            balance_type,
                                                            jdbcTemplate,
                                                            transactionManager,
                                                            status);
                                            if (!res_string.equals("success")) {
                                                return res_string;
                                            }
                                        } else if (newTx.getTx_type()
                                                .equals(Transaction.TX_TYPE_PAYOUT)) {
                                            // Record a settlement transaction for Payout
                                            Statement newTxS = new Statement();
                                            newTxS = new Statement();
                                            newTxS.setAmount(newTx.getOriginal_amount());
                                            newTxS.setGateway_id(newTx.getGateway_id());

                                            newTxS.setNarritive(
                                                    Transaction.TX_TYPE_PAYOUT_SETTLEMENT);
                                            newTxS.setTransactions_log_id(newTx.getId());
                                            newTxS.setMerchant_id(suspense_stock_account.getId());
                                            newTxS.setDescription(newTx.getTx_description());
                                            newTxS.setRecorded_by("SYSTEM");
                                            newTxS.setTx_type("DR");
                                            res_string =
                                                    Common.recordStatementTxWithoutTransaction(
                                                            newTxS,
                                                            balance_type,
                                                            jdbcTemplate,
                                                            transactionManager,
                                                            status);
                                            if (!res_string.equals("success")) {

                                                return res_string;
                                            }

                                            // Record a settlement transaction for Payout charge
                                            newTxS = new Statement();
                                            newTxS.setAmount(newTx.getCharges());
                                            newTxS.setGateway_id(newTx.getGateway_id());

                                            newTxS.setNarritive(
                                                    Transaction.TX_TYPE_PAYOUT_CHARGE_SETTLEMENT);
                                            newTxS.setTransactions_log_id(newTx.getId());
                                            newTxS.setMerchant_id(suspense_stock_account.getId());
                                            newTxS.setDescription(newTx.getTx_description());
                                            newTxS.setRecorded_by("SYSTEM");
                                            newTxS.setTx_type("DR");
                                            res_string =
                                                    Common.recordStatementTxWithoutTransaction(
                                                            newTxS,
                                                            balance_type,
                                                            jdbcTemplate,
                                                            transactionManager,
                                                            status);
                                            if (!res_string.equals("success")) {

                                                return res_string;
                                            }

                                            // Record Revenue to revenue account
                                            newTxS = new Statement();
                                            newTxS.setAmount(newTx.getCharges());
                                            newTxS.setGateway_id(newTx.getGateway_id());

                                            newTxS.setNarritive(Transaction.TX_TYPE_PAYOUT_REVENUE);
                                            newTxS.setTransactions_log_id(newTx.getId());
                                            newTxS.setMerchant_id(revenue_stock_account.getId());
                                            newTxS.setDescription(newTx.getTx_description());
                                            newTxS.setRecorded_by("SYSTEM");
                                            newTxS.setTx_type("CR");
                                            res_string =
                                                    Common.recordStatementTxWithoutTransaction(
                                                            newTxS,
                                                            balance_type,
                                                            jdbcTemplate,
                                                            transactionManager,
                                                            status);
                                            if (!res_string.equals("success")) {
                                                return res_string;
                                            }
                                        }
                                    } else if (resolve_to_status.equals("FAILED")) {
                                        if (newTx.getTx_type().equals(Transaction.TX_TYPE_PAYOUT)) {
                                            // Dr the amount on suspense account
                                            Statement newTxS = new Statement();
                                            newTxS.setAmount(newTx.getOriginal_amount());
                                            newTxS.setGateway_id(newTx.getGateway_id());

                                            newTxS.setNarritive(
                                                    Transaction.TX_TYPE_PAYOUT_REVERSAL);
                                            newTxS.setTransactions_log_id(newTx.getId());
                                            newTxS.setMerchant_id(suspense_stock_account.getId());
                                            newTxS.setDescription(newTx.getTx_description());
                                            newTxS.setRecorded_by("SYSTEM");
                                            newTxS.setTx_type("DR");

                                            res_string =
                                                    Common.recordStatementTxWithoutTransaction(
                                                            newTxS,
                                                            balance_type,
                                                            jdbcTemplate,
                                                            transactionManager,
                                                            status);
                                            if (!res_string.equals("success")) {

                                                return res_string;
                                            }

                                            // DR the charge reversal
                                            newTxS = new Statement();
                                            newTxS.setAmount(newTx.getCharges());
                                            newTxS.setGateway_id(newTx.getGateway_id());

                                            newTxS.setNarritive(
                                                    Transaction.TX_TYPE_PAYOUT_CHARGE_REVERSAL);
                                            newTxS.setTransactions_log_id(newTx.getId());
                                            newTxS.setMerchant_id(suspense_stock_account.getId());
                                            newTxS.setDescription(newTx.getTx_description());
                                            newTxS.setRecorded_by("SYSTEM");
                                            newTxS.setTx_type("DR");
                                            res_string =
                                                    Common.recordStatementTxWithoutTransaction(
                                                            newTxS,
                                                            balance_type,
                                                            jdbcTemplate,
                                                            transactionManager,
                                                            status);
                                            if (!res_string.equals("success")) {

                                                return res_string;
                                            }

                                            // CR the amount back to customer's account
                                            newTxS = new Statement();
                                            newTxS.setAmount(newTx.getOriginal_amount());
                                            newTxS.setGateway_id(newTx.getGateway_id());

                                            newTxS.setNarritive(
                                                    Transaction.TX_TYPE_PAYOUT_REVERSAL);
                                            newTxS.setTransactions_log_id(newTx.getId());
                                            newTxS.setMerchant_id(merchant.getId());
                                            newTxS.setDescription(newTx.getTx_description());
                                            newTxS.setRecorded_by("SYSTEM");
                                            newTxS.setTx_type("CR");
                                            res_string =
                                                    Common.recordStatementTxWithoutTransaction(
                                                            newTxS,
                                                            balance_type,
                                                            jdbcTemplate,
                                                            transactionManager,
                                                            status);
                                            if (!res_string.equals("success")) {

                                                return res_string;
                                            }

                                            // CR the charge back on customer's account
                                            newTxS = new Statement();
                                            newTxS.setAmount(newTx.getCharges());
                                            newTxS.setGateway_id(newTx.getGateway_id());

                                            newTxS.setNarritive(
                                                    Transaction.TX_TYPE_PAYOUT_CHARGE_REVERSAL);
                                            newTxS.setTransactions_log_id(newTx.getId());
                                            newTxS.setMerchant_id(merchant.getId());
                                            newTxS.setDescription(newTx.getTx_description());
                                            newTxS.setRecorded_by("SYSTEM");
                                            newTxS.setTx_type("CR");
                                            res_string =
                                                    Common.recordStatementTxWithoutTransaction(
                                                            newTxS,
                                                            balance_type,
                                                            jdbcTemplate,
                                                            transactionManager,
                                                            status);
                                            if (!res_string.equals("success")) {

                                                return res_string;
                                            }

                                            // Restore the float account
                                            newTxS = new Statement();
                                            newTxS.setAmount(newTx.getOriginal_amount());
                                            newTxS.setGateway_id(newTx.getGateway_id());

                                            newTxS.setNarritive(
                                                    Transaction.TX_TYPE_PAYOUT_REVERSAL);
                                            newTxS.setTransactions_log_id(newTx.getId());
                                            newTxS.setMerchant_id(float_stock_account.getId());
                                            newTxS.setDescription(newTx.getTx_description());
                                            newTxS.setRecorded_by("SYSTEM");
                                            newTxS.setTx_type("CR");
                                            res_string =
                                                    Common.recordStatementTxWithoutTransaction(
                                                            newTxS,
                                                            balance_type,
                                                            jdbcTemplate,
                                                            transactionManager,
                                                            status);
                                            if (!res_string.equals("success")) {
                                                return res_string;
                                            }
                                        }
                                    }
                                    return "success";
                                }
                            });

            if (result.equals("success")) {

                return GeneralSuccessResponse.getMessage("000", GeneralSuccessResponse.SUCCESS_000);
            } else {
                return result;
            }
        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName()).log(Level.SEVERE, null, ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    private Transaction getTransactionById(long id) {
        String sqlSelect = "SELECT *  FROM " + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG + " ";
        sqlSelect += " WHERE id = :id";

        RowMapper<Transaction> rm = Common.getTransactionRowMapper();
        List<Transaction> listTxs =
                jdbcTemplate.query(sqlSelect, new MapSqlParameterSource("id", id), rm);
        if (listTxs.size() > 0) {
            return listTxs.get(0);
        } else {
            return null;
        }
    }

    @PostMapping("/uploadBeneficiariesFile")
    public String uploadBeneficiariesFile(
            @RequestParam("file") MultipartFile file, HttpServletRequest request) {
        // Require an authenticated session
        HttpSession session = request.getSession();
        if (session.getAttribute("merchantUser") == null && session.getAttribute("user") == null) {
            return GeneralException.getError("107", GeneralException.ERRORS_107);
        }
        try {
            String validationError = validateSpreadsheetUpload(file);
            if (validationError != null) {
                return validationError;
            }
            /*File directory = new File(fileDestination);

            if (!directory.exists()) {
                directory.mkdir();
            }

            String unique_file_name = Common.generateUuid();
            String final_file_path = StringUtils.cleanPath(
                    fileDestination+File.separator+unique_file_name+"_"+file.getOriginalFilename()
            );

            Path copyLocation = Paths.get(final_file_path);
            Files.copy(file.getInputStream(), copyLocation, StandardCopyOption.REPLACE_EXISTING);
            */

            // Now process the file.
            String ext = Common.getExtensionByStringHandling(file.getOriginalFilename());
            if (!this.isSupportedExceExtension(ext)) {
                return GeneralException.getError(
                        "132",
                        String.format(
                                GeneralException.ERRORS_132,
                                file.getOriginalFilename() + " Extension: " + ext));
            }

            // FileInputStream file = new FileInputStream(new File(fileLocation));
            JSONArray bens = new JSONArray();
            try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
                Sheet sheet = workbook.getSheetAt(0);
                if (sheet.getLastRowNum() > MAX_UPLOAD_ROWS) {
                    return GeneralException.getError(
                            "131",
                            "Uploaded file has too many rows. Maximum allowed rows: "
                                    + MAX_UPLOAD_ROWS
                                    + ".");
                }

                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    JSONObject jObject = new JSONObject();
                    Row row = sheet.getRow(i);
                    // Row is null, then continue to next one.
                    if (row == null) {
                        continue;
                    }

                    Cell nameCell = row.getCell(0);
                    Cell accountCell = row.getCell(1);
                    Cell amountCell = row.getCell(2);

                    if (nameCell == null || accountCell == null || amountCell == null) {
                        continue;
                    }
                    try {
                        jObject.put("name", nameCell.getStringCellValue());
                    } catch (Exception e) {

                        return GeneralException.getError(
                                "131",
                                String.format(
                                                GeneralException.ERRORS_131,
                                                file.getOriginalFilename())
                                        + ". Cell A"
                                        + i);
                    }
                    try {
                        jObject.put("account", accountCell.getNumericCellValue());
                    } catch (Exception e) {
                        return GeneralException.getError(
                                "131",
                                String.format(
                                                GeneralException.ERRORS_131,
                                                file.getOriginalFilename())
                                        + ". Cell B"
                                        + i);
                    }
                    try {
                        jObject.put("amount", amountCell.getNumericCellValue());
                    } catch (Exception e) {
                        return GeneralException.getError(
                                "131",
                                String.format(
                                                GeneralException.ERRORS_131,
                                                file.getOriginalFilename())
                                        + ". Cell C"
                                        + i);
                    }

                    /*jObject.put("name", nameCell.getStringCellValue());
                    jObject.put("account", accountCell.getNumericCellValue());
                    jObject.put("amount", amountCell.getNumericCellValue());*/
                    jObject.put("account_type", "phone");
                    jObject.put("status", "ACTIVE");
                    jObject.put("delete", false);
                    jObject.put("id", "");

                    bens.put(jObject);
                }
            }

            JSONObject resJson = new JSONObject();
            resJson.put("state", "OK");
            resJson.put("code", "000");
            resJson.put("message", "");
            resJson.put("data", bens);

            return resJson.toString();
        } catch (Exception e) {
            Logger.getLogger(TransactionsLogController.class.getName())
                    .log(Level.SEVERE, e.getMessage(), e);
            return GeneralException.getError(
                    "131", String.format(GeneralException.ERRORS_131, file.getOriginalFilename()));
        }
    }

    @PostMapping("/uploadSmsRecipientsFile")
    public String uploadSmsRecipientsFile(
            @RequestParam("file") MultipartFile file, HttpServletRequest request) {
        // Require an authenticated session
        HttpSession session = request.getSession();
        if (session.getAttribute("merchantUser") == null && session.getAttribute("user") == null) {
            return GeneralException.getError("107", GeneralException.ERRORS_107);
        }

        Logger.getLogger(AuthenticationController.class.getName()).log(Level.SEVERE, "Params:", "");

        try {
            String validationError = validateSpreadsheetUpload(file);
            if (validationError != null) {
                return validationError;
            }

            // Now process the file.
            String ext = Common.getExtensionByStringHandling(file.getOriginalFilename());
            if (!this.isSupportedExceExtension(ext)) {
                return GeneralException.getError(
                        "132",
                        String.format(
                                GeneralException.ERRORS_132,
                                file.getOriginalFilename() + " Extension: " + ext));
            }

            // FileInputStream file = new FileInputStream(new File(fileLocation));
            JSONArray bens = new JSONArray();
            try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
                Sheet sheet = workbook.getSheetAt(0);
                if (sheet.getLastRowNum() > MAX_UPLOAD_ROWS) {
                    return GeneralException.getError(
                            "131",
                            "Uploaded file has too many rows. Maximum allowed rows: "
                                    + MAX_UPLOAD_ROWS
                                    + ".");
                }

                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    JSONObject jObject = new JSONObject();
                    Row row = sheet.getRow(i);
                    Cell phoneCell = row.getCell(0);
                    Cell cell1 = row.getCell(1);
                    Cell cell2 = row.getCell(2);
                    Cell cell3 = row.getCell(3);
                    Cell cell4 = row.getCell(4);
                    Cell cell5 = row.getCell(5);
                    Cell cell6 = row.getCell(6);
                    Cell cell7 = row.getCell(7);
                    Cell cell8 = row.getCell(8);
                    Cell cell9 = row.getCell(9);
                    Cell cell10 = row.getCell(10);
                    Cell cell11 = row.getCell(11);
                    Cell cell12 = row.getCell(12);

                    jObject.put("phone", phoneCell.getNumericCellValue());
                    jObject.put("cellB", getCellValueAsString(cell1));
                    jObject.put("cellC", getCellValueAsString(cell2));
                    jObject.put("cellD", getCellValueAsString(cell3));
                    jObject.put("cellE", getCellValueAsString(cell4));
                    jObject.put("cellF", getCellValueAsString(cell5));
                    jObject.put("cellG", getCellValueAsString(cell6));
                    jObject.put("cellH", getCellValueAsString(cell7));
                    jObject.put("cellI", getCellValueAsString(cell8));
                    jObject.put("cellJ", getCellValueAsString(cell9));
                    jObject.put("cellK", getCellValueAsString(cell10));
                    jObject.put("cellL", getCellValueAsString(cell11));
                    jObject.put("cellM", getCellValueAsString(cell12));
                    jObject.put("delete", false);
                    jObject.put("id", "");

                    bens.put(jObject);
                }
            }

            JSONObject resJson = new JSONObject();
            resJson.put("state", "OK");
            resJson.put("code", "000");
            resJson.put("message", "");
            resJson.put("data", bens);

            return resJson.toString();
        } catch (Exception e) {
            Logger.getLogger(TransactionsLogController.class.getName())
                    .log(Level.SEVERE, e.getMessage(), e);
            return GeneralException.getError(
                    "131", String.format(GeneralException.ERRORS_131, file.getOriginalFilename()));
        }
    }

    private String getCellValueAsString(Cell cell) {
        String r = "";
        if (cell == null) {
            return "";
        }
        try {
            r = cell.getStringCellValue();
        } catch (Exception ex) {
            double rd = cell.getNumericCellValue();
            r = rd + "";
        }
        return r;
    }

    private boolean isSupportedExceExtension(String ext) {
        if (ext == null) {
            return false;
        }
        if (ext.equalsIgnoreCase("xlsx")) {
            return true;
        } else if (ext.equalsIgnoreCase("xls")) {
            return true;
        } else {
            return false;
        }
    }

    private String validateSpreadsheetUpload(MultipartFile file) {
        String originalName =
                file == null
                        ? ""
                        : StringUtils.cleanPath(String.valueOf(file.getOriginalFilename()));
        if (file == null || file.isEmpty()) {
            return GeneralException.getError("131", "Uploaded file is empty.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return GeneralException.getError("131", "Uploaded file exceeds the 2 MB limit.");
        }
        String ext = Common.getExtensionByStringHandling(originalName);
        if (!isSupportedExceExtension(ext)) {
            return GeneralException.getError(
                    "132",
                    String.format(
                            GeneralException.ERRORS_132, originalName + " Extension: " + ext));
        }
        String contentType = file.getContentType();
        if (!isAllowedSpreadsheetContentType(contentType)) {
            return GeneralException.getError(
                    "132", "Unsupported upload content type: " + contentType + ".");
        }
        return null;
    }

    private boolean isAllowedSpreadsheetContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        .equalsIgnoreCase(contentType)
                || "application/vnd.ms-excel".equalsIgnoreCase(contentType)
                || "application/octet-stream".equalsIgnoreCase(contentType);
    }

    /**
     * A 2xx status is the only provider-agnostic signal available for this generic, settings-driven
     * HTTP SMS gateway integration (the response body format varies per configured provider) -
     * matches the same check net.citotech.cito.webhook.MerchantWebhookService uses for webhook
     * delivery success (audit P5).
     */
    boolean isSuccessfulSmsGatewayResponse(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    @PostMapping(path = "/testSendPendingSmsCron")
    // Audit G1-bis: this SMS pend-send batch bills the merchant (DR), sends the SMS, and reverses
    // (CR) on failure. Before this it was guarded only by a single-host file lock
    // (Common.CLASS_PATH_SEND_SMS_SERVICE_TX_LOCK), so two replicas could run the same PENDING
    // batch concurrently and double-bill/double-send. ShedLock now guards the whole method
    // cross-instance, exactly like testCheckstatusCron/paymentsPayCron. The @Scheduled below
    // stays commented out - this runs on operator/script invocation only.
    @SchedulerLock(
            name = "testSendPendingSmsCron",
            lockAtMostFor = "PT15M",
            lockAtLeastFor = "PT30S")

    // @Scheduled(fixedDelay = 3000, initialDelay = 1000)
    public String testSendPendingSmsCron(/*@RequestBody String requestBody,
            HttpServletRequest request, HttpServletResponse response*/ ) {
        // Set the response header

        String filePath = lockfiledirectory + Common.CLASS_PATH_SEND_SMS_SERVICE_TX_LOCK;
        Logger.getLogger(TransactionsLogController.class.getName())
                .log(Level.SEVERE, "LockFile " + filePath);

        try {

            RandomAccessFile writer =
                    new RandomAccessFile(Common.CLASS_PATH_SEND_SMS_SERVICE_TX_LOCK, "rw");

            File lfile = new File(filePath);
            if (lfile.createNewFile()) {
                Logger.getLogger(TransactionsLogController.class.getName())
                        .log(Level.SEVERE, "Filed " + filePath + " has been created.");
            }

            FileLock lock = writer.getChannel().lock();
            writer.write("Am handling lock!".getBytes());

            MapSqlParameterSource parameters = new MapSqlParameterSource();
            String sqlSelect =
                    "SELECT *  FROM "
                            + Common.DB_TABLE_MERCHANT_SMS
                            + " "
                            + " WHERE status IN('PENDING') LIMIT 1000 FOR UPDATE ";

            String sql_update =
                    " UPDATE "
                            + Common.DB_TABLE_MERCHANT_SMS
                            + " "
                            + " SET status=:status, "
                            + " gw_response=:gw_response, "
                            + " smsgw=:smsgw, "
                            + " trace=:trace";

            RowMapper<MerchantSms> rm =
                    new RowMapper<MerchantSms>() {
                        public MerchantSms mapRow(ResultSet rs, int rowNum) throws SQLException {
                            MerchantSms t = new MerchantSms();
                            t.setId(BigInteger.valueOf(rs.getLong("id")));
                            t.setCharge(rs.getDouble("charge"));
                            t.setCost(rs.getDouble("cost"));
                            t.setContent(rs.getString("content"));
                            t.setCreated_on(rs.getString("created_on"));
                            t.setSend_time(rs.getString("send_time"));
                            t.setStatus(rs.getString("status"));
                            t.setMerchant_id(BigInteger.valueOf(rs.getLong("merchant_id")));
                            t.setRecipients(rs.getString("recipients"));
                            t.setSmsgw(rs.getString("smsgw"));
                            t.setTotal_amount(rs.getDouble("total_amount"));
                            t.setTrace(rs.getString("trace"));
                            t.setTotal_recipients(rs.getInt("total_recipients"));
                            t.setGw_response(rs.getString("gw_response"));
                            t.setCreated_by(rs.getString("created_by"));

                            return t;
                        }
                    };
            // ResultSet rs;
            List<MerchantSms> pendingSms = jdbcTemplate.query(sqlSelect, parameters, rm);

            Logger.getLogger(TransactionsLogController.class.getName())
                    .log(Level.SEVERE, "Sending SMS (" + pendingSms.size() + ")", "");

            Setting getSMSGwURL = Common.getSettings("sms_api_url", jdbcTemplate);
            if (getSMSGwURL == null || getSMSGwURL.getSetting_value().isEmpty()) {
                // release lock
                lock.release();
                writer.close();
                return GeneralException.getError(
                        "136", String.format(GeneralException.ERRORS_136, "SMS API URL"));
            }

            Setting getSMSGwParams = Common.getSettings("sms_api_parameters", jdbcTemplate);
            if (getSMSGwParams == null || getSMSGwParams.getSetting_value().isEmpty()) {
                // release lock
                lock.release();
                writer.close();
                return GeneralException.getError(
                        "136", String.format(GeneralException.ERRORS_136, "SMS API Params"));
            }

            Setting getSMSGwHttpmethod = Common.getSettings("sms_api_http_method", jdbcTemplate);
            if (getSMSGwHttpmethod == null || getSMSGwHttpmethod.getSetting_value().isEmpty()) {
                // release lock
                lock.release();
                writer.close();
                return GeneralException.getError(
                        "136", String.format(GeneralException.ERRORS_136, "SMS API HTTP Method"));
            }

            Setting getSMSGwName = Common.getSettings("sms_gateway_name", jdbcTemplate);
            if (getSMSGwName == null || getSMSGwName.getSetting_value().isEmpty()) {
                // release lock
                lock.release();
                writer.close();
                return GeneralException.getError(
                        "136", String.format(GeneralException.ERRORS_136, "SMS Gateway Name"));
            }

            Setting getSMSGwCost = Common.getSettings("sms_gateway_cost", jdbcTemplate);
            if (getSMSGwCost == null || getSMSGwCost.getSetting_value().isEmpty()) {
                // release lock
                lock.release();
                writer.close();
                return GeneralException.getError(
                        "136",
                        String.format(GeneralException.ERRORS_136, "SMS Gateway Purchase Rate"));
            }

            Setting getSMSGwCustomerRate = Common.getSettings("sms_customer_charge", jdbcTemplate);
            if (getSMSGwCustomerRate == null || getSMSGwCustomerRate.getSetting_value().isEmpty()) {
                // release lock
                lock.release();
                writer.close();
                return GeneralException.getError(
                        "136", String.format(GeneralException.ERRORS_136, "SMS Customer Charge"));
            }

            Statement newTx;
            String balance_type = SmsGateway.BALANCE_TYPE;
            String result = "";
            for (MerchantSms tx : pendingSms) {

                Logger.getLogger(TransactionsLogController.class.getName())
                        .log(Level.INFO, "Working on Pending SMS: " + tx.getContent());

                // Get merchant account
                Merchant merchant_account =
                        Common.getMerchantById(tx.getMerchant_id() + "", jdbcTemplate);

                // Now bill this Merchant
                newTx = new Statement();
                newTx.setAmount(tx.getTotal_amount());
                newTx.setGateway_id(SmsGateway.getGatewayId());
                newTx.setNarritive(Transaction.TX_TYPE_SMS_CUSTOMER_CHARGE);
                newTx.setMerchant_id(merchant_account.getId());
                newTx.setDescription("SMS Charge: ");
                newTx.setRecorded_by("SYSTEM");
                newTx.setTx_type("DR");

                final Statement nSmsTx = newTx;
                TransactionTemplate template = new TransactionTemplate(transactionManager);
                result =
                        template.execute(
                                new TransactionCallback<String>() {
                                    @Override
                                    public String doInTransaction(TransactionStatus status) {
                                        String res = "";
                                        try {

                                            res =
                                                    Common.recordStatementTxWithoutTransaction(
                                                            nSmsTx,
                                                            balance_type,
                                                            jdbcTemplate,
                                                            transactionManager,
                                                            status);

                                            if (!res.equals("success")) {
                                                // release lock
                                                lock.release();
                                                writer.close();
                                                return res;
                                            }
                                            res = "success";

                                        } catch (Exception ex) {
                                            status.setRollbackOnly();
                                            Logger.getLogger(
                                                            TransactionsLogController.class
                                                                    .getName())
                                                    .log(Level.SEVERE, ex.getMessage(), ex);
                                            return GeneralException.getError(
                                                    "102", GeneralException.ERRORS_102);
                                        }
                                        return res;
                                    }
                                });

                if (result.equals("success")) {
                    // Now send the SMS.

                    Logger.getLogger(TransactionsLogController.class.getName())
                            .log(Level.INFO, "NOW SENDING THE SMS: " + tx.getContent());
                    String http_method = "";
                    String url_string = getSMSGwURL.getSetting_value();
                    String param = getSMSGwParams.getSetting_value();
                    String param_to_use = param;
                    param = param.replace("{CONTENT}", Common.urlEncodeValue(tx.getContent()));
                    // Clean up the phone number
                    String cleaned = tx.getRecipients();
                    HttpRequestResponse rs = null;
                    Map<String, String> headers = new HashMap<>();
                    if (url_string.contains("speedamobile")) {
                        cleaned = cleaned.replaceAll("[,]$", "");
                        String[] phones = cleaned.split("[,]");
                        for (int i = 0; i < phones.length; i++) {
                            String param_ = param_to_use.replace("{MSISDNS}", phones[i]);
                            param_ =
                                    param_.replace(
                                            "{CONTENT}", Common.urlEncodeValue(tx.getContent()));

                            if (getSMSGwHttpmethod.getSetting_value().equals("POST")) {
                                headers.put("Content-Type", "application/x-www-form-urlencoded");
                                http_method = "POST";
                                rs = Common.doHttpRequest(http_method, url_string, param_, headers);
                            } else {
                                http_method = "GET";
                                rs =
                                        Common.doHttpRequest(
                                                http_method,
                                                url_string + "?" + param_,
                                                "",
                                                headers);
                            }
                        }
                    } else {
                        cleaned = cleaned.replaceAll("[,]$", "");
                        param = param.replace("{MSISDNS}", cleaned);

                        if (getSMSGwHttpmethod.getSetting_value().equals("POST")) {
                            headers.put("Content-Type", "application/x-www-form-urlencoded");
                            http_method = "POST";
                            rs = Common.doHttpRequest(http_method, url_string, param, headers);
                        } else {
                            http_method = "GET";
                            rs =
                                    Common.doHttpRequest(
                                            http_method, url_string + "?" + param, "", headers);
                        }
                    }

                    Logger.getLogger(TransactionsLogController.class.getName())
                            .log(
                                    Level.INFO,
                                    "SMS RESULTS: " + tx.getContent() + " " + rs.getResponse());

                    if (rs == null || rs.getStatusCode() == 0) {
                        // SMS Failed, then reversion the amount
                        // Now bill this Merchant
                        newTx = new Statement();
                        newTx.setAmount(tx.getTotal_amount());
                        newTx.setGateway_id(SmsGateway.getGatewayId());
                        newTx.setNarritive(Transaction.TX_TYPE_SMS_CUSTOMER_CHARGE_REVERSAL);
                        newTx.setMerchant_id(merchant_account.getId());
                        newTx.setDescription("SMS Charge: ");
                        newTx.setRecorded_by("SYSTEM");
                        newTx.setTx_type("CR");

                        final Statement newTxReversal = newTx;
                        template = new TransactionTemplate(transactionManager);
                        result =
                                template.execute(
                                        new TransactionCallback<String>() {
                                            @Override
                                            public String doInTransaction(
                                                    TransactionStatus status) {
                                                String res = "";
                                                try {

                                                    res =
                                                            Common
                                                                    .recordStatementTxWithoutTransaction(
                                                                            newTxReversal,
                                                                            balance_type,
                                                                            jdbcTemplate,
                                                                            transactionManager,
                                                                            status);

                                                    if (!res.equals("success")) {
                                                        // release lock
                                                        lock.release();
                                                        writer.close();
                                                        return res;
                                                    }
                                                    res = "success";

                                                } catch (Exception ex) {
                                                    status.setRollbackOnly();
                                                    Logger.getLogger(
                                                                    TransactionsLogController.class
                                                                            .getName())
                                                            .log(Level.SEVERE, ex.getMessage(), ex);
                                                    return GeneralException.getError(
                                                            "102", GeneralException.ERRORS_102);
                                                }
                                                return res;
                                            }
                                        });

                        if (result.equals("success")) {
                            // Now update the SMS record
                            String sql_update_ = sql_update + " WHERE id=:id";
                            parameters = new MapSqlParameterSource();
                            parameters.addValue("id", tx.getId());
                            parameters.addValue("trace", "REQUEST FAILED");
                            parameters.addValue("gw_response", "");
                            parameters.addValue(
                                    "status",
                                    net.citotech.cito.Model.SmsDeliveryStatus.FAILED.name());
                            parameters.addValue("smsgw", getSMSGwName.getSetting_value());
                            jdbcTemplate.update(sql_update_, parameters);
                        }

                    } else if (!isSuccessfulSmsGatewayResponse(rs.getStatusCode())) {
                        // Audit P5: the gateway responded, but with a non-2xx status - a real
                        // provider-side rejection (invalid number, blocked content, insufficient
                        // provider credit, etc), not a successful send. Previously ANY response at
                        // all (including 4xx/5xx) was marked SENT with no refund; this now reverses
                        // the charge exactly like the transport-failure branch above, using the
                        // same 2xx check net.citotech.cito.webhook.MerchantWebhookService already
                        // uses to judge webhook delivery success.
                        newTx = new Statement();
                        newTx.setAmount(tx.getTotal_amount());
                        newTx.setGateway_id(SmsGateway.getGatewayId());
                        newTx.setNarritive(Transaction.TX_TYPE_SMS_CUSTOMER_CHARGE_REVERSAL);
                        newTx.setMerchant_id(merchant_account.getId());
                        newTx.setDescription("SMS Charge: ");
                        newTx.setRecorded_by("SYSTEM");
                        newTx.setTx_type("CR");

                        final Statement newTxRejectedReversal = newTx;
                        template = new TransactionTemplate(transactionManager);
                        result =
                                template.execute(
                                        new TransactionCallback<String>() {
                                            @Override
                                            public String doInTransaction(
                                                    TransactionStatus status) {
                                                String res = "";
                                                try {

                                                    res =
                                                            Common
                                                                    .recordStatementTxWithoutTransaction(
                                                                            newTxRejectedReversal,
                                                                            balance_type,
                                                                            jdbcTemplate,
                                                                            transactionManager,
                                                                            status);

                                                    if (!res.equals("success")) {
                                                        // release lock
                                                        lock.release();
                                                        writer.close();
                                                        return res;
                                                    }
                                                    res = "success";

                                                } catch (Exception ex) {
                                                    status.setRollbackOnly();
                                                    Logger.getLogger(
                                                                    TransactionsLogController.class
                                                                            .getName())
                                                            .log(Level.SEVERE, ex.getMessage(), ex);
                                                    return GeneralException.getError(
                                                            "102", GeneralException.ERRORS_102);
                                                }
                                                return res;
                                            }
                                        });

                        if (result.equals("success")) {
                            String sql_update_ = sql_update + " WHERE id=:id";
                            parameters = new MapSqlParameterSource();
                            parameters.addValue("id", tx.getId());
                            parameters.addValue("trace", rs.toString());
                            parameters.addValue("gw_response", rs.getResponse());
                            parameters.addValue(
                                    "status",
                                    net.citotech.cito.Model.SmsDeliveryStatus.REJECTED.name());
                            parameters.addValue("smsgw", getSMSGwName.getSetting_value());
                            jdbcTemplate.update(sql_update_, parameters);
                        }
                    } else {
                        String sql_update_ = sql_update + " WHERE id=:id";
                        parameters = new MapSqlParameterSource();
                        parameters.addValue("id", tx.getId());
                        parameters.addValue("trace", rs.toString());
                        parameters.addValue("gw_response", rs.getResponse());
                        parameters.addValue(
                                "status", net.citotech.cito.Model.SmsDeliveryStatus.SENT.name());
                        parameters.addValue("smsgw", getSMSGwName.getSetting_value());
                        jdbcTemplate.update(sql_update_, parameters);
                    }
                } else {
                    Logger.getLogger(TransactionsLogController.class.getName())
                            .log(
                                    Level.INFO,
                                    "Sending SMS FAILED: " + tx.getContent() + " " + result);
                    // Release lock
                    try {
                        lock.release();
                        // close the file
                        writer.close();
                    } catch (ClosedChannelException e) {

                    }
                    // Looks like we have got an error here.
                    // return result;
                }
                // Move to the next SMS.
            }

            // Release lock
            lock.release();
            // close the file
            writer.close();

            // Execution successfully.
            return GeneralSuccessResponse.getMessage(
                    "000", GeneralSuccessResponse.SUCCESS_000 + " Processed: " + pendingSms.size());

        } catch (IOException ex) {
            Logger.getLogger(TransactionsLogController.class.getName())
                    .log(Level.SEVERE, "HANDLING_SMS_SERVICE IOException:" + ex.getMessage(), ex);
            return GeneralException.getError("107", GeneralException.ERRORS_107);

        } catch (java.nio.channels.OverlappingFileLockException ex) {
            Logger.getLogger(AuthenticationController.class.getName())
                    .log(
                            Level.SEVERE,
                            "HANDLING_SMS_SERVICE OverlappingFileLockException: " + ex.getMessage(),
                            ex);

            return "OverlappingFileLockException";
        }
    }

    @GetMapping(path = "/testRecieveSmsRequest")
    public String testRecieveSmsRequest(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header
        return request.getQueryString();
    }
}
