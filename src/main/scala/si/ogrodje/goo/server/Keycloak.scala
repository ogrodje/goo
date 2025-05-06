package si.ogrodje.goo.server

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import si.ogrodje.goo.AppConfig
import zio.ZIO.logInfo
import zio.{Ref, ZIO, ZLayer}
import zio.http.{Client, Request}

import java.math.BigInteger
import java.security.{KeyFactory, PublicKey}
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import zio.{Duration, Schedule, Scope}

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
  def certs = currentCerts.get

  private val base64UrlDecode: String => Array[Byte] = Base64.getUrlDecoder.decode

  def rs256Key: ZIO[Any, Nothing, PublicKey] = certs
    .map(_.keys.find(_.alg == "RS256").get)
    .map: key =>
      val spec = new RSAPublicKeySpec(
        new BigInteger(1, base64UrlDecode(key.n)),
        new BigInteger(1, base64UrlDecode(key.e))
      )
      KeyFactory.getInstance("RSA").generatePublic(spec)

object Keycloak:
  private given Decoder[Key]   = deriveDecoder[Key]
  private given Decoder[Certs] = deriveDecoder[Certs]

  private def collectCerts(client: Client) = for
    response <- client.request(Request.get("/protocol/openid-connect/certs"))
    json     <- response.body.asString.flatMap(body => ZIO.fromEither(io.circe.parser.parse(body)))
    certs    <- ZIO.fromEither(json.hcursor.as[Certs])
    _        <- logInfo(s"Keycloak certs refreshed.")
  yield certs

  def live: ZLayer[Client, Throwable, Keycloak] = ZLayer.scoped:
    for
      (endpoint, realm) <- AppConfig.keycloakConfig
      client            <- ZIO.serviceWith[Client](_.url(endpoint.addPath(s"/realms/$realm")))
      certsRef          <- Ref.make(Certs.empty)
      reloadFib         <-
        collectCerts(client)
          .flatMap(certsRef.set)
          .repeat(Schedule.fixed(Duration.fromSeconds(4 * 60L))) // Every 4 minutes
          .fork
      _                 <-
        Scope.addFinalizer(reloadFib.interrupt <* logInfo("Keycloak client refreshing stopped."))
    yield Keycloak(client, certsRef)
