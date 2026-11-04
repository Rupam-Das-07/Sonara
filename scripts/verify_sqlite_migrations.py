#!/usr/bin/env python3
"""Verify the production 6 -> 7 -> 8 migration SQL against real SQLite.

The SQL is extracted from SonaraDatabase.kt instead of copied into this script, so the
verification follows the migration definitions that production Room executes.
"""

from __future__ import annotations

import re
import sqlite3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATABASE_SOURCE = ROOT / "app/src/main/java/com/example/sonara/data/local/db/SonaraDatabase.kt"


def migration_block(version: str) -> str:
    source = DATABASE_SOURCE.read_text(encoding="utf-8")
    marker = f"val MIGRATION_{version} = object : Migration"
    start = source.find(marker)
    if start < 0:
        raise AssertionError(f"Could not locate MIGRATION_{version} in {DATABASE_SOURCE}")
    next_migration = source.find("val MIGRATION_", start + len(marker))
    volatile = source.find("@Volatile", start + len(marker))
    end_candidates = [position for position in (next_migration, volatile) if position >= 0]
    end = min(end_candidates) if end_candidates else len(source)
    return source[start:end]


def production_sql(version: str) -> list[str]:
    body = migration_block(version)
    statements = []
    for match in re.finditer(r'db\.execSQL\(\s*"((?:\\.|[^"\\])*)"\s*\)', body):
        statement = bytes(match.group(1), "utf-8").decode("unicode_escape")
        statements.append(statement)
    if not statements:
        raise AssertionError(f"No SQL statements extracted for MIGRATION_{version}")
    return statements


def execute_migration(connection: sqlite3.Connection, version: str) -> None:
    for sql in production_sql(version):
        connection.execute(sql)


def index_columns(connection: sqlite3.Connection, index_name: str) -> list[str]:
    rows = connection.execute(f"PRAGMA index_info('{index_name}')").fetchall()
    return [row[2] for row in rows]


def main() -> None:
    connection = sqlite3.connect(":memory:")
    try:
        # Representative v6 schema: structured credits do not exist yet, and history has
        # only the legacy columns. Foreign keys are included to match the production table.
        connection.executescript(
            """
            PRAGMA foreign_keys = ON;
            CREATE TABLE tracks (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                durationMs INTEGER NOT NULL,
                artworkUrl TEXT,
                lastUpdated INTEGER NOT NULL
            );
            CREATE TABLE playback_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                trackId TEXT NOT NULL,
                listenedAt INTEGER NOT NULL,
                completed INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE
            );
            CREATE INDEX index_playback_history_trackId ON playback_history(trackId);
            CREATE INDEX index_playback_history_listenedAt ON playback_history(listenedAt);
            INSERT INTO tracks VALUES ('track-1', 'Interstellar', 'Hans Zimmer, Benjamin Wallfisch', '', 300000, NULL, 1000);
            INSERT INTO playback_history(trackId, listenedAt, completed) VALUES ('track-1', 2000, 1);
            """
        )

        execute_migration(connection, "6_7")
        assert connection.execute("PRAGMA table_info(tracks)").fetchall()[-1][1] == "artistCredits"
        connection.execute(
            "UPDATE tracks SET artistCredits = ? WHERE id = 'track-1'",
            ("Hans Zimmer\x1fUC_hz\x1eBenjamin Wallfisch\x1fUC_bw",),
        )

        execute_migration(connection, "7_8")
        history_columns = {row[1]: row for row in connection.execute("PRAGMA table_info(playback_history)")}
        assert history_columns["sourceSection"][4] == "'LEGACY_UNKNOWN'"
        assert history_columns["playbackSessionId"][3] == 0  # nullable storage sentinel
        assert connection.execute("SELECT COUNT(*) FROM playback_history").fetchone()[0] == 1
        assert connection.execute("SELECT sourceSection, playbackSessionId FROM playback_history").fetchone() == (
            "LEGACY_UNKNOWN",
            None,
        )
        assert connection.execute("SELECT artistCredits FROM tracks WHERE id='track-1'").fetchone()[0]
        assert index_columns(connection, "index_playback_history_sourceSection_listenedAt") == [
            "sourceSection",
            "listenedAt",
        ]
        assert index_columns(connection, "index_playback_history_playbackSessionId") == ["playbackSessionId"]

        connection.execute(
            "INSERT INTO playback_history(trackId, listenedAt, completed, sourceSection, playbackSessionId) "
            "VALUES ('track-1', 3000, 0, 'SEARCH_SONGS', 'session-1')"
        )
        duplicate_rejected = False
        try:
            connection.execute(
                "INSERT INTO playback_history(trackId, listenedAt, completed, sourceSection, playbackSessionId) "
                "VALUES ('track-1', 4000, 0, 'SEARCH_SONGS', 'session-1')"
            )
        except sqlite3.IntegrityError:
            duplicate_rejected = True
        assert duplicate_rejected, "non-empty session IDs must be unique"

        connection.execute(
            "INSERT INTO playback_history(trackId, listenedAt, completed, sourceSection, playbackSessionId) "
            "VALUES ('track-1', 5000, 0, 'LEGACY_UNKNOWN', NULL)"
        )
        connection.execute(
            "INSERT INTO playback_history(trackId, listenedAt, completed, sourceSection, playbackSessionId) "
            "VALUES ('track-1', 6000, 0, 'LEGACY_UNKNOWN', NULL)"
        )
        assert connection.execute(
            "SELECT COUNT(*) FROM playback_history WHERE playbackSessionId IS NULL"
        ).fetchone()[0] == 3

        print("SQLite migration verification passed: 6 -> 7 -> 8, legacy defaults, indexes, credits, and session uniqueness.")
    finally:
        connection.close()


if __name__ == "__main__":
    main()
