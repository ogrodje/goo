package si.ogrodje.goo.server

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import si.ogrodje.goo.AppConfig
import zio.ZIO.{logInfo, serviceWith, serviceWithZIO}
import zio.{Duration, Ref, Schedule, Scope, Task, UIO, ZIO, ZLayer}
import zio.http.{Client, Request}

import java.math.BigInteger
import java.security.{KeyFactory, PublicKey}
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

final case class Key(
  kid: String,
  kty: String,
  alg: String,
  use: String,
  x5c: List[String],
  x5t: String,
  `x5t#S256`: String,
  n: String,
  e: String
)

final case class Certs(
  keys: List[Key]
)
object Certs:
  val empty: Certs = Certs(List.empty)

final case class Keycloak private (
  private val client: Client,
  private val currentCerts: Ref[Certs]
):
  private val base64UrlDecode: String => Array[Byte] = Base64.getUrlDecoder.decode

  private def certs: UIO[Certs] = currentCerts.get

  private def rs256Key: Task[PublicKey] = certs
    .map(_.keys.find(_.alg == "RS256").get)
    .flatMap: key =>
      ZIO.attempt:
        val spec = new RSAPublicKeySpec(
          new BigInteger(1, base64UrlDecode(key.n)),
          new BigInteger(1, base64UrlDecode(key.e))
        )
        KeyFactory.getInstance("RSA").generatePublic(spec)

object Keycloak:
  private given Decoder[Key]   = deriveDecoder[Key]
  private given Decoder[Certs] = deriveDecoder[Certs]

  def certs: ZIO[Keycloak, Nothing, Certs]          = serviceWithZIO[Keycloak](_.certs)
  def rs256Key: ZIO[Keycloak, Throwable, PublicKey] = serviceWithZIO[Keycloak](_.rs256Key)

  private def collectCerts(client: Client) = for
    _        <- ZIO.unit
    request   = Request.get("/protocol/openid-connect/certs")
    response <- client.request(request)
    json     <- response.body.asString.flatMap(body => ZIO.fromEither(io.circe.parser.parse(body)))
    certs    <- ZIO.fromEither(json.hcursor.as[Certs])
    _        <- logInfo(s"Keycloak certs refreshed.")
  yield certs

  def live: ZLayer[Client, Throwable, Keycloak] = ZLayer.scoped:
    for
      keycloakConfig @ (endpoint, realm) <- AppConfig.keycloakConfig
      _                                  <- logInfo(s"Connecting with config: $keycloakConfig")
      client                             <- serviceWith[Client](_.url(endpoint.addPath(s"/realms/$realm")))
      certsRef                           <- Ref.make(Certs.empty)
      _                                  <- collectCerts(client).flatMap(certsRef.set)

      reloadFib <-
        collectCerts(client)
          .flatMap(certsRef.set)
          .repeat(Schedule.fixed(Duration.fromSeconds(4 * 60L))) // Every 4 minutes
          .fork
      _         <-
        Scope.addFinalizer(reloadFib.interrupt <* logInfo("Keycloak client refreshing stopped."))
    yield Keycloak(client, certsRef)
