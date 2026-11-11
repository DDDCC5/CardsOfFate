# Third-party assets and acknowledgements

## Rider–Waite–Smith card artwork

Original illustrations by Pamela Colman Smith, Rider–Waite–Smith / Pam-A deck. Source scans were obtained from Wikimedia Commons. Commons marks the referenced files as public domain. The exact78 file pages, names and source digests are recorded in [docs/card-art-provenance.json](docs/card-art-provenance.json).

Example primary source: https://commons.wikimedia.org/wiki/File:RWS_Tarot_00_Fool.jpg

Chinese/English title framing, card backs, scene models and interface composition were prepared for this project; attribution to the original illustrator is retained here.

## Typeface

Fate Serif is the project's renamed derivative/subset of Noto Serif SC. Original font licensing and copyright notice are bundled in `app/src/main/assets/licenses/NotoSerifSC-OFL.txt` (SIL Open Font License1.1). The font is embedded in runtime resources and card textures.

## Offline narration production

Narration was synthesized offline using Kokoro and Kokoro ONNX, followed by reverb processing. Model weights and synthesis tooling are not required or bundled in this repository. Upstream model card: https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh (Apache2.0). Corresponding upstream notices are retained in `app/src/main/assets/licenses/`.

## PICO and Android

The app imports official PICO Spatial SDK and Android/AndroidX APIs through Gradle dependencies. Their SDK binaries, source jars, local agent plugins and proprietary documentation are not included. Those dependencies remain under their respective upstream terms.

No blanket project-wide license is assigned by this publication; the notices above do not relicense third-party components.
