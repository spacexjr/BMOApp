import re
from datetime import datetime

from bmo_backend.actions import detect_local_intent, parse_authorized_action


def test_time_intent_is_local_and_portuguese():
    result = detect_local_intent("Ei BMO, que horas são?")
    assert result is not None
    assert result.action == "get_time"
    assert re.search(r"Agora são \d{2}:\d{2}\.", result.text)


def test_date_intent():
    result = detect_local_intent("que dia é hoje?")
    assert result is not None
    assert str(datetime.now().year) in result.text


def test_unrelated_text_has_no_local_action():
    assert detect_local_intent("conte uma história sobre robôs") is None


def test_authorized_json_action():
    result = parse_authorized_action('{"action": "get_time"}')
    assert result is not None
    assert result.action == "get_time"
    date = parse_authorized_action('{"action": "get_date"}')
    assert date is not None
    assert date.action == "get_date"


def test_unauthorized_shell_action_is_ignored():
    assert parse_authorized_action('{"action": "shell", "cmd": "rm -rf /"}') is None
    assert parse_authorized_action('{"action": "capture_image"}') is None
