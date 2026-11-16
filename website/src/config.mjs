export const groups = [
  {
    title: 'Get started',
    pages: [
      { slug: '', title: 'Introduction', icon: 'book', description: 'Select just the JSON fields you need, with your existing JVM stack.' },
      { slug: 'quickstart', title: 'Quickstart', source: 'docs/GETTING_STARTED.md', icon: 'terminal', description: 'Run your first JSON projection with Catnap.' },
      { slug: 'examples', title: 'Runnable examples', source: 'examples/README.md', description: 'Try standalone projection or run any of the five framework applications.' },
    ],
  },
  {
    title: 'Core concepts',
    pages: [
      { slug: 'query-syntax', title: 'Query syntax', source: 'docs/QUERY_SPECIFICATION.md', description: 'Google-style and LinkedIn-style queries, paths, arrays, and wildcards.' },
      { slug: 'core-api', title: 'Core API', source: 'docs/CORE_API.md', description: 'Compile immutable queries and evaluate native JSON trees.' },
      { slug: 'json-bindings', title: 'JSON bindings', source: 'docs/JSON_INTEGRATIONS.md', description: 'Use Jackson 2, Jackson 3, or Kotlin serialization with your existing configuration.' },
      { slug: 'streaming', title: 'Streaming', source: 'docs/STREAMING.md', description: 'Project NDJSON records and JSON-bearing server-sent events.' },
    ],
  },
  {
    title: 'Integrations',
    pages: [
      { slug: 'spring', title: 'Spring MVC & WebFlux', source: 'docs/SPRING_INTEGRATION.md', description: 'Separate Spring Boot starters for servlet and reactive applications.' },
      { slug: 'micronaut', title: 'Micronaut', source: 'docs/MICRONAUT_INTEGRATION.md', description: 'Native JSON serialization, finite responses, and bounded streaming.' },
      { slug: 'quarkus', title: 'Quarkus', source: 'docs/QUARKUS_INTEGRATION.md', description: 'REST responses, explicit streams, and bounded JSON-array publishers.' },
      { slug: 'ktor', title: 'Ktor', source: 'docs/KTOR_INTEGRATION.md', description: 'Kotlin serialization, coroutines, Flow, and imperative SSE sends.' },
    ],
  },
  {
    title: 'Reference',
    pages: [
      { slug: 'compatibility', title: 'Compatibility', source: 'docs/COMPATIBILITY.md', description: 'Tested Java runtimes, frameworks, JSON libraries, and support boundaries.' },
      { slug: 'migration', title: 'Migrating from v2', source: 'docs/MIGRATION.md', description: 'Move from Catnap 2 to the new JVM API and query contract.' },
      { slug: 'performance', title: 'Performance', source: 'docs/PERFORMANCE.md', description: 'Operating limits, JMH baselines, and framework streaming measurements.' },
      { slug: 'native-images', title: 'Native images', source: 'docs/NATIVE_IMAGES.md', description: 'GraalVM verification for the Quarkus and Micronaut examples.' },
      { slug: 'publication', title: 'Artifacts & publishing', source: 'docs/PUBLICATION.md', description: 'The Catnap BOM, local verification, and GitHub Packages publication.' },
      { slug: 'extending', title: 'Extending Catnap', source: 'docs/EXTENDING.md', description: 'Implement and verify your own JSON binding or framework adapter.' },
      { slug: 'release-audit', title: 'Release verification', source: 'docs/RELEASE_AUDIT.md', description: 'Requirement coverage, local evidence, and known verification limits.' },
    ],
  },
];

export const pages = groups.flatMap(group => group.pages.map(page => ({ ...page, group: group.title })));

// These are illustrative, predefined examples of the documented query contract.
// They deliberately do not implement a second query engine in the browser.
export const examples = [
  {
    id: 'nested', title: 'Nested fields',
    google: 'name,price(amount)', linkedin: 'name,price:(amount)',
    input: { id: 'p_042', name: 'Everyday headphones', price: { amount: 79, currency: 'USD' }, inStock: true },
    output: { name: 'Everyday headphones', price: { amount: 79 } },
    note: 'Keep the fields you ask for. Preserve the shape you expect.',
  },
  {
    id: 'arrays', title: 'Arrays',
    google: 'items(id,name)', linkedin: 'items:(id,name)',
    input: { items: [{ id: 1, name: 'Headphones', stock: 24 }, { id: 2, name: 'Speaker', stock: 12 }], total: 2 },
    output: { items: [{ id: 1, name: 'Headphones' }, { id: 2, name: 'Speaker' }] },
    note: 'Select fields in every array item. Keep item order and count.',
  },
  {
    id: 'wildcards', title: 'Wildcards',
    google: 'byId/*/name', linkedin: 'byId:($*:(name))',
    input: { byId: { p_042: { name: 'Headphones', stock: 24 }, p_043: { name: 'Speaker', stock: 12 } } },
    output: { byId: { p_042: { name: 'Headphones' }, p_043: { name: 'Speaker' } } },
    note: 'Project the same fields across every value in a map.',
  },
];
