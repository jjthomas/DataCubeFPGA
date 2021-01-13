#!/usr/bin/env python

import sys

dim = int(sys.argv[1])
for i in range(dim * dim):
  if i == 0:
    print "set_property ram_style ultra [get_cells streaming_wrapper/FeaturePair/DualPortRAMBB/mem_reg]"
  elif i < 800:
    print "set_property ram_style ultra [get_cells streaming_wrapper/FeaturePair_%d/DualPortRAMBB/mem_reg]" % i
  elif i < 2479:
    print "set_property ram_style block [get_cells streaming_wrapper/FeaturePair_%d/DualPortRAMBB/mem_reg]" % i
  else:
    print "set_property ram_style distributed [get_cells streaming_wrapper/FeaturePair_%d/DualPortRAMBB/mem_reg]" % i

