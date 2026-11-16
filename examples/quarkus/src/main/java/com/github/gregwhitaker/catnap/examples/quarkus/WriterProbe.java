package com.github.gregwhitaker.catnap.examples.quarkus;

import jakarta.annotation.Priority;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/** Exercises application provider selection and name-bound interceptor context. */
public final class WriterProbe {
  private WriterProbe() {}

  public record WrittenValue(String name) {}

  @NameBinding
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  public @interface Intercepted {}

  @Provider
  @Produces("application/json")
  @Priority(1000)
  public static class Writer implements MessageBodyWriter<WrittenValue> {
    @Override
    public boolean isWriteable(
        Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
      return type == WrittenValue.class;
    }

    @Override
    public void writeTo(
        WrittenValue value,
        Class<?> type,
        Type genericType,
        Annotation[] annotations,
        MediaType mediaType,
        MultivaluedMap<String, Object> headers,
        OutputStream stream)
        throws IOException {
      if (genericType != WrittenValue.class) throw new IOException("Declared type was lost");
      headers.putSingle("X-Writer", "selected");
      stream.write(
          ("{\"id\":\"" + value.name() + "\",\"hidden\":true}").getBytes(StandardCharsets.UTF_8));
      stream.flush();
    }
  }

  @Provider
  @Intercepted
  @Priority(1000)
  public static class Interceptor implements WriterInterceptor {
    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
      var value = (WrittenValue) context.getEntity();
      context.setEntity(new WrittenValue(value.name() + "-intercepted"));
      context.getHeaders().add("X-Interceptor", "once");
      context.proceed();
    }
  }
}
