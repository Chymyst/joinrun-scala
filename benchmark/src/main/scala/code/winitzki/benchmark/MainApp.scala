package code.winitzki.benchmark

import code.winitzki.benchmark.Benchmarks1._
import code.winitzki.benchmark.Benchmarks4._
import code.winitzki.benchmark.Benchmarks7._
import code.winitzki.benchmark.Benchmarks9._

import code.winitzki.jc.JoinRun.{defaultJoinPool, defaultReactionPool}

object MainApp extends App {
  val version = "0.0.5"

  def run3times(task: => Long): Long = {
    val prime1 = {
      task
    }
    val prime2 = {
      task
    }
    val result = {
      task
    }
    //    println(s"timing with priming: prime1 = $prime1, prime2 = $prime2, result = $result")
    (result + prime2 + 1) / 2
  }

  val n = 50000

  val threads = 8

  println(s"Benchmark parameters: count to $n, threads = $threads")

  Seq(
  // List the benchmarks that we should run.

    s"count using JoinRun" -> benchmark1 _,
    s"count using Jiansen's Join.scala" -> benchmark2 _,
    "counter in a closure, using JoinRun" -> benchmark3 _,
    "counter in a closure, using Jiansen's Join.scala" -> benchmark2a _,
    s"${Benchmarks4.differentReactions} different reactions chained together, 2000 times" -> benchmark4_100 _,

//  "(this deadlocks) 50 different reactions chained together, using Jiansen's Join.scala" -> benchmark5_100 _,
//  "(StackOverflowError) same but with only 6 reactions, using Jiansen's Join.scala" -> benchmark5_6 _,

    s"${Benchmarks7.numberOfCounters} concurrent counters with non-blocking access" -> benchmark7 _,

//  "(this deadlocks) many concurrent counters with non-blocking access, using Jiansen's Join.scala" -> benchmark8 _,

    s"${Benchmarks9.numberOfCounters} concurrent counters with blocking access" -> benchmark9_1 _,
    
    s"${Benchmarks9.pingPongCalls} blocked threads with ping-pong calls" -> benchmark9_2 _

  ).zipWithIndex.foreach {
    case ((message, benchmark), i) => println(s"Benchmark ${i+1} took ${run3times { benchmark(n,threads) }} ms ($message)")
  }

  defaultJoinPool.shutdownNow()
  defaultReactionPool.shutdownNow()

}
