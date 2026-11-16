package com.github.gregwhitaker.catnap.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Executed on each advertised runtime, not just compiled with --release. */
class RuntimeCompatibilityTest {
  @Test
  void productionClassesLoadOnTheSelectedRuntime() {
    assertTrue(Runtime.version().feature() >= 17);
    assertDoesNotThrow(() -> Class.forName(QueryDialect.class.getName()).getEnumConstants());
    System.out.println("Core runtime verified: " + Runtime.version());
  }
}
