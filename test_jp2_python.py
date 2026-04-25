from pathlib import Path
from PIL import Image
path = Path('face.jp2')
if not path.exists():
    print('MISSING')
else:
    try:
        im = Image.open(path)
        im.load()
        print('OK', im.size, im.format)
    except Exception as e:
        print('ERR', e)
