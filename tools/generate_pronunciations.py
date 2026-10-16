"""Generate only the project's vocabulary subset from pinned, redistributable CMUdict.
ARPAbet -> broad American IPA display conversion; retain the source license.
No word meanings or example sentences are imported from external dictionaries.
"""
from pathlib import Path
import csv
import hashlib
import re
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PIN = "74790861f652b15e4ac49015a90074ad62a27690"
raw = urllib.request.urlopen(f"https://raw.githubusercontent.com/cmusphinx/cmudict/{PIN}/cmudict.dict").read()
cache = ROOT / ".tools" / "fsrs-audit" / "cmudict.dict"
cache.write_bytes(raw)
dictionary = {}
for line in raw.decode("utf-8").splitlines():
    fields = line.split(" #", 1)[0].split()
    if fields:
        word = re.sub(r"\(\d+\)$", "", fields[0])
        dictionary.setdefault(word, []).append(fields[1:])
words = {row["word"] for row in csv.DictReader((ROOT / "app/src/main/assets/academic_words.csv").open(encoding="utf-8"))}
source = (ROOT / "app/src/main/java/com/example/cet6/data/StudyDatabase.kt").read_text(encoding="utf-8")
words.update(re.findall(r'WordEntity\("([^"]+)"', source.split("val starterWords = listOf(", 1)[1]))
phonemes = dict(AA="ɑ", AE="æ", AH="ʌ", AO="ɔ", AW="aʊ", AY="aɪ", B="b", CH="tʃ", D="d", DH="ð", EH="ɛ", ER="ɝ", EY="eɪ", F="f", G="ɡ", HH="h", IH="ɪ", IY="i", JH="dʒ", K="k", L="l", M="m", N="n", NG="ŋ", OW="oʊ", OY="ɔɪ", P="p", R="ɹ", S="s", SH="ʃ", T="t", TH="θ", UH="ʊ", UW="u", V="v", W="w", Y="j", Z="z", ZH="ʒ")
def ipa(tokens):
    # Maximum-onset approximation; preserve CMU stress, not a new phonology model.
    onsets = {"P R", "B R", "T R", "D R", "K R", "G R", "F R", "TH R", "SH R",
              "P L", "B L", "K L", "G L", "F L", "S L", "T W", "D W", "K W", "G W", "S W",
              "S P", "S T", "S K", "S M", "S N", "S F", "S P R", "S T R", "S K R", "S P L", "S K L", "S K W"}
    bases = [re.sub(r"[012]$", "", token) for token in tokens]
    markers = {}
    last_vowel = -1
    for i, token in enumerate(tokens):
        if token[-1].isdigit():
            start = i
            for candidate in range(last_vowel + 1, i):
                cluster = bases[candidate:i]
                if (len(cluster) == 1 and cluster[0] != "NG") or " ".join(cluster) in onsets:
                    start = candidate
                    break
            if token[-1] in "12":
                markers[start] = "ˈ" if token[-1] == "1" else "ˌ"
            last_vowel = i
    result = []
    for i, token in enumerate(tokens):
        base = re.sub(r"[012]$", "", token)
        stress = token[-1] if token[-1].isdigit() else ""
        symbol = "ə" if token == "AH0" else "ɚ" if token == "ER0" else phonemes[base]
        result.append(markers.get(i, "") + symbol)
    return "/" + "".join(result) + "/"
missing = words - dictionary.keys()
assert not missing, missing
output = ROOT / "app/src/main/assets/pronunciations.tsv"
output.write_text("word\tipa\n" + "".join(f"{word}\t{' 或 '.join(dict.fromkeys(ipa(tokens) for tokens in dictionary[word]))}\n" for word in sorted(words)), encoding="utf-8")
license_data = urllib.request.urlopen(f"https://raw.githubusercontent.com/cmusphinx/cmudict/{PIN}/LICENSE").read()
notices = ROOT / "app/src/main/assets/licenses"
notices.mkdir(parents=True, exist_ok=True)
(notices / "cmudict.txt").write_bytes(license_data)
print(f"Generated {len(words)} broad American pronunciations, CMUdict {PIN}; raw SHA256 {hashlib.sha256(raw).hexdigest()}")
