package com.github.gregwhitaker.catnap.benchmarks;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * Captures environment and an identity for the measured source without recording host/user names.
 */
public final class EnvironmentReport {
  private EnvironmentReport() {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]), output = Path.of(args[1]);
    var mapper = JsonMapper.builder().build();
    var report = new LinkedHashMap<String, Object>();
    report.put("capturedAt", Instant.now().toString());
    for (String property :
        List.of(
            "java.runtime.version",
            "java.vm.name",
            "java.vendor",
            "os.name",
            "os.version",
            "os.arch")) {
      report.put(property, System.getProperty(property));
    }
    report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
    if (operatingSystem instanceof OperatingSystemMXBean bean)
      report.put("physicalMemoryBytes", bean.getTotalMemorySize());
    report.put("forkJvmArguments", List.of("-Xms256m", "-Xmx512m"));
    var sizes = new LinkedHashMap<String, Object>();
    for (String name : List.of("SMALL", "LARGE_ARRAY", "DEEP", "SPARSE", "WILDCARD")) {
      var workload = Workload.named(name);
      sizes.put(
          name,
          Map.of(
              "inputBytes",
              mapper.writeValueAsBytes(workload.value()).length,
              "selectedBytes",
              mapper.writeValueAsBytes(workload.expected()).length,
              "googleQuery",
              workload.google(),
              "linkedInQuery",
              workload.linkedIn()));
    }
    report.put("workloads", sizes);
    var source = new TreeMap<String, byte[]>();
    for (String directory :
        List.of(
            "catnap-core/src/main",
            "catnap-http/src/main",
            "catnap-jackson3/src/main",
            "benchmarks/src/main")) {
      try (var paths = Files.walk(root.resolve(directory))) {
        for (Path path : paths.filter(Files::isRegularFile).toList())
          source.put(root.relativize(path).toString(), Files.readAllBytes(path));
      }
    }
    for (String path :
        List.of(
            "gradle/libs.versions.toml",
            "benchmarks/build.gradle.kts",
            "benchmarks/gradle.lockfile")) source.put(path, Files.readAllBytes(root.resolve(path)));
    var digest = MessageDigest.getInstance("SHA-256");
    for (var entry : source.entrySet()) {
      digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(entry.getValue());
      digest.update((byte) 0);
    }
    report.put("measuredSourceSha256", HexFormat.of().formatHex(digest.digest()));
    report.put("measuredSourceFiles", source.keySet());
    Files.createDirectories(output.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
  }
}
