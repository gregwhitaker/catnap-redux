package com.github.gregwhitaker.catnap.quarkus;

import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import com.github.gregwhitaker.catnap.http.stream.JsonStreamEncoder;
import java.io.IOException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** One-item demand, a total serialized-document bound, and no retained domain-object list. */
final class FiniteJsonSubscriber<T> implements Flow.Subscriber<T> {
  private final JsonStreamEncoder.Serializer<T> serializer;
  private final Consumer<byte[]> complete;
  private final Consumer<Throwable> failure;
  private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
  private final AtomicReference<BoundedBuffer> capture;
  private final AtomicBoolean terminal = new AtomicBoolean();
  private final AtomicInteger requesting = new AtomicInteger();
  private boolean first = true;

  FiniteJsonSubscriber(
      JsonStreamEncoder.Serializer<T> serializer,
      int maximum,
      Consumer<byte[]> complete,
      Consumer<Throwable> failure) {
    this.serializer = serializer;
    this.complete = complete;
    this.failure = failure;
    capture = new AtomicReference<>(new BoundedBuffer(maximum));
  }

  @Override
  public void onSubscribe(Flow.Subscription incoming) {
    if (!subscription.compareAndSet(null, incoming)) {
      incoming.cancel();
      return;
    }
    if (terminal.get()) {
      subscription.compareAndSet(incoming, null);
      incoming.cancel();
      return;
    }
    try {
      var buffer = capture.get();
      if (buffer == null) return;
      buffer.write('[');
      requestOne();
    } catch (IOException | RuntimeException error) {
      onError(error);
    }
  }

  @Override
  public void onNext(T value) {
    if (terminal.get()) return;
    var buffer = capture.get();
    if (buffer == null) return;
    try {
      if (!first) buffer.write(',');
      first = false;
      serializer.write(value, buffer);
      requestOne();
    } catch (IOException | RuntimeException error) {
      onError(error);
    }
  }

  private void requestOne() {
    if (requesting.getAndIncrement() != 0) return;
    do {
      var current = subscription.get();
      if (!terminal.get() && current != null) current.request(1);
    } while (requesting.decrementAndGet() != 0);
  }

  @Override
  public void onComplete() {
    if (!terminal.compareAndSet(false, true)) return;
    var buffer = capture.getAndSet(null);
    try {
      buffer.write(']');
      complete.accept(buffer.toByteArray());
    } catch (IOException | RuntimeException error) {
      failure.accept(error);
    } finally {
      subscription.set(null);
    }
  }

  @Override
  public void onError(Throwable error) {
    if (!terminal.compareAndSet(false, true)) return;
    capture.set(null);
    try {
      cancelSubscription();
    } finally {
      failure.accept(error);
    }
  }

  void cancel() {
    if (!terminal.compareAndSet(false, true)) return;
    capture.set(null);
    cancelSubscription();
  }

  private void cancelSubscription() {
    var current = subscription.getAndSet(null);
    if (current != null) current.cancel();
  }
}
