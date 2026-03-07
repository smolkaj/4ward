Packet mismatch detection
=========================

When the actual output doesn't match the STF's expect lines, 4ward
reports FAIL and exits with code 1.

passthrough.p4 always outputs on port 1. If we expect port 9, the
test should fail:

  $ cp "$P4" passthrough.p4

  $ cat > mismatch.stf << 'EOF'
  > packet 0 FFFFFFFFFFFF 000000000001 0800
  > expect 9 FFFFFFFFFFFF 000000000001 0800
  > EOF

  $ 4ward run passthrough.p4 mismatch.stf > out.txt 2>&1
  [1]
  $ grep FAIL out.txt
  FAIL
  $ grep "expected packet on port 9" out.txt
    expected packet on port 9 but got none
