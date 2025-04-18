package si.ogrodje.goo

import zio.*
import zio.http.*
import java.nio.file.Path as JPath

final case class VCR private (
  private val client: Client,
  private val host: Option[String] = None,
  private val recording: Boolean = false
):
  private def requestToFileName(req: Request): (String, String, JPath) =
    val hostname   = req.url.host.getOrElse("unknown_host")
    val urlPart    = req.url.path.toString.replaceAll("[^a-zA-Z0-9]", "_")
    val methodPart = req.method.toString
    (hostname, methodPart, JPath.of(s"${methodPart}_${hostname}_$urlPart.response"))

  def readFileContentAsString(path: JPath): RIO[Scope, String] =
    for source <- ZIO.fromAutoCloseable(ZIO.attempt(scala.io.Source.fromFile(path.toFile)))
    yield source.mkString

  def sendRequestAndStoreResponse(req: Request, path: JPath): RIO[Scope, JPath] = for
    _            <- ZIO.debug(s"sendRequestAndStoreResponse --> ${req.url} + ${req.url.host}")
    response     <- client.request(req)
    responseBody <- response.body.asString
    _            <- ZIO.logInfo(s"Storing response to ${path.toAbsolutePath}")
    _            <- ZIO.attempt(java.nio.file.Files.write(path, responseBody.getBytes))
  yield path

  def request(req: Request): ZIO[Any, Throwable, Response] = ZIO.scoped:
    for
      _                             <- ZIO.logInfo(s"Requesting via VCR: ${request}")
      request                        = host.fold(req)(h => req.updateURL(_ => req.url.host(h)))
      key @ (hostname, method, path) = requestToFileName(request)

      _ <- ZIO.debug(s"HOSTNAME: ${hostname}")

      maybeRecordedContent <- readFileContentAsString(path).option.debug("content")

      maybeResponse <-
        ZIO.whenCase(maybeRecordedContent) {
          case Some(content) => ZIO.some(Response.html(content))
          case None          =>
            for
              _        <- ZIO.logInfo(s"Requesting w/ ${request}")
              response <- ZIO.when(recording) {
                            sendRequestAndStoreResponse(request, path) *> readFileContentAsString(path).map(
                              Response.html(_)
                            )
                          }
            yield response
        }

      response <- ZIO.fromOption(maybeResponse.flatten).orElseFail(new Exception(s"No response for $key"))
    yield response

object VCR:

  def request(request: Request): ZIO[VCR, Throwable, Response] = ZIO.serviceWithZIO[VCR](_.request(request))

  def live(host: Option[String] = None, recording: Boolean = false): TaskLayer[VCR] =
    ZLayer.fromZIO:
      {
        for client <- ZIO.serviceWith[Client] { client =>
                        host.fold(client)(h => client.host(h))
                      }
        yield VCR(client, host, recording)
      }.provideSome(Client.default)

  def recording: TaskLayer[VCR] = live(recording = true)
