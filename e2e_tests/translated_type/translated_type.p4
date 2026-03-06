/* translated_type.p4 — test fixture for @p4runtime_translation.
 *
 * Uses a translated port type (sdn_bitwidth=32 for a 9-bit field) to verify
 * that the P4Runtime server correctly converts between controller and
 * dataplane representations in action params and match fields.
 */

#include <core.p4>
#include <v1model.p4>

@p4runtime_translation("test.port_id", 32)
type bit<9> port_id_t;

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t { ethernet_t ethernet; }
struct metadata_t { port_id_t ingress_port; }

parser MyParser(packet_in pkt, out headers_t hdr,
                inout metadata_t meta, inout standard_metadata_t smeta) {
    state start {
        meta.ingress_port = (port_id_t)smeta.ingress_port;
        pkt.extract(hdr.ethernet);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t standard_metadata) {

    action drop() { mark_to_drop(standard_metadata); }
    action forward(port_id_t port) { standard_metadata.egress_spec = (bit<9>)port; }

    table forwarding {
        key = { hdr.ethernet.etherType : exact; }
        actions = { forward; drop; NoAction; }
        default_action = drop();
    }

    // Second table: match field uses the translated port_id_t type.
    // p4info will emit type_name on this match field, exercising match translation.
    table port_forward {
        key = { meta.ingress_port : exact; }
        actions = { forward; drop; NoAction; }
        default_action = NoAction;
    }

    apply {
        forwarding.apply();
        port_forward.apply();
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
