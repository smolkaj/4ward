/* switch_action_run.p4 — switch on table.apply().action_run.
 *
 * After the forwarding table runs, the selected action name is inspected via
 * a switch statement to add post-action logic. Tests switch/case on action_run
 * and the SwitchStmt path through the interpreter.
 */

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t { ethernet_t ethernet; }
struct metadata_t {}

parser MyParser(packet_in pkt, out headers_t hdr,
                inout metadata_t meta, inout standard_metadata_t standard_metadata) {
    state start {
        pkt.extract(hdr.ethernet);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t standard_metadata) {

    action drop() { mark_to_drop(standard_metadata); }

    // forward sets egress_spec; post-action switch may override to a different port.
    action forward(bit<9> port) { standard_metadata.egress_spec = port; }

    table dmac_table {
        key = { hdr.ethernet.dstAddr : exact; }
        actions = { forward; drop; NoAction; }
        default_action = drop();
    }

    apply {
        // After table runs, switch on action_run to add additional per-action logic.
        switch (dmac_table.apply().action_run) {
            forward: {
                // Rewrite srcAddr to mark the packet as locally forwarded.
                hdr.ethernet.srcAddr = 0xAAAAAAAAAA01;
            }
            drop: {
                // Nothing extra — mark_to_drop already set egress_spec.
            }
        }
    }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
