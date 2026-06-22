"""Tests for AckTracker + RetryQueue (build step 2)."""
import time

from retalert.core.alert import Alert, SENT, DELIVERED, FAILED
from retalert.core.ack_tracker import AckTracker
from retalert.core.retry_queue import RetryQueue


def test_ack_tracker_transitions():
    ack = AckTracker()
    alert = Alert(severity="danger", text="help", recipients=["aa" * 16, "bb" * 16])
    ack.track(alert)

    assert set(ack.unacked_recipients(alert.alert_id)) == {"aa" * 16, "bb" * 16}
    assert not ack.is_done(alert.alert_id)

    ack.on_delivered(alert.alert_id, "aa" * 16)
    assert ack.state(alert.alert_id, "aa" * 16) == DELIVERED
    # step 2: DELIVERED is terminal -> no longer retried
    assert "aa" * 16 not in ack.unacked_recipients(alert.alert_id)

    ack.on_ack(alert.alert_id, "aa" * 16)
    assert ack.state(alert.alert_id, "aa" * 16) == "acked"
    ack.on_failed(alert.alert_id, "bb" * 16, "no path")
    assert ack.state(alert.alert_id, "bb" * 16) == FAILED

    assert ack.is_done(alert.alert_id)
    assert ack.unacked_recipients(alert.alert_id) == []


def test_retry_queue_persists_and_flushes(tmp_path):
    ack = AckTracker()
    sent: list = []

    def send_fn(alert, recipient):
        sent.append((alert.alert_id, recipient))
        # aa delivers immediately; bb never delivers (stays SENT -> retried).
        if recipient == "aa" * 16:
            ack.on_delivered(alert.alert_id, recipient)

    qpath = tmp_path / "alerts.json"
    q = RetryQueue(qpath, ack, send_fn=send_fn)
    alert = Alert(severity="medical", text="med", recipients=["aa" * 16, "bb" * 16],
                  retry_interval=0.01, max_attempts=0)
    q.enqueue(alert)

    # aa delivered (terminal). bb stays SENT and keeps being retried.
    for _ in range(5):
        q.flush()
        time.sleep(0.01)

    assert ack.state(alert.alert_id, "aa" * 16) == DELIVERED
    assert ack.state(alert.alert_id, "bb" * 16) == SENT
    assert sum(1 for (_, r) in sent if r == "bb" * 16) >= 2  # retried
    assert sum(1 for (_, r) in sent if r == "aa" * 16) == 1  # delivered once, not retried

    # Alert stays pending (bb still active) and survives reload.
    q2 = RetryQueue(qpath, AckTracker(), send_fn=send_fn)
    assert alert.alert_id in [a.alert_id for a in q2.pending()]


def test_retry_queue_max_attempts_failed(tmp_path):
    ack = AckTracker()

    def send_fn(alert, recipient):
        # never delivers
        pass

    q = RetryQueue(tmp_path / "alerts.json", ack, send_fn=send_fn)
    alert = Alert(severity="medical", text="med", recipients=["bb" * 16],
                  retry_interval=0.01, max_attempts=3)
    q.enqueue(alert)

    for _ in range(50):
        q.flush()
        if ack.state(alert.alert_id, "bb" * 16) == FAILED:
            break
        time.sleep(0.01)

    assert ack.state(alert.alert_id, "bb" * 16) == FAILED


def test_retry_queue_drops_done(tmp_path):
    ack = AckTracker()

    def send_fn(alert, recipient):
        ack.on_delivered(alert.alert_id, recipient)
        ack.on_ack(alert.alert_id, recipient)

    q = RetryQueue(tmp_path / "alerts.json", ack, send_fn=send_fn)
    alert = Alert(severity="help", text="ok", recipients=["cc" * 16],
                  retry_interval=0.01)
    q.enqueue(alert)
    q.flush()  # send_fn acks -> done
    assert alert.alert_id not in [a.alert_id for a in q.pending()]