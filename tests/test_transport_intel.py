"""Tests for TransportIntelligence (build steps 3-4)."""
from types import SimpleNamespace

from retalert.core.transport_intel import (
    TransportIntelligence, DeliveryPlan,
    HIGH, MEDIUM, LOW,
    FAN_OUT_OFF, FAN_OUT_CRITICAL, FAN_OUT_ALL,
)
from retalert.core.alert import Alert


def _iface(cls, name, online=True, out=True):
    """Build a fake RNS interface object with the given class name."""
    klass = type(cls, (object,), {})
    obj = klass()
    obj.name = name
    obj.online = online
    obj.OUT = out
    obj.IN = out
    return obj


def _ti_with(*ifaces):
    ti = TransportIntelligence(reticulum=object())  # reticulum unused; we inject
    # Bypass classify_interfaces (needs RNS.Transport) by monkeypatching.
    ti.up_interfaces = lambda: [
        i for i in ti.classify_interfaces() if i.online and i.out_capable
    ]
    import RNS  # noqa
    # Provide a fake RNS.Transport.interfaces list.
    class _T:
        interfaces = list(ifaces)
        @staticmethod
        def has_path(h):
            return True
    RNS.Transport = _T
    return ti


def test_classify_tiers():
    ti = _ti_with(_iface("AutoInterface", "Default Interface"),
                  _iface("RNodeInterface", "lora"),
                  _iface("TCPClientInterface", "tcp"))
    info = {i.cls: i.tier for i in ti.classify_interfaces()}
    assert info["AutoInterface"] == MEDIUM
    assert info["RNodeInterface"] == LOW
    assert info["TCPClientInterface"] == HIGH


def test_unknown_interface_defaults_low():
    ti = _ti_with(_iface("SomeWeirdInterface", "x"))
    assert ti.classify_interfaces()[0].tier == LOW


def test_user_override():
    ti = _ti_with(_iface("AutoInterface", "Default Interface"))
    ti.overrides = {"Default Interface": HIGH}
    assert ti.classify_interfaces()[0].tier == HIGH


def test_payload_policy():
    ti = _ti_with(_iface("RNodeInterface", "lora"))  # only Low up
    ok, best = ti.gate_payload("text")
    assert ok and best == LOW
    ok, best = ti.gate_payload("audio")
    assert not ok  # audio needs High; only Low up
    ok, best = ti.gate_payload("gps_live")
    assert not ok  # needs Medium


def test_gate_blocks_when_nothing_up():
    ti = _ti_with(_iface("AutoInterface", "Default", online=False))
    ok, best = ti.gate_payload("text")
    assert not ok and best is None


def test_rank_orders_by_tier():
    ti = _ti_with(_iface("RNodeInterface", "lora"),
                  _iface("TCPClientInterface", "tcp"),
                  _iface("AutoInterface", "auto"))
    ranked = ti.rank_for(None, "text")
    tiers = [i.tier for i in ranked]
    assert tiers == [HIGH, MEDIUM, LOW]


def test_rank_filters_by_payload_tier():
    ti = _ti_with(_iface("RNodeInterface", "lora"),
                  _iface("AutoInterface", "auto"))
    ranked = ti.rank_for(None, "gps_live")  # needs Medium
    assert [i.tier for i in ranked] == [MEDIUM]


def test_delivery_plan_hail_mary_for_critical():
    ti = _ti_with(_iface("RNodeInterface", "lora"),
                  _iface("TCPClientInterface", "tcp"))
    alert = Alert(severity="danger", text="help", recipients=["aa" * 16],
                  payload={"text": True}, fan_out=FAN_OUT_CRITICAL)
    plan = ti.delivery_plan(alert, fan_out=FAN_OUT_CRITICAL)
    assert plan.mode == "parallel"
    assert len(plan.interfaces) == 2  # fan out on all up interfaces


def test_delivery_plan_sequential_for_noncritical():
    ti = _ti_with(_iface("RNodeInterface", "lora"),
                  _iface("TCPClientInterface", "tcp"))
    alert = Alert(severity="help", text="hi", recipients=["aa" * 16],
                  payload={"text": True}, fan_out=FAN_OUT_CRITICAL)
    plan = ti.delivery_plan(alert, fan_out=FAN_OUT_CRITICAL)
    assert plan.mode == "sequential"
    assert [i.tier for i in plan.interfaces] == [HIGH, LOW]


def test_delivery_plan_fanout_all_for_noncritical():
    ti = _ti_with(_iface("RNodeInterface", "lora"),
                  _iface("TCPClientInterface", "tcp"))
    alert = Alert(severity="help", text="hi", recipients=["aa" * 16],
                  payload={"text": True}, fan_out=FAN_OUT_ALL)
    plan = ti.delivery_plan(alert, fan_out=FAN_OUT_ALL)
    assert plan.mode == "parallel"


def test_delivery_plan_heavy_payload_not_fanned():
    ti = _ti_with(_iface("RNodeInterface", "lora"),
                  _iface("TCPClientInterface", "tcp"))
    alert = Alert(severity="danger", text="help", recipients=["aa" * 16],
                  payload={"audio": True}, fan_out=FAN_OUT_ALL)
    plan = ti.delivery_plan(alert, fan_out=FAN_OUT_ALL)
    # audio needs High -> sequential on High only, not parallel fan-out
    assert plan.mode == "sequential"
    assert [i.tier for i in plan.interfaces] == [HIGH]


def test_delivery_plan_queued_when_no_qualifying_iface():
    ti = _ti_with(_iface("RNodeInterface", "lora"))
    alert = Alert(severity="help", text="hi", recipients=["aa" * 16],
                  payload={"audio": True}, fan_out=FAN_OUT_OFF)
    plan = ti.delivery_plan(alert, fan_out=FAN_OUT_OFF)
    assert plan.queued
    assert plan.interfaces == []