## Data Cubing Hardware

Hardware for the software at https://github.com/jjthomas/pandas_interesting_subgroups.

The hardware crosses two sets of 4-bit columns, each set of size N, such that all
N^2 pairs have counts and metric sums for each of the 256 possible combinations
of their two column values. The hardware takes data in row-oriented format,
with each 512-bit AXI line containing an 8-bit metric value and then
2N\*4 bits for the two sets of column values. (If this is less than 512 bits,
the rest of the line is unused.) See the software repo for more details
on this computation.

## Building

You will need a computer with Vivado 2018.2 on the PATH and around
30 GB or more of memory. EC2 instances running Centos FPGA AMI
versions around 1.4.6 will work.

Get the `minimal_single_ddr` branch of https://github.com/jjthomas/aws-fpga.
Source hdk_setup.sh and set environment variable `CL_DIR` to `<path to aws-fpga>/hdk/cl/examples/cl_dram_dma`.

Install sbt. Then run ./build.sh N, with N being the parameter mentioned above.
N=48 is the design used by the current version of the software.

You can then build the design by running
`./aws_build_dcp_from_cl_main.sh -strategy TIMING -monolithic_flow` from
`$CL_DIR/build/scripts`.
If this finishes successfully, you can copy the final
checkpoint from the `build/checkpoints` dir to `build/to_aws/000000.SH_CL_routed.dcp`
and then run `./aws_build_dcp_from_cl_zip_only.sh` in the scripts
dir to generate the final tar file that you can upload to S3 and turn
into an AFI. Instructions for uploading the tar can be found here: https://github.com/jjthomas/aws-fpga/blob/master/hdk/cl/examples/README.md#3-submit-the-design-checkpoint-to-aws-to-register-the-afi.

Tests can be run with `sbt "test:runMain edu.stanford.fpgacube.Tests`
if verilator is installed.
