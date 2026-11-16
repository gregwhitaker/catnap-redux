package com.github.gregwhitaker.catnap.benchmarks.runner;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Preserve source and resolved dependency identity before any timed measurement begins. */
final class MeasurementSources {
  private MeasurementSources() {}

  static Map<String, String> snapshot(Path root, Path destination) throws Exception {
    var hashes = new TreeMap<String, String>();
    var files = new TreeMap<String, Path>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
            String name = path.getFileName().toString();
            return !path.equals(root)
                    && (name.startsWith(".") || name.equals("build") || name.equals("docs"))
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
            String relative = root.relativize(path).toString().replace('\\', '/');
            String name = path.getFileName().toString();
            if (name.endsWith(".java")
                || name.endsWith(".kt")
                || name.endsWith(".kts")
                || name.endsWith(".lockfile")
                || name.equals("libs.versions.toml")
                || name.equals("gradle.properties")
                || name.equals("gradle-wrapper.properties")
                || relative.contains("/src/main/resources/")) files.put(relative, path);
            return FileVisitResult.CONTINUE;
          }
        });
    try (var zip = new ZipOutputStream(Files.newOutputStream(destination))) {
      for (var file : files.entrySet()) {
        byte[] bytes = Files.readAllBytes(file.getValue());
        hashes.put(
            file.getKey(),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var entry = new ZipEntry(file.getKey());
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
      }
    }
    if (hashes.isEmpty()) throw new IOException("No measurement sources found in " + root);
    return hashes;
  }
}
