/* constrained_table.p4 — table with @entry_restriction for constraint validation tests.
 *
 * The ACL table has an @entry_restriction requiring that when ipv4_dst is
 * matched (mask != 0), the ether_type must be 0x0800 (IPv4). This lets us
 * test that the p4-constraints validator accepts valid entries and rejects
 * entries that violate the constraint.
 */

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

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t standard_metadata) {

    action drop() { mark_to_drop(standard_metadata); }
    action forward(bit<9> port) { standard_metadata.egress_spec = port; }

    // Constraint: matching on ipv4_dst requires ether_type == 0x0800.
    @entry_restriction("
        ipv4_dst::mask != 0 -> ether_type::value == 0x0800;
    ")
    table acl {
        key = {
            hdr.ethernet.etherType : ternary @name("ether_type");
            hdr.ipv4.dstAddr       : ternary @name("ipv4_dst");
        }
        actions = { forward; drop; }
        default_action = drop();
    }

    apply { acl.apply(); }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply {
        pkt.emit(hdr.ethernet);
        pkt.emit(hdr.ipv4);
    }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
