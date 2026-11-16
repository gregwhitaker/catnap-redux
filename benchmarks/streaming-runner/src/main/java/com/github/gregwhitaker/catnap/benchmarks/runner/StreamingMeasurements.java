package com.github.gregwhitaker.catnap.benchmarks.runner;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Separate client/server JVMs and incremental readers; timed runs are opt-in, never normal tests.
 */
public final class StreamingMeasurements {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> MODES = List.of("native", "bypass", "all", "sparse");
  private static final List<String> PROTOCOLS = List.of("ndjson", "sse");
  private final boolean smoke;
  private final String measurementMode;
  private final int stressBoundaries = Integer.getInteger("catnap.stress.boundaries", 100);
  private final double stressSeconds =
      Double.parseDouble(System.getProperty("catnap.stress.seconds", "0.1"));
  private final String stressCase = System.getProperty("catnap.stress.case", "all");
  private final boolean stressReconnect = Boolean.getBoolean("catnap.stress.reconnect");
  private final boolean stressSynchronous = Boolean.getBoolean("catnap.stress.synchronous");
  private final boolean stressResumeFirst = Boolean.getBoolean("catnap.stress.resumefirst");
  private final boolean stressUnpausedStart = Boolean.getBoolean("catnap.stress.unpausedstart");
  private final boolean stressTrace = Boolean.getBoolean("catnap.stress.trace");
  private final boolean stressHoldReaders = Boolean.getBoolean("catnap.stress.holdreaders");
  private final Path output;
  private final ExecutorService readers =
      Executors.newCachedThreadPool(
          runnable -> {
            var thread = new Thread(runnable, "benchmark-reader");
            thread.setDaemon(true);
            return thread;
          });
  private final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();

  private StreamingMeasurements(String measurementMode, Path output) {
    this.measurementMode = measurementMode;
    this.smoke = measurementMode.equals("smoke");
    this.output = output;
    require(
        stressBoundaries > 0
            && stressBoundaries <= 1000
            && stressSeconds > 0
            && stressSeconds <= 10,
        "Invalid stress duration/count");
    require(
        stressCase.equals("all")
            || PROTOCOLS.stream()
                .anyMatch(
                    protocol ->
                        MODES.stream().anyMatch(mode -> stressCase.equals(protocol + ":" + mode))),
        "Invalid stress case");
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 4
        || args.length % 2 != 0
        || !List.of("smoke", "measure", "stress").contains(args[0]))
      throw new IllegalArgumentException(
          "Usage: smoke|measure|stress OUTPUT FRAMEWORK LAUNCH_SPEC ...");
    var runner = new StreamingMeasurements(args[0], Path.of(args[1]));
    Files.createDirectories(runner.output);
    var completed = new ArrayList<String>();
    var summary = new LinkedHashMap<String, Object>();
    summary.put("started", Instant.now().toString());
    summary.put("kind", args[0]);
    summary.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
    summary.put("architecture", System.getProperty("os.arch"));
    summary.put("processors", Runtime.getRuntime().availableProcessors());
    summary.put("clientJava", System.getProperty("java.runtime.version"));
    summary.put("concurrency", 2);
    summary.put("payloadBytes", 1024);
    summary.put("completedFrameworks", completed);
    if (runner.measurementMode.equals("stress"))
      summary.put(
          "stress",
          Map.of(
              "case",
              runner.stressCase,
              "seconds",
              runner.stressSeconds,
              "boundaries",
              runner.stressBoundaries,
              "reconnect",
              runner.stressReconnect,
              "synchronousSource",
              runner.stressSynchronous,
              "resumeBeforeClosingClients",
              runner.stressResumeFirst,
              "unpausedStart",
              runner.stressUnpausedStart,
              "trace",
              runner.stressTrace,
              "holdReaders",
              runner.stressHoldReaders));
    if (!runner.smoke) {
      summary.put(
          "measuredSourcesSha256",
          MeasurementSources.snapshot(
              Path.of("").toAbsolutePath(), runner.output.resolve("measured-sources.zip")));
    }
    summary.put(
        "methodology",
        "HTTP/1.1 loopback, separate server JVMs, sequential cases; body bytes include protocol framing but exclude HTTP headers/chunk framing. Allocation is server-wide including control requests. Retained snapshots use observed GC with sources paused and readers drained. Direct-memory counters may overlap; do not sum them. Smoke durations are functional checks, not performance evidence.");
    runner.save("summary.json", summary);
    try {
      for (int i = 2; i < args.length; i += 2) {
        runner.framework(args[i], Path.of(args[i + 1]));
        completed.add(args[i]);
        runner.save("summary.json", summary);
      }
      summary.put("completed", Instant.now().toString());
      runner.save("summary.json", summary);
    } finally {
      runner.readers.shutdownNow();
    }
  }

  private void framework(String framework, Path spec) throws Exception {
    var result = new LinkedHashMap<String, Object>();
    var cases = new ArrayList<Map<String, Object>>();
    result.put("framework", framework);
    result.put("kind", measurementMode);
    result.put("cases", cases);
    result.put("started", Instant.now().toString());
    String file = framework + ".json";
    save(file, result);
    try (var server = new Server(framework, spec)) {
      result.put("serverCommand", server.command);
      result.put("initialMetrics", server.metrics(true));
      for (String protocol : PROTOCOLS) {
        if (measurementMode.equals("stress")
            && !stressCase.equals("all")
            && !stressCase.startsWith(protocol + ":")) continue;
        isolation(server, protocol);
        for (String mode : MODES) {
          if (measurementMode.equals("stress")) {
            if (!stressCase.equals("all") && !stressCase.equals(protocol + ":" + mode)) continue;
            progress(
                framework
                    + " "
                    + protocol
                    + " "
                    + mode
                    + ": repeated pause/drain/resume boundaries");
            cases.add(boundaryStress(server, protocol, mode));
            save(file, result);
            continue;
          }
          progress(framework + " " + protocol + " " + mode + ": throughput and first-item latency");
          var measured = throughput(server, protocol, mode);
          measured.put("firstItemLatency", latency(server, protocol, mode));
          cases.add(measured);
          save(file, result);
        }
        for (String mode :
            measurementMode.equals("stress") ? List.<String>of() : List.of("native", "all")) {
          progress(
              framework + " " + protocol + " " + mode + ": retained memory with open connections");
          cases.add(retention(server, protocol, mode));
          save(file, result);
        }
      }
      require(
          cases.size()
              == (measurementMode.equals("stress") ? (stressCase.equals("all") ? 8 : 1) : 12),
          "Missing measurement cases");
      result.put("sameSourceQueryIsolation", "passed for every selected protocol");
      result.put("finalMetrics", server.metrics(true));
      server.checkLog();
      result.put("completed", Instant.now().toString());
      save(file, result);
    } catch (Exception failure) {
      result.put("failure", failure.toString());
      save(file, result);
      throw failure;
    }
  }

  private Map<String, Object> throughput(Server server, String protocol, String mode)
      throws Exception {
    try (var group = new Group(server, protocol, mode, 0)) {
      group.runFor(smoke ? .15 : 2);
      group.pauseAndDrain();
      JsonNode before = server.metrics(true);
      require(number(before, "active") == 2, "Throughput requires two live subscriptions");
      long count = group.count(), bytes = group.bytes();
      long clientCpu = clientCpuNanos();
      long start = System.nanoTime();
      server.pause(false);
      group.runFor(smoke ? .3 : 5);
      group.pauseAndDrain();
      double seconds = secondsSince(start);
      JsonNode after = server.metrics(false);
      require(number(after, "active") == 2, "A throughput subscription ended prematurely");
      long delivered = group.count() - count;
      long allocated = number(after, "allocatedBytes") - number(before, "allocatedBytes");
      require(delivered > 0 && allocated > 0, "No measured records or allocation");
      var result = caseResult(protocol, mode, "throughput");
      result.put("seconds", seconds);
      result.put("clientCpuSeconds", (clientCpuNanos() - clientCpu) / 1_000_000_000.0);
      result.put("records", delivered);
      result.put("bodyBytes", group.bytes() - bytes);
      result.put("recordsPerSecond", delivered / seconds);
      result.put("bodyBytesPerSecond", (group.bytes() - bytes) / seconds);
      result.put("allocatedBytes", allocated);
      result.put("allocatedBytesPerRecord", (double) allocated / delivered);
      result.put("before", before);
      result.put("after", after);
      return result;
    }
  }

  private Map<String, Object> boundaryStress(Server server, String protocol, String mode)
      throws Exception {
    if (stressReconnect) {
      var counts = new ArrayList<Long>();
      var backlogs = new ArrayList<Long>();
      for (int i = 0; i < stressBoundaries; i++) {
        latency(server, protocol, mode);
        try (var group = new Group(server, protocol, mode, 0)) {
          group.runFor(stressSeconds);
          group.pauseAndDrain();
          counts.add(group.count());
          backlogs.add(group.heldBacklog);
        }
        if ((i + 1) % 5 == 0)
          progress(
              server.framework
                  + " "
                  + protocol
                  + " "
                  + mode
                  + ": "
                  + (i + 1)
                  + " reconnect boundaries passed");
      }
      var result = caseResult(protocol, mode, "reconnect-boundary-stress");
      result.put("recordsPerConnectionPair", counts);
      if (stressHoldReaders) result.put("recordsQueuedWhileReadersHeld", backlogs);
      return result;
    }
    try (var group = new Group(server, protocol, mode, 0)) {
      var counts = new ArrayList<Long>();
      var backlogs = new ArrayList<Long>();
      for (int i = 0; i < stressBoundaries; i++) {
        group.runFor(stressSeconds);
        group.pauseAndDrain();
        counts.add(group.count());
        backlogs.add(group.heldBacklog);
        if (i + 1 < stressBoundaries) server.pause(false);
      }
      var result = caseResult(protocol, mode, "boundary-stress");
      result.put("recordsAtBoundaries", counts);
      if (stressHoldReaders) result.put("recordsQueuedWhileReadersHeld", backlogs);
      result.put("metrics", server.metrics(false));
      return result;
    }
  }

  private Map<String, Object> latency(Server server, String protocol, String mode)
      throws Exception {
    var values = new ArrayList<Double>();
    for (int i = 0; i < (smoke ? 2 : 30); i++) {
      try (var stream = new Stream(server, protocol, mode, 500, null)) {
        values.add(stream.first.get(15, TimeUnit.SECONDS) / 1_000_000.0);
      }
      server.awaitInactive();
    }
    var sorted = values.stream().sorted().toList();
    return Map.of(
        "samplesMs",
        values,
        "medianMs",
        sorted.get(sorted.size() / 2),
        "p95Ms",
        sorted.get((int) Math.ceil(sorted.size() * .95) - 1),
        "minMs",
        sorted.get(0),
        "maxMs",
        sorted.get(sorted.size() - 1),
        "description",
        "Warm server, fresh streaming HTTP requests; from client send to first complete validated record/event.");
  }

  private Map<String, Object> retention(Server server, String protocol, String mode)
      throws Exception {
    var snapshots = new ArrayList<JsonNode>();
    var points = new ArrayList<Map<String, Object>>();
    double[] checkpoints = smoke ? new double[] {.25, .5, 1} : new double[] {5, 20, 60};
    try (var group = new Group(server, protocol, mode, 500)) {
      double previous = 0;
      long start = System.nanoTime();
      for (double checkpoint : checkpoints) {
        group.runFor(checkpoint - previous);
        group.pauseAndDrain();
        JsonNode snapshot = server.metrics(true);
        require(
            number(snapshot, "active") == 2, "Retention sample must retain two open subscriptions");
        snapshots.add(snapshot);
        points.add(
            Map.of(
                "activeSecondsTarget",
                checkpoint,
                "wallSeconds",
                secondsSince(start),
                "records",
                group.count(),
                "metrics",
                snapshot));
        progress(
            server.framework
                + " "
                + protocol
                + " "
                + mode
                + ": "
                + checkpoint
                + "s, "
                + group.count()
                + " records, post-GC heap "
                + number(snapshot, "heapBytes"));
        server.pause(false);
        previous = checkpoint;
      }
      var result = caseResult(protocol, mode, "retention");
      result.put("recordsPerSecondPerConnection", 500);
      result.put("snapshots", points);
      var ranges = new LinkedHashMap<String, Long>();
      for (String metric : List.of("heapBytes", "jvmDirectBytes", "nettyDirectBytes")) {
        long min = snapshots.stream().mapToLong(node -> number(node, metric)).min().orElseThrow();
        long max = snapshots.stream().mapToLong(node -> number(node, metric)).max().orElseThrow();
        long limit = Math.max(16L * 1024 * 1024, min / 10);
        ranges.put(metric, max - min);
        require(
            max - min <= limit,
            "Retained " + metric + " range " + (max - min) + " exceeds " + limit);
      }
      result.put("retainedRangesBytes", ranges);
      result.put(
          "gate",
          "Range across observed-GC samples <= max(16 MiB, 10% of minimum). Empirical duration/concurrency check, not a proof for every application.");
      return result;
    }
  }

  private void isolation(Server server, String protocol) throws Exception {
    progress(server.framework + " " + protocol + ": simultaneous queries on the same publisher");
    try (var first = new Stream(server, protocol, "all", 500, "id");
        var second = new Stream(server, protocol, "all", 500, "name")) {
      first.first.get(15, TimeUnit.SECONDS);
      second.first.get(15, TimeUnit.SECONDS);
      await(
          () -> first.count.get() >= 20 && second.count.get() >= 20,
          10,
          "Isolation records",
          first,
          second);
      require(
          number(server.metrics(false), "active") == 2,
          "Both subscribers must share the active source");
      first.close();
      long before = second.count.get();
      await(
          () -> second.count.get() >= before + 20,
          10,
          "Second subscriber after first disconnect",
          second);
      second.check();
    } finally {
      server.awaitInactive();
    }
  }

  private final class Group implements AutoCloseable {
    final Server server;
    final List<Stream> streams = new ArrayList<>();
    final long baseline;
    long heldBacklog;

    Group(Server server, String protocol, String mode, int rate) throws Exception {
      this.server = server;
      server.awaitInactive();
      server.pause(true);
      baseline = number(server.metrics(false), "emitted");
      try {
        if (stressUnpausedStart) server.pause(false);
        for (int i = 0; i < 2; i++) streams.add(new Stream(server, protocol, mode, rate, null));
        if (!stressUnpausedStart) server.pause(false);
        for (var stream : streams) stream.first.get(15, TimeUnit.SECONDS);
      } catch (Exception failure) {
        close();
        throw failure;
      }
    }

    long count() {
      return streams.stream().mapToLong(stream -> stream.count.get()).sum();
    }

    long bytes() {
      return streams.stream()
          .mapToLong(stream -> stream.protocolReader == null ? 0 : stream.protocolReader.bytes())
          .sum();
    }

    void runFor(double seconds) throws Exception {
      if (stressHoldReaders) streams.forEach(stream -> stream.readingPaused = true);
      long deadline = System.nanoTime() + (long) (seconds * 1_000_000_000L);
      while (System.nanoTime() < deadline) {
        for (var stream : streams) stream.check();
        require(server.process.isAlive(), "Server exited during measurement");
        Thread.sleep(10);
      }
    }

    void pauseAndDrain() throws Exception {
      server.pause(true);
      long expected = number(server.metrics(false), "emitted") - baseline;
      heldBacklog = expected - count();
      streams.forEach(stream -> stream.readingPaused = false);
      try {
        await(
            () -> count() == expected,
            15,
            "Client drain: expected " + expected + " delivered records",
            streams.toArray(Stream[]::new));
        if (stressTrace && server.framework.equals("webflux")) {
          var traces = server.get("/benchmark/trace");
          for (var stream : streams) {
            var trace = traces.get(stream.traceId);
            require(trace != null, "Missing connection trace " + stream.traceId);
            require(
                number(trace, "source") == stream.count.get(),
                "Per-connection source/delivery mismatch: " + trace);
            require(
                number(trace, "bytes") == stream.protocolReader.bytes(),
                "Per-connection native/client byte mismatch: " + trace);
          }
        }
      } catch (Exception failure) {
        var diagnostics = new LinkedHashMap<String, Object>();
        diagnostics.put("expected", expected);
        diagnostics.put("delivered", count());
        diagnostics.put(
            "byConnection", streams.stream().map(stream -> stream.count.get()).toList());
        diagnostics.put("bodyBytes", bytes());
        diagnostics.put("emittedAtStart", baseline);
        diagnostics.put("server", server.metrics(false));
        if (stressTrace && server.framework.equals("webflux")) {
          diagnostics.put("connectionIds", streams.stream().map(stream -> stream.traceId).toList());
          try {
            diagnostics.put("traces", server.get("/benchmark/trace?threads=true"));
          } catch (Exception traceFailure) {
            diagnostics.put("traceFailure", traceFailure.toString());
          }
        }
        save(server.framework + "-drain-failure.json", diagnostics);
        throw new IllegalStateException("Drain mismatch: " + diagnostics, failure);
      }
    }

    @Override
    public void close() throws Exception {
      if (stressResumeFirst) server.pause(false);
      for (var stream : streams) stream.close();
      // A transport may detect the disconnect during its next write.
      if (!stressResumeFirst) server.pause(false);
      server.awaitInactive();
    }
  }

  private final class Stream implements AutoCloseable {
    final String traceId = UUID.randomUUID().toString();
    final AtomicLong count = new AtomicLong();
    final CompletableFuture<Long> first = new CompletableFuture<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    volatile InputStream body;
    volatile ProtocolReader protocolReader;
    volatile boolean closed;
    volatile boolean readingPaused;
    final Future<?> worker;

    Stream(Server server, String protocol, String mode, int rate, String override) {
      String fields =
          override != null
              ? override
              : switch (mode) {
                case "all" -> "*";
                case "sparse" -> "id,name,price(amount)";
                default -> null;
              };
      String path =
          "/benchmark/"
              + (mode.equals("native") ? "native" : "catnap")
              + "/"
              + protocol
              + "?rate="
              + rate;
      if (fields != null) path += "&fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8);
      if (stressTrace && server.framework.equals("webflux")) path += "&trace=" + traceId;
      JsonNode expected = expected(fields);
      URI uri = server.uri(path);
      worker =
          readers.submit(
              () -> {
                long start = System.nanoTime();
                try {
                  var response =
                      client.send(
                          HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                          HttpResponse.BodyHandlers.ofInputStream());
                  body = response.body();
                  if (closed) {
                    body.close();
                    return;
                  }
                  require(
                      response.statusCode() == 200,
                      "HTTP "
                          + response.statusCode()
                          + " from "
                          + uri
                          + ": "
                          + (response.statusCode() == 200
                              ? ""
                              : new String(body.readNBytes(4096), StandardCharsets.UTF_8)));
                  String media = response.headers().firstValue("content-type").orElse("");
                  require(
                      media.startsWith(
                          protocol.equals("sse") ? "text/event-stream" : "application/x-ndjson"),
                      "Unexpected Content-Type " + media);
                  protocolReader = new ProtocolReader(body, protocol.equals("sse"));
                  String firstJson = null;
                  while (!closed) {
                    while (readingPaused && !closed) Thread.sleep(1);
                    if (closed) break;
                    var frame = protocolReader.next();
                    if (frame == null)
                      throw new IOException("Infinite source ended unexpectedly: " + uri);
                    if (protocol.equals("sse"))
                      require(
                          "1".equals(frame.id())
                              && "sample".equals(frame.event())
                              && "1000".equals(frame.retry())
                              && "benchmark".equals(frame.comment()),
                          "Lost SSE metadata: " + frame);
                    if (firstJson == null) {
                      require(
                          expected.equals(JSON.readTree(frame.json())),
                          "Unexpected projected payload: " + frame.json());
                      firstJson = frame.json();
                    } else
                      require(
                          firstJson.equals(frame.json()),
                          "Payload changed within immutable-source connection");
                    count.incrementAndGet();
                    first.complete(System.nanoTime() - start);
                  }
                } catch (Throwable error) {
                  if (!closed) {
                    failure.set(error);
                    first.completeExceptionally(error);
                  }
                } finally {
                  if (body != null)
                    try {
                      body.close();
                    } catch (IOException ignored) {
                    }
                }
              });
    }

    void check() {
      if (failure.get() != null)
        throw new IllegalStateException("Streaming reader failed", failure.get());
    }

    @Override
    public void close() throws IOException {
      closed = true;
      if (body != null) body.close();
      worker.cancel(true);
    }
  }

  private final class Server implements AutoCloseable {
    final String framework;
    final int port;
    final List<String> command;
    final Process process;
    final Thread shutdown;

    Server(String framework, Path spec) throws Exception {
      this.framework = framework;
      try (var socket = new ServerSocket(0)) {
        port = socket.getLocalPort();
      }
      var launch = Files.readAllLines(spec);
      command = new ArrayList<>();
      command.add(launch.get(0));
      command.addAll(
          List.of(
              "-Xms256m",
              "-Xmx512m",
              "-Dbenchmark.port=" + port,
              "-Dserver.port=" + port,
              "-Dserver.address=127.0.0.1",
              "-Dspring.main.banner-mode=off",
              "-Dlogging.level.root=WARN",
              "-Dmicronaut.server.port=" + port,
              "-Dmicronaut.server.host=127.0.0.1",
              "-Dmicronaut.server.netty.worker.threads=4",
              "-Dquarkus.http.port=" + port,
              "-Dquarkus.http.host=127.0.0.1",
              "-Dquarkus.log.level=WARN",
              "-Dquarkus.vertx.event-loops-pool-size=4",
              "-Dquarkus.thread-pool.max-threads=8",
              "-Dreactor.netty.ioWorkerCount=4",
              "-Dio.netty.eventLoopThreads=4"));
      if (stressSynchronous) command.add("-Dcatnap.benchmark.synchronous-source=true");
      if (stressTrace) command.add("-Dcatnap.benchmark.trace=true");
      command.addAll(launch.subList(1, launch.size()));
      process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(output.resolve(framework + "-server.log").toFile())
              .start();
      shutdown =
          new Thread(
              () -> {
                process.destroy();
                if (process.isAlive()) process.destroyForcibly();
              },
              "stop-benchmark-server");
      Runtime.getRuntime().addShutdownHook(shutdown);
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (true) {
          require(process.isAlive(), framework + " failed to start; see server log");
          try {
            metrics(false);
            break;
          } catch (IOException failure) {
            if (System.nanoTime() >= deadline) throw failure;
            Thread.sleep(100);
          }
        }
      } catch (Exception failure) {
        close();
        throw failure;
      }
      progress(framework + " server ready on loopback port " + port);
    }

    URI uri(String path) {
      return URI.create("http://127.0.0.1:" + port + path);
    }

    JsonNode get(String path) throws Exception {
      var response =
          client.send(
              HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200)
        throw new IOException("Control HTTP " + response.statusCode() + ": " + response.body());
      return JSON.readTree(response.body());
    }

    JsonNode metrics(boolean collect) throws Exception {
      return get("/benchmark/metrics?collect=" + collect);
    }

    void checkLog() throws IOException {
      String log = Files.readString(output.resolve(framework + "-server.log"));
      require(
          !log.contains("OutOfMemoryError")
              && !log.contains("OutOfDirectMemoryError")
              && !log.contains("StackOverflowError"),
          "Server reported a fatal memory/stack error; see " + framework + "-server.log");
    }

    void pause(boolean value) throws Exception {
      get("/benchmark/pause?value=" + value);
    }

    void awaitInactive() throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (number(metrics(false), "active") != 0) {
        if (System.nanoTime() >= deadline)
          throw new IllegalStateException(
              "Server retained an active subscription after client disconnect");
        Thread.sleep(20);
      }
    }

    @Override
    public void close() throws Exception {
      process.destroy();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        require(process.waitFor(5, TimeUnit.SECONDS), "Failed to terminate benchmark server");
      }
      Runtime.getRuntime().removeShutdownHook(shutdown);
    }
  }

  private static JsonNode expected(String fields) {
    if ("id".equals(fields)) return JSON.readTree("{\"id\":1}");
    if ("name".equals(fields)) return JSON.readTree("{\"name\":\"ACME\"}");
    if ("id,name,price(amount)".equals(fields))
      return JSON.readTree("{\"id\":1,\"name\":\"ACME\",\"price\":{\"amount\":12}}");
    return JSON.valueToTree(
        Map.of(
            "id",
            1,
            "name",
            "ACME",
            "price",
            Map.of("amount", 12, "currency", "USD"),
            "padding",
            "x".repeat(950)));
  }

  private static Map<String, Object> caseResult(String protocol, String mode, String kind) {
    var result = new LinkedHashMap<String, Object>();
    result.put("protocol", protocol);
    result.put("mode", mode);
    result.put("kind", kind);
    return result;
  }

  private static long number(JsonNode node, String field) {
    require(
        node.has(field) && node.get(field).isIntegralNumber(), "Missing numeric metric " + field);
    return node.get(field).asLong();
  }

  private static void await(
      BooleanSupplier condition, int seconds, String description, Stream... streams)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (!condition.getAsBoolean()) {
      for (var stream : streams) stream.check();
      if (System.nanoTime() >= deadline)
        throw new IllegalStateException("Timed out: " + description);
      Thread.sleep(5);
    }
    for (var stream : streams) stream.check();
  }

  private static double secondsSince(long start) {
    return (System.nanoTime() - start) / 1_000_000_000.0;
  }

  private static long clientCpuNanos() {
    return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
        .getProcessCpuTime();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private static void progress(String message) {
    System.out.println(Instant.now() + " " + message);
  }

  private void save(String file, Object value) throws IOException {
    Path temp = output.resolve(file + ".tmp");
    Files.writeString(temp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    Files.move(
        temp,
        output.resolve(file),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }
}
