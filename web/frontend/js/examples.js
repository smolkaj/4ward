// Example P4 programs (inline source or fetch paths for larger files).

export const EXAMPLES = {
  basic_table: `// basic_table.p4 — simplest table-based v1model program.
//
// Looks up the Ethernet type in an exact-match table.
// If it matches, forward to the specified port; otherwise, drop.

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
                inout metadata_t meta, inout standard_metadata_t smeta) {
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
    action forward(bit<9> port) { standard_metadata.egress_spec = port; }

    table port_table {
        key = { hdr.ethernet.etherType : exact; }
        actions = { forward; drop; NoAction; }
        default_action = drop();
    }

    apply { port_table.apply(); }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
`,

  passthrough: `// passthrough.p4 — forward every packet to port 1.
//
// No tables, no parsing — the simplest possible v1model program.

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
                inout metadata_t meta, inout standard_metadata_t smeta) {
    state start {
        pkt.extract(hdr.ethernet);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t smeta) {
    apply { smeta.egress_spec = 1; }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
`,

  mirror: `// mirror.p4 — IPv4 router with port mirroring.
//
// Parses Ethernet + IPv4, routes via LPM on the destination IP,
// and clones every forwarded packet to a mirror port for monitoring.
// The trace tree forks at the clone point, showing both paths.
//
// To try it:
//   1. Compile & Load
//   2. Add a route: ipv4_lpm with dstAddr=10.0.0.0/8, action=forward, port=1
//   3. Send an IPv4 packet (use the IPv4 preset)
//   4. Check the Trace tab — the clone fork shows both paths!
//
// The clone branch drops until a clone session is configured (via the
// P4Runtime PRE API). The original packet routes normally. Cloned
// copies get their source MAC rewritten to identify them as mirrors.

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

header ipv4_t {
    bit<4>  version;
    bit<4>  ihl;
    bit<8>  diffserv;
    bit<16> totalLen;
    bit<16> identification;
    bit<3>  flags;
    bit<13> fragOffset;
    bit<8>  ttl;
    bit<8>  protocol;
    bit<16> hdrChecksum;
    bit<32> srcAddr;
    bit<32> dstAddr;
}

struct headers_t {
    ethernet_t ethernet;
    ipv4_t     ipv4;
}

struct metadata_t {}

// --- Parser: Ethernet → IPv4 ---

parser MyParser(packet_in pkt, out headers_t hdr,
                inout metadata_t meta, inout standard_metadata_t smeta) {
    state start {
        pkt.extract(hdr.ethernet);
        transition select(hdr.ethernet.etherType) {
            0x0800: parse_ipv4;
            default: accept;
        }
    }
    state parse_ipv4 {
        pkt.extract(hdr.ipv4);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

// --- Ingress: LPM routing + clone for mirroring ---

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t smeta) {

    action drop() { mark_to_drop(smeta); }

    action forward(bit<9> port) {
        smeta.egress_spec = port;
        hdr.ipv4.ttl = hdr.ipv4.ttl - 1;
    }

    table ipv4_lpm {
        key = { hdr.ipv4.dstAddr : lpm; }
        actions = { forward; drop; }
        default_action = drop();
    }

    apply {
        if (hdr.ipv4.isValid()) {
            ipv4_lpm.apply();
            // Mirror all forwarded traffic (clone to session 100).
            clone(CloneType.I2E, 32w100);
        }
    }
}

// --- Egress: tag mirrored copies ---

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) {

    action tag_mirror() {
        // Mark mirrored copies with a distinctive source MAC.
        hdr.ethernet.srcAddr = 48w0xDEAD00000000;
    }

    // instance_type: 0 = original, 1 = ingress clone
    table classify {
        key = { smeta.instance_type : exact; }
        actions = { NoAction; tag_mirror; }
        const entries = {
            0 : NoAction();   // original — pass through
            1 : tag_mirror(); // clone — tag it
        }
    }

    apply { classify.apply(); }
}

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply {
        pkt.emit(hdr.ethernet);
        pkt.emit(hdr.ipv4);
    }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
`,

  sai_middleblock: '/examples/sai_middleblock.p4',
};
