package com.wavesplatform.events.grpc

import com.wavesplatform.events.BlockchainUpdated
import com.wavesplatform.events.grpc.protobuf.{BlockchainUpdatesApiGrpc, GetForHeightRequest, GetForHeightResponse, SubscribeEvent, SubscribeRequest}
import com.wavesplatform.events.protobuf.serde._
import com.wavesplatform.events.repo.UpdatesRepo
import com.wavesplatform.utils.ScorexLogging
import io.grpc.stub.StreamObserver
import monix.execution.{Ack, Scheduler}
import io.grpc.{Status, StatusRuntimeException}
import monix.execution.Ack.{Continue, Stop}
import monix.reactive.Observer

import scala.concurrent.Future
import scala.util.{Failure, Success}

class BlockchainUpdatesApiGrpcImpl(repo: UpdatesRepo.Read with UpdatesRepo.Stream)(implicit sc: Scheduler)
    extends BlockchainUpdatesApiGrpc.BlockchainUpdatesApi
    with ScorexLogging {
  override def getForHeight(request: GetForHeightRequest): Future[GetForHeightResponse] = Future {
    repo.updateForHeight(request.height) match {
      case Success(Some(upd)) => GetForHeightResponse(Some(upd.protobuf))
      case Success(None)      => throw new StatusRuntimeException(Status.NOT_FOUND)
      case Failure(exception) =>
        log.error(s"BlockchainUpdates failed to get block append for height ${request.height}", exception)
        throw new StatusRuntimeException(Status.INTERNAL)
    }
  }

  override def subscribe(request: SubscribeRequest, responseObserver: StreamObserver[SubscribeEvent]): Unit = {
    if (request.fromHeight <= 0) {
      responseObserver.onError(new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("height must be a positive integer")))
    } else {
      repo
        .stream(request.fromHeight)
        .subscribe({
          log.info(s"BlockchainUpdates started streaming updates from ${request.fromHeight}")
          new Observer[BlockchainUpdated] {
            override def onNext(elem: BlockchainUpdated): Future[Ack] = {
              log.info(s"Sending update ${elem.toHeight}, ${elem.toId}")
              try {
                responseObserver.onNext(SubscribeEvent(update = Some(elem.protobuf)))
                Future.successful(Continue)
              } catch {
                case ex: StatusRuntimeException if ex.getStatus == Status.CANCELLED =>
                  log.info("BlockchainUpdates stream cancelled by client")
                  Future.successful(Stop)
                case ex: Throwable =>
                  log.error("BlockchainUpdates gRPC streaming error", ex)
                  Future.successful(Stop)
              }
            }
            override def onError(ex: Throwable): Unit = {
              log.error("BlockchainUpdates gRPC streaming error", ex)
              responseObserver.onError(new StatusRuntimeException(Status.INTERNAL))
            }
            override def onComplete(): Unit = responseObserver.onCompleted()
          }
        })
    }
  }

}
