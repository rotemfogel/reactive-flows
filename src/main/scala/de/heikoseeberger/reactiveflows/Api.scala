/*
 * Copyright 2015 Heiko Seeberger
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

package de.heikoseeberger.reactiveflows

import java.net.InetSocketAddress

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Status }
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.{ Directives, Route }
import akka.pattern.{ ask, pipe }
import akka.stream.scaladsl.Source
import akka.stream.{ Materializer, OverflowStrategy }
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

object Api {

  final case class AddPostRequest(text: String)

  final val Name = "api"

  def apply(address: String,
            port: Int,
            flowFacade: ActorRef,
            flowFacadeTimeout: FiniteDuration,
            mediator: ActorRef,
            eventBufferSize: Int,
            eventHeartbeat: FiniteDuration)(implicit mat: Materializer): Props =
    Props(
      new Api(address,
              port,
              flowFacade,
              flowFacadeTimeout,
              mediator,
              eventBufferSize,
              eventHeartbeat)
    )

  def route(flowFacade: ActorRef,
            flowFacadeTimeout: Timeout,
            mediator: ActorRef,
            eventBufferSize: Int,
            eventHeartbeat: FiniteDuration)(implicit ec: ExecutionContext): Route = {
    import Directives._
    import EventStreamMarshalling._
    import FailFastCirceSupport._
    import io.circe.generic.auto._
    import io.circe.syntax._

    def assets = {
      def redirectSingleSlash =
        pathSingleSlash {
          get {
            redirect("index.html", PermanentRedirect)
          }
        }
      getFromResourceDirectory("web") ~ redirectSingleSlash
    }

    def flows = {
      import FlowFacade._
      implicit val timeout = flowFacadeTimeout
      pathPrefix("flows") {
        pathEnd {
          get {
            complete((flowFacade ? GetFlows).mapTo[Flows].map(_.flows))
          } ~
          post {
            entity(as[AddFlow]) { addFlow =>
              onSuccess(flowFacade ? addFlow) {
                case ic: InvalidCommand => complete(BadRequest -> ic)
                case fe: FlowExists     => complete(Conflict -> fe)
                case fa: FlowAdded      => completeCreated(fa.desc.name, fa)
              }
            }
          }
        } ~
        pathPrefix(Segment) { flowName =>
          pathEnd {
            delete {
              onSuccess(flowFacade ? RemoveFlow(flowName)) {
                case ic: InvalidCommand => complete(BadRequest -> ic)
                case fu: FlowUnknown    => complete(NotFound -> fu)
                case _: FlowRemoved     => complete(NoContent)
              }
            }
          } ~
          path("posts") {
            get {
              parameters('seqNo.as[Long] ? Long.MaxValue, 'count.as[Int] ? 1) { (id, count) =>
                onSuccess(flowFacade ? GetPosts(flowName, id, count)) {
                  case ic: InvalidCommand => complete(BadRequest -> ic)
                  case fu: FlowUnknown    => complete(NotFound -> fu)
                  case Flow.Posts(posts)  => complete(posts)
                }
              }
            } ~
            post {
              entity(as[AddPostRequest]) {
                case AddPostRequest(text) =>
                  onSuccess(flowFacade ? AddPost(flowName, text)) {
                    case ic: InvalidCommand => complete(BadRequest -> ic)
                    case fu: FlowUnknown    => complete(NotFound -> fu)
                    case ma: Flow.PostAdded => completeCreated(ma.post.seqNo.toString, ma)
                  }
              }
            }
          }
        }
      }
    }

    def flowsEvents = {
      import FlowFacade._
      def toServerSentEvent(event: Event) =
        event match {
          case FlowAdded(desc)   => ServerSentEvent(desc.asJson.noSpaces, "added")
          case FlowRemoved(name) => ServerSentEvent(name, "removed")
        }
      path("flows-events") {
        get {
          complete {
            events(toServerSentEvent)
          }
        }
      }
    }

    def flowEvents = {
      import Flow._
      def toServerSentEvent(event: Event) =
        event match {
          case postAdded: PostAdded => ServerSentEvent(postAdded.asJson.noSpaces, "added")
        }
      path("flow-events") {
        get {
          complete {
            events(toServerSentEvent)
          }
        }
      }
    }

    def completeCreated[A: ToEntityMarshaller](id: String, a: A) =
      extractUri { uri =>
        val location = Location(uri.withPath(uri.path / id))
        complete((Created, Vector(location), a))
      }

    def events[A: ClassTag](toServerSentEvent: A => ServerSentEvent) =
      Source
        .actorRef[A](eventBufferSize, OverflowStrategy.dropHead)
        .map(toServerSentEvent)
        .keepAlive(eventHeartbeat, () => ServerSentEvent.heartbeat)
        .mapMaterializedValue(source => mediator ! Subscribe(className[A], source))

    assets ~ flows ~ flowsEvents ~ flowEvents
  }
}

final class Api(address: String,
                port: Int,
                flowFacade: ActorRef,
                flowFacadeTimeout: FiniteDuration,
                mediator: ActorRef,
                eventBufferSize: Int,
                eventHeartbeat: FiniteDuration)(implicit mat: Materializer)
    extends Actor
    with ActorLogging {
  import Api._
  import context.dispatcher

  Http(context.system)
    .bindAndHandle(route(flowFacade, flowFacadeTimeout, mediator, eventBufferSize, eventHeartbeat),
                   address,
                   port)
    .pipeTo(self)

  override def receive = {
    case Http.ServerBinding(address) => handleServerBinding(address)
    case Status.Failure(cause)       => handleBindFailure(cause)
  }

  private def handleServerBinding(address: InetSocketAddress) = {
    log.info("Listening on {}", address)
    context.become(Actor.emptyBehavior)
  }

  private def handleBindFailure(cause: Throwable) = {
    log.error(cause, s"Can't bind to $address:$port!")
    context.stop(self)
  }
}
