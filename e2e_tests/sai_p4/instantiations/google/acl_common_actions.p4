#ifndef SAI_ACL_COMMON_ACTIONS_P4_
#define SAI_ACL_COMMON_ACTIONS_P4_

#include "e2e_tests/sai_p4/fixed/v1model_sai.p4"
#include "ids.h"

@id(ACL_DROP_ACTION_ID)
@sai_action(SAI_PACKET_ACTION_DROP)
action acl_drop(inout local_metadata_t local_metadata) {
  local_metadata.acl_drop = true;
}

#endif  // SAI_ACL_COMMON_ACTIONS_P4_
