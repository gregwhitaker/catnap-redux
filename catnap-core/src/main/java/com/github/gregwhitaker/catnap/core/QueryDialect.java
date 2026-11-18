package com.github.gregwhitaker.catnap.core;

/** The explicitly selected query syntax. */
public enum QueryDialect {
  /** Google-style fields, including parenthesized children and slash paths. */
  GOOGLE,
  /** LinkedIn-style fields with colon-prefixed child selections. */
  LINKEDIN,
  /** Google-style field selection with bounded RSQL predicates on arrays. */
  CATNAP
}
