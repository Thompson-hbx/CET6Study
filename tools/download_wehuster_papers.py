"""Audit cached CET-6 PDFs for personal study; automated network retrieval disabled.

Audits raw-papers/ (or --out-dir) without modifying repository source or making
network requests. Files are copyrighted exam materials and should NOT be
committed to version control.

Usage:
    python tools/download_wehuster_papers.py [--dry-run] [--out-dir DIR] [--manifest FILE] [--robots-file FILE]
"""
import argparse
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import sys
import time
from urllib.robotparser import RobotFileParser

BASE_URL = "https://www.wehuster.com/static/cet6"

# Exam sessions (2022-06 through 2026-06, covering recent 4+ years).
# Format: (wehuster_slug, has_mp3)
EXAM_PAGES = [
    # 2022-06
    ("cet6_2022_06_1", True),
    ("cet6_2022_06_2-3", False),
    # 2022-09
    ("cet6_2022_09_1", True),
    ("cet6_2022_09_2-3", False),
    # 2022-12
    ("cet6_2022_12_1", True),
    ("cet6_2022_12_2", True),
    ("cet6_2022_12_3", False),
    # 2023-03 (加考)
    ("cet6_2023_03_1", True),
    ("cet6_2023_03_2-3", False),   # combined set, no separate MP3
    # 2023-06
    ("cet6_2023_06_1", True),
    ("cet6_2023_06_2", True),
    ("cet6_2023_06_3", False),
    # 2023-12
    ("cet6_2023_12_1", True),
    ("cet6_2023_12_2", True),
    ("cet6_2023_12_3", False),
    # 2024-06
    ("cet6_2024_06_1", True),
    ("cet6_2024_06_2", True),
    ("cet6_2024_06_3", False),
    # 2024-12
    ("cet6_2024_12_1", True),
    ("cet6_2024_12_2", True),
    ("cet6_2024_12_3", False),
    # 2025-06
    ("cet6_2025_06_1", True),
    ("cet6_2025_06_2", True),
    ("cet6_2025_06_3", False),
    # 2025-12
    ("cet6_2025_12_1", True),
    ("cet6_2025_12_2", True),
    ("cet6_2025_12_3", False),
    # 2026-06
    ("cet6_2026_06_1", True),
    ("cet6_2026_06_2", True),
    ("cet6_2026_06_3", False),
]


class _PreTextExtractor(HTMLParser):
    """Extract text inside <pre> if present, otherwise collect all visible text."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self._in_pre = False
        self._pre_chunks = []
        self._all_chunks = []
        self._saw_pre = False

    def handle_starttag(self, tag, attrs):
        if tag.lower() == "pre":
            self._in_pre = True
            self._saw_pre = True

    def handle_endtag(self, tag):
        if tag.lower() == "pre":
            self._in_pre = False

    def handle_data(self, data):
        if self._in_pre:
            self._pre_chunks.append(data)
        self._all_chunks.append(data)

    def extracted_text(self):
        chunks = self._pre_chunks if self._saw_pre else self._all_chunks
        return "".join(chunks)


def _sha256_file(path):
    with open(path, "rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def _unwrap_robots_text(raw_text):
    if "<" in raw_text and ">" in raw_text:
        extractor = _PreTextExtractor()
        extractor.feed(raw_text)
        extractor.close()
        return extractor.extracted_text()
    return raw_text


def parse_robots_policy(policy_path):
    """Parse robots.txt (or HTML-wrapped <pre> robots cache). Returns None on fail-closed."""
    if policy_path is None or not policy_path.is_file():
        return None
    raw_text = policy_path.read_text(encoding="utf-8-sig")
    cleaned = _unwrap_robots_text(raw_text)
    lines = [line.strip() for line in cleaned.splitlines() if line.strip()]
    if not any(re.match(r"(?i)^user-agent\s*:", line) for line in lines):
        return None
    policy = RobotFileParser()
    policy.parse(lines)
    if (
        not policy.entries
        and getattr(policy, "default_entry", None) is None
        and not policy.disallow_all
        and not policy.allow_all
    ):
        return None
    return policy


def check_robots_policy(policy_path, user_agent, url):
    """Fail-closed robots.txt check when a policy file is supplied."""
    policy = parse_robots_policy(policy_path)
    if policy is None:
        return False
    return bool(policy.can_fetch(user_agent, url))


def build_urls():
    """Build the list of (url, local_filename) tuples to download."""
    urls = []
    for slug, has_mp3 in EXAM_PAGES:
        if not slug.startswith(("cet6_2023_", "cet6_2024_", "cet6_2025_", "cet6_2026_")):
            continue
        # Exam PDF
        urls.append((f"{BASE_URL}/{slug}.pdf", f"{slug}.pdf"))
        # Answer PDF
        urls.append((f"{BASE_URL}/{slug}_ans.pdf", f"{slug}_ans.pdf"))
    return urls


def download_file(url, dest, dry_run=False, robots_file=None):
    """Use local cache; refuse automated retrieval under the reviewed site policy."""
    if dest.is_file():
        print(f"  SKIP (exists): {dest.name}")
        return "skip"
    if dest.exists():
        print(f"  FAIL (not a regular file): {dest}")
        return "fail"
    if dry_run:
        print(f"  DRY-RUN: {url} -> {dest.name}")
        return "dry"

    if robots_file is not None and not check_robots_policy(robots_file, "CET6Study-personal-study", url):
        print(f"  FAIL (robots policy disallows or invalid): {dest.name}")
        return "fail"

    print(f"  FAIL (missing local file; network retrieval disabled): {dest.name}")
    return "fail"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true",
                        help="List files without creating directories or writing manifests")
    parser.add_argument("--out-dir", type=Path, default=None,
                        help="Directory containing local raw-papers PDFs (default: repo raw-papers/)")
    parser.add_argument("--manifest", type=Path, default=None,
                        help="Optional path to write audit manifest JSON (ignored in --dry-run)")
    parser.add_argument("--robots-file", type=Path, default=None,
                        help="Optional path to a local robots.txt (or <pre> HTML cache) to check")
    args = parser.parse_args(argv)

    script_dir = Path(__file__).resolve().parent.parent  # CET6Study/
    out_dir = args.out_dir if args.out_dir is not None else script_dir / "raw-papers"

    urls = build_urls()
    print(f"\nTotal files to audit: {len(urls)}")
    print(f"Output directory: {out_dir}\n")

    stats = {"ok": 0, "skip": 0, "404": 0, "fail": 0, "dry": 0}
    for i, (url, filename) in enumerate(urls, 1):
        print(f"[{i}/{len(urls)}] {filename}")
        result = download_file(url, out_dir / filename, dry_run=args.dry_run, robots_file=args.robots_file)
        stats[result] += 1

    print("\n--- Summary ---")
    print(f"Downloaded: {stats['ok']}")
    print(f"Skipped (existing): {stats['skip']}")
    print(f"Not found (404): {stats['404']}")
    print(f"Failed: {stats['fail']}")
    if args.dry_run:
        print(f"Dry-run: {stats['dry']}")

    if not args.dry_run and args.manifest is not None:
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest_data = {
            "source": "https://www.wehuster.com/cet6",
            "downloadedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "licenseStatus": "personal-use",
            "note": "Copyrighted exam materials for personal study only. Do not redistribute.",
            "files": [
                {
                    "file": f,
                    "url": u,
                    "exists": (out_dir / f).is_file(),
                    "sha256": _sha256_file(out_dir / f) if (out_dir / f).is_file() else None,
                }
                for u, f in urls
            ],
            "stats": stats,
        }
        args.manifest.write_text(json.dumps(manifest_data, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nManifest written to {args.manifest}")

    if args.dry_run:
        return 0
    return 0 if stats["fail"] == 0 and stats["404"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
