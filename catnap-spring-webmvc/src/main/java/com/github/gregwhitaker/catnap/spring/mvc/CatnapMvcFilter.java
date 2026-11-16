package com.github.gregwhitaker.catnap.spring.mvc;

import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import com.github.gregwhitaker.catnap.http.ProjectionRequest;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import com.github.gregwhitaker.catnap.http.ResponsePolicy;
import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Projects bounded finite JSON after the application's native converter has serialized it. Register
 * for REQUEST and ASYNC dispatches with asyncSupported=true, together with {@link
 * CatnapMvcInterceptor}. NDJSON and SSE retain their native streaming path.
 */
public final class CatnapMvcFilter extends OncePerRequestFilter {
  private final ProjectionSettings settings;
  private final JsonMapper mapper;
  private final Jackson3Json json;

  public CatnapMvcFilter(ProjectionSettings settings) {
    this.settings = Objects.requireNonNull(settings);
    mapper =
        JsonMapper.builder(
                JsonFactory.builder()
                    .streamReadConstraints(
                        StreamReadConstraints.builder()
                            .maxNestingDepth(settings.evaluation().maxDepth() + 1)
                            .build())
                    .build())
            .build();
    json = new Jackson3Json(mapper);
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!settings.enabled()) return true;
    String[] values = request.getParameterValues(settings.parameter());
    return values == null
        || (values.length == 1
            && values[0].chars().allMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n'));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Capture existing = WebUtils.getNativeResponse(response, Capture.class);
    Capture capture = existing == null ? new Capture(request, response) : existing;
    chain.doFilter(request, existing == null ? capture : response);
    if (!isAsyncStarted(request)) {
      try {
        capture.finish();
      } catch (IOException | RuntimeException error) {
        // A failed or truncated projection must never be sent as the original response.
        if (response.isCommitted()) throw error;
        response.reset();
        response.sendError(500, "JSON response projection failed");
      }
    }
  }

  private final class Capture extends HttpServletResponseWrapper {
    private final HttpServletRequest request;
    private final ServletOutputStream stream = new CapturedOutput();
    private BoundedBuffer buffer;
    private boolean decided;
    private boolean finished;
    private boolean streamRequested;
    private PrintWriter writer;

    private Capture(HttpServletRequest request, HttpServletResponse response) {
      super(response);
      this.request = request;
    }

    private boolean eligible() {
      ProjectionRequest projection = CatnapMvcInterceptor.projection(request);
      return projection != null
          && projection.active()
          && ResponsePolicy.finiteJson(
              getContentType(), getStatus(), getHeader("Content-Encoding"));
    }

    private OutputStream destination() throws IOException {
      if (!decided) {
        decided = true;
        if (eligible()) buffer = new BoundedBuffer(settings.maxPayloadBytes());
      }
      return buffer == null ? super.getOutputStream() : buffer;
    }

    @Override
    public ServletOutputStream getOutputStream() {
      if (writer != null) throw new IllegalStateException("getWriter() has already been called");
      streamRequested = true;
      return stream;
    }

    @Override
    public PrintWriter getWriter() {
      if (streamRequested)
        throw new IllegalStateException("getOutputStream() has already been called");
      if (writer == null)
        writer =
            new PrintWriter(
                new OutputStreamWriter(stream, Charset.forName(getCharacterEncoding())));
      return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
      if (writer != null) writer.flush();
      destination().flush();
      if (buffer == null) super.flushBuffer();
    }

    @Override
    public void resetBuffer() {
      super.resetBuffer();
      buffer = null;
      decided = false;
    }

    @Override
    public void reset() {
      super.reset();
      buffer = null;
      decided = false;
      writer = null;
      streamRequested = false;
    }

    @Override
    public void sendError(int status, String message) throws IOException {
      resetBuffer();
      super.sendError(status, message);
    }

    @Override
    public void sendError(int status) throws IOException {
      resetBuffer();
      super.sendError(status);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
      resetBuffer();
      super.sendRedirect(location);
    }

    private void finish() throws IOException {
      if (finished) return;
      if (writer != null) writer.flush();
      finished = true;
      if (buffer == null) return;
      byte[] bytes = buffer.toByteArray();
      if (bytes.length == 0) return;
      if (eligible()) {
        var projection = CatnapMvcInterceptor.projection(request);
        var result = projection.evaluate(json.parse(bytes), json.adapter());
        Charset charset = MediaType.parseMediaType(getContentType()).getCharset();
        if (charset == null) charset = StandardCharsets.UTF_8;
        BoundedBuffer encoded = new BoundedBuffer(settings.maxPayloadBytes());
        mapper.writeValue(new OutputStreamWriter(encoded, charset), result);
        bytes = encoded.toByteArray();
        for (String header : ResponsePolicy.BODY_HEADERS) super.setHeader(header, null);
        super.setContentLengthLong(bytes.length);
      }
      super.getOutputStream().write(bytes);
      super.flushBuffer();
      buffer = null;
    }

    private final class CapturedOutput extends ServletOutputStream {
      @Override
      public void write(int value) throws IOException {
        destination().write(value);
      }

      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException {
        destination().write(bytes, offset, length);
      }

      @Override
      public void flush() throws IOException {
        destination().flush();
      }

      @Override
      public boolean isReady() {
        try {
          OutputStream target = destination();
          return !(target instanceof ServletOutputStream servlet) || servlet.isReady();
        } catch (IOException error) {
          throw new IllegalStateException(error);
        }
      }

      @Override
      public void setWriteListener(WriteListener listener) {
        try {
          OutputStream target = destination();
          if (!(target instanceof ServletOutputStream servlet))
            throw new IllegalStateException(
                "Use Catnap's streaming integration for nonblocking writes");
          servlet.setWriteListener(listener);
        } catch (IOException error) {
          throw new IllegalStateException(error);
        }
      }
    }
  }
}
