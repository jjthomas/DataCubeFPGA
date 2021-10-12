package edu.stanford.fpgacube

import chisel3.iotesters.PeekPokeTester

class StreamingWrapperTests(c: StreamingWrapper, input0: (Int, BigInt), input1: (Int, BigInt),
                            output: (Int, BigInt)) extends PeekPokeTester(c) {
  poke(c.io.inputMemBlockValid(0), false)
  poke(c.io.inputMemBlockValid(1), false)
  poke(c.io.outputMemAddrReady, false)
  poke(c.io.outputMemBlockReady, false)

  var inputLeft0 = input0._2
  var inputLeft1 = input1._2
  var inputBitsLeft = input0._1
  var curInputAddr = c.inputStartAddr
  while (inputBitsLeft > 0) {
    poke(c.io.inputMemAddrReady(0), true)
    poke(c.io.inputMemAddrReady(1), true)
    while (peek(c.io.inputMemAddrValid).toInt == 0) {
      step(1)
    }
    assert(peek(c.io.inputMemAddr).toInt == curInputAddr)
    val len = peek(c.io.inputMemAddrLen).toInt + 1
    assert(len <= (inputBitsLeft + c.busWidth - 1) / c.busWidth)
    step(1)
    poke(c.io.inputMemAddrReady(0), false)
    poke(c.io.inputMemAddrReady(1), false)
    poke(c.io.inputMemBlockValid(0), true)
    poke(c.io.inputMemBlockValid(1), true)
    for (i <- 0 until len) {
      poke(c.io.inputMemBlock(0), inputLeft0 & ((BigInt(1) << c.busWidth) - 1))
      poke(c.io.inputMemBlock(1), inputLeft1 & ((BigInt(1) << c.busWidth) - 1))
      while (peek(c.io.inputMemBlockReady).toInt == 0) {
        step(1)
      }
      step(1)
      curInputAddr += c.busWidth / 8
      inputLeft0 >>= c.busWidth
      inputLeft1 >>= c.busWidth
      inputBitsLeft -= c.busWidth
    }
    poke(c.io.inputMemBlockValid(0), false)
    poke(c.io.inputMemBlockValid(1), false)
  }

  var outputLeft = output._2
  var outputBitsLeft = output._1
  var curOutputAddr = c.outputStartAddr
  while (outputBitsLeft > 0) {
    poke(c.io.outputMemAddrReady, true)
    while (peek(c.io.outputMemAddrValid).toInt == 0) {
      step(1)
    }
    assert(peek(c.io.outputMemAddr).toInt == curOutputAddr)
    step(1)
    poke(c.io.outputMemAddrReady, false)
    poke(c.io.outputMemBlockReady, true)
    while (peek(c.io.outputMemBlockValid).toInt == 0) {
      step(1)
    }
    val mask = (BigInt(1) << Math.min(c.busWidth, outputBitsLeft)) - 1
    println("output line: " + (peek(c.io.outputMemBlock) & mask).toString(16))
    assert((peek(c.io.outputMemBlock) & mask) == (outputLeft & mask))
    step(1)
    poke(c.io.outputMemBlockReady, false)

    curOutputAddr += c.busWidth / 8
    outputLeft >>= c.busWidth
    outputBitsLeft -= c.busWidth
  }

  assert(peek(c.io.finished).toInt == 1)
}
