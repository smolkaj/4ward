/* assert_log_msg.p4 — exercises assert(), assume(), and log_msg().
 *
 * Ingress:
 *  - log_msg prints a debug message
 *  - assert checks that ingress_port != 5
 *  - assume checks that dstAddr is valid (always true after parser)
 *  - forwards to port 1
 *
 * Packets on port 0: passes all assertions, forwarded to port 1.
 * Packets on port 5: assert fails, packet dropped.
 */

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t {
    ethernet_t ethernet;
}

struct metadata_t {}

parser MyParser(
    packet_in packet,
    out headers_t hdr,
    inout metadata_t meta,
    inout standard_metadata_t standard_metadata)
{
    state start {
        packet.extract(hdr.ethernet);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) {
    apply {}
}

control MyIngress(
    inout headers_t hdr,
    inout metadata_t meta,
    inout standard_metadata_t standard_metadata)
{
    apply {
        log_msg("ingress port = {}", {standard_metadata.ingress_port});
        assert(standard_metadata.ingress_port != 9w5);
        assume(hdr.ethernet.isValid());
        standard_metadata.egress_spec = 1;
    }
}

control MyEgress(
    inout headers_t hdr,
    inout metadata_t meta,
    inout standard_metadata_t standard_metadata)
{
    apply {}
}

control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) {
    apply {}
}

control MyDeparser(packet_out packet, in headers_t hdr) {
    apply {
        packet.emit(hdr.ethernet);
    }
}

V1Switch(
    MyParser(),
    MyVerifyChecksum(),
    MyIngress(),
    MyEgress(),
    MyComputeChecksum(),
    MyDeparser()
) main;
