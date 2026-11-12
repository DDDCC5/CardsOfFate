"""Export the approved mark for the compatibility, adaptive and PICO identity entries.
Requires resvg-py. This changes icon packaging only; it does not redraw the mark.
"""
from pathlib import Path
import re
import resvg_py
ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'app/src/main/res'
svg=(ROOT/'assets/identity/fate-mark.svg').read_text()
body=re.sub(r'^<svg[^>]*>|</svg>\s*$','',svg.strip())
def render(content,viewbox='0 0 460 460'):
 return resvg_py.svg_to_bytes(svg_string=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{viewbox}">{content}</svg>',width=1024,height=1024)
spatial=render('<circle cx="230" cy="230" r="230" fill="#10101E"/>'+body)
(RES/'drawable/icon_3d_layer_0.png').write_bytes(spatial)
(RES/'mipmap-xxxhdpi/ic_spatial_launcher.png').write_bytes(spatial)
(RES/'drawable/fate_launcher_foreground.png').write_bytes(render(body,'-115 -115 690 690'))
print('Exported approved mark to compatibility, adaptive and one-layer spatial icons')
