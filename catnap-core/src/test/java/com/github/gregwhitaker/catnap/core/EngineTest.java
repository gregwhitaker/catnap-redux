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

class EngineTest {
  @Test
  void mutableScalarOwnershipBelongsToTheAdapter() {
    var scalar = new StringBuilder("value");
    var adapter =
        new PlainTreeAdapter() {
          @Override
          public Object copyScalar(Object value) {
            return value instanceof StringBuilder text ? new StringBuilder(text) : value;
          }
        };
    StringBuilder result = (StringBuilder) Catnap.compile("*", GOOGLE).evaluate(scalar, adapter);
    result.append(" changed");
    assertEquals("value", scalar.toString());
  }

  @Test
  void concurrentDistinctCacheEntriesStayBoundedAndFailuresAreNotRetained() throws Exception {
    var compiler = new QueryCompiler(5);
    var executor = Executors.newFixedThreadPool(8);
    try {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 200; i++) {
        String name = "field" + i;
        tasks.add(
            () -> {
              assertEquals(
                  Map.of(name, 1),
                  compiler
                      .compile(name, GOOGLE)
                      .evaluate(Map.of(name, 1, "discard", 2), PlainTreeAdapter.INSTANCE));
              assertTrue(compiler.cacheSize() <= 5);
              return null;
            });
      }
      for (var result : executor.invokeAll(tasks)) result.get();
      assertEquals(5, compiler.cacheSize());
    } finally {
      executor.shutdownNow();
    }
    compiler.clearCache();
    assertThrows(QuerySyntaxException.class, () -> compiler.compile("a(", GOOGLE));
    assertEquals(0, compiler.cacheSize());
    compiler.compile("a(b)", GOOGLE);
    assertThrows(
        LimitExceededException.class,
        () -> compiler.compile("a(b)", GOOGLE, new CompileOptions(100, 1, 100)));
    assertEquals(1, compiler.cacheSize());
  }

  @Test
  void maximumEvaluationDepthWorksAndAnOverlappingPlanRespectsWorkLimits() {
    Object nested = 1;
    for (int i = 0; i < 256; i++) nested = List.of(nested);
    Object input = nested;
    assertEquals(
        input,
        Catnap.compile("*", GOOGLE)
            .evaluate(input, PlainTreeAdapter.INSTANCE, new EvaluationOptions(256, 1000)));
    Object document = Map.of("a", Map.of("a", Map.of("id", 1, "x", 2)));
    CompiledQuery overlapping = Catnap.compile("*(a(id)),a(*(x)),a(a(id))", GOOGLE);
    assertEquals(
        Map.of("a", Map.of("a", Map.of("id", 1, "x", 2))),
        overlapping.evaluate(document, PlainTreeAdapter.INSTANCE));
    assertThrows(
        LimitExceededException.class,
        () ->
            overlapping.evaluate(
                document, PlainTreeAdapter.INSTANCE, new EvaluationOptions(20, 5)));
  }

  private static Object project(String expression, Object value) {
    return Catnap.compile(expression, GOOGLE).evaluate(value, PlainTreeAdapter.INSTANCE);
  }

  @Test
  void astIsImmutableAndIndependentOfDialectAndSelectorOrder() {
    Selection first = Catnap.compile("b/x,a(y),a(z),a(y)", GOOGLE).selection();
    Selection second = Catnap.compile("a:(z,y),b:(x)", LINKEDIN).selection();
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertThrows(UnsupportedOperationException.class, () -> first.fields().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> first.fields().get("a").fields().clear());
    assertEquals(
        Catnap.compile("a", GOOGLE).selection(), Catnap.compile("a(x),a", GOOGLE).selection());
    assertTrue(Catnap.compile("\"*\"", GOOGLE).selection().wildcard().isEmpty());
    assertTrue(Catnap.compile("*", GOOGLE).selection().wildcard().isPresent());
  }

  @Test
  @SuppressWarnings("unchecked")
  void copiedWholeSubtreesDoNotAliasInEitherDirection() {
    for (String expression : List.of("*", "a", "a(items),a(empty)")) {
      var leaf = new LinkedHashMap<String, Object>(Map.of("value", 1));
      var array = new ArrayList<Object>(List.of(leaf));
      var branch = new LinkedHashMap<String, Object>();
      branch.put("items", array);
      branch.put("empty", new ArrayList<>());
      var input = new LinkedHashMap<String, Object>(Map.of("a", branch));
      var output = (Map<String, Object>) project(expression, input);
      var copied = (Map<String, Object>) output.get("a");
      var copiedArray = (List<Object>) copied.get("items");
      var copiedLeaf = (Map<String, Object>) copiedArray.get(0);
      copiedLeaf.put("value", 2);
      copiedArray.add(null);
      ((List<Object>) copied.get("empty")).add(3);
      assertEquals(1, leaf.get("value"));
      assertEquals(1, array.size());
      assertTrue(((List<?>) branch.get("empty")).isEmpty());
      leaf.put("sourceOnly", true);
      assertFalse(copiedLeaf.containsKey("sourceOnly"));
    }
  }

  @Test
  void arraysAndSourceFieldOrderArePreserved() {
    var input = new LinkedHashMap<String, Object>();
    input.put("a", 1);
    input.put("b", 2);
    input.put("c", 3);
    assertEquals(List.of("a", "c"), new ArrayList<>(((Map<?, ?>) project("c,a", input)).keySet()));
    assertEquals(
        List.of(Map.of("id", 2), Map.of(), Map.of("id", 1)),
        project("id", List.of(Map.of("id", 2), Map.of("v", 0), Map.of("id", 1))));
  }

  @Test
  void matchingWildcardAndLiteralBranchesMergeAtEveryDepth() {
    Object input = Map.of("a", Map.of("b", Map.of("id", 1, "name", "N", "x", 0)));
    assertEquals(
        Map.of("a", Map.of("b", Map.of("id", 1, "name", "N"))),
        project("*(*(id)),a(b(name))", input));
    assertEquals(input, project("*(*(id)),a(b)", input));
    assertEquals(input, project("a(b),*(*(id))", input));
  }

  @Test
  void cachedPlansCanBeSharedAcrossThreadsWithoutPayloadState() throws Exception {
    QueryCompiler compiler = new QueryCompiler(8);
    var executor = Executors.newFixedThreadPool(8);
    try {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 200; i++) {
        final int id = i;
        tasks.add(
            () -> {
              CompiledQuery plan = compiler.compile("items(id)", GOOGLE);
              Object input = Map.of("items", List.of(Map.of("id", id, "discard", "x")));
              assertEquals(
                  Map.of("items", List.of(Map.of("id", id))),
                  plan.evaluate(input, PlainTreeAdapter.INSTANCE));
              assertSame(plan, compiler.compile("items(id)", GOOGLE));
              return null;
            });
      }
      for (var result : executor.invokeAll(tasks)) result.get();
      assertEquals(1, compiler.cacheSize());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void cacheIsBoundedLruAndKeysContainDialectAndOptions() {
    QueryCompiler compiler = new QueryCompiler(2);
    CompiledQuery a = compiler.compile("a", GOOGLE);
    CompiledQuery b = compiler.compile("b", GOOGLE);
    assertSame(a, compiler.compile("a", GOOGLE));
    compiler.compile("c", GOOGLE);
    assertEquals(2, compiler.cacheSize());
    assertSame(a, compiler.compile("a", GOOGLE));
    assertNotSame(b, compiler.compile("b", GOOGLE));
    assertNotSame(compiler.compile("a", GOOGLE), compiler.compile("a", LINKEDIN));
    assertNotSame(
        compiler.compile("a", GOOGLE), compiler.compile("a", GOOGLE, new CompileOptions(20, 4, 5)));
    assertThrows(QuerySyntaxException.class, () -> compiler.compile("a(", GOOGLE));
    assertEquals(2, compiler.cacheSize());
    compiler.clearCache();
    assertEquals(0, compiler.cacheSize());
    QueryCompiler disabled = new QueryCompiler(0);
    assertNotSame(disabled.compile("a", GOOGLE), disabled.compile("a", GOOGLE));
    assertEquals(0, disabled.cacheSize());
  }

  @Test
  void compilationLimitsIncludePathsDuplicatesAndDominatedSelectors() {
    CompileOptions limits = new CompileOptions(20, 2, 3);
    assertNotNull(Catnap.compile("a/b,a", GOOGLE, limits));
    assertEquals(
        LimitExceededException.Limit.QUERY_DEPTH,
        assertThrows(LimitExceededException.class, () -> Catnap.compile("a/b/c", GOOGLE, limits))
            .limit());
    var length =
        assertThrows(
            LimitExceededException.class, () -> Catnap.compile("a".repeat(21), GOOGLE, limits));
    assertEquals(LimitExceededException.Limit.QUERY_LENGTH, length.limit());
    assertEquals(20, length.position().offset());
    assertEquals(21, length.actual());
    assertEquals(
        LimitExceededException.Limit.SELECTORS,
        assertThrows(LimitExceededException.class, () -> Catnap.compile("a,a,a,a", GOOGLE, limits))
            .limit());
    assertThrows(QuerySyntaxException.class, () -> Catnap.compile("a,a(b,)", GOOGLE));
    assertThrows(LimitExceededException.class, () -> Catnap.compile("a,a(b(c))", GOOGLE, limits));
  }

  @Test
  void evaluationLimitsCoverWholeCopiesUnselectedFieldsAndCycles() {
    CompiledQuery all = Catnap.compile("*", GOOGLE);
    assertEquals(7, all.evaluate(7, PlainTreeAdapter.INSTANCE, new EvaluationOptions(0, 1)));
    var depth =
        assertThrows(
            LimitExceededException.class,
            () ->
                all.evaluate(
                    Map.of("a/b~", List.of(1)),
                    PlainTreeAdapter.INSTANCE,
                    new EvaluationOptions(1, 100)));
    assertEquals(LimitExceededException.Limit.JSON_DEPTH, depth.limit());
    assertEquals("/a~1b~0/0", depth.path());
    var many = new LinkedHashMap<String, Object>();
    for (int i = 0; i < 100; i++) many.put("f" + i, i);
    var work =
        assertThrows(
            LimitExceededException.class,
            () ->
                Catnap.compile("missing", GOOGLE)
                    .evaluate(many, PlainTreeAdapter.INSTANCE, new EvaluationOptions(4, 10)));
    assertEquals(LimitExceededException.Limit.EVALUATION_WORK, work.limit());
    assertEquals(11, work.actual());
    assertNull(work.position());
    var cyclic = new ArrayList<Object>();
    cyclic.add(cyclic);
    assertThrows(
        LimitExceededException.class, () -> all.evaluate(cyclic, PlainTreeAdapter.INSTANCE));
    assertEquals(Map.of("ok", 1), all.evaluate(Map.of("ok", 1), PlainTreeAdapter.INSTANCE));
  }

  @Test
  void adapterFailuresAreStructuredAndLeaveInputAlone() {
    var input = Map.of("a", "value");
    RuntimeException cause = new IllegalStateException("custom serializer failure");
    var failing =
        new PlainTreeAdapter() {
          @Override
          public Object copyScalar(Object value) {
            throw cause;
          }
        };
    var failure =
        assertThrows(
            EvaluationException.class, () -> Catnap.compile("a", GOOGLE).evaluate(input, failing));
    assertEquals(EvaluationException.Code.ADAPTER_FAILURE, failure.code());
    assertEquals("/a", failure.path());
    assertSame(cause, failure.getCause());
    assertEquals(Map.of("a", "value"), input);
  }

  @Test
  void invalidArgumentsAndSurrogatesFailPredictably() {
    assertThrows(NullPointerException.class, () -> Catnap.compile(null, GOOGLE));
    assertThrows(NullPointerException.class, () -> Catnap.compile("a", null));
    assertThrows(IllegalArgumentException.class, () -> new CompileOptions(1, 257, 1));
    assertThrows(IllegalArgumentException.class, () -> new EvaluationOptions(257, 1));
    assertThrows(IllegalArgumentException.class, () -> new QueryCompiler(-1));
    for (String source : List.of("\"\uD800\"", "\"\\uD800x\"", "\uDC00", "\"\\uDC00\"")) {
      assertEquals(
          QuerySyntaxException.Code.INVALID_NAME,
          assertThrows(QuerySyntaxException.class, () -> Catnap.compile(source, GOOGLE)).code());
    }
  }

  @Test
  void adversarialExpressionsTerminateWithBoundedDiagnostics() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          for (QueryDialect dialect : QueryDialect.values()) {
            String open = dialect == LINKEDIN ? "a:(" : "a(";
            assertThrows(
                LimitExceededException.class,
                () -> Catnap.compile(open.repeat(1000) + "x", dialect));
            // Exercise the maximum supported recursion depth, in addition to the default limit.
            assertNotNull(
                Catnap.compile(
                    open.repeat(255) + "x" + ")".repeat(255),
                    dialect,
                    new CompileOptions(4096, 256, 4096)));
          }
          Random random = new Random(731);
          String alphabet = "abc/$*():,[]~=\"\\ \n\r\t😀";
          for (int sample = 0; sample < 3000; sample++) {
            var source = new StringBuilder();
            for (int i = random.nextInt(100); i > 0; i--)
              source.append(alphabet.charAt(random.nextInt(alphabet.length())));
            for (QueryDialect dialect : QueryDialect.values()) {
              try {
                Catnap.compile(source.toString(), dialect);
              } catch (QuerySyntaxException error) {
                assertTrue(
                    error.position().offset() >= 0 && error.position().offset() <= source.length());
              }
            }
          }
        });
  }
}
