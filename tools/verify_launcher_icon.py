"""Inspect the compiled APK, not only the source resource directory.
Usage: python verify_launcher_icon.py APK /path/to/aapt2
Requires Pillow for pixel-exact comparison after Android PNG processing.
"""
from pathlib import Path
import hashlib,io,json,re,subprocess,sys,zipfile
from PIL import Image
root=Path(__file__).resolve().parents[1]
apk=Path(sys.argv[1]);aapt=sys.argv[2]
badging=subprocess.check_output([aapt,'dump','badging',str(apk)],text=True)
resources=subprocess.check_output([aapt,'dump','resources',str(apk)],text=True)
package,code,version=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging).groups()
assert package=="com.example.cardsfate"
icon=re.search(r"application-icon-160:'([^']+)'",badging).group(1)
assert icon.endswith('/ic_spatial_launcher.png')
with zipfile.ZipFile(apk) as z:
 names=z.namelist()
 def compare(suffix,source):
  found=[p for p in names if p.startswith('res/') and p.endswith('/'+suffix)]
  assert len(found)==1,(suffix,found)
  packed=Image.open(io.BytesIO(z.read(found[0]))).convert('RGBA')
  original=Image.open(root/source).convert('RGBA')
  assert packed.size==original.size and packed.tobytes()==original.tobytes(),suffix
  return {'path':found[0],'size':list(packed.size),'pixelSha256':hashlib.sha256(packed.tobytes()).hexdigest()}
 entries=[compare('ic_spatial_launcher.png','app/src/main/res/mipmap-xxxhdpi/ic_spatial_launcher.png'),
          compare('icon_3d_layer_0.png','app/src/main/res/drawable/icon_3d_layer_0.png'),
          compare('fate_launcher_foreground.png','app/src/main/res/drawable/fate_launcher_foreground.png')]
 for obsolete in ('icon_3d_layer_1.png','icon_3d_sdf_1.png','ic_launcher_foreground.png'):
  assert not any(p.endswith('/'+obsolete) for p in names),obsolete
 for resource,expected in [('icon_3d_list','icon_3d_layer_0'),('icon_sdf_list','icon_3d_sdf_0')]:
  section=resources.split('array/'+resource+'\n',1)[1].split('    resource ',1)[0]
  assert '(array) size=1' in section and '@drawable/'+expected in section,section
print(json.dumps({'package':package,'version':version,'versionCode':int(code),
 'launcherIcon':icon,'spatialLayerCount':1,'templateLayersRemoved':True,
 'compiledIconPixelChecks':entries,'passed':True},indent=2))
