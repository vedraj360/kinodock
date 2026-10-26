#!/usr/bin/env python3
"""Build a licensed, dark 1080p wallpaper set from Wikimedia Commons' public API."""

import json
import re
import sys
from io import BytesIO
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from PIL import Image, ImageEnhance, ImageOps


OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "../tv-assets/wallpapers-commons")
OUT.mkdir(parents=True, exist_ok=True)
API = "https://commons.wikimedia.org/w/api.php"
HEADERS = {"User-Agent": "KinoDock/1.0 (Wikimedia wallpaper asset fetcher)"}
QUERIES = [
    "filetype:bitmap foggy forest landscape",
    "filetype:bitmap misty forest trees",
    "filetype:bitmap dark mountain fog landscape",
    "filetype:bitmap monochrome forest landscape",
    "filetype:bitmap cloudy mountain landscape",
    "filetype:bitmap night skyline city landscape",
    "filetype:bitmap forest mountain fog panorama",
    "filetype:bitmap atmospheric landscape clouds",
]
ALLOWED_LICENSES = {"Public domain", "CC0", "CC BY 2.0", "CC BY 3.0", "CC BY 4.0",
                    "CC BY-SA 2.0", "CC BY-SA 3.0", "CC BY-SA 4.0"}


def api(params):
    request = Request(API + "?" + urlencode(params), headers=HEADERS)
    with urlopen(request, timeout=20) as response:
        return json.load(response)


def safe_name(title):
    title = re.sub(r"^File:", "", title)
    stem = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    return stem[:64] or "commons-wallpaper"


def process(data):
    image = Image.open(BytesIO(data)).convert("RGB")
    image = ImageOps.fit(image, (1920, 1080), method=Image.Resampling.LANCZOS, centering=(0.5, 0.46))
    image = ImageOps.grayscale(image).convert("RGB")
    image = ImageEnhance.Contrast(image).enhance(1.10)
    image = ImageEnhance.Brightness(image).enhance(0.56)
    black = Image.new("RGB", image.size, (0, 0, 0))
    ramp = []
    for y in range(1080):
        t = max(0.0, min(1.0, (y / 1080 - 0.49) / 0.51))
        ramp.append(round(224 * t * t * (3 - 2 * t)))
    mask = Image.new("L", (1, 1080))
    mask.putdata(ramp)
    image = Image.composite(black, image, mask.resize(image.size))
    return image


records = []
seen = set()
for query in QUERIES:
    params = {
        "action": "query", "format": "json", "generator": "search",
        "gsrsearch": query, "gsrnamespace": 6, "gsrlimit": 20,
        "prop": "imageinfo", "iiprop": "url|size|extmetadata", "iiurlwidth": 2400,
    }
    for page in api(params).get("query", {}).get("pages", {}).values():
        if page["title"] in seen:
            continue
        seen.add(page["title"])
        info = page.get("imageinfo", [{}])[0]
        width, height = int(info.get("width", 0)), int(info.get("height", 0))
        if width < 1920 or height < 1080 or width / max(1, height) < 1.30:
            continue
        meta = info.get("extmetadata", {})
        license_name = re.sub("<[^>]*>", "", meta.get("LicenseShortName", {}).get("value", "")).strip()
        if license_name not in ALLOWED_LICENSES:
            continue
        image_url = info.get("thumburl") or info.get("url")
        if not image_url:
            continue
        try:
            request = Request(image_url, headers=HEADERS)
            with urlopen(request, timeout=30) as response:
                image_data = response.read(25 * 1024 * 1024 + 1)
            if len(image_data) > 25 * 1024 * 1024:
                continue
            output = OUT / (safe_name(page["title"]) + ".png")
            process(image_data).save(output, format="PNG", optimize=True)
        except Exception as exc:
            print("skip", page["title"], exc)
            continue
        artist = re.sub("<[^>]*>", "", meta.get("Artist", {}).get("value", "Unknown")).strip()
        records.append({
            "file": output.name,
            "title": page["title"],
            "artist": artist,
            "license": license_name,
            "license_url": meta.get("LicenseUrl", {}).get("value", ""),
            "source": "https://commons.wikimedia.org/wiki/" + page["title"].replace(" ", "_"),
            "dimensions": [width, height],
            "api_query": query,
        })
        print(output.name, width, height, license_name)
        if len(records) >= 12:
            break
    if len(records) >= 12:
        break

(OUT / "sources.json").write_text(json.dumps(records, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print("saved", len(records), "wallpapers to", OUT)
