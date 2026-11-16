package consumer;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.core.Catnap;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import org.junit.jupiter.api.Test;

class CoreConsumerTest {
  @Test
  void compilesBothDialectsFromPublishedJava17Jar() {
    assertNotNull(Catnap.compile("name,price(amount)", QueryDialect.GOOGLE));
    assertNotNull(Catnap.compile("name,price:(amount)", QueryDialect.LINKEDIN));
    assertTrue(
        Catnap.class
            .getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .toString()
            .endsWith(".jar"));
  }
}
