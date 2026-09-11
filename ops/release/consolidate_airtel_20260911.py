#!/usr/bin/env python3
"""Reproduce the reviewed merge resolutions. Never pushes a release branch or touches runtime data.

Inputs are pinned existing Git commits. Changed Java is formatted and the complete
candidate is tested by the associated disposable-runner workflow before publication.
"""
from pathlib import Path
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
MAIN = 'de3011367b67eac2cad65e508e72966a3f70b5ea'
PR184 = 'ae17f944f25caa41d1090194fd1681c3cc5ee53d'
PR185 = '292bccb6ec69b71199eb30eaa590fa9fa86cff2a'
BACKEND = ROOT / 'InitializrSpringbootProjectFresh'
JAVA = BACKEND / 'src/main/java/net/citotech/cito'
TEST = BACKEND / 'src/test/java/net/citotech/cito'


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()


def replace(path, old, new, count=1):
    text = path.read_text()
    if text.count(old) < count:
        raise RuntimeError('Expected source anchor missing: ' + str(path) + ': ' + old[:80])
    path.write_text(text.replace(old, new, count))


def merge(ref, resolutions):
    completed = subprocess.run(['git', 'merge', '--no-commit', '--no-ff', ref], cwd=ROOT)
    if completed.returncode not in (0, 1):
        raise RuntimeError('Git merge failed')
    conflicts = set(git('diff', '--name-only', '--diff-filter=U').splitlines())
    if conflicts != set(resolutions):
        raise RuntimeError('Unexpected conflict set: ' + repr(conflicts))
    pattern = r'<<<<<<< HEAD\n(.*?)=======\n(.*?)>>>>>>> ' + re.escape(ref) + r'\n'
    for name, mode in resolutions.items():
        path = ROOT / name
        index = [0]
        def select(match):
            number = index[0]
            index[0] += 1
            ours, theirs = match.group(1), match.group(2)
            if mode == 'fees': return ours if number < 2 else theirs
            if mode == 'imports': return ours + theirs if number == 0 else ours
            return ours
        text = re.sub(pattern, select, path.read_text(), flags=re.S)
        if not index[0] or re.search(r'^(<<<<<<<|=======|>>>>>>>)', text, re.M):
            raise RuntimeError('Unresolved conflict in ' + name)
        path.write_text(text)
    subprocess.run(['git', 'add', '-A'], cwd=ROOT, check=True)
    subprocess.run(['git', 'commit', '-m', 'Reconcile ' + ref + ' preserving current-main controls'], cwd=ROOT, check=True)


def main():
    if git('rev-parse', 'origin/main') != MAIN:
        raise RuntimeError('Main changed; re-review before preparing a new candidate')
    subprocess.run(['git', 'fetch', 'origin',
                    'refs/pull/184/head:refs/heads/review-pr184',
                    'refs/pull/185/head:refs/heads/review-pr185'], cwd=ROOT, check=True)
    if git('rev-parse', 'review-pr184') != PR184 or git('rev-parse', 'review-pr185') != PR185:
        raise RuntimeError('Airtel input changed; re-review required')
    merge('review-pr184', {
        'InitializrSpringbootProjectFresh/src/main/java/net/citotech/cito/DoPayGateway.java': 'fees',
        'InitializrSpringbootProjectFresh/src/test/java/net/citotech/cito/FlywayMigrationSmokeTest.java': 'ours',
        'InitializrSpringbootProjectFresh/src/test/java/net/citotech/cito/ledger/DoubleEntryLedgerServiceTestcontainersTest.java': 'ours',
        'clientside/src/features/ProviderTreasuryConsole.tsx': 'imports',
    })
    p = ROOT / 'clientside/src/features/ProviderTreasuryConsole.tsx'
    replace(p, "channelCode: 'airtel_money', environment: 'PRODUCTION'", "channelCode: channelScope || 'airtel_money', environment: 'PRODUCTION'")
    replace(p, "}, new URLSearchParams(window.location.search).get('channel')", "}, channelScope || (new URLSearchParams(window.location.search).get('channel')")
    replace(p, "? 'airtel_open_api' : 'mtn_momo', 'SANDBOX'));", "? 'airtel_open_api' : 'mtn_momo'), 'SANDBOX'));")
    replace(p, "environment: '', channel: '', currency: '', role: '', state: ''", "environment: '', channel: channelScope || '', currency: '', role: '', state: ''")
    subprocess.run(['git', 'add', '-A'], cwd=ROOT, check=True)
    subprocess.run(['git', 'commit', '-m', 'Preserve sandbox-first MTN profiles in provider-scoped UI'], cwd=ROOT, check=True)
    merge('review-pr185', {
        'InitializrSpringbootProjectFresh/src/main/java/net/citotech/cito/DoPayGateway.java': 'fees',
        'InitializrSpringbootProjectFresh/src/main/java/net/citotech/cito/gateway/AirtelOpenApiCredentialSchema.java': 'ours',
    })
    p = JAVA / 'gateway/AirtelOpenApiCredentialSchema.java'
    text = p.read_text().replace('import java.net.URI;\n', '')
    start = text.index('    private static URI httpsUri(')
    end = text.index('    private static String value(', start)
    p.write_text(text[:start] + text[end:])
    replace(p, '        ProviderEndpointPolicy.requireOrigin(', '''        if (!List.of("SANDBOX", "PRODUCTION").contains(normalize(environment))) {
            throw new PaymentGatewayException("Airtel environment must be SANDBOX or PRODUCTION");
        }
        ProviderEndpointPolicy.requireOrigin(''')

    migrations = BACKEND / 'src/main/resources/db/migration'
    (migrations / 'V123__airtel_recovery_cursors.sql').rename(migrations / 'V129__airtel_recovery_cursors.sql')
    (migrations / 'V123__airtel_durable_recovery.sql').rename(migrations / 'V130__airtel_durable_recovery.sql')
    replace(migrations / 'V130__airtel_durable_recovery.sql', '    KEY idx_airtel_recovery_due',
            '    UNIQUE KEY uq_airtel_canonical_transaction (transaction_id),\n    KEY idx_airtel_recovery_due')
    replace(migrations / 'V130__airtel_durable_recovery.sql', ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;',
            ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;')
    replace(TEST / 'gateway/AirtelRecoveryStoreTestcontainersTest.java', 'V123__airtel_durable_recovery.sql', 'V130__airtel_durable_recovery.sql')
    p = TEST / 'FlywayMigrationSmokeTest.java'
    text = p.read_text().replace('V126-to-V128', 'V126-to-V130').replace('execute V127 and V128', 'execute V127 through V130')
    p.write_text(text.replace('assertEquals("128", latestSuccessfulVersion(connection))', 'assertEquals("130", latestSuccessfulVersion(connection))'))

    p = JAVA / 'gateway/AirtelRecoveryService.java'
    text = p.read_text()
    start = text.index('                AirtelStatusClient.Verified verified =\n')
    end = text.index('                if (!verified.terminal())', start)
    p.write_text(text[:start] + '''                // Submission HTTP codes are diagnostic only. Only authenticated status for
                // the original attempt may authorize terminal financial finalization.
                AirtelStatusClient.Verified verified = statusClient.verify(
                        entry.operation(), entry.provider(), entry.environment(), entry.country(),
                        entry.currency(), entry.amount(), values);
''' + text[end:])
    replace(p, '''                        ignored -> {
                            Ticket saved = store.prepare(candidate);''', '''                        ignored -> {
                            Transaction current = candidate.transactionId() == null
                                    ? null : lockedTransaction(candidate);
                            if (current != null) verifyTransaction(candidate, current);
                            Ticket saved = store.prepare(candidate);
                            if (saved.created() && current != null
                                    && ("SUCCESSFUL".equals(current.getStatus())
                                            || "FAILED".equals(current.getStatus()))) {
                                throw new PaymentGatewayException("AIRTEL_TRANSACTION_ALREADY_FINAL");
                            }''')
    replace(p, '''        if (tx == null
                || !Long.toString(entry.merchantId())''', '''        if (tx == null
                || entry.transactionId() == null
                || entry.transactionId().longValue() != tx.getId()
                || !Long.toString(entry.merchantId())''')
    p = JAVA / 'gateway/AirtelRecoveryStore.java'
    replace(p, 'import java.util.List;', 'import java.util.List;\nimport java.util.Objects;')
    replace(p, 'if (found.size() != 1 || !found.get(0).requestHash().equals(entry.requestHash()))', 'if (found.size() != 1 || !sameAttempt(found.get(0), entry))')
    replace(p, '    public List<Entry> findByMerchantReference', '''    static boolean sameAttempt(Entry saved, Entry candidate) {
        return saved.merchantId() == candidate.merchantId()
                && Objects.equals(saved.merchantNumber(), candidate.merchantNumber())
                && Objects.equals(saved.merchantReference(), candidate.merchantReference())
                && Objects.equals(saved.transactionId(), candidate.transactionId())
                && (saved.transactionId() == null || Objects.equals(saved.provider(), candidate.provider()))
                && Objects.equals(saved.operation(), candidate.operation())
                && Objects.equals(saved.environment(), candidate.environment())
                && Objects.equals(saved.country(), candidate.country())
                && Objects.equals(saved.currency(), candidate.currency())
                && Objects.equals(saved.source(), candidate.source())
                && Objects.equals(saved.identity(), candidate.identity())
                && saved.amount().compareTo(candidate.amount()) == 0
                && Objects.equals(saved.requestHash(), candidate.requestHash())
                && Objects.equals(saved.callbackUrl(), candidate.callbackUrl());
    }

    public List<Entry> findByMerchantReference''')

    p = JAVA / 'scheduler/AirtelOpenApiStatusPollScheduler.java'
    replace(p, 'import java.util.List;', 'import java.math.BigDecimal;\nimport java.util.List;')
    replace(p, 'import net.citotech.cito.gateway.AirtelOpenApiCredentialSchema;', 'import net.citotech.cito.gateway.AirtelOpenApiCredentialSchema;\nimport net.citotech.cito.gateway.AirtelRecoveryOwnership;')
    replace(p, '        if (observed == null || text(observed.getTx_unique_id()).isEmpty()) return;', '        if (observed == null || text(observed.getTx_unique_id()).isEmpty()) return;\n        if (AirtelRecoveryOwnership.ownsLegacy(jdbcTemplate, observed.getId())) return;')
    replace(p, '                            if (!unresolved(tx.getStatus())) return;', '                            if (!unresolved(tx.getStatus())) return;\n                            if (AirtelRecoveryOwnership.ownsLegacy(jdbcTemplate, tx.getId())) return;')
    replace(p, '"SELECT r.id, r.merchant_id, r.operation, r.provider_reference, r.currency_code,"', '"SELECT r.id, r.merchant_id, r.operation, r.provider_reference, r.currency_code, r.amount,"')
    replace(p, '        // Shared provider transaction IDs must be unambiguous', '''        if (row.get("merchant_id") != null
                && AirtelRecoveryOwnership.ownsShared(jdbcTemplate, number(row.get("merchant_id")),
                        submittedReference, text(row.get("environment")))) return;
        // Shared provider transaction IDs must be unambiguous''')
    replace(p, '"SELECT status, merchant_id, merchant_reference, treasury_account_id, operation, currency_code"', '"SELECT status, merchant_id, merchant_reference, treasury_account_id, operation, currency_code, amount"')
    replace(p, '                            if (!"PENDING".equals(text(current.get("status")))) return;', '''                            if (!"PENDING".equals(text(current.get("status")))) return;
                            if (AirtelRecoveryOwnership.ownsShared(jdbcTemplate,
                                    number(current.get("merchant_id")), submittedReference, environment)) return;''')
    replace(p, '                                    || !operation.equals(text(current.get("operation")))', '''                                    || !operation.equals(text(current.get("operation")))
                                    || new BigDecimal(text(row.get("amount"))).compareTo(
                                            new BigDecimal(text(current.get("amount")))) != 0''')
    fixtures = ROOT / 'ops/release/consolidation-fixtures'
    for name in ('AirtelRecoveryOwnership', 'AirtelRecoveryEvidenceTest', 'AirtelStatusBodyLimitTest'):
        destination = (TEST if name.endswith('Test') else JAVA) / 'gateway' / (name + '.java')
        destination.write_text((fixtures / (name + '.java.txt')).read_text())

    p = JAVA / 'gateway/AirtelStatusClient.java'
    text = p.read_text().replace('import java.io.InputStream;', '''import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;''')
    start = text.index('        try {\n            HttpResponse<InputStream> response =')
    end = text.index('    static Verified parse(', start)
    p.write_text(text[:start] + '''        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(
                request.build(), info -> new BoundedBody());
        try {
            // The deadline includes the full body, preventing slow-drip reads outliving a lease.
            HttpResponse<byte[]> response = pending.get(15, TimeUnit.SECONDS);
            return new Reply(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new PaymentGatewayException("AIRTEL_STATUS_INTERRUPTED");
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new PaymentGatewayException("AIRTEL_STATUS_TRANSPORT_UNCERTAIN");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PaymentGatewayException gateway) throw gateway;
            throw new PaymentGatewayException("AIRTEL_STATUS_TRANSPORT_UNCERTAIN");
        }
    }

    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private static final int MAX_BYTES = 65536;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null) { incoming.cancel(); return; }
            subscription = incoming;
            incoming.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new PaymentGatewayException("AIRTEL_RESPONSE_TOO_LARGE"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }

''' + text[end:])
    for name in ('airtel-recovery.yml', 'payment-recovery-validation.yml'):
        p = ROOT / '.github/workflows' / name
        p.write_text(p.read_text().replace('actions/setup-java@v5', 'actions/setup-java@v6')
                .replace('actions/setup-node@v4', 'actions/setup-node@v7')
                .replace('actions/upload-artifact@v4', 'actions/upload-artifact@v7'))
    # The subsequent companion script supplies regression additions, release docs and the
    # evidence-limited governance review. No tests or release gates are disabled.
    subprocess.run(['python3', 'ops/release/consolidation_records_20260911.py'], cwd=ROOT, check=True)
    subprocess.run(['git', 'diff', '--check'], cwd=ROOT, check=True)
    # An applied migration may not be changed by this consolidation.
    changed = git('diff', '--name-only', MAIN).splitlines()
    for name in changed:
        match = re.search(r'/db/migration/V(\d+)__', name)
        if match and int(match.group(1)) <= 128:
            raise RuntimeError('Historical migration changed: ' + name)
    print('Prepared consolidated candidate; formatting, tests and exact-head review remain required.')


if __name__ == '__main__':
    main()
