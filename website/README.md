# Catnap documentation website

A self-contained static documentation site. Its Markdown pages, logo, displayed version, styles, and scripts all live under `website/`. Node renders every page ahead of time; GitHub Pages serves ordinary HTML, CSS, JavaScript, and images. No server, database, external search service, or JVM build is required.

Features include local full-text search with keyboard navigation, syntax highlighting, copyable code, light/dark themes, mobile navigation, linked section contents, and predefined query examples with Catnap selected by default and Google/LinkedIn options. Core content and navigation work without JavaScript.

## Develop locally

Use Node 22 or newer (Actions uses Node 24):

```bash
cd website
npm ci
npm run dev
```

Open `http://127.0.0.1:4173`. The development server rebuilds and reloads when documentation, visual assets, or client code change. Restart it after changing `src/config.mjs`, the build script, or environment. Set `PORT` to use another port.

For a production build and preview:

```bash
npm run build
npm run check
npm run preview
```

To exercise a GitHub Pages project URL locally:

```bash
BASE_PATH=/catnap/ SITE_URL=https://gregwhitaker.github.io npm run build
npm run check
npm run preview
```

Open `http://127.0.0.1:4173/catnap/`. Build again without those variables to return to root hosting. `SITE_URL` is an origin, with no path; `BASE_PATH` holds the path. The preview server is a local utility, not part of the deployment.

## Deploy with GitHub Actions

1. Commit and push `website/` and the [Documentation workflow](https://github.com/gregwhitaker/catnap-redux/blob/main/.github/workflows/docs.yml) to the repository's default branch. The workflow checks out only the website directory and runs when website files or the workflow change.
2. In the GitHub repository, open **Settings → Pages → Build and deployment**, and set **Source** to **GitHub Actions**. If the repository is private, its plan must support Pages. See [GitHub's publishing source instructions](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site).
3. Open **Actions → Documentation → Run workflow** on the default branch for the first deployment, or push a documentation change. The deployment's environment link is the live site URL.

Subsequent matching pushes to the default branch deploy automatically. Pull requests and other branches run the build and checks without publishing. The default branch is read from the event, so either `main`, `master`, or another default works. A manual run on a non-default branch validates without deploying.

`actions/configure-pages` supplies the correct origin and base path for both project sites (`/catnap/`) and root/custom-domain sites (`/`). The workflow uses GitHub's Pages artifact and deployment actions with the automatic token; no personal access token or deployment secret is needed. Only the deployment job receives `pages: write` and `id-token: write`. Pages must already be enabled through repository settings; the workflow does not change repository visibility or enable it automatically.

For details on the deployment contract, see [GitHub's custom workflow guide](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages). Actions are pinned to verified commit SHAs. Update pins alongside their version comments.

## Maintain content

- Edit Markdown pages in `content/`. The catalog walkthroughs live in `content/examples/`. Repository READMEs and archived evidence are not build inputs.
- Add or reorder pages in `src/config.mjs`; each page has a stable URL, title, summary, and source file.
- Keep application setup in Installation and the framework guides. Put publishing, release checks, and historical audits in the Maintainers navigation group. Preserve existing page URLs and section anchors when reorganizing content.
- Keep links between site pages relative; the builder converts them into site URLs and preserves anchors. Use explicit GitHub URLs for repository source and archived evidence. Relative links that leave `website/` are rejected.
- Change layout in `src/styles.css` and site assets in `assets/`. The logo is `assets/logo.png`; its provenance is recorded in [assets/README.md](assets/README.md). No assets are copied from the parent repository during a build.
- The version label comes from the `version` export in `src/config.mjs`. Update it and the release availability statements when a release actually ships; the website does not read `gradle.properties`.
- `GITHUB_REPOSITORY` sets the GitHub source repository (default `gregwhitaker/catnap-redux`). `DOCS_REF` sets the source branch (default `main`); Actions supplies the repository's default branch.

Search data, table-of-contents anchors, previous/next navigation, a 404 page, and `.nojekyll` are generated during the build. `SITE_URL` additionally enables canonical URLs, Open Graph URLs, a sitemap, and `robots.txt`. Generated output stays in ignored `dist/`; do not commit it. Relative documentation images deliberately require an explicit copy rule in the builder, so unhandled assets cannot silently ship as broken links.

## Verification

```bash
npm test
npm run build
npm run check
```

Tests also build and check an isolated copy of `website/` without the parent repository. They build under root, project, and nested prefixes, check generated pages, all internal links and anchors, search destinations, and canonical/sitemap URLs. They also verify that broken anchors fail checks and that search ranks titles and sections. The check script validates basic document structure, duplicate IDs, accessible button names, and image descriptions. External URLs are not network-checked. Browser checks should cover search, code copying, themes, mobile navigation, and the query examples after client-side changes.
