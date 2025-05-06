package si.ogrodje.goo.server

import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}
import zio.Config.Secret
import zio.ZIO
import zio.ZIO.logInfo
import zio.http.*

object Authentication:
  type AuthUser = String

  // TODO: Certs need to be rotated.

  private def decodeToken(secret: Secret): ZIO[Keycloak, Throwable, (JwtHeader, JwtClaim, String)] = for
    publicKey <- ZIO.serviceWithZIO[Keycloak](_.rs256Key)
    token      = secret.stringValue
    decoded   <- ZIO.fromTry(Jwt.decodeAll(token, publicKey, Seq(JwtAlgorithm.RS256)))
  yield decoded

  val Authenticated: HandlerAspect[Keycloak, AuthUser] =
    HandlerAspect.interceptIncomingHandler(Handler.fromFunctionZIO[Request] { request =>
      request.header(Header.Authorization) match
        case Some(Header.Authorization.Bearer(token)) =>
          for
            content <- decodeToken(token)
                         .tapError(e => logInfo(s"Error: ${e.getMessage}"))
                         .orDie
            _        = println(content)
          // TODO: Continue here.
          // publicKey <- ZIO.serviceWithZIO[KeycloakClient](_.rs256Key)
          // _          = println(publicKey)
          yield request -> "SOMEONE"
        case _                                        =>
          ZIO.fail(Response.unauthorized.addHeaders(Headers(Header.WWWAuthenticate.Bearer(realm = "Access"))))
    })
