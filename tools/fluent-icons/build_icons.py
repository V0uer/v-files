"""Convierte los iconos Fluent (MIT, Microsoft) a recursos de Android.

- Monocromos (*_regular): VectorDrawable blanco, que se tiñe en Compose.
- A color: se dibujan a PNG con resvg (render.js); los que Fluent no trae a color
  (carpeta, zip, música) se arman aquí con la forma Fluent y colores de Windows 11.
"""
import os
import re
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'res')
DRAW = os.path.join(RES, 'drawable')
NODPI = os.path.join(RES, 'drawable-nodpi')
os.makedirs(NODPI, exist_ok=True)

MONO = {
    'fl_menu': 'line_horizontal_3_24_regular',
    'fl_back': 'arrow_left_24_regular',
    'fl_up': 'arrow_up_24_regular',
    'fl_search': 'search_24_regular',
    'fl_close': 'dismiss_24_regular',
    'fl_more': 'more_horizontal_24_regular',
    'fl_split_v': 'split_vertical_24_regular',
    'fl_split_h': 'split_horizontal_24_regular',
    'fl_check': 'checkmark_24_regular',
    'fl_drive': 'hard_drive_24_regular',
    'fl_delete': 'delete_24_regular',
}


def paths(svg):
    return re.findall(r'<path d="([^"]+)"', svg)


for res_name, src in MONO.items():
    svg = open(os.path.join(HERE, src + '.svg'), encoding='utf-8').read()
    body = '\n'.join(f'    <path android:fillColor="#FFFFFFFF" android:pathData="{d}"/>' for d in paths(svg))
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<!-- Fluent UI System Icons (MIT, Microsoft): ' + src + ' -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="24dp" android:height="24dp"\n'
           '    android:viewportWidth="24" android:viewportHeight="24">\n' + body + '\n</vector>\n')
    open(os.path.join(DRAW, res_name + '.xml'), 'w', encoding='utf-8', newline='\n').write(xml)


def grad(gid, a, b, x1=12, y1=3, x2=12, y2=21):
    return (f'<linearGradient id="{gid}" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" gradientUnits="userSpaceOnUse">'
            f'<stop stop-color="{a}"/><stop offset="1" stop-color="{b}"/></linearGradient>')


def svg_doc(defs, body):
    return ('<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">'
            f'<defs>{defs}</defs>{body}</svg>')


# Carpeta estilo Windows 11: pestaña más oscura detrás, frente amarillo con degradado.
folder = paths(open(os.path.join(HERE, 'folder_24_filled.svg'), encoding='utf-8').read())[0]
tab, front = folder.split('ZM2 9.5')
tab += 'Z'
front = 'M2 9.5' + front
custom = {
    'fl_folder': svg_doc(
        grad('t', '#F2B53A', '#E39B1F') + grad('f', '#FFD96B', '#F8BB3A'),
        f'<path d="{tab}" fill="url(#t)"/><path d="{front}" fill="url(#f)"/>'),
    'fl_zip': svg_doc(
        grad('z', '#FFD96B', '#F2A93B'),
        f'<path d="{paths(open(os.path.join(HERE, "folder_zip_24_filled.svg"), encoding="utf-8").read())[0]}" fill="url(#z)"/>'),
    'fl_music': svg_doc(
        grad('m', '#FF8A65', '#E8467C', 4, 2, 20, 22),
        f'<path d="{paths(open(os.path.join(HERE, "music_note_2_24_filled.svg"), encoding="utf-8").read())[0]}" fill="url(#m)"/>'),
}
for name, doc in custom.items():
    open(os.path.join(HERE, name + '.svg'), 'w', encoding='utf-8').write(doc)

COLOR = {
    'fl_folder': 'fl_folder',
    'fl_zip': 'fl_zip',
    'fl_music': 'fl_music',
    'fl_image': 'image_24_color',
    'fl_video': 'video_24_color',
    'fl_document': 'document_24_color',
    'fl_text': 'document_text_24_color',
    'fl_app': 'apps_24_color',
    'fl_star': 'star_24_color',
    'fl_phone': 'phone_24_color',
}
jobs = [f'{os.path.join(HERE, src + ".svg")}={os.path.join(NODPI, name + ".png")}' for name, src in COLOR.items()]
subprocess.run(['node', os.path.join(HERE, 'render.js'), *jobs], check=True, cwd=HERE)
print('ok', len(MONO), 'vectores,', len(COLOR), 'png')
