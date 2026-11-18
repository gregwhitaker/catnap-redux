package consumer;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.core.Catnap;
import com.github.gregwhitaker.catnap.core.JsonScalar;
import com.github.gregwhitaker.catnap.core.JsonTreeAdapter;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import com.github.gregwhitaker.catnap.core.QueryPredicate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreConsumerTest {
  @Test
  void compilesAllDialectsFromPublishedJava17Jar() {
    assertNotNull(Catnap.compile("name,price(amount)", QueryDialect.GOOGLE));
    assertNotNull(Catnap.compile("name,price:(amount)", QueryDialect.LINKEDIN));
    assertNotNull(Catnap.compile("images(url)[size==medium]", QueryDialect.CATNAP));
    assertTrue(
        Catnap.class
            .getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .toString()
            .endsWith(".jar"));
  }

  @Test
  void evaluatesCatnapPredicatesWithConsumerOwnedAdapter() {
    var query =
        Catnap.compile("images(url)[size=in=(medium,large);width>=640]", QueryDialect.CATNAP);
    var small = Map.of("url", "small.png", "size", "small", "width", 320);
    var medium = Map.of("url", "medium.png", "size", "medium", "width", 640);
    var narrow = Map.of("url", "narrow.png", "size", "medium", "width", 639);
    var large = Map.of("url", "large.png", "size", "large", "width", 1280);
    var input = Map.of("images", List.of(small, medium, narrow, large));
    var expected =
        Map.of("images", List.of(Map.of("url", "medium.png"), Map.of("url", "large.png")));
    var adapter = new ConsumerTreeAdapter();

    assertEquals(expected, query.evaluate(input, adapter));
    assertEquals(4, input.get("images").size());
    assertEquals(
        Map.of("images", List.of()), query.evaluate(Map.of("images", List.of(small)), adapter));
    assertEquals(expected, query.evaluate(input, adapter));

    // The immutable predicate AST and typed scalar API must also be present in the published jar.
    var predicate = query.selection().fields().get("images").filters().get(0).predicate();
    var conjunction = assertInstanceOf(QueryPredicate.Junction.class, predicate);
    assertTrue(conjunction.conjunction());
    var width = assertInstanceOf(QueryPredicate.Comparison.class, conjunction.terms().get(1));
    assertEquals(new JsonScalar(new BigDecimal("640")), width.arguments().get(0));
    assertEquals(
        Catnap.class.getProtectionDomain().getCodeSource().getLocation(),
        QueryPredicate.class.getProtectionDomain().getCodeSource().getLocation());
  }

  private static final class ConsumerTreeAdapter implements JsonTreeAdapter<Object> {
    @Override
    public Kind kind(Object value) {
      return value instanceof Map ? Kind.OBJECT : value instanceof List ? Kind.ARRAY : Kind.SCALAR;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterable<? extends Map.Entry<String, Object>> fields(Object value) {
      return ((Map<String, Object>) value).entrySet();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterable<Object> elements(Object value) {
      return (List<Object>) value;
    }

    @Override
    public Object object(Map<String, Object> fields) {
      return fields;
    }

    @Override
    public Object array(List<Object> elements) {
      return elements;
    }

    @Override
    public Object copyScalar(Object value) {
      return value;
    }

    @Override
    public JsonScalar scalarValue(Object value) {
      return new JsonScalar(value instanceof Number ? new BigDecimal(value.toString()) : value);
    }
  }
}
