package com.github.gregwhitaker.catnap.quarkus;

import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import jakarta.ws.rs.container.CompletionCallback;
import jakarta.ws.rs.container.ConnectionCallback;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.spi.ContentType;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveResourceInfo;
import org.jboss.resteasy.reactive.server.spi.ServerHttpResponse;
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext;

/** A bounded, uncommitted sink retaining the endpoint's native serialization metadata. */
final class CapturingContext implements ServerRequestContext, ServerHttpResponse {
  private final ResteasyReactiveRequestContext original;
  private final ContainerResponseContext response;
  private final BoundedBuffer output;

  CapturingContext(
      ResteasyReactiveRequestContext original,
      ContainerResponseContext response,
      BoundedBuffer output) {
    this.original = original;
    this.response = response;
    this.output = output;
  }

  @Override
  public ServerHttpResponse serverResponse() {
    return this;
  }

  @Override
  public InputStream getInputStream() {
    return original.getInputStream();
  }

  @Override
  public ContentType getResponseContentType() {
    return original.getResponseContentType();
  }

  @Override
  public MediaType getResponseMediaType() {
    return response.getMediaType();
  }

  @Override
  public OutputStream getOrCreateOutputStream() {
    return output;
  }

  @Override
  public ResteasyReactiveResourceInfo getResteasyReactiveResourceInfo() {
    return original.getResteasyReactiveResourceInfo();
  }

  @Override
  public HttpHeaders getRequestHeaders() {
    return original.getRequestHeaders();
  }

  @Override
  public void abortWith(Response replacement) {
    throw new IllegalStateException("A JSON writer cannot abort during serialization");
  }

  @Override
  public void registerCompletionCallback(CompletionCallback callback) {
    original.registerCompletionCallback(callback);
  }

  @Override
  public void registerConnectionCallback(ConnectionCallback callback) {
    original.registerConnectionCallback(callback);
  }

  @Override
  public ServerHttpResponse setStatusCode(int code) {
    response.setStatus(code);
    return this;
  }

  @Override
  public ServerHttpResponse end() {
    return this;
  }

  @Override
  public boolean headWritten() {
    return false;
  }

  @Override
  public ServerHttpResponse end(byte[] bytes) {
    try {
      output.write(bytes, 0, bytes.length);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
    return this;
  }

  @Override
  public ServerHttpResponse end(String text) {
    return end(text.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public ServerHttpResponse addResponseHeader(CharSequence name, CharSequence value) {
    response.getHeaders().add(name.toString(), value.toString());
    return this;
  }

  @Override
  public ServerHttpResponse setResponseHeader(CharSequence name, CharSequence value) {
    if (value == null) removeResponseHeader(name.toString());
    else response.getHeaders().putSingle(name.toString(), value.toString());
    return this;
  }

  @Override
  public ServerHttpResponse setResponseHeader(CharSequence name, Iterable<CharSequence> values) {
    List<Object> copy = new ArrayList<>();
    for (var value : values) copy.add(value.toString());
    response.getHeaders().put(name.toString(), copy);
    return this;
  }

  @Override
  public Iterable<Map.Entry<String, String>> getAllResponseHeaders() {
    List<Map.Entry<String, String>> entries = new ArrayList<>();
    response
        .getStringHeaders()
        .forEach((key, values) -> values.forEach(value -> entries.add(Map.entry(key, value))));
    return entries;
  }

  @Override
  public String getResponseHeader(String name) {
    return response.getHeaderString(name);
  }

  @Override
  public void removeResponseHeader(String name) {
    response.getHeaders().keySet().removeIf(name::equalsIgnoreCase);
  }

  @Override
  public boolean closed() {
    return original.serverResponse().closed();
  }

  @Override
  public ServerHttpResponse setChunked(boolean chunked) {
    return this;
  }

  @Override
  public ServerHttpResponse write(byte[] bytes, Consumer<Throwable> callback) {
    try {
      end(bytes);
    } catch (RuntimeException failure) {
      callback.accept(failure);
      throw failure;
    }
    callback.accept(null);
    return this;
  }

  @Override
  public CompletionStage<Void> write(byte[] bytes) {
    end(bytes);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public ServerHttpResponse sendFile(String path, long offset, long length) {
    // File/send operations are transport operations, outside the finite JSON writer contract.
    throw new IllegalStateException("JSON projection requires a synchronous message body writer");
  }

  @Override
  public OutputStream createResponseOutputStream() {
    return output;
  }

  @Override
  public void setPreCommitListener(Consumer<ResteasyReactiveRequestContext> listener) {
    throw new IllegalStateException("A JSON writer cannot install a transport commit listener");
  }

  @Override
  public ServerHttpResponse addCloseHandler(Runnable callback) {
    original.serverResponse().addCloseHandler(callback);
    return this;
  }

  @Override
  public boolean isWriteQueueFull() {
    return false;
  }

  @Override
  public ServerHttpResponse addDrainHandler(Runnable callback) {
    return this;
  }

  @Override
  public void reset() {
    throw new IllegalStateException("A JSON writer cannot reset the transport");
  }
}
