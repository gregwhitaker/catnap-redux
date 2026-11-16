package com.github.gregwhitaker.catnap.examples.mvc;

import com.github.gregwhitaker.catnap.testkit.probe.StreamLifecycleProbe;
import java.io.IOException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An example Flow publisher: honors demand, has no item queue, and exposes deterministic test
 * gates.
 */
final class LifecycleSource implements Flow.Publisher<JsonNode> {
  private final JsonMapper mapper;
  private final String mode;
  private final StreamLifecycleProbe probe;

  LifecycleSource(JsonMapper mapper, String mode, StreamLifecycleProbe probe) {
    this.mapper = mapper;
    this.mode = mode;
    this.probe = probe;
  }

  @Override
  public void subscribe(Flow.Subscriber<? super JsonNode> subscriber) {
    var subscription =
        new Flow.Subscription() {
          private final AtomicInteger work = new AtomicInteger();
          private final AtomicLong requested = new AtomicLong();
          private volatile boolean stopped;
          private int index;

          @Override
          public void request(long count) {
            if (stopped) return;
            if (count <= 0) {
              cancel();
              subscriber.onError(new IllegalArgumentException("Positive demand required"));
              return;
            }
            requested.getAndUpdate(
                current -> current > Long.MAX_VALUE - count ? Long.MAX_VALUE : current + count);
            drain();
          }

          void drain() {
            if (work.getAndIncrement() != 0) return;
            int missed = 1;
            do {
              while (!stopped && requested.get() > 0) {
                if (mode.equals("before")
                    || (mode.equals("after") && index == 1 && probe.release.isDone())) {
                  cancel();
                  subscriber.onError(new IOException("Source failed"));
                  break;
                }
                if (index > 0 && !mode.equals("infinite") && !probe.release.isDone()) break;
                if (index == 2 && !mode.equals("infinite")) {
                  cancel();
                  subscriber.onComplete();
                  break;
                }
                requested.decrementAndGet();
                String other =
                    mode.equals("infinite")
                        ? "x".repeat(3800)
                        : mode.equals("limit") && index == 1 ? "x".repeat(10_000) : "private";
                var value = mapper.createObjectNode().put("id", index++).put("other", other);
                probe.emitted.incrementAndGet();
                subscriber.onNext(value);
                if (index == 2 && !mode.equals("infinite") && !stopped) {
                  cancel();
                  subscriber.onComplete();
                }
              }
              missed = work.addAndGet(-missed);
            } while (missed != 0);
          }

          @Override
          public void cancel() {
            stopped = true;
            probe.finished.complete(null);
          }
        };
    probe.release.whenComplete((unused, failure) -> subscription.drain());
    subscriber.onSubscribe(subscription);
  }
}
