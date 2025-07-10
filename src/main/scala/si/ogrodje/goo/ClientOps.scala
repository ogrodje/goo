package si.ogrodje.goo

import zio.*
import zio.http.{Client, Request, Response}
import zio.metrics.*

object ClientOps:
  private val clientRequestsSent     = Metric.counter("client_requests_sent")
  private val clientRequestsReceived = Metric.counter("client_requests_received")
  private val clientRequestsFailed   = Metric.counter("client_requests_failed")
  private val clientRequestLatency   = Metric.histogram(
    "client_request_latency_seconds",
    MetricKeyType.Histogram.Boundaries.fromChunk(
      Chunk(
        0.100, // 100ms
        0.200, // 200ms
        0.500, // 500ms
        1.000, // 1s
        2.000, // 2s
        5.000  // 5s
        // +Inf is implicit
      )
    )
  )

  private def tagsFromRequest(request: Request): Set[MetricLabel] =
    request.url.host
      .map(host =>
        Map(
          "host"   -> host,
          "method" -> request.method.toString
          // "path"   -> request.url.path.toString
        )
      )
      .getOrElse(Map.empty)
      .map((k, v) => MetricLabel(k, v))
      .toSet

  extension (client: Client)
    def requestMetered(request: Request): RIO[Scope, Response] = for
      _             <- ZIO.unit
      tags           = tagsFromRequest(request)
      _             <- clientRequestsSent.tagged(tags).increment
      (_, response) <-
        client
          .request(request)
          .timed
          .tapEither {
            case Left(_)                     => clientRequestsFailed.tagged(tags).increment
            case Right((duration, response)) =>
              val statusTags = tags ++ Set(MetricLabel("status_code", response.status.text))
              clientRequestsReceived.tagged(statusTags).increment *>
                clientRequestLatency.tagged(statusTags).update(duration.toSeconds.toDouble)
          }
    yield response
