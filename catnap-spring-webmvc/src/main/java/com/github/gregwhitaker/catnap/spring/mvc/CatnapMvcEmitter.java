package com.github.gregwhitaker.catnap.spring.mvc;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * Connection-owned bounded sends. Return {@link #response()} to MVC; use this wrapper for all
 * sends. An accepted send's stage completes only after native write/flush. Overflow rejects that
 * send.
 */
public final class CatnapMvcEmitter<T> {
  @FunctionalInterface
  interface Write {
    void run() throws IOException;
  }

  @FunctionalInterface
  interface Prepare<T> {
    Write apply(T value) throws Exception;
  }

  private record Pending<T>(T value, CompletableFuture<Void> result) {}

  private final ResponseBodyEmitter response;
  private final Executor executor;
  private final int capacity;
  private final Prepare<T> prepare;
  private final Consumer<Throwable> finishFailure;
  private final ArrayDeque<Pending<T>> pending = new ArrayDeque<>();
  private final CompletableFuture<Void> termination = new CompletableFuture<>();
  private boolean initialized, started, scheduled, terminal, completing;
  private Throwable failure;
  private Flow.Publisher<T> source;
  private Flow.Subscription subscription;

  CatnapMvcEmitter(
      ResponseBodyEmitter response,
      Executor executor,
      int capacity,
      Prepare<T> prepare,
      Consumer<Throwable> finishFailure) {
    this.response = response;
    this.executor = executor;
    this.capacity = capacity;
    this.prepare = prepare;
    this.finishFailure = finishFailure;
    response.onTimeout(() -> stop(new IOException("JSON stream timed out")));
    response.onError(this::stop);
    response.onCompletion(() -> stop(null));
  }

  public ResponseBodyEmitter response() {
    return response;
  }

  public CompletionStage<Void> termination() {
    return termination.minimalCompletionStage();
  }

  public CompletionStage<Void> send(T value) {
    var result = new CompletableFuture<Void>();
    synchronized (this) {
      if (terminal || completing)
        return CompletableFuture.failedFuture(
            new IllegalStateException("Stream is closed for sends"));
      if (pending.size() >= capacity)
        return CompletableFuture.failedFuture(
            new RejectedExecutionException("Pending stream send limit exceeded"));
      pending.addLast(new Pending<>(value, result));
    }
    schedule();
    return result.minimalCompletionStage();
  }

  /** Finishes after accepted sends have flushed. */
  public void complete() {
    finish(null);
  }

  /**
   * Propagates a source failure after already-accepted sends; no unprojected fallback is emitted.
   */
  public void fail(Throwable cause) {
    finish(Objects.requireNonNull(cause));
  }

  private void finish(Throwable cause) {
    synchronized (this) {
      if (terminal || completing) return;
      completing = true;
      failure = cause;
    }
    schedule();
  }

  /** Subscribes only after MVC initialization, then requests one item after each completed send. */
  public void subscribe(Flow.Publisher<T> publisher) {
    synchronized (this) {
      if (started || source != null || terminal || completing || !pending.isEmpty())
        throw new IllegalStateException(
            "Attach one publisher before the emitter starts, without imperative sends");
      source = Objects.requireNonNull(publisher);
    }
  }

  /** Supports Reactive Streams publishers without introducing a Reactor dependency. */
  public void subscribe(org.reactivestreams.Publisher<T> publisher) {
    Objects.requireNonNull(publisher);
    subscribe(
        (Flow.Publisher<T>)
            downstream ->
                publisher.subscribe(
                    new org.reactivestreams.Subscriber<T>() {
                      @Override
                      public void onSubscribe(org.reactivestreams.Subscription upstream) {
                        downstream.onSubscribe(
                            new Flow.Subscription() {
                              @Override
                              public void request(long n) {
                                upstream.request(n);
                              }

                              @Override
                              public void cancel() {
                                upstream.cancel();
                              }
                            });
                      }

                      @Override
                      public void onNext(T item) {
                        downstream.onNext(item);
                      }

                      @Override
                      public void onError(Throwable cause) {
                        downstream.onError(cause);
                      }

                      @Override
                      public void onComplete() {
                        downstream.onComplete();
                      }
                    }));
  }

  synchronized void initialized() {
    initialized = true;
  }

  synchronized boolean isInitialized() {
    return initialized;
  }

  void start() {
    synchronized (this) {
      if (!initialized || terminal || started) return;
      started = true;
    }
    schedule();
  }

  private void schedule() {
    synchronized (this) {
      if (!started || terminal || scheduled || (pending.isEmpty() && source == null && !completing))
        return;
      scheduled = true;
    }
    try {
      executor.execute(this::drainOne);
    } catch (RejectedExecutionException rejected) {
      abort(new IOException("Stream executor is at capacity", rejected));
    }
  }

  private void drainOne() {
    Pending<T> item;
    Flow.Publisher<T> publisher;
    boolean finish;
    Throwable cause;
    synchronized (this) {
      if (terminal) {
        scheduled = false;
        return;
      }
      publisher = source;
      source = null;
      item = publisher == null ? pending.pollFirst() : null;
      finish = publisher == null && item == null && completing;
      cause = failure;
    }
    try {
      if (publisher != null) publisher.subscribe(new PullSubscriber());
      else if (item != null) {
        Write write;
        try {
          write = prepare.apply(item.value());
        } catch (Exception encoding) {
          item.result().completeExceptionally(publicFailure(encoding));
          abort(encoding);
          return;
        }
        try {
          synchronized (this) {
            if (terminal) {
              item.result().completeExceptionally(new CancellationException("Stream ended"));
              return;
            }
          }
          write.run();
          item.result().complete(null);
        } catch (IOException disconnected) {
          item.result().completeExceptionally(publicFailure(disconnected));
          // A native write IOException is handled by the Servlet container's async error dispatch.
          stop(disconnected);
        } catch (RuntimeException nativeFailure) {
          item.result().completeExceptionally(publicFailure(nativeFailure));
          abort(nativeFailure);
        }
      } else if (finish) {
        if (cause == null) {
          response.complete();
          stop(null);
        } else abort(cause);
      }
    } catch (Exception upstreamFailure) {
      abort(upstreamFailure);
    } finally {
      synchronized (this) {
        scheduled = false;
      }
      schedule();
    }
  }

  private void abort(Throwable cause) {
    if (!stop(cause)) return;
    finishFailure.accept(publicFailure(cause));
  }

  /** Releases queued references and cancels the source on completion, disconnect, or shutdown. */
  boolean stop(Throwable cause) {
    Flow.Subscription upstream;
    ArrayList<Pending<T>> abandoned;
    synchronized (this) {
      if (terminal) return false;
      terminal = true;
      source = null;
      upstream = subscription;
      subscription = null;
      abandoned = new ArrayList<>(pending);
      pending.clear();
    }
    if (upstream != null) upstream.cancel();
    Throwable terminalFailure =
        cause == null ? new CancellationException("Stream ended") : publicFailure(cause);
    abandoned.forEach(item -> item.result().completeExceptionally(terminalFailure));
    if (cause == null) termination.complete(null);
    else termination.completeExceptionally(terminalFailure);
    return true;
  }

  private static Throwable publicFailure(Throwable cause) {
    return cause instanceof CancellationException
        ? cause
        : new IllegalStateException("JSON stream response failed", cause);
  }

  private final class PullSubscriber implements Flow.Subscriber<T> {
    @Override
    public void onSubscribe(Flow.Subscription upstream) {
      synchronized (CatnapMvcEmitter.this) {
        if (terminal || subscription != null) {
          upstream.cancel();
          return;
        }
        subscription = upstream;
      }
      upstream.request(1);
    }

    @Override
    public void onNext(T item) {
      send(item)
          .whenComplete(
              (unused, error) -> {
                if (error != null) {
                  Flow.Subscription upstream;
                  synchronized (CatnapMvcEmitter.this) {
                    upstream = subscription;
                  }
                  if (upstream != null) upstream.cancel();
                  fail(error);
                  return;
                }
                Flow.Subscription upstream;
                synchronized (CatnapMvcEmitter.this) {
                  upstream = terminal || completing ? null : subscription;
                }
                if (upstream != null) upstream.request(1);
              });
    }

    @Override
    public void onError(Throwable cause) {
      fail(cause);
    }

    @Override
    public void onComplete() {
      complete();
    }
  }
}
