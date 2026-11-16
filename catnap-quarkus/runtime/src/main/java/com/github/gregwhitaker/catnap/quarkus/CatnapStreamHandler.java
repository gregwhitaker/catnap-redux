package com.github.gregwhitaker.catnap.quarkus;

import com.github.gregwhitaker.catnap.http.ResponsePolicy;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.core.SseUtil;
import org.jboss.resteasy.reactive.server.core.StreamingUtil;
import org.jboss.resteasy.reactive.server.handlers.PublisherResponseHandler;
import org.jboss.resteasy.reactive.server.spi.ServerRestHandler;

/** Native REST handler that defers commitment until a complete bounded payload exists. */
public final class CatnapStreamHandler extends PublisherResponseHandler {
  private List<StreamingResponseCustomizer> customizers = List.of();
  private static final ServerRestHandler[] TERMINAL = {
    context -> {
      throw new IllegalStateException("Streaming response cannot restart its success chain");
    }
  };

  @Override
  public void setStreamingResponseCustomizers(List<StreamingResponseCustomizer> values) {
    customizers = List.copyOf(values);
  }

  @Override
  public void handle(ResteasyReactiveRequestContext context) {
    if (!(context.getResult() instanceof CatnapStream stream)) return;
    Map<String, List<String>> headers;
    Integer status;
    synchronized (stream) {
      if (!stream.started.compareAndSet(false, true))
        throw new IllegalStateException("Stream already started");
      headers = Map.copyOf(stream.headers);
      status = stream.status;
    }
    var settings = new ArrayList<>(customizers);
    settings.add(
        response -> {
          if (status != null) response.setStatusCode(status);
          headers.forEach(
              (name, values) ->
                  response.setResponseHeader(name, new ArrayList<CharSequence>(values)));
          if (stream.query.active())
            ResponsePolicy.BODY_HEADERS.forEach(context.serverResponse()::removeResponseHeader);
        });
    context.setResponseContentType(stream.mediaType);
    context.setGenericReturnType(byte[].class);
    context.suspend();
    context.restart(TERMINAL, true);
    var subscriber =
        new QuarkusStreamSubscriber<CatnapStream.Frame>(
            new QuarkusStreamSubscriber.Transport<>() {
              @Override
              public CompletionStage<?> write(CatnapStream.Frame frame) {
                return send(context, settings, frame);
              }

              @Override
              public void complete() {
                if (stream.mediaType.isCompatible(MediaType.SERVER_SENT_EVENTS_TYPE))
                  SseUtil.setHeaders(context, context.serverResponse(), settings);
                else StreamingUtil.setHeaders(context, context.serverResponse(), settings);
                context.serverResponse().end();
                context.close();
              }

              @Override
              public void fail(Throwable failure, boolean transportFailure) {
                if (!transportFailure
                    && stream.onError != null
                    && context.serverResponse().headWritten()
                    && !context.serverResponse().closed()
                    && !(failure instanceof CancellationException)) {
                  try {
                    var event = stream.onError.apply(failure);
                    if (event != null) {
                      send(context, settings, event)
                          .whenComplete(
                              (unused, error) -> {
                                if (error == null) complete();
                                else abort(context, error);
                              });
                      return;
                    }
                  } catch (RuntimeException error) {
                    failure = error;
                  }
                }
                abort(context, failure);
              }
            });
    context
        .serverResponse()
        .addCloseHandler(
            () -> {
              subscriber.cancel();
              context.close();
            });
    try {
      stream.source.subscribe(subscriber);
    } catch (RuntimeException failure) {
      subscriber.onError(failure);
    }
  }

  private static CompletionStage<?> send(
      ResteasyReactiveRequestContext context,
      List<StreamingResponseCustomizer> settings,
      CatnapStream.Frame frame) {
    if (frame instanceof CatnapStream.JsonRecord record)
      return StreamingUtil.send(context, settings, record.json(), null, "\n");
    var event = ((CatnapStream.SseFrame) frame).event();
    String comment = event.getComment();
    if (comment == null || (!comment.contains("\n") && !comment.contains("\r")))
      return SseUtil.send(context, event, settings);
    // The pinned native SSE writer truncates multiline comments. Emit their logical lines before
    // the native event (without a blank separator); all other fields and the final frame stay
    // native.
    var prefix = new StringBuilder();
    for (String line : comment.split("\\r\\n|\\r|\\n", -1))
      prefix.append(':').append(line).append('\n');
    SseUtil.setHeaders(context, context.serverResponse(), settings);
    return context
        .serverResponse()
        .write(prefix.toString().getBytes(StandardCharsets.UTF_8))
        .thenCompose(
            unused ->
                SseUtil.send(
                    context, new EncodedEvent(event, (byte[]) event.getData(), null), settings));
  }

  private static void abort(ResteasyReactiveRequestContext context, Throwable failure) {
    if (context.serverResponse().headWritten()) {
      context.serverResponse().reset();
      context.close();
    } else
      context.resume(
          new InternalServerErrorException("JSON stream response failed", failure), true);
  }

  private static String singleLine(String value) {
    if (value == null) return null;
    int end = value.length();
    int lf = value.indexOf('\n');
    int cr = value.indexOf('\r');
    if (lf >= 0) end = Math.min(end, lf);
    if (cr >= 0) end = Math.min(end, cr);
    return value.substring(0, end);
  }

  /** Native byte-array data avoids a second JSON serialization of already projected data. */
  record EncodedEvent(OutboundSseEvent original, byte[] data, String comment)
      implements OutboundSseEvent {
    @Override
    public String getId() {
      return singleLine(original.getId());
    }

    @Override
    public String getName() {
      return singleLine(original.getName());
    }

    @Override
    public long getReconnectDelay() {
      return original.getReconnectDelay();
    }

    @Override
    public boolean isReconnectDelaySet() {
      return original.isReconnectDelaySet();
    }

    @Override
    public Class<?> getType() {
      return byte[].class;
    }

    @Override
    public Type getGenericType() {
      return byte[].class;
    }

    @Override
    public MediaType getMediaType() {
      return original.getMediaType();
    }

    @Override
    public Object getData() {
      return data;
    }

    @Override
    public String getComment() {
      return comment;
    }
  }
}
