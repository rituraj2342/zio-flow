/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.runtime.internal.executor

import zio.flow.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.flow._
import zio.flow.mock.MockedOperation
import zio.flow.runtime.internal._
import zio.flow.runtime._
import zio.schema.Schema
import zio.test.{Live, Spec, TestClock, TestEnvironment, TestResult}
import zio.{
  Cause,
  Chunk,
  Clock,
  Duration,
  Exit,
  FiberFailure,
  FiberId,
  FiberRefs,
  LogLevel,
  LogSpan,
  Promise,
  Queue,
  Runtime,
  Scope,
  Trace,
  Unsafe,
  ZIO,
  ZLayer,
  ZLogger,
  durationInt
}

import java.util.concurrent.atomic.AtomicInteger

trait PersistentExecutorBaseSpec extends ZIOFlowBaseSpec {

  private val counter      = new AtomicInteger(0)
  protected val unit: Unit = ()

  def flowSpec: Spec[TestEnvironment with IndexedStore with DurableLog with KeyValueStore with Configuration, Any]

  override def spec: Spec[TestEnvironment with Scope, Any] =
    flowSpec
      .provideSome[TestEnvironment](
        IndexedStore.inMemory,
        DurableLog.layer,
        KeyValueStore.inMemory,
        Configuration.inMemory,
        Runtime.removeDefaultLoggers,
        Runtime.addLogger(TestFlowLogger.filterLogLevel(_ >= LogLevel.Debug))
      )

  protected def testFlowAndLogsExit[E: Schema, A: Schema](
    label: String,
    periodicAdjustClock: Option[Duration],
    gcPeriod: Duration = 5.minutes,
    maxCount: Int = 30
  )(
    flow: ZFlow[Any, E, A]
  )(assert: (Exit[E, A], Chunk[String]) => TestResult, mock: MockedOperation) =
    test(label) {
      val wfId = "wf" + counter.incrementAndGet().toString
      for {
        _        <- ZIO.logDebug(s"=== testFlowAndLogsExit $label started [$wfId] === ")
        logQueue <- Queue.unbounded[String]
        runtime  <- ZIO.runtime[Any]
        logger = new ZLogger[String, Any] {

                   override def apply(
                     trace: Trace,
                     fiberId: FiberId,
                     logLevel: LogLevel,
                     message: () => String,
                     cause: Cause[Any],
                     context: FiberRefs,
                     spans: List[LogSpan],
                     annotations: Map[String, String]
                   ): String = Unsafe.unsafe { implicit u =>
                     val msg = message()
                     runtime.unsafe.run(logQueue.offer(message()).unit).getOrThrowFiberFailure()
                     msg
                   }
                 }
        fiber <-
          flow
            .evaluateTestPersistent(wfId, mock, gcPeriod)
            .provideSomeLayer[DurableLog with KeyValueStore with Configuration](Runtime.addLogger(logger))
            .exit
            .fork
        flowResult <- periodicAdjustClock match {
                        case Some(value) =>
                          waitAndPeriodicallyAdjustClock("flow result", 1.second, value, maxCount)(fiber.join)
                        case None => fiber.join
                      }
        logLines <- logQueue.takeAll
      } yield assert(flowResult, logLines)
    }

  protected def testFlowAndLogs[E: Schema, A: Schema](
    label: String,
    periodicAdjustClock: Option[Duration] = None,
    gcPeriod: Duration = 5.minutes,
    maxCount: Int = 30
  )(flow: ZFlow[Any, E, A])(assert: (A, Chunk[String]) => TestResult, mock: MockedOperation = MockedOperation.Empty) =
    testFlowAndLogsExit(label, periodicAdjustClock, gcPeriod, maxCount)(flow)(
      { case (exit, logs) =>
        exit.foldExit(cause => throw FiberFailure(cause), result => assert(result, logs))
      },
      mock
    )

  protected def testFlow[E: Schema, A: Schema](
    label: String,
    periodicAdjustClock: Option[Duration] = None,
    gcPeriod: Duration = 5.minutes,
    maxCount: Int = 30
  )(
    flow: ZFlow[Any, E, A]
  )(
    assert: A => TestResult,
    mock: MockedOperation = MockedOperation.Empty
  ) =
    testFlowAndLogs(label, periodicAdjustClock, gcPeriod, maxCount)(flow)({ case (result, _) => assert(result) }, mock)

  protected def testFlowExit[E: Schema, A: Schema](
    label: String,
    periodicAdjustClock: Option[Duration] = None,
    gcPeriod: Duration = 5.minutes,
    maxCount: Int = 30
  )(
    flow: ZFlow[Any, E, A]
  )(
    assert: Exit[E, A] => TestResult,
    mock: MockedOperation = MockedOperation.Empty
  ) =
    testFlowAndLogsExit(label, periodicAdjustClock, gcPeriod, maxCount)(flow)(
      { case (result, _) => assert(result) },
      mock
    )

  protected def testRestartFlowAndLogs[E: Schema, A: Schema](
    label: String
  )(flow: ZFlow[Any, Nothing, Unit] => ZFlow[Any, E, A])(assert: (A, Chunk[String], Chunk[String]) => TestResult) =
    test(label) {
      for {
        _            <- ZIO.logDebug(s"=== testRestartFlowAndLogs $label started === ")
        logQueue     <- Queue.unbounded[String]
        runtime      <- ZIO.runtime[Any]
        breakPromise <- Promise.make[Nothing, Unit]
        logger = new ZLogger[String, Any] {

                   override def apply(
                     trace: Trace,
                     fiberId: FiberId,
                     logLevel: LogLevel,
                     message: () => String,
                     cause: Cause[Any],
                     context: FiberRefs,
                     spans: List[LogSpan],
                     annotations: Map[String, String]
                   ): String = Unsafe.unsafe { implicit u =>
                     val msg = message()
                     runtime.unsafe.run {
                       msg match {
                         case "!!!BREAK!!!" => breakPromise.succeed(())
                         case _             => logQueue.offer(msg).unit
                       }
                     }.getOrThrowFiberFailure()
                     msg
                   }
                 }
        results <- {
          val break: ZFlow[Any, Nothing, Unit] =
            (ZFlow.log("!!!BREAK!!!") *>
              ZFlow.waitTill(Instant.ofEpochSecond(100L)))
          val finalFlow = flow(break)
          for {
            fiber1 <- finalFlow
                        .evaluateTestPersistent(label)
                        .provideSomeLayer[DurableLog with KeyValueStore with Configuration](Runtime.addLogger(logger))
                        .fork
            _ <- ZIO.logDebug(s"Adjusting clock by 20s")
            _ <- TestClock.adjust(20.seconds)
            _ <- waitAndPeriodicallyAdjustClock("break event", 1.second, 10.seconds, 30) {
                   breakPromise.await
                 }
            _         <- ZIO.logDebug("Interrupting executor")
            _         <- fiber1.interrupt
            logLines1 <- logQueue.takeAll
            fiber2 <- finalFlow
                        .evaluateTestPersistent(label)
                        .provideSomeLayer[DurableLog with KeyValueStore with Configuration](Runtime.addLogger(logger))
                        .fork
            _ <- ZIO.logDebug(s"Adjusting clock by 200s")
            _ <- TestClock.adjust(200.seconds)
            result <- waitAndPeriodicallyAdjustClock("executor to finish", 1.second, 10.seconds, 30) {
                        fiber2.join
                      }
            logLines2 <- logQueue.takeAll
          } yield (result, logLines1, logLines2)
        }
      } yield assert.tupled(results)
    }

  protected def testGCFlow[E: Schema, A: Schema](
    label: String
  )(
    flow: ZFlow[Any, Nothing, Unit] => ZFlow[Any, E, A]
  )(assert: (A, Map[ScopedRemoteVariableName, Chunk[Timestamp]]) => TestResult) =
    test(label) {
      for {
        _            <- ZIO.logDebug(s"=== testGCFlow $label started === ")
        runtime      <- ZIO.runtime[Any]
        breakPromise <- Promise.make[Nothing, Unit]
        logger = new ZLogger[String, Any] {

                   override def apply(
                     trace: Trace,
                     fiberId: FiberId,
                     logLevel: LogLevel,
                     message: () => String,
                     cause: Cause[Any],
                     context: FiberRefs,
                     spans: List[LogSpan],
                     annotations: Map[String, String]
                   ): String = Unsafe.unsafe { implicit u =>
                     val msg = message()
                     runtime.unsafe.run {
                       msg match {
                         case "!!!BREAK!!!" => breakPromise.succeed(())
                         case _             => ZIO.unit
                       }
                     }.getOrThrowFiberFailure()
                     msg
                   }
                 }
        results <- {
          val break: ZFlow[Any, Nothing, Unit] =
            (ZFlow.log("!!!BREAK!!!") *>
              ZFlow.waitTill(Instant.ofEpochSecond(100L)))
          val finalFlow = flow(break)

          ZIO.scoped[Live with DurableLog with KeyValueStore with Configuration] {
            for {
              pair <- finalFlow
                        .submitTestPersistent(label)
                        .provideSomeLayer[Scope with DurableLog with KeyValueStore with Configuration](
                          Runtime.addLogger(logger)
                        )
              (executor, fiber) = pair
              _                <- ZIO.logDebug(s"Adjusting clock by 20s")
              _                <- TestClock.adjust(20.seconds)
              _ <- waitAndPeriodicallyAdjustClock("break event", 1.second, 10.seconds, 30) {
                     breakPromise.await
                   }
              _ <- ZIO.logDebug("Forcing GC")
              _ <- executor.forceGarbageCollection()
              vars <-
                RemoteVariableKeyValueStore.allStoredVariables
                  .mapZIO(scopedVar =>
                    RemoteVariableKeyValueStore.getAllTimestamps(scopedVar.name, scopedVar.scope).runCollect.map {
                      timestamps =>
                        scopedVar -> timestamps
                    }
                  )
                  .runCollect
                  .provideSome[DurableLog with KeyValueStore](
                    RemoteVariableKeyValueStore.layer,
                    Configuration.inMemory,
                    ZLayer(
                      ZIO
                        .service[Configuration]
                        .map(config => ExecutionEnvironment(zio.flow.runtime.serialization.json, config))
                    ) // TODO: this should not be recreated here
                  )
              _ <- ZIO.logDebug(s"Adjusting clock by 200s")
              _ <- TestClock.adjust(200.seconds)
              result <- waitAndPeriodicallyAdjustClock("executor to finish", 1.second, 10.seconds, 30) {
                          fiber.join
                        }
            } yield (result, vars.toMap)
          }
        }
      } yield assert.tupled(results)
    }

  protected def waitAndPeriodicallyAdjustClock[E, A](
    description: String,
    duration: Duration,
    adjustment: Duration,
    maxCount: Int
  )(wait: ZIO[Any, E, A]): ZIO[Live, E, A] =
    for {
      _           <- ZIO.logTrace(s"Test runner waiting for $description")
      maybeResult <- wait.timeout(duration).withClock(Clock.ClockLive)
      result <- maybeResult match {
                  case Some(result) => ZIO.succeed(result)
                  case None if maxCount > 0 =>
                    for {
//                      _      <- ZIO.logDebug(s"Adjusting clock by $adjustment")
                      _ <- TestClock.adjust(adjustment)
//                      now    <- Clock.instant
//                      _      <- ZIO.logDebug(s"T=$now")
                      result <- waitAndPeriodicallyAdjustClock(description, duration, adjustment, maxCount - 1)(wait)
                    } yield result
                  case _ =>
                    ZIO.dieMessage(s"Test runner timed out waiting for $description")
                }
    } yield result

  object TestFlowLogger extends ZLogger[String, Unit] {
    def apply(
      trace: Trace,
      fiberId: FiberId,
      logLevel: LogLevel,
      message0: () => String,
      cause: Cause[Any],
      context: FiberRefs,
      spans0: List[LogSpan],
      annotations: Map[String, String]
    ): Unit = {
      val sb = new StringBuilder()

      val color = logLevel match {
        case LogLevel.Trace   => Console.BLUE
        case LogLevel.Info    => Console.GREEN
        case LogLevel.Warning => Console.YELLOW
        case LogLevel.Error   => Console.RED
        case _                => Console.WHITE
      }
      sb.append(color)

      sb.append("[" + annotations.getOrElse("vts", "") + "] ")
      sb.append("[" + annotations.getOrElse("flowId", "") + "] ")
      sb.append("[" + annotations.getOrElse("txId", "") + "] ")

      val padding = math.max(0, 30 - sb.size)
      sb.append(" " * padding)
      sb.append(message0())

      val remainingAnnotations = annotations - "vts" - "flowId" - "txId"
      if (remainingAnnotations.nonEmpty) {
        sb.append(" ")

        val it    = remainingAnnotations.iterator
        var first = true

        while (it.hasNext) {
          if (first) {
            first = false
          } else {
            sb.append(" ")
          }

          val (key, value) = it.next()

          sb.append(key)
          sb.append("=")
          sb.append(value)
        }
      }

      if (cause != null && cause != Cause.empty) {
        sb.append("\nCause:")
          .append(cause.prettyPrint)
          .append("\n")
      }

      sb.append(Console.RESET)
      println(sb.toString())
    }
  }
}
