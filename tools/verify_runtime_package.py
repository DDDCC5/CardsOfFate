"""Validate the built APK's locale/content/model contract without launching a debug shortcut."""
from pathlib import Path
import json, struct, sys, zipfile
apk = Path(sys.argv[1] if len(sys.argv)>1 else 'app/build/outputs/apk/debug/app-debug.apk')
with zipfile.ZipFile(apk) as z:
    entries=set(z.namelist())
    cards=json.loads(z.read('assets/data/card-catalog.json'))
    units=json.loads(z.read('assets/data/narration.json'))['units']
    assert len(cards)==78 and len({c['id'] for c in cards})==78
    assert len(units)==1262 and len({u['id'] for u in units})==1262
    for u in units:
        assert 'assets/audio/narration/'+u['id']+'.ogg' in entries, u['id']
        assert u['text'].strip(), u['id']
    for lang in ('zh','en'):
        data=z.read(f'assets/models/deck_{lang}.glb')
        assert data[:4]==b'glTF'
        size,kind=struct.unpack_from('<II',data,12)
        gltf=json.loads(data[20:20+size]); names={n.get('name') for n in gltf['nodes']}
        for card in cards:
            suffix=lang+'_'+card['id'].replace('-','_')
            for prefix in ('Card_', 'CardFace_', 'CardBack_', 'CardEdge_'):
                assert prefix+suffix in names, prefix+suffix
            for orientation in ('upright','reversed'):
                for position in ('past','present','future'):
                    assert any(u['id']==f'reading/{lang}/{card["id"]}/{orientation}/{position}' for u in units)
    for model in ('candle','crystal','astrolabe','rune_0','rune_1','rune_2','rune_3'):
        assert f'assets/models/{model}.glb' in entries
    assert not any('/.venv/' in n or n.endswith('.blend') for n in entries)
    print(json.dumps({'apk':str(apk.resolve()),'cards':78,'localizedDecks':2,'narrationClips':len(units),'modelAssets':9,'passed':True},ensure_ascii=False,indent=2))
