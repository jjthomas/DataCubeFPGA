#!/bin/bash

./gen_constrs.py $1 > cons.xdc
cat cl_synth_user_base.xdc cons.xdc > $CL_DIR/build/constraints/cl_synth_user.xdc
