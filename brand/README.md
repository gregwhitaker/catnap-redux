# Catnap brand assets

Start with the [brand guide](guides/Catnap-Brand-Guide.pdf) or the one-page [quick reference](guides/Catnap-Quick-Reference.pdf). The [preview](graphics/catnap-brand-preview.png) shows the original identity system.

| Folder | Contents |
| --- | --- |
| [logos/](logos/) | Original transparent logo and [color variants](logos/colorways/README.txt), with usage notes and generation prompts. |
| [guides/](guides/) | Full brand guide and quick reference PDFs. |
| [graphics/](graphics/) | Social preview (1200 × 630), repository banner (1600 × 400), announcement (1080 × 1080), and brand overview. |
| [fonts/](fonts/) | Manrope and IBM Plex Mono font files with their SIL Open Font License notices. |
| [tokens/](tokens/) | CSS variables and local font declarations, JSON design tokens, and a GIMP palette. |
| [copy/](copy/) | Descriptions, tagline, voice guidance, and reusable messaging. |
| [source/](source/) | Python builder, dependencies, references, original logo prompt, and historical delivery validation. |
| [archives/](archives/) | Original brand packet and colorway ZIP deliveries. |

## Choose a logo

- [Original](logos/catnap-logo.png): transparent logo used by the repository README in light mode.
- [Slate blue and graphite](logos/colorways/catnap-slate-graphite.png): transparent variant used by the documentation website.
- [Navy and steel gray](logos/colorways/catnap-navy-steel.png): alternate transparent variant for light backgrounds.
- [Ice blue and white](logos/colorways/catnap-ice-white-on-navy.png): variant on an opaque navy background.
- [White and blue gray](logos/colorways/catnap-white-slate-on-navy.png): variant on an opaque navy background, used by the repository README in dark mode.

The guides, graphics, and tokens describe the original green palette. The blue, gray, and white logo variants are separate color explorations; their [manifest](logos/colorways/colorways-and-prompts.json) records colors and transparency. Keep the font license notices with redistributed fonts and preserve the `tokens/` and `fonts/` relationship when using the CSS.

## Rebuild the guides and graphics

With Python 3 and Poppler installed, run from the repository root:

```bash
python3 -m venv brand/.venv
brand/.venv/bin/python -m pip install -r brand/source/requirements.txt
brand/.venv/bin/python brand/source/build_brand_packet.py
```

The builder defaults to this `brand/` directory regardless of the working directory. It overwrites the PDFs in `guides/`, the design tokens in `tokens/`, and the three application graphics in `graphics/`. Use `--packet /path/to/asset-copy` to rebuild a copy containing the same logos and fonts. If needed, pass `--pdftoppm /absolute/path/to/pdftoppm`; without that renderer, application graphics are emitted as PDFs.

The builder does not regenerate logos, the overview preview, ZIP archives, or historical validation. See [START-HERE.txt](START-HERE.txt) for additional usage guidance.

## Original deliveries

The ZIPs in `archives/` retain their original contents and folder layouts. The [original delivery validation](source/original-delivery-validation.json) records checks and file hashes for the contents of `catnap-brand-packet.zip`; its paths and visual review apply to that delivery. It is preserved as provenance and is not a verification manifest for subsequent edits to this folder.
