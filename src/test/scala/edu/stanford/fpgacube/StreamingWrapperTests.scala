package edu.stanford.fpgacube

import chisel3.iotesters.PeekPokeTester

class StreamingWrapperTests(c: StreamingWrapper, input: (Int, BigInt), output: (Int, BigInt)) extends PeekPokeTester(c) {
  poke(c.io.inputMemBlockValid, false)
  poke(c.io.outputMemAddrReady, false)
  poke(c.io.outputMemBlockReady, false)

  var inputLeft = input._2
  var inputBitsLeft = input._1
  var curInputAddr = c.inputStartAddr
  while (inputBitsLeft > 0) {
    poke(c.io.inputMemAddrReady, true)
    while (peek(c.io.inputMemAddrValid).toInt == 0) {
      step(1)
    }
    assert(peek(c.io.inputMemAddr).toInt == curInputAddr)
    step(1)
    poke(c.io.inputMemAddrReady, false)
    poke(c.io.inputMemBlock, inputLeft & ((BigInt(1) << 512) - 1))
    poke(c.io.inputMemBlockValid, true)
    while (peek(c.io.inputMemBlockReady).toInt == 0) {
      step(1)
    }
    step(1)
    poke(c.io.inputMemBlockValid, false)

    curInputAddr += 64
    inputLeft >>= 512
    inputBitsLeft -= 512
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
    val mask = (BigInt(1) << Math.min(512, outputBitsLeft)) - 1
    println("output line: " + (peek(c.io.outputMemBlock) & mask).toString(16))
    assert((peek(c.io.outputMemBlock) & mask) == (outputLeft & mask))
    step(1)
    poke(c.io.outputMemBlockReady, false)

    curOutputAddr += 64
    outputLeft >>= 512
    outputBitsLeft -= 512
  }

  assert(peek(c.io.finished).toInt == 1)
}
