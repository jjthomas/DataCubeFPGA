package edu.stanford.fpgacube

import chisel3._
import chisel3.util._

class FeaturePair(val wordWidth: Int, val inputPipeDepth: Int, val outputPipeDepth: Int) extends Module {
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

  val inputFeatureOne = ShiftRegister(io.inputFeatureOne, inputPipeDepth)
  val inputFeatureTwo = ShiftRegister(io.inputFeatureTwo, inputPipeDepth)
  val inputMetric = ShiftRegister(io.inputMetric, inputPipeDepth)
  val inputValid = ShiftRegister(io.inputValid, inputPipeDepth, false.B, true.B)
  val shiftMode = ShiftRegister(io.shiftMode, inputPipeDepth, false.B, true.B)
  val doShift = ShiftRegister(io.doShift, inputPipeDepth, false.B, true.B)

  // count in top 32 bits, metric sum in bottom 32 bits
  val bram = instantiateBRAM(64, 2 * wordWidth, clock, false)

  val lastFeatureOne = RegNext(inputFeatureOne)
  val lastFeatureTwo = RegNext(inputFeatureTwo)
  val lastMetric = RegNext(inputMetric)
  val lastInputValid = RegNext(inputValid, false.B)
  bram.a_addr := inputFeatureOne ## inputFeatureTwo
  bram.b_din := (bram.a_dout(63, 32) + 1.U) ## (bram.a_dout(31, 0) + lastMetric)
  bram.b_addr := lastFeatureOne ## lastFeatureTwo
  bram.b_wr := lastInputValid

  val outputCounter = RegInit(0.asUInt((2 * wordWidth).W))
  // assumes doShift signals are not pipelined, otherwise read counter needs to get ahead of write counter
  val doShift_delayed = ShiftRegister(doShift, outputPipeDepth, false.B, true.B)
  when (doShift_delayed) {
    outputCounter := outputCounter + 1.U // wraps around
  }
  when (shiftMode) {
    bram.a_addr := Mux(doShift_delayed, outputCounter + 1.U, outputCounter)
    bram.b_din := io.neighborOutputIn
    bram.b_addr := outputCounter
    bram.b_wr := doShift_delayed
  }
  io.output := ShiftRegister(bram.a_dout, outputPipeDepth)
}

object FeaturePair extends App {
  chisel3.Driver.execute(args, () => new FeaturePair(args(0).toInt, args(1).toInt, args(2).toInt))
}
