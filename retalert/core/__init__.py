"""RetAlert daemon core modules.

Step-1 scope only wires the transport/identity and lxmf_transport. The modules
below are interface stubs for later build steps (see PROMPT.md § Decisions and
§ Suggested build order). Each defines the class surface its later implementer
will fill; calling them raises NotImplementedError.
"""