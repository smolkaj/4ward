/* tiny.p4 — minimal v1model program used to exercise `fourward_pipeline`
 * output configurations. A single exact-match table gives p4info and
 * DeviceConfig non-trivial content to serialize.
 */

#include <core.p4>
#include <v1model.p4>

header eth_t {
    bit<48> dst;
    bit<48> src;
    bit<16> etype;
}

struct headers_t { eth_t eth; }
struct metadata_t {}

parser P(packet_in pkt, out headers_t hdr,
         inout metadata_t meta, inout standard_metadata_t smeta) {
    state start { pkt.extract(hdr.eth); transition accept; }
}

control VC(inout headers_t hdr, inout metadata_t meta) { apply {} }
control CC(inout headers_t hdr, inout metadata_t meta) { apply {} }

control Ig(inout headers_t hdr, inout metadata_t meta,
           inout standard_metadata_t smeta) {
    action forward(bit<9> port) { smeta.egress_spec = port; }
    action drop() { mark_to_drop(smeta); }
    table t {
        key = { hdr.eth.etype : exact; }
        actions = { forward; drop; NoAction; }
        default_action = drop();
    }
    apply { t.apply(); }
}

control Eg(inout headers_t hdr, inout metadata_t meta,
           inout standard_metadata_t smeta) { apply {} }

control D(packet_out pkt, in headers_t hdr) { apply { pkt.emit(hdr.eth); } }

V1Switch(P(), VC(), Ig(), Eg(), CC(), D()) main;
