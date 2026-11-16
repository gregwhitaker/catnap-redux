package com.github.gregwhitaker.catnap.quarkus;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Acknowledges one native write before requesting another item; terminal signals wait for it. */
final class QuarkusStreamSubscriber<T> implements Flow.Subscriber<T> {
  interface Transport<T> {
    CompletionStage<?> write(T item);

    void complete();

    void fail(Throwable failure, boolean transportFailure);
  }

  private final Transport<T> transport;
  private final AtomicInteger work = new AtomicInteger();
  private Flow.Subscription subscription;
  private boolean requested, writing, ended, terminal, transportFailure;
  private Throwable failure;

  QuarkusStreamSubscriber(Transport<T> transport) {
    this.transport = transport;
  }

  @Override
  public void onSubscribe(Flow.Subscription incoming) {
    boolean reject;
    synchronized (this) {
      reject = terminal || subscription != null;
      if (!reject) subscription = incoming;
    }
    if (reject) incoming.cancel();
    else drain();
  }

  @Override
  public void onNext(T item) {
    boolean overflow;
    synchronized (this) {
      if (terminal || ended) return;
      overflow = writing || !requested;
      if (!overflow) {
        requested = false;
        writing = true;
      }
    }
    if (overflow) {
      onError(new RejectedExecutionException("Publisher emitted without demand"));
      return;
    }
    try {
      transport.write(item).whenComplete((unused, error) -> written(error));
    } catch (RuntimeException error) {
      written(error);
    }
  }

  private void written(Throwable error) {
    synchronized (this) {
      writing = false;
      if (error != null) {
        failure = error;
        ended = true;
        transportFailure = true;
      }
    }
    if (error != null) cancelUpstream();
    drain();
  }

  @Override
  public void onError(Throwable error) {
    synchronized (this) {
      if (terminal || ended) return;
      failure = error;
      ended = true;
      requested = false;
    }
    cancelUpstream();
    drain();
  }

  @Override
  public void onComplete() {
    synchronized (this) {
      if (terminal || ended) return;
      ended = true;
      requested = false;
    }
    drain();
  }

  void cancel() {
    synchronized (this) {
      if (terminal) return;
      terminal = true;
    }
    cancelUpstream();
  }

  private void cancelUpstream() {
    Flow.Subscription current;
    synchronized (this) {
      current = subscription;
      subscription = null;
    }
    if (current != null) current.cancel();
  }

  private void drain() {
    if (work.getAndIncrement() != 0) return;
    do {
      Flow.Subscription demand = null;
      boolean finish = false;
      Throwable error;
      boolean wireFailure;
      synchronized (this) {
        if (!terminal && !writing) {
          if (ended) {
            finish = true;
            terminal = true;
            subscription = null;
          } else if (!requested && subscription != null) {
            requested = true;
            demand = subscription;
          }
        }
        error = failure;
        wireFailure = transportFailure;
      }
      if (finish) {
        if (error == null) transport.complete();
        else transport.fail(error, wireFailure);
      } else if (demand != null) {
        try {
          demand.request(1);
        } catch (RuntimeException requestFailure) {
          onError(requestFailure);
        }
      }
    } while (work.decrementAndGet() != 0);
  }
}
