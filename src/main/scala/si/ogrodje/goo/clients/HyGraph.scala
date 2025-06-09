package si.ogrodje.goo.clients

import io.circe.{Decoder, Json}
import si.ogrodje.goo.AppConfig
import si.ogrodje.goo.models.Meetup
import zio.*
import zio.http.*

import java.nio.charset.Charset
import java.time.OffsetDateTime

final class HyGraph private (private val client: Client):
  private given urlDecoder: Decoder[URL] = Decoder[String].emap(raw => URL.decode(raw).left.map(_.getMessage))

  private given Decoder[Meetup] = Decoder[Json].emap { json =>
    val cursor = json.hcursor
    val parsed = for
      id             <- cursor.get[String]("id")
      name           <- cursor.get[String]("name")
      stage          <- cursor.get[Option[String]]("stage")
      hidden         <- cursor.get[Boolean]("hidden")
      homepageUrl    <- cursor.get[Option[URL]]("homePageUrl")
      meetupUrl      <- cursor.get[Option[URL]]("meetupUrl")
      discordUrl     <- cursor.get[Option[URL]]("discordUrl")
      linkedinUrl    <- cursor.get[Option[URL]]("linkedinUrl")
      kompotUrl      <- cursor.get[Option[URL]]("kompotUrl")
      eventbriteUrl  <- cursor.get[Option[URL]]("eventbriteUrl")
      icalUrl        <- cursor.get[Option[URL]]("icalUrl")
      logoImage       = cursor.downFields("logoImage", "url").focus.flatMap(_.as[Option[URL]].toOption).flatten
      mainColor       = cursor.downFields("mainColor", "hex").focus.flatMap(_.as[Option[String]].toOption).flatten
      backgroundColor = cursor.downFields("backgroundColor", "hex").focus.flatMap(_.as[Option[String]].toOption).flatten
      updatedAt      <- cursor.get[OffsetDateTime]("updatedAt")
      createdAt      <- cursor.get[OffsetDateTime]("createdAt")
    yield Meetup(
      id = id,
      name = name,
      stage = stage,
      hidden = hidden,
      homepageUrl = homepageUrl,
      meetupUrl = meetupUrl,
      discordUrl = discordUrl,
      linkedinUrl = linkedinUrl,
      kompotUrl = kompotUrl,
      eventbriteUrl = eventbriteUrl,
      icalUrl = icalUrl,
      logoImage = logoImage,
      mainColor = mainColor,
      backgroundColor = backgroundColor,
      createdAt = createdAt,
      updatedAt = updatedAt
    )

    parsed.left.map(_.getMessage)
  }

  private def readFromGraph(
    query: String,
    variables: (String, Json)*
  ): RIO[Scope, Json] = for
    json         <- ZIO.succeed:
                      Json
                        .fromFields(
                          Seq(
                            "query"     -> Json.fromString(query),
                            "variables" -> Json.fromFields(variables)
                          )
                        )
                        .noSpaces
    body          = Body.fromString(text = json)
    response     <- client.post("")(body)
    _            <-
      ZIO.unless(response.status.isSuccess)(
        ZIO.fail(new Exception(s"GraphQL request has failed with status ${response.status}"))
      )
    bodyAsString <- response.body.asString(Charset.defaultCharset())
    json         <- ZIO.fromEither(io.circe.parser.parse(bodyAsString)).map(_.\\("data").headOption)
    dataPart     <- ZIO.fromOption(json).orElseFail(new Exception("No \"data\" part in JSON GraphQL response."))
  yield dataPart

  def allMeetups: RIO[Scope, List[Meetup]] = readFromGraph(
    """query AllMeetups($size: Int) {
      | meetups(first: $size) { 
      |   id 
      |   name
      |   stage
      |   hidden
      |   homePageUrl 
      |   meetupUrl
      |   discordUrl
      |   linkedInUrl 
      |   kompotUrl
      |   eventbriteUrl
      |   icalUrl
      |   updatedAt
      |   createdAt
      |   mainColor { hex }
      |   backgroundColor { hex }
      |   logoImage {
      |     url(
      |      transformation: {
      |        document: {output: {format: jpg}},
      |        image: { resize: { width: 100, height: 100, fit: clip } }
      |      }
      |    ) 
      |   }
      | }
      |}""".stripMargin,
    "size" -> Json.fromInt(255)
  ).flatMap(json => ZIO.fromEither(json.hcursor.get[List[Meetup]]("meetups")))

object HyGraph:
  def live: ZLayer[Client, Throwable, HyGraph] = ZLayer.fromZIO:
    for
      endpoint <- AppConfig.config.map(_.hygraphEndpoint)
      client   <- ZIO.serviceWith[Client](_.url(endpoint))
    yield new HyGraph(client)
