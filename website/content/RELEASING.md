# Release checklist

This is the maintainer checklist for shipping Catnap 3. For application setup, use [installation](INSTALLATION.md). For upload commands and registry configuration, use [publication](PUBLICATION.md).

## Verification status

Status reviewed November 23, 2026. The checkout is `3.0.0-SNAPSHOT`; no Catnap 3 GitHub Release has been published. The CATNAP dialect and catalog examples are in commit `62eaeb0f7e262e3a4a9f34de55617dae4c80a6eb`. Verification records apply to their stated source snapshots and environments.

| Area | Latest recorded result | Remaining release work |
|---|---|---|
| JVM build, compatibility, and local consumers | [November 19 Build](https://github.com/gregwhitaker/catnap-redux/actions/runs/44620469016) passed on Ubuntu at `62eaeb0`, including both publication metadata modes and streaming smoke checks | Run the gate at the chosen candidate version and commit |
| Documentation | [November 19 deployment](https://github.com/gregwhitaker/catnap-redux/actions/runs/44620469163) passed at `62eaeb0` | Include the documentation cleanup in the candidate and verify the resulting site |
| Quarkus and Micronaut native execution | [November 19 native report](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/native/2026-11-19/README.md): 160 Quarkus and 152 Micronaut tests pass on macOS AArch64, including CATNAP predicates and catalog cases | Include the recorded Quarkus native-test entrypoint; run hosted Linux native jobs before claiming Linux native verification |
| Performance | [November 16 JMH](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/benchmarks/2026-11-16/README.md) and [HTTP measurements](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/benchmarks/2026-11-16/http/README.md) cover the earlier field-selection implementation | Add predicate workloads and record measurements for the candidate before making predicate performance claims |
| Remote artifact consumption | [November 16 snapshot publication](PUBLICATION.md#hosted-snapshot-verification) passed both remote consumer modes at `853ef009e0389585eaa5303ed854094c6b1ca66d` | Publish and consume a candidate containing CATNAP and the catalog examples |

The native report includes a Quarkus test entrypoint added after `62eaeb0`; its source archive identifies that change. The remote snapshot predates CATNAP. A passing older upload, audit, or benchmark is not verification of a later candidate.

## Prepare the candidate

- [ ] Choose the version and destination. The existing workflow publishes to GitHub Packages. A different destination, such as Maven Central, needs its own publishing configuration and verification.
- [ ] Include the intended code, tests, native evidence, and documentation in one candidate commit. Record its full SHA.
- [ ] Review the [changelog](https://github.com/gregwhitaker/catnap-redux/blob/main/CHANGELOG.md) and [migration guide](MIGRATION.md), including the CATNAP default, predicate syntax, Java baselines, and framework requirements.
- [ ] Ensure installation snippets and the website's version label describe the intended version. The website's version is set in `website/src/config.mjs`. The publish workflow's `-Pversion` override does not update that value, `gradle.properties`, or Markdown examples.
- [ ] Confirm the scope of native and performance claims using the evidence above. Preserve earlier reports and unresolved observations when adding new results.

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

- [ ] Publish the candidate through [Publish GitHub Packages](PUBLICATION.md#publish-to-github-packages), using a unique non-SNAPSHOT version such as `3.0.0-rc.1`.
- [ ] Require both fresh remote-consumer jobs to pass: Gradle module metadata and Maven POM only. Confirm the run's source SHA, version, and all expected library/BOM artifacts.
- [ ] For the final release, use a new version and matching tag, update the changelog and installation availability, and publish through the same verified workflow. Publishing a GitHub Release triggers package upload; creating only a tag does not.
- [ ] Check the documentation deployment and record links to the release, publication run, consumer reports, and native/performance evidence. Update this status with the version that actually shipped.

## Historical evidence

These records preserve their original source and workload scope:

| Record | Scope |
|---|---|
| [November 16 requirement audit](RELEASE_AUDIT.md) | Accepted implementation criteria and verification limits before CATNAP predicates |
| [November 16 local verification](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/verification/2026-11-16/release-candidate/README.md) | JVM build, compatibility, local publication consumers, and streaming smoke |
| [Java 17 wrapper launch](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/verification/2026-11-16/java17-launch/README.md) | Provisioning the Java 25 build daemon from a Java 17 launcher |
| [November 16 native baseline](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/native/2026-11-16/README.md) | Earlier Quarkus/Micronaut suites and the Micronaut handoff rerun |
| [WebFlux drain investigation](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/benchmarks/2026-11-16/http/webflux-drain-audit/README.md) | An unexplained historical timeout and passing follow-up workloads |
| [Micronaut streaming investigation](https://github.com/gregwhitaker/catnap-redux/blob/main/docs/benchmarks/2026-11-16/http/micronaut-reentrancy/README.md) | Bounded handoff correction, cleanup regression, and measured cost |
