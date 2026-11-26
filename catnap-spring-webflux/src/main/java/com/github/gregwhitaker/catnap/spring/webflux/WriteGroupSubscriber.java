package com.github.gregwhitaker.catnap.spring.webflux;

import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.Exceptions;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Operators;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

/**
 * The transport prefetches flush groups. Each group after the first waits for a buffer request
 * before pulling its record from the source.
 */
final class WriteGroupSubscriber extends BaseSubscriber<Publisher<? extends DataBuffer>> {
  private enum Stage {
    UNOPENED,
    NEEDS_RECORD,
    REQUESTED,
    SUPPLIED
  }

  private static final class WriteGroup {
    final Sinks.One<Publisher<? extends DataBuffer>> record = Sinks.one();
    final boolean first;
    Stage stage;

    WriteGroup(boolean first) {
      this.first = first;
      // Spring needs a successfully encoded first record before it can commit the response.
      stage = first ? Stage.NEEDS_RECORD : Stage.UNOPENED;
    }
  }

  private final FluxSink<Publisher<? extends DataBuffer>> output;
  private final AtomicInteger work = new AtomicInteger();
  private long demand;
  private boolean first = true;
  private WriteGroup current;
  private boolean complete;

  WriteGroupSubscriber(FluxSink<Publisher<? extends DataBuffer>> output) {
    this.output = output;
  }

  @Override
  public Context currentContext() {
    return Context.of(output.contextView());
  }

  void grant(long count) {
    synchronized (this) {
      demand = Operators.addCap(demand, count);
    }
    advance();
  }

  @Override
  protected void hookOnSubscribe(Subscription subscription) {
    advance();
  }

  // The transport can request groups, open a body, or finish it reentrantly.
  private void advance() {
    if (work.getAndIncrement() != 0) return;
    int missed = 1;
    do {
      WriteGroup offer = null;
      boolean pull = false;
      synchronized (this) {
        if (upstream() != null && !isDisposed()) {
          if (current == null && demand > 0) {
            if (demand != Long.MAX_VALUE) demand--;
            current = new WriteGroup(first);
            if (!first) offer = current;
            first = false;
          }
          if (current != null && current.stage == Stage.NEEDS_RECORD) {
            current.stage = Stage.REQUESTED;
            pull = true;
          }
        }
      }
      if (offer != null) output.next(body(offer));
      if (pull) request(1);
      missed = work.addAndGet(-missed);
    } while (missed != 0);
  }

  private Flux<DataBuffer> body(WriteGroup slot) {
    return slot.record
        .asMono()
        .<DataBuffer>flatMapMany(Flux::from)
        .doOnRequest(ignored -> open(slot))
        .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
        .doFinally(signal -> finished(slot, signal));
  }

  private void open(WriteGroup slot) {
    synchronized (this) {
      if (current != slot || slot.stage != Stage.UNOPENED) return;
      slot.stage = Stage.NEEDS_RECORD;
    }
    advance();
  }

  @Override
  protected void hookOnNext(Publisher<? extends DataBuffer> group) {
    WriteGroup slot;
    synchronized (this) {
      slot = current;
      if (slot == null || slot.stage != Stage.REQUESTED) {
        throw Exceptions.failWithOverflow("Source emitted a write group without demand");
      }
      slot.stage = Stage.SUPPLIED;
    }
    slot.record.tryEmitValue(group);
    if (slot.first) output.next(body(slot));
  }

  private void finished(WriteGroup slot, SignalType signal) {
    boolean finish;
    synchronized (this) {
      if (slot != current) return;
      if (signal == SignalType.ON_COMPLETE) current = null;
      finish = complete || signal != SignalType.ON_COMPLETE;
    }
    if (signal != SignalType.ON_COMPLETE) cancel();
    if (finish) output.complete();
    else advance();
  }

  @Override
  protected void hookOnComplete() {
    WriteGroup slot;
    synchronized (this) {
      complete = true;
      slot = current;
      if (slot != null && slot.stage == Stage.SUPPLIED) return;
    }
    if (slot != null) slot.record.tryEmitEmpty();
    output.complete();
  }

  @Override
  protected void hookOnError(Throwable failure) {
    output.error(failure);
  }

  @Override
  protected void hookFinally(SignalType signal) {
    if (signal == SignalType.ON_COMPLETE) return;
    WriteGroup slot;
    synchronized (this) {
      slot = current;
      current = null;
    }
    if (slot != null) slot.record.tryEmitEmpty();
  }
}
