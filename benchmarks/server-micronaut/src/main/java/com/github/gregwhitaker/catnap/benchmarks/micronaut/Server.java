package com.github.gregwhitaker.catnap.benchmarks.micronaut;

import io.micronaut.runtime.Micronaut;

public final class Server {
  private Server() {}

  public static void main(String[] args) {
    Micronaut.run(Server.class, args);
  }
}
