"""
Minimal yt-dlp resolver for VideoDL.

    pip install -r requirements.txt
    uvicorn resolver:app --host 0.0.0.0 --port 8000

Then set in app/build.gradle.kts:
    buildConfigField("String", "YTDLP_BASE_URL", "\"http://10.0.2.2:8000\"")

Security: do not expose this publicly without authentication and rate
limiting. It fetches arbitrary URLs on your behalf (SSRF surface).
"""

from __future__ import annotations

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import yt_dlp

app = FastAPI(title="VideoDL yt-dlp resolver")


class ResolveRequest(BaseModel):
    url: str


def needs_muxing(fmt: dict) -> bool:
    """DASH video-only or audio-only rendition -> not a playable file alone."""
    return fmt.get("vcodec") == "none" or fmt.get("acodec") == "none"


@app.post("/resolve")
def resolve(request: ResolveRequest) -> dict:
    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        # Prefer formats that already contain audio+video so the app does not
        # have to mux. Remove this line if you want every rendition listed.
        "format": "best*[acodec!=none][vcodec!=none]/best*",
    }

    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(request.url, download=False)
    except Exception as exc:  # yt-dlp raises a wide range of errors
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    if info.get("_type") == "playlist":
        entries = info.get("entries") or []
        if not entries:
            raise HTTPException(status_code=422, detail="Empty playlist")
        info = entries[0]

    formats = []
    for fmt in info.get("formats") or []:
        url = fmt.get("url")
        if not url:
            continue
        if fmt.get("protocol") in {"m3u8", "m3u8_native", "http_dash_segments", "ism"}:
            # HLS is fine (the app can fetch segments); DASH segment lists are not.
            if "dash_segments" in (fmt.get("protocol") or ""):
                continue
        formats.append(
            {
                "format_id": fmt.get("format_id"),
                "url": url,
                "ext": fmt.get("ext"),
                "height": fmt.get("height"),
                "vcodec": fmt.get("vcodec"),
                "acodec": fmt.get("acodec"),
                "filesize": fmt.get("filesize"),
                "filesize_approx": fmt.get("filesize_approx"),
                "tbr": fmt.get("tbr"),
                "format_note": fmt.get("format_note"),
                "protocol": fmt.get("protocol"),
                "http_headers": fmt.get("http_headers") or {},
            }
        )

    if not formats:
        raise HTTPException(status_code=422, detail="No downloadable formats")

    return {
        "title": info.get("title"),
        "thumbnail": info.get("thumbnail"),
        "duration": info.get("duration"),
        "formats": formats,
    }


@app.get("/healthz")
def healthz() -> dict:
    return {"ok": True}
