package edu.stanford.fpgacube

import chisel3._

class FeaturePair(val wordWidth: Int) extends Module {
  val io = IO(new Bundle {
    val inputFeatureOne = Input(UInt(wordWidth.W))
    val inputFeatureTwo = Input(UInt(wordWidth.W))
    val inputMetric = Input(UInt(32.W))
    val inputValid = Input(Bool())
    val inputFinished = Input(Bool()) // begins to be asserted sometime after the last inputValid; output should be 0th
    // tuple on the next cycle
    val outputShift = Input(Bool())
    val neighborOutputIn = Input(UInt(64.W))
    val output = Output(UInt(64.W))
  })

  // Must add collision attribute to BRAM for correct synthesis.
  // count in top 32 bits, metric sum in bottom 32 bits
  val data = Module(new DualPortBRAM(64, 2 * wordWidth))

  val lastFeatureOne = RegNext(io.inputFeatureOne)
  val lastFeatureTwo = RegNext(io.inputFeatureTwo)
  val lastMetric = RegNext(io.inputMetric)
  val lastInputValid = RegNext(io.inputValid, false.B)
  data.io.a_addr := io.inputFeatureOne ## io.inputFeatureTwo
  data.io.b_din := (data.io.a_dout(63, 32) + 1.U) ## (data.io.a_dout(31, 0) + lastMetric)
  data.io.b_addr := lastFeatureOne ## lastFeatureTwo
  data.io.b_wr := lastInputValid

  val outputCounter = RegInit(0.asUInt((2 * wordWidth).W))
  when (io.outputShift) {
    outputCounter := outputCounter + 1.U // wraps around
  }
  when (io.inputFinished) {
    data.io.a_addr := Mux(io.outputShift, outputCounter + 1.U, outputCounter)
    data.io.b_din := io.neighborOutputIn
    data.io.b_addr := outputCounter
    data.io.b_wr := io.outputShift
  }
  io.output := data.io.a_dout
}

class StreamingWrapper(val inputStartAddr: Int, val outputStartAddr: Int, val busWidth: Int, val wordWidth: Int)
  extends Module {
  val io = IO(new Bundle {
    val inputMemAddr = Output(UInt(64.W))
    val inputMemAddrValid = Output(Bool())
    val inputMemAddrLen = Output(UInt(8.W))
    val inputMemAddrReady = Input(Bool())
    val inputMemBlock = Input(UInt(busWidth.W))
    val inputMemBlockValid = Input(Bool())
    val inputMemBlockReady = Output(Bool())
    val outputMemAddr = Output(UInt(64.W))
    val outputMemAddrValid = Output(Bool())
    val outputMemAddrLen = Output(UInt(8.W))
    val outputMemAddrId = Output(UInt(16.W))
    val outputMemAddrReady = Input(Bool())
    val outputMemBlock = Output(UInt(busWidth.W))
    val outputMemBlockValid = Output(Bool())
    val outputMemBlockLast = Output(Bool())
    val outputMemBlockReady = Input(Bool())
    val finished = Output(Bool())
  })

}

object StreamingWrapper extends App {
  chisel3.Driver.execute(args, () => new StreamingWrapper(0, 1000000000, 512, 4))
}