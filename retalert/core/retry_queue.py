"""RetryQueue — persists unsent alerts, flushes when transport returns.

Build step: 2. See PROMPT.md § RetryQueue, § Transport intelligence & failover.
Survives restart. During an active (unacked) emergency, retries are fast and
aggressive (no long backoff); normal backoff resumes after ack.
"""


class RetryQueue:
    """Persistent queue of unsent/unacked alerts. Flushes opportunistically
    when an interface/path returns."""

    def __init__(self, storage):
        self.storage = storage

    def enqueue(self, alert):
        raise NotImplementedError("RetryQueue.enqueue — build step 2")

    def flush(self):
        raise NotImplementedError("RetryQueue.flush — build step 2")