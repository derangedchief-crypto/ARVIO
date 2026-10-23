#!/usr/bin/env python3
"""Prepare immutable, local-only Android brand resources from an owner CLI export.
No network, panel access, signing keys, APK publication or arbitrary paths from uploads.
"""
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
ROLES = ('loading_asset', 'logo_asset', 'icon_asset')
Image.MAX_IMAGE_PIXELS = 8_000_000


def prepare(bundle, output, resources=None):
    resources = resources or ROOT / 'app/src/main/res'
    if set(bundle) != {'manifest', 'images'}:
        raise ValueError('Unexpected build bundle fields')
    manifest = bundle['manifest']
    if set(manifest) != {'schema_version', 'brand_id', 'brand_version', 'app_version', 'app_version_code', 'app_name', 'package_name', 'assets'}:
        raise ValueError('Unexpected manifest fields')
    brand = manifest['brand_id']
    name = manifest['app_name']
    if manifest['schema_version'] != 1 or not re.fullmatch('[a-f0-9]{32}', brand):
        raise ValueError('Invalid brand identity')
    if manifest['package_name'] != 'com.extreme.brand.b' + brand:
        raise ValueError('Package identity does not match brand')
    if type(manifest['brand_version']) is not int or not 1 <= manifest['brand_version'] <= 99999:
        raise ValueError('Invalid brand version')
    if not isinstance(manifest['app_version'],str) or not re.fullmatch(r'\d+\.\d+\.\d+',manifest['app_version']) or type(manifest['app_version_code']) is not int or manifest['app_version_code']<1:
        raise ValueError('Invalid platform version')
    if not isinstance(name, str) or not 1 <= len(name) <= 60 or not name[0].isalnum() or not all(c.isalnum() or c in " .&’'()+,-" for c in name):
        raise ValueError('Invalid app name')
    if set(manifest['assets']) != set(ROLES) or set(bundle['images']) != set(ROLES):
        raise ValueError('Invalid image roles')
    images = {}
    for role in ROLES:
        meta = manifest['assets'][role]
        if set(meta) != {'id', 'sha256'} or not re.fullmatch('[a-f0-9]{32}', meta['id']):
            raise ValueError('Invalid image identity')
        encoded = bundle['images'][role]
        if not isinstance(encoded, str) or len(encoded) > 1400000:
            raise ValueError('Image is too large')
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) > 1024 * 1024 or hashlib.sha256(raw).hexdigest() != meta['sha256']:
            raise ValueError('Image integrity mismatch')
        with Image.open(io.BytesIO(raw)) as image:
            if image.format not in {'PNG', 'JPEG', 'WEBP'} or getattr(image, 'n_frames', 1) != 1:
                raise ValueError('Use static PNG, JPEG or WebP images')
            if max(image.size) > 4096 or image.width * image.height > 8_000_000:
                raise ValueError('Image dimensions exceed limits')
            image.load()
            images[role] = ImageOps.contain(ImageOps.exif_transpose(image).convert('RGBA'),
                (1920, 1920) if role == 'loading_asset' else (1024, 1024), Image.Resampling.LANCZOS)
    # Refuse to overwrite any user's existing output. No recursive deletes.
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    res = output / 'res'
    def save(image, relative, size=None):
        target = res / relative
        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if size:
            fitted = ImageOps.contain(image, size, Image.Resampling.LANCZOS)
            canvas = Image.new('RGBA', size)
            canvas.alpha_composite(fitted, ((size[0]-fitted.width)//2, (size[1]-fitted.height)//2))
            image = canvas
        # Re-encoding removes metadata; file extensions/resource names are owned by us.
        image.save(target, format='WEBP' if target.suffix == '.webp' else 'PNG', lossless=True)
    pattern = re.compile(r'Extreme\s+(?:TV|Hub)', re.IGNORECASE)
    for source in sorted(resources.glob('values*/strings.xml')):
        result = ET.Element('resources')
        for entry in ET.parse(source).getroot():
            xml = ET.tostring(entry, encoding='unicode')
            if entry.attrib.get('name') != 'app_name' and not pattern.search(''.join(entry.itertext())):
                continue
            # Replace TEXT only, never resource names, URLs, field identifiers or formats.
            copied = ET.fromstring(xml)
            for node in copied.iter():
                if node.text: node.text = pattern.sub(lambda _: name, node.text)
                if node.tail: node.tail = pattern.sub(lambda _: name, node.tail)
            if copied.attrib.get('name') == 'app_name': copied.text = name
            # Android string escaping, independent of XML escaping by ElementTree.
            for node in copied.iter():
                if node.text: node.text = re.sub(r"(?<!\\)'", r"\\'", node.text)
                if node.tail: node.tail = re.sub(r"(?<!\\)'", r"\\'", node.tail)
            result.append(copied)
        if len(result):
            target = res / source.parent.name / 'brand_strings.xml'
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            ET.ElementTree(result).write(target, encoding='utf-8', xml_declaration=True)
    save(images['loading_asset'], 'drawable-nodpi/arvio_loading_logo.webp')
    save(images['logo_asset'], 'drawable/ic_logo_text.png')
    # Replace EVERY density variant; otherwise Android may select upstream artwork.
    for source in resources.glob('drawable*/app_banner.webp'):
        save(images['logo_asset'], str(source.relative_to(resources)), (320, 180))
    for qualifier, scale in [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)]:
        for icon in ['ic_launcher', 'ic_launcher_round']:
            save(images['icon_asset'], f'mipmap-{qualifier}/{icon}.png', (int(48*scale), int(48*scale)))
        foreground = Image.new('RGBA', (int(108*scale), int(108*scale)))
        fitted = ImageOps.contain(images['icon_asset'], (int(66*scale), int(66*scale)), Image.Resampling.LANCZOS)
        foreground.alpha_composite(fitted, ((foreground.width-fitted.width)//2, (foreground.height-fitted.height)//2))
        save(foreground, f'mipmap-{qualifier}/ic_launcher_foreground.png')
    save(images['icon_asset'], 'drawable/ic_launcher_monochrome.png', (108, 108))
    # Public build identity only. Never contains updater URLs, credentials or signing input.
    props = {'brand_id': brand, 'brand_name': name, 'package_name': manifest['package_name'], 'brand_version':str(manifest['brand_version']), 'app_version':manifest['app_version'], 'app_version_code':str(manifest['app_version_code'])}
    (output / 'brand.properties').write_text(''.join(k+'='+v.replace('\\', '\\\\')+'\n' for k,v in props.items()), encoding='utf-8')
    (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(json.loads(args.bundle.read_text()), args.output)
    print('Brand resources prepared locally. No APK built, signed or published.')
