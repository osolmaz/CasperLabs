package coop.rchain.node

import scala.collection.JavaConverters._
import scala.tools.jline.console._
import completer.StringsCompleter

import cats.implicits._

import coop.rchain.casper.util.comm._
import coop.rchain.catscontrib._
import coop.rchain.catscontrib.TaskContrib._
import coop.rchain.casper.util.BondingUtil
import coop.rchain.comm._
import coop.rchain.node.configuration._
import coop.rchain.node.diagnostics.client.GrpcDiagnosticsService
import coop.rchain.node.effects._
import coop.rchain.shared._
import coop.rchain.shared.StringOps._

import monix.eval.Task
import monix.execution.Scheduler

object Main {

  private implicit val logSource: LogSource = LogSource(this.getClass)
  private implicit val log: Log[Task]       = effects.log

  def main(args: Array[String]): Unit = {

    implicit val scheduler: Scheduler = Scheduler.computation(
      Math.max(java.lang.Runtime.getRuntime.availableProcessors(), 2),
      "node-runner",
      reporter = UncaughtExceptionLogger
    )

    val exec: Task[Unit] =
      for {
        conf <- Configuration(args)
        _    <- Task.defer(mainProgram(conf))
      } yield ()

    exec.unsafeRunSync
  }

  private def mainProgram(conf: Configuration)(implicit scheduler: Scheduler): Task[Unit] = {
    implicit val diagnosticsService: GrpcDiagnosticsService =
      new diagnostics.client.GrpcDiagnosticsService(
        conf.grpcServer.host,
        conf.grpcServer.portInternal,
        conf.server.maxMessageSize
      )
    implicit val deployService: GrpcDeployService =
      new GrpcDeployService(
        conf.grpcServer.host,
        conf.grpcServer.portExternal,
        conf.server.maxMessageSize
      )

    implicit val time: Time[Task]           = effects.time
    implicit val consoleIO: ConsoleIO[Task] = (str: String) => Task(println(str))

    val program = conf.command match {
      case Diagnostics => diagnostics.client.Runtime.diagnosticsProgram[Task]
      case Deploy(address, phlo, phloPrice, nonce, location) =>
        DeployRuntime.deployFileProgram[Task](address, phlo, phloPrice, nonce, location)
      case DeployDemo        => DeployRuntime.deployDemoProgram[Task]
      case Propose           => DeployRuntime.propose[Task]()
      case ShowBlock(hash)   => DeployRuntime.showBlock[Task](hash)
      case ShowBlocks(depth) => DeployRuntime.showBlocks[Task](depth)
      case Run               => nodeProgram(conf)
      case BondingDeployGen(bondKey, ethAddress, amount, secKey, pubKey) =>
        BondingUtil.bondingDeploy[Task](bondKey, ethAddress, amount, secKey, pubKey)
      case FaucetBondingDeployGen(amount, sigAlgorithm, secKey, pubKey) =>
        BondingUtil.writeFaucetBasedRhoFiles[Task](amount, sigAlgorithm, secKey, pubKey)
      case _ => conf.printHelp()
    }

    program.doOnFinish(
      _ =>
        Task.delay {
          diagnosticsService.close()
          deployService.close()
        }
    )
  }

  private def nodeProgram(conf: Configuration)(implicit scheduler: Scheduler): Task[Unit] = {
    val node =
      for {
        _       <- log.info(VersionInfo.get).toEffect
        runtime <- NodeRuntime(conf)
        _       <- runtime.main
      } yield ()

    node.value >>= {
      case Right(_) =>
        Task.unit
      case Left(CouldNotConnectToBootstrap) =>
        log.error("Node could not connect to bootstrap node.")
      case Left(InitializationError(msg)) =>
        log.error(msg)
        Task.delay(System.exit(-1))
      case Left(error) =>
        log.error(s"Failed! Reason: '$error")
    }
  }
}