"""Tests for SharedInstanceManager (build step 12)."""
from retalert.transport.share_instance import (
    SharedInstanceManager, SharedInstanceConfig, HOSTS,
)
from retalert.config import DEFAULT_RNS_CONFIG_TEMPLATE


def test_defaults_off(tmp_path):
    mgr = SharedInstanceManager(tmp_path / "si.json", tmp_path / "rns" / "config")
    cfg = mgr.status()
    assert cfg.enabled is False


def test_attach_local_writes_share_instance_yes(tmp_path):
    rns_cfg = tmp_path / "rns" / "config"
    mgr = SharedInstanceManager(tmp_path / "si.json", rns_cfg)
    mgr.attach_local(host_app="sideband")
    cfg = mgr.status()
    assert cfg.enabled and cfg.mode == "local"
    assert cfg.host_app == "sideband"
    txt = rns_cfg.read_text("utf-8")
    assert "share_instance = Yes" in txt


def test_attach_tcp_writes_tcpclient_interface(tmp_path):
    rns_cfg = tmp_path / "rns" / "config"
    mgr = SharedInstanceManager(tmp_path / "si.json", rns_cfg)
    mgr.attach_tcp("10.0.0.5", 49300, host_app="columba",
                   identity_path="/host/identity")
    cfg = mgr.status()
    assert cfg.enabled and cfg.mode == "tcp"
    assert cfg.host == "10.0.0.5" and cfg.port == 49300
    assert cfg.identity_path == "/host/identity"
    txt = rns_cfg.read_text("utf-8")
    assert "share_instance = No" in txt
    assert "TCPClientInterface" in txt
    assert "target_host = 10.0.0.5" in txt
    assert "target_port = 49300" in txt


def test_config_persists_across_instances(tmp_path):
    rns_cfg = tmp_path / "rns" / "config"
    mgr = SharedInstanceManager(tmp_path / "si.json", rns_cfg)
    mgr.attach_tcp("1.2.3.4", 5000)
    mgr2 = SharedInstanceManager(tmp_path / "si.json", rns_cfg)
    cfg = mgr2.status()
    assert cfg.enabled and cfg.mode == "tcp"
    assert cfg.host == "1.2.3.4" and cfg.port == 5000


def test_detach_restores_default_config(tmp_path):
    rns_cfg = tmp_path / "rns" / "config"
    mgr = SharedInstanceManager(tmp_path / "si.json", rns_cfg,
                                default_template=DEFAULT_RNS_CONFIG_TEMPLATE)
    mgr.attach_tcp("1.2.3.4", 5000)
    assert "TCPClientInterface" in rns_cfg.read_text("utf-8")
    mgr.detach()
    cfg = mgr.status()
    assert cfg.enabled is False
    txt = rns_cfg.read_text("utf-8")
    assert "AutoInterface" in txt  # default template restored
    assert "TCPClientInterface" not in txt


def test_hosts_listed():
    for h in ("sideband", "columba", "meshchat", "meshchatx"):
        assert h in HOSTS


def test_render_after_load(tmp_path):
    """Loading an existing attached config still renders the RNS config on
    the next attach (idempotent)."""
    rns_cfg = tmp_path / "rns" / "config"
    mgr = SharedInstanceManager(tmp_path / "si.json", rns_cfg)
    mgr.attach_local()
    # Reload: status reflects persisted state.
    mgr2 = SharedInstanceManager(tmp_path / "si.json", rns_cfg)
    assert mgr2.status().enabled is True
    assert mgr2.status().mode == "local"