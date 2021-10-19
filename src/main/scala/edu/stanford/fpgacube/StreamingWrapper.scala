package edu.stanford.fpgacube

import chisel3._
import chisel3.util._

class StreamingWrapper(val busWidth: Int, val wordWidth: Int, val numWordsPerGroup: Int, val groupSpace: Int,
                       val metricWidth: Int) extends Module {
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

  assert(busWidth >= 64)
  val numFeaturePairs = numWordsPerGroup * numWordsPerGroup
  val outputWordsInLine = busWidth / 64
  var numOutputWords = numFeaturePairs * (1 << (2 * wordWidth))
  // round up to nearest full line
  numOutputWords = (numOutputWords + outputWordsInLine - 1) / outputWordsInLine *
    outputWordsInLine
  val bytesInLine = busWidth / 8
  val validBitsInLine = 2 * (wordWidth * groupSpace + metricWidth)
  val bufsPerLine = (validBitsInLine + 63) / 64
  val bufSize = 128 // must be power of 2; must be at least the size of the max AXI burst (B) to avoid deadlock;
  // even further, must be >= B + 1 to ensure dest buffer is never full (startup latency of 3 cycles);
  // add one more entry to ensure neither buffer (not just dest) reaches full at any point => size >= B + 2

  val inputLengthAddr :: loadInputLength :: mainLoop :: pause :: writeOutput :: finished :: Nil = Enum(6)
  val state = RegInit(inputLengthAddr)
  val inputLength = Reg(UInt(32.W))
  val inputLines = Wire(UInt(32.W))
  inputLines := (inputLength + 1.U) >> 1
  val inputAddrLineCount = RegInit(0.asUInt(32.W))
  val inputRowCount = RegInit(0.asUInt(32.W))
  val secondGroupAddrReq = RegInit(false.B)
  val secondGroupDataFetch = RegInit(false.B)
  val firstGroupInputLines = RegInit(0.asUInt(32.W))
  val secondGroupInputLines = RegInit(0.asUInt(6.W))
  val sendingAddr :: fillingLine :: sendingLine :: Nil = Enum(3)
  val outputState = RegInit(sendingAddr)
  val outputWordCounter = RegInit(0.asUInt(log2Ceil(numOutputWords + 1).W))
  val outputLine = Reg(Vec(outputWordsInLine, UInt(64.W)))

  val firstBuffers = new Array[DualPortRAMIO](bufsPerLine)
  val secondBuffers = new Array[DualPortRAMIO](bufsPerLine)
  val bufHead = RegInit(0.asUInt(log2Ceil(bufSize).W))
  val nextBufHead = WireInit(bufHead)
  bufHead := nextBufHead
  val firstBufTail = RegInit(0.asUInt(log2Ceil(bufSize).W))
  val firstBufEmtpyPrev = RegNext(nextBufHead === firstBufTail)
  val secondBufTail = RegInit(0.asUInt(log2Ceil(bufSize).W))
  val secondBufEmtpyPrev = RegNext(nextBufHead === secondBufTail)
  val bufWrite = WireInit(io.inputMemBlockValid && state === mainLoop)
  for (i <- 0 until firstBuffers.size) {
    firstBuffers(i) = instantiateRAM(64, log2Ceil(bufSize), clock, false)
    secondBuffers(i) = instantiateRAM(64, log2Ceil(bufSize), clock, false)
    firstBuffers(i).a_addr := nextBufHead
    secondBuffers(i).a_addr := nextBufHead
    firstBuffers(i).b_addr := firstBufTail
    secondBuffers(i).b_addr := secondBufTail
    firstBuffers(i).b_din := io.inputMemBlock((i + 1) * 64 - 1, i * 64)
    secondBuffers(i).b_din := io.inputMemBlock((i + 1) * 64 - 1, i * 64)
    firstBuffers(i).b_wr := bufWrite && !secondGroupDataFetch
    secondBuffers(i).b_wr := bufWrite && secondGroupDataFetch
  }
  val bufValid = WireInit(!firstBufEmtpyPrev && !secondBufEmtpyPrev)
  val secondBatch = RegInit(false.B)
  when (bufValid) {
    secondBatch := !secondBatch
  }
  when (bufValid && secondBatch) {
    nextBufHead := bufHead + 1.U
  }
  when (bufWrite) {
    when (secondGroupDataFetch) {
      secondBufTail := secondBufTail + 1.U
      secondGroupInputLines := secondGroupInputLines + 1.U
      when (secondGroupInputLines === 63.U) {
        secondGroupDataFetch := false.B
      }
    } .otherwise {
      firstBufTail := firstBufTail + 1.U
      firstGroupInputLines := firstGroupInputLines + 1.U
      when (firstGroupInputLines(5, 0) === 63.U || firstGroupInputLines === inputLines - 1.U) {
        secondGroupDataFetch := true.B
      }
    }
  }

  def select(buffers: Array[DualPortRAMIO], upper: Int, lower: Int): UInt = {
    val buf = upper / 64
    assert(buf == lower / 64) // must be in same buf
    buffers(buf).a_dout(upper % 64, lower % 64)
  }
  val featurePairs = new Array[FeaturePair](numFeaturePairs)
  for (i <- 0 until numWordsPerGroup) {
    for (j <- 0 until numWordsPerGroup) {
      val idx = i * numWordsPerGroup + j
      val featurePair = Module(new FeaturePair(wordWidth, metricWidth, idx))
      featurePairs(idx) = featurePair
      val metricBase = 2 * groupSpace * wordWidth
      featurePair.io.inputMetric :=
        Mux(secondBatch,
          select(firstBuffers, metricBase + 2 * metricWidth - 1, metricBase + metricWidth),
          select(firstBuffers, metricBase + metricWidth - 1, metricBase))
      featurePair.io.inputFeatureOne :=
        Mux(secondBatch,
          select(firstBuffers, (i + 1 + groupSpace) * wordWidth - 1, (i + groupSpace) * wordWidth),
          select(firstBuffers, (i + 1) * wordWidth - 1, i * wordWidth))
      featurePair.io.inputFeatureTwo :=
        Mux(secondBatch,
          select(secondBuffers, (j + 1 + groupSpace) * wordWidth - 1, (j + groupSpace) * wordWidth),
          select(secondBuffers, (j + 1) * wordWidth - 1, j * wordWidth))
      featurePair.io.inputValid := bufValid && state === mainLoop
      featurePair.io.shiftMode := state === writeOutput
      featurePair.io.doShift := state === writeOutput && outputState === fillingLine
    }
  }
  for (i <- 0 until numFeaturePairs) {
    if (i == numFeaturePairs - 1) {
      featurePairs(i).io.neighborOutputIn := 0.U
    } else {
      featurePairs(i).io.neighborOutputIn := featurePairs(i + 1).io.output
    }
  }

  io.inputMemAddr := 0.U
  io.inputMemAddrValid := state === inputLengthAddr || (state === mainLoop && inputAddrLineCount =/= inputLines)
  io.inputMemAddrLen := 0.U
  io.inputMemBlockReady := true.B
  io.outputMemAddr := (outputWordCounter << 3).asUInt()
  io.outputMemAddrValid := state === writeOutput && outputState === sendingAddr
  io.outputMemAddrLen := 0.U
  io.outputMemAddrId := 0.U
  io.outputMemBlock := outputLine.asUInt()
  io.outputMemBlockValid := state === writeOutput && outputState === sendingLine
  io.outputMemBlockLast := true.B
  io.finished := state === finished
  switch (state) {
    is (inputLengthAddr) {
      when (io.inputMemAddrReady) {
        state := loadInputLength
      }
    }
    is (loadInputLength) {
      when (io.inputMemBlockValid) {
        inputLength := io.inputMemBlock
        state := mainLoop
      }
    }
    is (mainLoop) {
      val inputMemAddrBase = WireInit((inputAddrLineCount << log2Ceil(bytesInLine)).asUInt() +
        bytesInLine.U) // final term is start offset of main data stream
      io.inputMemAddr := Mux(secondGroupAddrReq, inputMemAddrBase + 1000000000.U, inputMemAddrBase)
      val remainingAddrLines = WireInit(inputLines - inputAddrLineCount)
      io.inputMemAddrLen := Mux(remainingAddrLines > 63.U, 63.U, remainingAddrLines - 1.U)
      when (io.inputMemAddrReady) {
        secondGroupAddrReq := !secondGroupAddrReq
        when (secondGroupAddrReq) {
          inputAddrLineCount := Mux(remainingAddrLines > 63.U, inputAddrLineCount + 64.U, inputLines)
        }
      }
      when (bufValid) {
        inputRowCount := inputRowCount + 1.U
        when (inputRowCount === (inputLength - 1.U)) {
          state := pause
        }
      }
    }
    is (pause) {
      // required to flush FeaturePair pipeline before shiftMode is set
      state := writeOutput
    }
    is (writeOutput) {
      switch (outputState) {
        is (sendingAddr) {
          when (io.outputMemAddrReady) {
            outputState := fillingLine
          }
        }
        is (fillingLine) {
          outputLine(outputWordsInLine - 1) := featurePairs(0).io.output
          for (i <- 0 until outputWordsInLine - 1) {
            outputLine(i) := outputLine(i + 1)
          }
          val wordInLine = if (outputWordsInLine == 1) 0.U else
            outputWordCounter(log2Ceil(outputWordsInLine) - 1, 0)
          when (wordInLine === (outputWordsInLine - 1).U) {
            outputState := sendingLine
          }
          outputWordCounter := outputWordCounter + 1.U
        }
        is (sendingLine) {
          when (io.outputMemBlockReady) {
            when (outputWordCounter === numOutputWords.U) {
              state := finished
            } .otherwise {
              outputState := sendingAddr
            }
          }
        }
      }
    }
  }
}

object StreamingWrapper extends App {
  chisel3.Driver.execute(args, () => new StreamingWrapper(512, 4, args(0).toInt,
    48, 8))
}
