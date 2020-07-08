package edu.stanford.fpgacube

import chisel3.iotesters.Driver
import scala.collection.mutable.ArrayBuffer

object Tests {
  val tests = Map(
    "Test1" -> { (backendName: String) =>
      Driver(() => new StreamingWrapper(0, 1000000000, 8, 4), backendName) {
        (c) => {
          new StreamingWrapperTests(c, (0, BigInt(0)), (0, BigInt(0)))
        }
      }
    }
  )
  def main(args: Array[String]): Unit = {
    // Choose the default backend based on what is available.
    lazy val firrtlTerpBackendAvailable: Boolean = {
      try {
        val cls = Class.forName("chisel3.iotesters.FirrtlTerpBackend")
        cls != null
      } catch {
        case e: Throwable => false
      }
    }
    lazy val defaultBackend = if (firrtlTerpBackendAvailable) {
      "firrtl"
    } else {
      ""
    }
    val backendName = defaultBackend.split(" ").head
    val testsToRun = if (args.isEmpty || args.head == "all") {
      tests.keys.toSeq.sorted.toArray
    }
    else {
      args
    }

    var successful = 0
    val errors = new ArrayBuffer[String]
    for (testName <- testsToRun) {
      tests.get(testName) match {
        case Some(test) =>
          println(s"Starting test $testName")
          try {
            if (test(backendName)) {
              successful += 1
            }
            else {
              errors += s"Test $testName: error occurred"
            }
          } catch {
            case exception: Exception =>
              exception.printStackTrace()
              errors += s"Test $testName: exception ${exception.getMessage}"
            case t : Throwable =>
              t.printStackTrace()
              errors += s"Test $testName: throwable ${t.getMessage}"
          }
        case _ =>
          errors += s"Bad test name: $testName"
      }

    }
    if (successful > 0) {
      println(s"Tests passing: $successful")
    }
    if (errors.nonEmpty) {
      println("=" * 80)
      println(s"Errors: ${errors.length}: in the following tests")
      println(errors.mkString("\n"))
      println("=" * 80)
      System.exit(1)
    }
  }
}

