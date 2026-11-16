package com.github.gregwhitaker.catnap.spring.mvc;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Starts a Catnap emitter only after Spring has attached its native asynchronous handler. */
public final class CatnapMvcStreamFilter extends OncePerRequestFilter {
  static final String INSTALLED = CatnapMvcStreamFilter.class.getName() + ".installed";
  static final String EMITTER = CatnapMvcStreamFilter.class.getName() + ".emitter";

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    request.setAttribute(INSTALLED, Boolean.TRUE);
    boolean success = false;
    try {
      chain.doFilter(request, response);
      success = true;
    } finally {
      var emitter = (CatnapMvcEmitter<?>) request.getAttribute(EMITTER);
      if (emitter != null) {
        if (success && request.isAsyncStarted()) {
          if (emitter.isInitialized()) emitter.start();
        } else if (!success || !emitter.isInitialized())
          emitter.stop(new IOException("Emitter was not installed in an asynchronous response"));
      }
    }
  }
}
