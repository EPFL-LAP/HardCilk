import chext_test
import hdlinfo

from chext_test import ElasticProtocol

portsToSignals = [
    ("TDATA", "bits.data"),
    ("TSTRB", "bits.strb"),
    ("TKEEP", "bits.keep"),
    ("TLAST", "bits.last"),
    ("TDEST", "bits.dest"),
    ("TREADY", "ready"),
    ("TVALID", "valid")
]

ElasticProtocol(
    "axis4_mFPGA",
    includeStr='<sysc_netw/Protocols.hpp>',
    bitsSignalType="axis4_mFPGA_Signals",
    portsToSignals=portsToSignals
)
