"""Execute the SQL actually declared in StudyDatabase.kt against released Room schemas.
Host SQLite evidence only: does not replace the Android/Room instrumentation runner.
"""
from pathlib import Path
import json
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "app/schemas/com.example.cet6.data.StudyDatabase"
source = (ROOT / "app/src/main/java/com/example/cet6/data/StudyDatabase.kt").read_text(encoding="utf-8")
migrations = {}
for start, end, body in re.findall(r"val MIGRATION_(\d+)_(\d+) = (.*?)(?=\n        val MIGRATION_|\n        @Volatile)", source, re.S):
    migrations[int(start)] = (int(end), [json.loads('"' + statement + '"') for statement in re.findall(r'db.execSQL\("((?:[^"\\]|\\.)*)"\)', body)])
latest = max(int(path.stem) for path in SCHEMAS.glob("*.json"))
expected = json.loads((SCHEMAS / f"{latest}.json").read_text(encoding="utf-8"))["database"]
for version in range(1, latest):
    db = sqlite3.connect(":memory:")
    original = json.loads((SCHEMAS / f"{version}.json").read_text(encoding="utf-8"))["database"]
    for table in original["entities"]:
        db.execute(table["createSql"].replace("${TABLE_NAME}", table["tableName"]))
        for index in table.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", table["tableName"]))
    fields = "word,meaning,example,dueAt,repetitions,intervalDays" + (",source" if version >= 3 else "")
    values = ["preserved", "original meaning", "original example", 1800000000000, 5, 30] + (["original source"] if version >= 3 else [])
    db.execute(f"INSERT INTO words ({fields}) VALUES ({','.join('?' for _ in values)})", values)
    db.execute("INSERT INTO reading_attempts VALUES (10,'q','B',1,1000)")
    db.execute("INSERT INTO essays VALUES (11,'topic','draft','feedback',1001)")
    if version >= 2:
        db.execute("INSERT INTO daily_plans VALUES ('2026-10-16',7,15)")
        db.execute("INSERT INTO word_reviews (id,word,remembered,wasNew,studiedOn,createdAt,nextDueAt) VALUES ('old-log','preserved',1,0,'2026-10-16',1000,1800000000000)")
    if version >= 5:
        db.execute("INSERT INTO coach_practices (id,kind,title,material,questions,answer,feedback,model,errorReason,submittedAt,updatedAt) VALUES ('preserved-practice','READING','title','material','question','A','feedback','test-model','reason',100,200)")
    if version >= 6:
        db.execute("UPDATE coach_practices SET savedSentences='Original evidence.'")
    if version >= 7:
        db.execute("UPDATE coach_practices SET sourcePracticeId='older-practice',practiceGoal='Preserved goal'")
    before = {}
    for table in original["entities"]:
        name = table["tableName"]
        columns = [row[1] for row in db.execute(f"PRAGMA table_info({name})")]
        before[name] = (columns, db.execute(f"SELECT {','.join(columns)} FROM {name}").fetchall())
    current = version
    while current < latest:
        target, statements = migrations[current]
        assert statements, f"No migration SQL for version {current}"
        for sql in statements:
            db.execute(sql)
        current = target
    for table in expected["entities"]:
        name = table["tableName"]
        columns = {row[1]: row for row in db.execute(f"PRAGMA table_info({name})")}
        assert set(columns) == {field["columnName"] for field in table["fields"]}, name
        for field in table["fields"]:
            actual = columns[field["columnName"]]
            assert actual[2] == field["affinity"] and bool(actual[3]) == field["notNull"], (name, field)
        assert [col for col, spec in columns.items() if spec[5]] == table["primaryKey"]["columnNames"], name
        indexes = {row[1]: row for row in db.execute(f"PRAGMA index_list({name})") if row[3] == "c"}
        assert set(indexes) == {index["name"] for index in table.get("indices", [])}, name
        for index in table.get("indices", []):
            assert bool(indexes[index["name"]][2]) == index["unique"], (name, index)
            assert [row[2] for row in db.execute(f"PRAGMA index_info({index['name']})")] == index["columnNames"], (name, index)
    for name, (columns, rows) in before.items():
        assert db.execute(f"SELECT {','.join(columns)} FROM {name}").fetchall() == rows, f"Lost data: {name}"
    assert db.execute("SELECT count(*) FROM word_schedules").fetchone()[0] == 0, "Fabricated memory"
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    print(f"PASS: actual migration SQL {version}->{latest}; columns/types/keys match Room {latest}; all original rows preserved; no fabricated FSRS state.")
    db.close()
