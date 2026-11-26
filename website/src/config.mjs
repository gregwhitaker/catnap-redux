export const version = '3.0.0';

export const groups = [
  {
    title: 'Get started',
    pages: [
      { slug: '', title: 'Introduction', icon: 'book', description: 'Select just the JSON fields you need, with your existing JVM stack.' },
      { slug: 'quickstart', title: 'Quickstart', source: 'content/GETTING_STARTED.md', icon: 'terminal', description: 'Run your first JSON projection with Catnap.' },
      { slug: 'installation', title: 'Installation', source: 'content/INSTALLATION.md', description: 'Configure Gradle or Maven and choose the Catnap module for your application.' },
      { slug: 'examples', title: 'Runnable examples', source: 'content/examples/README.md', description: 'Try standalone projection or run any of the five framework applications.' },
    ],
  },
  {
    title: 'Core concepts',
    pages: [
      { slug: 'query-syntax', title: 'Query syntax', source: 'content/QUERY_SPECIFICATION.md', description: 'Catnap RSQL predicates, Google/LinkedIn queries, paths, arrays, and wildcards.' },
      { slug: 'core-api', title: 'Core API', source: 'content/CORE_API.md', description: 'Compile immutable queries and evaluate native JSON trees.' },
      { slug: 'json-bindings', title: 'JSON bindings', source: 'content/JSON_INTEGRATIONS.md', description: 'Use Jackson 2, Jackson 3, or Kotlin serialization with your existing configuration.' },
      { slug: 'streaming', title: 'Streaming', source: 'content/STREAMING.md', description: 'Project NDJSON records and JSON-bearing server-sent events.' },
    ],
  },
  {
    title: 'Integrations',
    pages: [
      { slug: 'spring', title: 'Spring MVC & WebFlux', source: 'content/SPRING_INTEGRATION.md', description: 'Separate Spring Boot starters for servlet and reactive applications.' },
      { slug: 'micronaut', title: 'Micronaut', source: 'content/MICRONAUT_INTEGRATION.md', description: 'Native JSON serialization, finite responses, and bounded streaming.' },
      { slug: 'quarkus', title: 'Quarkus', source: 'content/QUARKUS_INTEGRATION.md', description: 'REST responses, explicit streams, and bounded JSON-array publishers.' },
      { slug: 'ktor', title: 'Ktor', source: 'content/KTOR_INTEGRATION.md', description: 'Kotlin serialization, coroutines, Flow, and imperative SSE sends.' },
    ],
  },
  {
    title: 'Catalog examples',
    pages: [
      { slug: 'examples/spring-mvc', title: 'Spring Boot MVC', source: 'content/examples/spring-boot-mvc/README.md', description: 'Product detail, paginated search, and image/variant filters with MVC and Jackson 3.' },
      { slug: 'examples/spring-webflux', title: 'Spring Boot WebFlux', source: 'content/examples/spring-boot-webflux/README.md', description: 'A reactive catalog API with Mono responses and Jackson 3.' },
      { slug: 'examples/micronaut', title: 'Micronaut', source: 'content/examples/micronaut/README.md', description: 'A seeded catalog with generated Serde models and native HTTP projection.' },
      { slug: 'examples/quarkus', title: 'Quarkus', source: 'content/examples/quarkus/README.md', description: 'A seeded catalog with CDI, Mutiny, and Jackson 2.' },
      { slug: 'examples/ktor', title: 'Ktor', source: 'content/examples/ktor/README.md', description: 'A Kotlin catalog with suspending routes, typed responses, and exact decimal prices.' },
    ],
  },
  {
    title: 'Reference',
    pages: [
      { slug: 'compatibility', title: 'Compatibility', source: 'content/COMPATIBILITY.md', description: 'Tested Java runtimes, frameworks, JSON libraries, and support boundaries.' },
      { slug: 'migration', title: 'Migrating from v2', source: 'content/MIGRATION.md', description: 'Move from Catnap 2 to the new JVM API and query contract.' },
      { slug: 'performance', title: 'Performance', source: 'content/PERFORMANCE.md', description: 'Operating limits and how to measure projection and streaming workloads.' },
      { slug: 'native-images', title: 'Native images', source: 'content/NATIVE_IMAGES.md', description: 'GraalVM verification for the Quarkus and Micronaut examples.' },
    ],
  },
  {
    title: 'Maintainers',
    pages: [
      { slug: 'extending', title: 'Extending Catnap', source: 'content/EXTENDING.md', description: 'Implement and verify your own JSON binding or framework adapter.' },
      { slug: 'publication', title: 'Publishing artifacts', source: 'content/PUBLICATION.md', description: 'Verify and publish libraries, the BOM, and API documentation.' },
      { slug: 'releasing', title: 'Release checklist', source: 'content/RELEASING.md', description: 'Prepare a candidate, run verification, and publish a release.' },
    ],
  },
];

export const pages = groups.flatMap(group => group.pages.map(page => ({ ...page, group: group.title })));

// These are illustrative, predefined examples of the documented query contract.
// They deliberately do not implement a second query engine in the browser.
export const examples = [
  {
    id: 'nested', title: 'Nested fields',
    catnap: 'name,price(amount)', google: 'name,price(amount)', linkedin: 'name,price:(amount)',
    input: { id: 'p_042', name: 'Everyday headphones', price: { amount: 79, currency: 'USD' }, inStock: true },
    output: { name: 'Everyday headphones', price: { amount: 79 } },
    note: 'Keep the fields you ask for. Preserve the shape you expect.',
  },
  {
    id: 'arrays', title: 'Arrays',
    catnap: 'items(id,name)', google: 'items(id,name)', linkedin: 'items:(id,name)',
    input: { items: [{ id: 1, name: 'Headphones', stock: 24 }, { id: 2, name: 'Speaker', stock: 12 }], total: 2 },
    output: { items: [{ id: 1, name: 'Headphones' }, { id: 2, name: 'Speaker' }] },
    note: 'Select fields in every array item. Keep item order and count.',
  },
  {
    id: 'wildcards', title: 'Wildcards',
    catnap: 'byId/*/name', google: 'byId/*/name', linkedin: 'byId:($*:(name))',
    input: { byId: { p_042: { name: 'Headphones', stock: 24 }, p_043: { name: 'Speaker', stock: 12 } } },
    output: { byId: { p_042: { name: 'Headphones' }, p_043: { name: 'Speaker' } } },
    note: 'Project the same fields across every value in a map.',
  },
];
