#!/bin/bash

sbt "runMain edu.stanford.fpgacube.StreamingWrapper $1"
cat DualPortRAMBB.v StreamingWrapper.v > StreamingWrapper$1.v
cp StreamingWrapper$1.v $CL_DIR/design/sw.sv
./update_cons.sh $1
