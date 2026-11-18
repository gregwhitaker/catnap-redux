package com.github.gregwhitaker.catnap.core;

import static com.github.gregwhitaker.catnap.core.QueryDialect.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class CatnapDialectTest {
  @Test
  void otherDialectsStillRejectPredicatesAtTheOriginalLocation() {
    for (QueryDialect dialect : List.of(GOOGLE, LINKEDIN)) {
      String prefix = dialect == GOOGLE ? "images(url)" : "images:(url)";
      var error =
          assertThrows(
              QuerySyntaxException.class, () -> Catnap.compile(prefix + "[size==medium]", dialect));
      assertEquals(QuerySyntaxException.Code.UNEXPECTED_TOKEN, error.code());
      assertEquals(prefix.length(), error.position().offset());
    }
  }

  @Test
  void predicateSyntaxAndWorkAreBoundedEvenWhenBranchesAreDominated() {
    var limits = new CompileOptions(10_000, 4, 5);
    assertNotNull(Catnap.compile("a[id=in=(1,2,3)]", CATNAP, limits));
    var count =
        assertThrows(
            LimitExceededException.class,
            () -> Catnap.compile("a[id=in=(1,2,3,4)]", CATNAP, limits));
    assertEquals(LimitExceededException.Limit.SELECTORS, count.limit());
    assertEquals(6, count.actual());
    assertThrows(
        LimitExceededException.class, () -> Catnap.compile("a,a[id=in=(1,2,3)]", CATNAP, limits));
    assertThrows(QuerySyntaxException.class, () -> Catnap.compile("a,a[id==]", CATNAP));
    var depth =
        assertThrows(
            LimitExceededException.class, () -> Catnap.compile("a[(((id==1)))]", CATNAP, limits));
    assertEquals(LimitExceededException.Limit.QUERY_DEPTH, depth.limit());

    var query = Catnap.compile("a(id)[id==1]", CATNAP);
    Object input = Map.of("a", List.of(Map.of("id", 2)));
    var jsonDepth =
        assertThrows(
            LimitExceededException.class,
            () -> query.evaluate(input, PlainTreeAdapter.INSTANCE, new EvaluationOptions(2, 1000)));
    assertEquals(LimitExceededException.Limit.JSON_DEPTH, jsonDepth.limit());
    assertEquals("/a/0/id", jsonDepth.path());
    var work =
        assertThrows(
            LimitExceededException.class,
            () ->
                query.evaluate(
                    Map.of("a", java.util.Collections.nCopies(100, Map.of("id", 2))),
                    PlainTreeAdapter.INSTANCE,
                    new EvaluationOptions(10, 30)));
    assertEquals(LimitExceededException.Limit.EVALUATION_WORK, work.limit());
    assertEquals(31, work.actual());
    assertEquals(Map.of("a", List.of()), query.evaluate(input, PlainTreeAdapter.INSTANCE));
  }

  @Test
  void membershipComparisonsAndRejectedScalarsConsumeWork() {
    String arguments = "1,".repeat(99) + "1";
    var query = Catnap.compile("a[id=in=(" + arguments + ")]", CATNAP);
    var failure =
        assertThrows(
            LimitExceededException.class,
            () ->
                query.evaluate(
                    Map.of("a", List.of(Map.of("id", 2))),
                    PlainTreeAdapter.INSTANCE,
                    new EvaluationOptions(10, 50)));
    assertEquals("/a/0/id", failure.path());
    assertThrows(
        LimitExceededException.class,
        () ->
            query.evaluate(
                Map.of("a", java.util.Collections.nCopies(100, 7)),
                PlainTreeAdapter.INSTANCE,
                new EvaluationOptions(10, 50)));
  }

  @Test
  void scalarAdapterFailuresKeepTheSourcePathAndFieldOnlyQueriesNeverInspectScalars() {
    var unsupported =
        new PlainTreeAdapter() {
          @Override
          public JsonScalar scalarValue(Object value) {
            throw new UnsupportedOperationException("no predicates");
          }
        };
    Object input = Map.of("a", List.of(Map.of("a/b~", 1, "id", 2)));
    for (var dialect : QueryDialect.values()) {
      assertEquals(input, Catnap.compile("a", dialect).evaluate(input, unsupported));
    }
    var error =
        assertThrows(
            EvaluationException.class,
            () -> Catnap.compile("a(id)[\"a/b~\"==1]", CATNAP).evaluate(input, unsupported));
    assertEquals("/a/0/a~1b~0", error.path());
    assertInstanceOf(UnsupportedOperationException.class, error.getCause());
    // Whole-value dominance does not require evaluating an irrelevant predicate.
    assertEquals(input, Catnap.compile("a,a[id==2]", CATNAP).evaluate(input, unsupported));
  }

  @Test
  @SuppressWarnings("unchecked")
  void filteredResultsAndPredicatePlansHaveIndependentOwnership() {
    var original =
        new LinkedHashMap<String, Object>(Map.of("id", 1, "nested", new ArrayList<>(List.of(2))));
    var input = new LinkedHashMap<String, Object>(Map.of("a", new ArrayList<>(List.of(original))));
    var query = Catnap.compile("a[id==1]", CATNAP);
    var output = (Map<String, Object>) query.evaluate(input, PlainTreeAdapter.INSTANCE);
    var selected = (List<Map<String, Object>>) output.get("a");
    ((List<Object>) selected.get(0).get("nested")).add(3);
    assertEquals(List.of(2), original.get("nested"));
    original.put("later", true);
    assertFalse(selected.get(0).containsKey("later"));
    var filters = query.selection().fields().get("a").filters();
    assertThrows(UnsupportedOperationException.class, filters::clear);
    var comparison = (QueryPredicate.Comparison) filters.get(0).predicate();
    assertThrows(UnsupportedOperationException.class, () -> comparison.arguments().clear());
  }

  @Test
  void oneCachedPredicateCanServeConcurrentDocuments() throws Exception {
    var compiler = new QueryCompiler(8);
    var plan = compiler.compile("items(id)[keep==true]", CATNAP);
    var executor = Executors.newFixedThreadPool(4);
    try {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 80; i++) {
        int id = i;
        tasks.add(
            () -> {
              assertSame(plan, compiler.compile("items(id)[keep==true]", CATNAP));
              Object source =
                  Map.of(
                      "items",
                      List.of(Map.of("id", id, "keep", true), Map.of("id", -1, "keep", false)));
              assertEquals(
                  Map.of("items", List.of(Map.of("id", id))),
                  plan.evaluate(source, PlainTreeAdapter.INSTANCE));
              return null;
            });
      }
      for (var result : executor.invokeAll(tasks)) result.get();
      assertNotSame(compiler.compile("items", CATNAP), compiler.compile("items", GOOGLE));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void predicateParserTerminatesAtMaximumDepthAndForMalformedUnicode() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          assertNotNull(
              Catnap.compile(
                  "a[" + "(".repeat(254) + "x==1" + ")".repeat(254) + "]",
                  CATNAP,
                  new CompileOptions(4096, 256, 4096)));
          assertThrows(
              LimitExceededException.class,
              () -> Catnap.compile("a[" + "(".repeat(1000) + "x==1", CATNAP));
          for (String query : List.of("a[x=='\uD800']", "a[\uDC00==1]", "a[x==\uD800]")) {
            assertEquals(
                QuerySyntaxException.Code.INVALID_NAME,
                assertThrows(QuerySyntaxException.class, () -> Catnap.compile(query, CATNAP))
                    .code());
          }
          var random = new Random(591);
          String alphabet = "abc123=!<>();,[]'\"\\ \n\r\t😀";
          for (int i = 0; i < 2000; i++) {
            var query = new StringBuilder("a[");
            for (int j = random.nextInt(100); j > 0; j--)
              query.append(alphabet.charAt(random.nextInt(alphabet.length())));
            query.append(']');
            try {
              Catnap.compile(query.toString(), CATNAP);
            } catch (QuerySyntaxException error) {
              assertTrue(
                  error.position().offset() >= 0 && error.position().offset() <= query.length());
            }
          }
        });
  }
}
