"""Audit cached CET-6 PDFs for personal study; automated network retrieval disabled.

Saves files to raw-papers/ in the repository root. Does not modify any project
source or commit anything. Files are copyrighted exam materials and should NOT
be committed to version control.

Usage:
    python tools/download_wehuster_papers.py [--dry-run]
"""
import argparse
import hashlib
import json
import time
from urllib.robotparser import RobotFileParser
from pathlib import Path

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


def download_file(url, dest, dry_run=False):
    """Use local cache; refuse automated retrieval under the reviewed site policy."""
    if dest.exists():
        print(f"  SKIP (exists): {dest.name}")
        return "skip"
    if dry_run:
        print(f"  DRY-RUN: {url} -> {dest.name}")
        return "dry"

    # Cached policy from the reviewed source; do not automate disallowed /static paths.
    policy_path = Path(__file__).resolve().parent.parent / ".tools/private-exams/source-pages/wehuster-robots.html"
    if not policy_path.exists():
        raise RuntimeError("No reviewed site policy cache; network retrieval disabled")
    policy = RobotFileParser()
    policy.parse(policy_path.read_text(encoding="utf-8").splitlines())
    if not policy.can_fetch("CET6Study-personal-study", url):
        raise RuntimeError("Source robots policy disallows this path; use local files or permitted manual download")
    raise RuntimeError("Automatic network retrieval disabled for this source; existing files can be audited locally")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true",
                        help="List files without downloading")
    args = parser.parse_args()

    # Determine output directory
    script_dir = Path(__file__).resolve().parent.parent  # CET6Study/
    out_dir = script_dir / "raw-papers"
    out_dir.mkdir(exist_ok=True)


    urls = build_urls()
    print(f"\nTotal files to download: {len(urls)}")
    print(f"Output directory: {out_dir}\n")

    stats = {"ok": 0, "skip": 0, "404": 0, "fail": 0, "dry": 0}
    for i, (url, filename) in enumerate(urls, 1):
        print(f"[{i}/{len(urls)}] {filename}")
        try:
            result = download_file(url, out_dir / filename, args.dry_run)
        except RuntimeError as e:
            print(f"PAUSED: {e}")
            return
        stats[result] += 1
        # Be polite: brief pause between requests
        if result == "ok":
            time.sleep(3)

    print(f"\n--- Summary ---")
    print(f"Downloaded: {stats['ok']}")
    print(f"Skipped (existing): {stats['skip']}")
    print(f"Not found (404): {stats['404']}")
    print(f"Failed: {stats['fail']}")
    if args.dry_run:
        print(f"Dry-run: {stats['dry']}")

    # Write manifest
    manifest = script_dir / ".tools" / "private-exams" / "agent-download-audit.json"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest_data = {
        "source": "https://www.wehuster.com/cet6",
        "downloadedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "licenseStatus": "personal-use",
        "note": "Copyrighted exam materials for personal study only. Do not redistribute.",
        "files": [{"file": f, "url": u, "exists": (out_dir/f).exists(), "sha256": hashlib.sha256((out_dir/f).read_bytes()).hexdigest() if (out_dir/f).exists() else None} for u, f in urls],
        "stats": stats,
    }
    manifest.write_text(json.dumps(manifest_data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nManifest written to {manifest}")


if __name__ == "__main__":
    main()
