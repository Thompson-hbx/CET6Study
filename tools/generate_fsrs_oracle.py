"""Run pinned py-fsrs itself; no formula reimplementation or pip install.
PowerShell: python tools/generate_fsrs_oracle.py
Downloads only public sources to ignored .tools; output is committed for offline tests.
"""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import hashlib
import json
import sys
import types
import typing
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PIN = "3abe686e9c058d3f3c00bbeb92e68b71211b2b31"
CACHE = ROOT / ".tools" / "fsrs-oracle"
PACKAGE = CACHE / "fsrs"
PACKAGE.mkdir(parents=True, exist_ok=True)
(PACKAGE / "__init__.py").write_text("", encoding="utf-8")
for name in ("scheduler", "card", "rating", "state", "review_log"):
    raw = urllib.request.urlopen(f"https://raw.githubusercontent.com/open-spaced-repetition/py-fsrs/{PIN}/fsrs/{name}.py").read()
    if name == "scheduler":
        blob = hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest()
        assert blob == "6d42ecb259bbaaa02101f13c5e1b2ec7cdc77eae", blob
    (PACKAGE / f"{name}.py").write_bytes(raw)
# Only a typing alias; official executable source bytes are unmodified.
shim = types.ModuleType("typing_extensions")
shim.Self = typing.Self
sys.modules["typing_extensions"] = shim
sys.path.insert(0, str(CACHE))
from fsrs.scheduler import Scheduler
from fsrs.card import Card
from fsrs.state import State
from fsrs.rating import Rating

template = ROOT / "third_party/bee-fsrs/upstream-reference-cases.json"
fixture = json.loads(template.read_text(encoding="utf-8"))
base = datetime(2026, 1, 1, tzinfo=timezone.utc)
changed = []
for case in fixture["cases"]:
    before = json.dumps(case, sort_keys=True)
    if case["kind"] == "validation":
        continue  # Kotlin-side validation contract, not an oracle claim.
    scheduler = Scheduler(parameters=case["parameters"],
        desired_retention=case.get("desiredRetention", 0.9),
        maximum_interval=case.get("maximumInterval", 36500),
        learning_steps=(), relearning_steps=(), enable_fuzzing=False)
    rating = Rating[case.get("rating", "GOOD").title()]
    if case["kind"] == "initial":
        card, _ = scheduler.review_card(Card(card_id=1, due=base), rating, base)
        case["expectedInitialState"] = dict(stability=card.stability, difficulty=card.difficulty)
    elif case["kind"] == "review":
        previous = case["previousState"]
        card = Card(card_id=1, state=State.Review, step=None, due=base,
                    last_review=base, **previous)
        now = base + timedelta(days=case["elapsedDays"])
        case["expectedRetrievability"] = scheduler.get_card_retrievability(card, now)
        card, _ = scheduler.review_card(card, rating, now)
        case["expectedNextState"] = dict(stability=card.stability, difficulty=card.difficulty)
        case["expectedNextIntervalDays"] = (card.due - now).days
    elif case["kind"] == "interval":
        case["expectedNextIntervalDays"] = scheduler._next_interval(stability=case["stability"])
    elif case["kind"] == "shortTerm":
        case["expectedShortTermStability"] = scheduler._short_term_stability(stability=case["stability"], rating=rating)
    if before != json.dumps(case, sort_keys=True):
        changed.append(case["name"])
fixture["generatedBy"] = "tools/generate_fsrs_oracle.py: executes unmodified pinned py-fsrs"
fixture["reference"]["notes"] = ["Computed by executing upstream; validation cases retain Kotlin input guards."]
output = ROOT / "app/testdata/upstream-reference-cases.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Generated {len(fixture['cases'])} cases from official py-fsrs {PIN}.")
print("Changed (includes removal of rounding in fixture):", len(changed))

traces = []
for first in (Rating.Again, Rating.Hard, Rating.Good):
    scheduler = Scheduler(relearning_steps=(timedelta(minutes=1),), enable_fuzzing=False)
    card = Card(card_id=1, due=base)
    now = base
    trace = []
    for rating in (first, Rating.Hard, Rating.Again, Rating.Good, Rating.Good,
                   Rating.Hard, Rating.Again, Rating.Hard, Rating.Good, Rating.Good):
        card, _ = scheduler.review_card(card, rating, now)
        trace.append(dict(now=int(now.timestamp() * 1000), rating=rating.value,
            stability=card.stability, difficulty=card.difficulty,
            phase=card.state.value, step=card.step or 0,
            dueAt=int(card.due.timestamp() * 1000)))
        now = card.due
    traces.append(trace)
(ROOT / "app/testdata/fsrs-policy-reference.json").write_text(
    json.dumps(dict(upstream=PIN, traces=traces), indent=2) + "\n", encoding="utf-8")
print("Generated 30 learning/relearning transitions by upstream Scheduler.review_card.")
