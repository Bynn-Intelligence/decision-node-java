package com.bynn.decisionnode;

import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A body read that fails when no data arrives for {@code idle} while the reader is waiting for
 * more. A slow reader is never timed out: the clock runs only while there is demand.
 */
final class IdleTimeout<T> implements HttpResponse.BodySubscriber<T> {
    private final HttpResponse.BodySubscriber<T> delegate;
    private final long idleNanos;
    private final ScheduledExecutorService timers;
    private Flow.Subscription upstream;
    private ScheduledFuture<?> timer;
    private long demand;
    private boolean done;

    IdleTimeout(HttpResponse.BodySubscriber<T> delegate, Duration idle, ScheduledExecutorService timers) {
        this.delegate = delegate;
        this.idleNanos = idle.toNanos();
        this.timers = timers;
    }

    @Override
    public CompletionStage<T> getBody() {
        return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        synchronized (this) {
            upstream = subscription;
        }
        delegate.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                synchronized (IdleTimeout.this) {
                    demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
                    arm();
                }
                subscription.request(n);
            }

            @Override
            public void cancel() {
                synchronized (IdleTimeout.this) {
                    done = true;
                    disarm();
                }
                subscription.cancel();
            }
        });
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
        synchronized (this) {
            if (done) {
                return;
            }
            demand = demand == Long.MAX_VALUE ? demand : demand - 1;
            disarm();
            if (demand > 0) {
                arm();
            }
        }
        delegate.onNext(item);
    }

    @Override
    public void onError(Throwable throwable) {
        if (finish()) {
            delegate.onError(throwable);
        }
    }

    @Override
    public void onComplete() {
        if (finish()) {
            delegate.onComplete();
        }
    }

    private synchronized boolean finish() {
        if (done) {
            return false;
        }
        done = true;
        disarm();
        return true;
    }

    private void arm() {
        if (timer == null && !done && demand > 0) {
            timer = timers.schedule(this::expire, idleNanos, TimeUnit.NANOSECONDS);
        }
    }

    private void disarm() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }

    private void expire() {
        Flow.Subscription s;
        synchronized (this) {
            if (done) {
                return;
            }
            done = true;
            timer = null;
            s = upstream;
        }
        if (s != null) {
            s.cancel();
        }
        delegate.onError(new HttpTimeoutException("no data for " + Duration.ofNanos(idleNanos).toMillis() + " ms"));
    }
}
