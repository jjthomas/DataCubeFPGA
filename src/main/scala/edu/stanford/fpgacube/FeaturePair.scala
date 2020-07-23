package edu.stanford.fpgacube

import chisel3._

class FeaturePair(val wordWidth: Int) extends Module {
  val io = IO(new Bundle {
    val inputFeatureOne = Input(UInt(wordWidth.W))
    val inputFeatureTwo = Input(UInt(wordWidth.W))
    val inputMetric = Input(UInt(32.W))
    val inputValid = Input(Bool())
    val shiftMode = Input(Bool()) // one cycle pause required between last inputValid and start of shiftMode
    val doShift = Input(Bool())
    val neighborOutputIn = Input(UInt(64.W))
    val output = Output(UInt(64.W))
  })

  // count in top 32 bits, metric sum in bottom 32 bits
  val bram = instantiateBRAM(64, 2 * wordWidth, clock, false)

  val lastFeatureOne = RegNext(io.inputFeatureOne)
  val lastFeatureTwo = RegNext(io.inputFeatureTwo)
  val lastMetric = RegNext(io.inputMetric)
  val lastInputValid = RegNext(io.inputValid, false.B)
  bram.a_addr := io.inputFeatureOne ## io.inputFeatureTwo
  bram.b_din := (bram.a_dout(63, 32) + 1.U) ## (bram.a_dout(31, 0) + lastMetric)
  bram.b_addr := lastFeatureOne ## lastFeatureTwo
  bram.b_wr := lastInputValid

  val outputCounter = RegInit(0.asUInt((2 * wordWidth).W))
  when (io.doShift) {
    outputCounter := outputCounter + 1.U // wraps around
  }
  when (io.shiftMode) {
    bram.a_addr := Mux(io.doShift, outputCounter + 1.U, outputCounter)
    bram.b_din := io.neighborOutputIn
    bram.b_addr := outputCounter
    bram.b_wr := io.doShift
  }
  io.output := bram.a_dout
}

object FeaturePair extends App {
  chisel3.Driver.execute(args, () => new FeaturePair(args(0).toInt))
}
