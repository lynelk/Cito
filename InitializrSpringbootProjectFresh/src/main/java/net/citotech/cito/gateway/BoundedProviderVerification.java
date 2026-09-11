package net.citotech.cito.gateway;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Bounds read-only status verification, including a provider that stalls after response headers.
 * Financial finalization stays on the caller and never runs after an abandoned lookup.
 * A non-interruptible provider can occupy at most two daemon workers; excess work fails closed.
 */
@Component
public class BoundedProviderVerification {
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "cito-provider-verification");
                thread.setDaemon(true);
                return thread;
            });

    public <T> T execute(Callable<T> operation) {
        return execute(operation, 30_000);
    }

    <T> T execute(Callable<T> operation, long timeoutMillis) {
        Future<T> future = workers.submit(operation);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new PaymentGatewayException("Provider verification interrupted; outcome remains pending");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new PaymentGatewayException("Provider verification failed; outcome remains pending");
        } catch (java.util.concurrent.TimeoutException error) {
            throw new PaymentGatewayException("Provider verification deadline exceeded; outcome remains pending");
        } finally {
            future.cancel(true);
        }
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }
}
