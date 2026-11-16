package com.github.gregwhitaker.catnap.quarkus;

import com.github.gregwhitaker.catnap.core.QueryDialect;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Runtime settings for finite and streaming JSON projection. */
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "catnap")
public interface CatnapConfig {
  /** Enable projection and query validation. */
  @WithDefault("true")
  boolean enabled();

  /** The decoded query parameter name. */
  @WithDefault("fields")
  String parameter();

  /** Explicit query dialect; syntax is never auto-detected. */
  @WithDefault("GOOGLE")
  QueryDialect dialect();

  /** Maximum UTF-16 units in a query. */
  @WithDefault("16384")
  int maxQueryLength();

  /** Maximum selector nesting. */
  @WithDefault("64")
  int maxQueryDepth();

  /** Maximum parsed selector segments. */
  @WithDefault("4096")
  int maxSelectors();

  /** Maximum JSON traversal depth, with the root at zero. */
  @WithDefault("128")
  int maxJsonDepth();

  /** Maximum evaluation work, including selector matching. */
  @WithDefault("1000000")
  long maxWork();

  /** Maximum compiled queries cached per application; zero disables caching. */
  @WithDefault("256")
  int cacheCapacity();

  /** Maximum serialized bytes in a finite document or one stream payload. */
  @WithDefault("1048576")
  int maxPayloadBytes();
}
