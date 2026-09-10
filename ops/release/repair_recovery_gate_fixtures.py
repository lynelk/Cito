"""Assertion-guarded edits; never change production configuration or waive a gate."""
from pathlib import Path
ROOT = Path('InitializrSpringbootProjectFresh/src')

def replace(text, old, new, count=1):
    assert text.count(old) == count, 'Unexpected source: ' + old[:80]
    return text.replace(old, new)

path = ROOT/'main/java/net/citotech/cito/ledger/DoubleEntryLedgerService.java'
s = path.read_text()
# Acquire the merchant/currency serialization lock before the idempotent lookup, then
# force current reads even if an enclosing REPEATABLE READ transaction has an older snapshot.
s = replace(s, '        ExistingReservation existing = findReservation(reservation.reservationReference());', '        lockReservationScope(merchantId, reservation.currency());\n        ExistingReservation existing = findReservation(reservation.reservationReference());')
s = replace(s, '        lockReservationScope(merchantId, reservation.currency());\n        BigDecimal availableBalance = availableMerchantBalance(merchantId, reservation.currency());', '        BigDecimal availableBalance = availableMerchantBalance(merchantId, reservation.currency(), true);')
s = replace(s, 'BigDecimal available = availableMerchantBalance(merchantId, normalizedCurrency);', 'BigDecimal available = availableMerchantBalance(merchantId, normalizedCurrency, true);')
s = replace(s, '    public BigDecimal availableMerchantBalance(long merchantId, String currency) {', '''    public BigDecimal availableMerchantBalance(long merchantId, String currency) {
        return availableMerchantBalance(merchantId, currency, false);
    }

    /** Reservations must not use a snapshot created before the merchant-scope lock. */
    private BigDecimal availableMerchantBalance(long merchantId, String currency, boolean locking) {''')
s = replace(s, '+ " lr.reservation_status=\'RESERVED\'), 0) AS active_reservations FROM"', '+ " lr.reservation_status=\'RESERVED\'"\n                                + (locking ? " FOR UPDATE" : "")\n                                + "), 0) AS active_reservations FROM"')
s = replace(s, '+ " le.currency=:currency",\n                        p);', '+ " le.currency=:currency"\n                                + (locking ? " FOR UPDATE" : ""),\n                        p);')
# findReservation is used only after the same merchant/currency lock in reserve/reserveAll.
a = s.index('    private ExistingReservation findReservation(')
b = s.index('    private void validateEntries(', a)
part = s[a:b]
part = replace(part, 'reservation_reference=:reservation_reference"', 'reservation_reference=:reservation_reference FOR UPDATE"')
s = s[:a]+part+s[b:]
path.write_text(s)

# Supply genuine synthetic source-completeness evidence to tests that intend to finalize.
# Approval remains a distinct maker/checker action; missing-source tests still fail closed.
for name in ['billing/export/BillingTraceChainServiceTestcontainersTest.java', 'billing/invoicing/BillingInvoiceFinalizeWorkflowTestcontainersTest.java', 'billing/invoicing/BillingPhase3ExitCriterionTestcontainersTest.java']:
    path = ROOT/'test/java/net/citotech/cito'/name
    s = path.read_text()
    old = '        gateService.submit(invoiceId, "billing-maker");'
    assert old in s
    s = s.replace(old, '        net.citotech.cito.billing.SyntheticBillingEvidence.recordCompleteSource(jdbcTemplate, invoiceRepository, invoiceId);\n'+old)
    path.write_text(s)

path = ROOT/'test/java/net/citotech/cito/ledger/DoubleEntryLedgerServiceTestcontainersTest.java'
s = path.read_text()
marker = '    private Callable<Boolean> reserveWhenReleased('
regression = '''    @Test
    void reservationsUseCurrentBalancesEvenWhenOuterTransactionHasAnOlderSnapshot() throws Exception {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbc);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        for (boolean batch : List.of(false, true)) {
            long merchant = batch ? 2202L : 2201L;
            String prefix = "STALE-SNAPSHOT-" + merchant;
            service.post(prefix + "-SEED", "PAYMENT", prefix + "-SEED", "synthetic opening liability",
                    List.of(entry("merchant:" + merchant + ":UGX:merchant_liability", "MERCHANT_LIABILITY", "MERCHANT", merchant, "CR", "100000", "UGX"),
                            entry("provider:mtn_momo:UGX:stale-snapshot:" + merchant, "CONTROL", "PROVIDER", 9001L, "DR", "100000", "UGX")));
            TransactionTemplate outer = new TransactionTemplate(manager);
            outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                outer.executeWithoutResult(status -> {
                    jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM ledger_reservations", Integer.class);
                    Future<?> committed = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(inner ->
                            service.reserve(prefix + "-FIRST", merchant, prefix + "-PAYMENT-FIRST", new BigDecimal("80000"), "UGX")));
                    try { committed.get(30, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                    if (batch) {
                        var result = service.reserveAll(merchant, "UGX", List.of(new DoubleEntryLedgerService.ReservationCommand(prefix + "-SECOND", prefix + "-PAYMENT-SECOND", new BigDecimal("80000"))));
                        assertThat(result.reserved()).isFalse();
                        assertThat(result.available()).isEqualByComparingTo("20000.0000");
                    } else {
                        assertThatThrownBy(() -> service.reserve(prefix + "-SECOND", merchant, prefix + "-PAYMENT-SECOND", new BigDecimal("80000"), "UGX"))
                                .isInstanceOf(PaymentGatewayException.class).hasMessageContaining("Insufficient ledger-derived available balance");
                    }
                });
            }
            assertThat(service.availableMerchantBalance(merchant, "UGX")).isEqualByComparingTo("20000.0000");
            Integer count = jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM ledger_reservations WHERE merchant_id=? AND reservation_status='RESERVED'", Integer.class, merchant);
            assertThat(count).isEqualTo(1);
        }
    }

'''
s = replace(s, marker, regression + marker)
path.write_text(s)
