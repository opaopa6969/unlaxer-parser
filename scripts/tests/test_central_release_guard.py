#!/usr/bin/env python3
import contextlib
import io
import pathlib
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import central_release_guard as guard  # noqa: E402


def listing(*entries):
    lines = ['<a href="../">../</a>']
    for name, timestamp in entries:
        lines.append(
            f'<a href="{name}/" title="{name}/">{name}/</a> '
            f'{timestamp} -'
        )
    return "\n".join(lines)


class CentralReleaseGuardTest(unittest.TestCase):
    def test_parse_directory_listing(self):
        entries = guard.parse_directory_listing(
            listing(("alpha", "2026-08-01 01:02"), ("beta", "2026-08-02 03:04"))
        )
        self.assertEqual(["alpha", "beta"], [entry.name for entry in entries])
        self.assertEqual("2026-08-01T01:02:00+00:00", entries[0].published_at.isoformat())

    def test_collect_groups_reactor_modules_by_utc_minute(self):
        pages = {
            "https://repo.example/org/unlaxer/": listing(
                ("alpha", "2020-01-01 00:00"),
                ("beta", "2020-01-01 00:00"),
            ),
            "https://repo.example/org/unlaxer/alpha/": listing(
                ("1.0.0", "2026-08-10 09:30"),
                ("1.1.0", "2026-08-20 11:45"),
            ),
            "https://repo.example/org/unlaxer/beta/": listing(
                ("1.0.0", "2026-08-10 09:30"),
                ("0.9.0", "2026-07-01 00:00"),
            ),
        }

        events = guard.collect_release_events(
            "https://repo.example", "org.unlaxer", "2026-08", pages.__getitem__
        )

        self.assertEqual(2, len(events))
        self.assertEqual(2, len(events[0]["coordinates"]))
        self.assertEqual("2026-08-20T11:45Z", events[1]["publishedAt"])

    def test_capacity_check_fails_closed(self):
        event = {"publishedAt": "2026-08-10T09:30Z", "coordinates": ["g:a:1"]}
        stderr = io.StringIO()
        stdout = io.StringIO()
        with patch.object(guard, "collect_release_events", return_value=[event]):
            with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(stdout):
                result = guard.main(["--month", "2026-08", "--max-releases", "1"])
        self.assertEqual(3, result)
        self.assertIn("budget is exhausted", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
