package com.github.gregwhitaker.catnap.quarkus;

import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.IOException;
import java.io.OutputStream;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.jaxrs.AbstractInterceptorContext;
import org.jboss.resteasy.reactive.server.spi.ServerMessageBodyWriter;

/** Uses the same provider ordering and native/standard writer choice as Quarkus REST. */
final class NativeSerialization {
  private NativeSerialization() {}

  @SuppressWarnings("unchecked")
  static byte[] capture(
      ResteasyReactiveRequestContext context, ContainerResponseContext response, int limit)
      throws IOException {
    var captured = new BoundedBuffer(limit);
    var interceptors = context.getWriterInterceptors();
    boolean nativePath = interceptors == null && context.getOutputStream() == null;
    var type = response.getEntityClass();
    var genericType = response.getEntityType();
    var nativeType =
        context.hasGenericReturnType()
            ? genericType
            : context.getTarget() == null ? null : context.getTarget().getReturnType();
    var annotations = response.getEntityAnnotations();
    var mediaType = response.getMediaType();
    for (var candidate :
        context
            .getDeployment()
            .getSerialisers()
            .findWriters(null, type, mediaType, RuntimeType.SERVER)) {
      if (nativePath && candidate instanceof ServerMessageBodyWriter<?> serverWriter) {
        var nativeWriter = (ServerMessageBodyWriter<Object>) serverWriter;
        if (!nativeWriter.isWriteable(
            type, nativeType, context.getResteasyReactiveResourceInfo(), mediaType)) continue;
        nativeWriter.writeResponse(
            response.getEntity(), nativeType, new CapturingContext(context, response, captured));
      } else {
        var writer = (MessageBodyWriter<Object>) candidate;
        if (!writer.isWriteable(type, genericType, annotations, mediaType)) continue;
        if (interceptors == null)
          writer.writeTo(
              response.getEntity(),
              type,
              genericType,
              annotations,
              mediaType,
              response.getHeaders(),
              captured);
        else new Interceptors(context, response, writer, interceptors, captured).proceed();
      }
      return captured.toByteArray();
    }
    throw new IOException("No JSON message body writer for " + type.getName());
  }

  private static final class Interceptors extends AbstractInterceptorContext
      implements WriterInterceptorContext {
    private final WriterInterceptor[] interceptors;
    private final MessageBodyWriter<Object> writer;
    private final MultivaluedMap<String, Object> headers;
    private Object entity;
    private OutputStream output;
    private int index;

    Interceptors(
        ResteasyReactiveRequestContext context,
        ContainerResponseContext response,
        MessageBodyWriter<Object> writer,
        WriterInterceptor[] interceptors,
        OutputStream output) {
      super(
          context,
          response.getEntityAnnotations(),
          response.getEntityClass(),
          response.getEntityType(),
          response.getMediaType(),
          context.getDeployment().getSerialisers());
      this.writer = writer;
      this.interceptors = interceptors;
      this.output = output;
      this.entity = response.getEntity();
      this.headers = response.getHeaders();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void proceed() throws IOException {
      if (index < interceptors.length) {
        interceptors[index++].aroundWriteTo(this);
        return;
      }
      MessageBodyWriter<Object> selected = writer;
      if (rediscoveryNeeded) {
        selected = null;
        for (var candidate :
            serialisers.findWriters(null, entity.getClass(), mediaType, RuntimeType.SERVER)) {
          if (candidate.isWriteable(type, genericType, annotations, mediaType)) {
            selected = (MessageBodyWriter<Object>) candidate;
            break;
          }
        }
        if (selected == null)
          throw new IOException("No JSON writer after interceptor changed the entity type");
      }
      selected.writeTo(entity, type, genericType, annotations, mediaType, headers, output);
      output.close();
    }

    @Override
    public Object getEntity() {
      return entity;
    }

    @Override
    public void setEntity(Object entity) {
      this.entity = entity;
    }

    @Override
    public OutputStream getOutputStream() {
      return output;
    }

    @Override
    public void setOutputStream(OutputStream output) {
      this.output = output;
    }

    @Override
    public MultivaluedMap<String, Object> getHeaders() {
      return headers;
    }
  }
}
