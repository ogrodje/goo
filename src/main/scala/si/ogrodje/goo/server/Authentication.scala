package si.ogrodje.goo.server

import io.circe
import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, Json}
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}
import zio.Config.Secret
import zio.ZIO.{fromEither, fromOption, fromTry, logError}
import zio.http.*
import zio.schema.{DeriveSchema, Schema}
import zio.{Task, ZIO}

import java.util.UUID

trait User:
  def userId: UUID

final case class AuthUser(
  userId: UUID,
  name: String,
  `given_name`: String,
  `family_name`: String,
  email: String,
  `preferred_username`: String,
  scope: String
) extends User

object AuthUser:
  given schema: Schema[AuthUser]  = DeriveSchema.gen
  private given Decoder[AuthUser] = deriveDecoder[AuthUser]

  def fromJwtClaim(claim: JwtClaim): Task[AuthUser] = for
    json   <- fromEither(io.circe.parser.parse(claim.content))
    userId <- fromOption(claim.subject).orElseFail(new RuntimeException("Missing user ID"))
    user   <- fromEither(json.deepMerge(Json.obj("userId" -> Json.fromString(userId))).as[AuthUser])
  yield user

object Authentication:
  private def decodeToken(secret: Secret): ZIO[Keycloak, Throwable, JwtClaim] = for
    publicKey <- Keycloak.rs256Key
    token      = secret.stringValue
    claim     <- fromTry(JwtCirce.decode(token, publicKey, Seq(JwtAlgorithm.RS256)))
  yield claim

  private val unauthorized = Response.unauthorized("Invalid or expired token.")

  val Authenticated: HandlerAspect[Keycloak, AuthUser] =
    HandlerAspect.interceptIncomingHandler(Handler.fromFunctionZIO[Request] { request =>
      request.header(Header.Authorization) match
        case Some(Header.Authorization.Bearer(token)) =>
          decodeToken(token)
            .flatMap(AuthUser.fromJwtClaim)
            .tapError(e => logError(s"Authentication error: ${e.getMessage}"))
            .mapBoth(_ => unauthorized, request -> _)

        case _ => ZIO.fail(unauthorized)
    })
