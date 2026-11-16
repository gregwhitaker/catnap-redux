package com.github.gregwhitaker.catnap.spring.mvc;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CatnapMvcFilterTest {
  private final ProjectionSettings settings = ProjectionSettings.DEFAULT;
  private final CatnapMvcFilter filter = new CatnapMvcFilter(settings);

  private MockHttpServletRequest request() {
    var request = new MockHttpServletRequest("GET", "/json");
    request.addParameter("fields", "id");
    request.setAttribute(
        CatnapMvcInterceptor.REQUEST_ATTRIBUTE,
        new ProjectionService(settings).bind(List.of("id")));
    return request;
  }

  @Test
  void missingQueryPassesTheOriginalServletResponseThrough() throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest(), response, (req, res) -> assertSame(response, res));
  }

  @Test
  void finiteWritesCannotFlushOriginalBytesBeforeProjection() throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request(),
        response,
        (req, res) -> {
          var http = (HttpServletResponse) res;
          http.setContentType("application/json");
          http.getOutputStream().write("{\"id\":1,\"private\":2}".getBytes(StandardCharsets.UTF_8));
          http.flushBuffer();
          assertFalse(response.isCommitted());
          assertEquals(0, response.getContentAsByteArray().length);
        });
    assertEquals("{\"id\":1}", response.getContentAsString());
    assertTrue(response.isCommitted());
  }

  @Test
  void theNativeJsonEncodingAndResponseStatusArePreserved() throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request(),
        response,
        (req, res) -> {
          var http = (HttpServletResponse) res;
          http.setStatus(202);
          http.setContentType("application/vnd.catnap+json;charset=UTF-16LE");
          http.getOutputStream()
              .write("{\"id\":\"é🐈\",\"private\":2}".getBytes(StandardCharsets.UTF_16LE));
        });
    assertEquals(202, response.getStatus());
    assertEquals(
        "{\"id\":\"é🐈\"}",
        new String(response.getContentAsByteArray(), StandardCharsets.UTF_16LE));
    assertTrue(response.getContentType().contains("application/vnd.catnap+json"));
  }

  @Test
  void resetAndRedirectDiscardCapturedBytes() throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request(),
        response,
        (req, res) -> {
          var http = (HttpServletResponse) res;
          http.setContentType("application/json");
          http.getOutputStream().write("{\"id\":1}".getBytes(StandardCharsets.UTF_8));
          http.sendRedirect("/next");
        });
    assertEquals(302, response.getStatus());
    assertEquals("", response.getContentAsString());
    assertEquals("/next", response.getRedirectedUrl());
  }

  @Test
  void nonJsonStreamsFlushImmediatelyAndDoNotBuildADocument() throws Exception {
    for (String contentType :
        List.of("application/x-ndjson", "text/event-stream", "application/octet-stream")) {
      var response = new MockHttpServletResponse();
      filter.doFilter(
          request(),
          response,
          (req, res) -> {
            var http = (HttpServletResponse) res;
            http.setContentType(contentType);
            http.getOutputStream().write("first".getBytes(StandardCharsets.UTF_8));
            http.flushBuffer();
            assertEquals("first", response.getContentAsString());
            assertTrue(response.isCommitted());
          });
    }
  }
}
