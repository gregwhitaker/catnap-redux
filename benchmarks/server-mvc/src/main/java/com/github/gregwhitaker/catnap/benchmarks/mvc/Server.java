package com.github.gregwhitaker.catnap.benchmarks.mvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Server {
  public static void main(String[] args) {
    var app = new SpringApplication(Server.class);
    app.setWebApplicationType(WebApplicationType.SERVLET);
    app.run(args);
  }
}
