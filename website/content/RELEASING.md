# Release checklist

This is the maintainer checklist for shipping Catnap 3. For application setup, use [installation](INSTALLATION.md). For upload commands and registry configuration, use [publication](PUBLICATION.md).

## Prepare the candidate

- [ ] Choose a version. Publish all 16 libraries and the BOM to `https://maven.pkg.github.com/gregwhitaker/catnap-redux` for snapshots, release candidates, and stable releases.
- [ ] Include the intended code, tests, and documentation in one candidate commit. Record its full SHA.
- [ ] Review the [changelog](https://github.com/gregwhitaker/catnap-redux/blob/main/CHANGELOG.md) and [migration guide](MIGRATION.md), including the CATNAP default, predicate syntax, Java baselines, and framework requirements.
- [ ] Set the checkout version in `gradle.properties` and the website version in `website/src/config.mjs`. Update the README and guide examples to match. Both Spring starters stamp their bundled install examples with the actual publication version, including a workflow override.
- [ ] Scope native and performance claims to the candidate's verified platforms and workloads. Run fresh checks when source, dependencies, or the environment change.

## Verify the candidate

From the repository root, substituting the chosen version:

```bash
./gradlew build compatibilityTest publicationSmokeTest \
  :benchmarks:streaming-runner:smoke -Pversion=3.0.0-rc.1
```

Run the documentation checks from `website/`:

```bash
npm ci
npm test
npm run build
npm run check
```

- [ ] Require successful JVM checks and both independent local consumer modes for the candidate version.
- [ ] Run the [native suites](NATIVE_IMAGES.md) for the source and platforms claimed by the release. The Build workflow's `native_images` input enables the hosted Linux jobs; an ordinary push skips them.
- [ ] Follow the [benchmark instructions](https://github.com/gregwhitaker/catnap-redux/blob/main/benchmarks/README.md) for current workload measurements. Native quick-build tests and HTTP smoke timings do not establish production performance.
- [ ] Inspect installation, framework navigation, search, and examples on the built documentation site. The site tests cover root and project hosting paths.
- [ ] If signing is part of the chosen release policy, verify it with real signing credentials using the [signing instructions](PUBLICATION.md#signing-and-release-publication). The current GitHub Packages workflow publishes unsigned artifacts by default.

## Publish and verify consumption

- [ ] Publish the candidate once, using a unique non-SNAPSHOT version such as `3.0.0-rc.1`. Use either a manual run of [Publish GitHub Packages](PUBLICATION.md#publish-to-github-packages) or a published GitHub pre-release tagged `v3.0.0-rc.1`.
- [ ] Require both fresh remote-consumer jobs to pass: Gradle module metadata and Maven POM only. Confirm the run's source SHA, version, and all expected library/BOM artifacts.
- [ ] For the final release, use a new version and matching tag, update the changelog and installation availability, and publish through the same verified workflow. Publishing a GitHub Release triggers package upload; creating only a tag does not.
- [ ] Check the documentation deployment and include links to the release, publication run, and relevant CI artifacts in the release notes.

## Verification artifacts

The [Build workflow](https://github.com/gregwhitaker/catnap-redux/blob/main/.github/workflows/ci.yml) uploads test reports, benchmark results, and native test reports as artifacts. The manual `benchmarks`, `streaming_benchmarks`, and `native_images` inputs enable the corresponding jobs. Publication runs retain their independent consumer results separately.

Keep local reports, logs, measurements, and generated source snapshots under ignored `build/` directories. Use CI run links and artifacts when sharing results; do not commit copies into the repository. Each result applies to its recorded commit, toolchain, platform, and workload.
