package si.ogrodje.goo

import zio.*
import scala.util.Try

object ZIP:
  final case class ZIPMap(private val map: Map[String, String]):
    def get(name: String): Task[String] =
      ZIO.fromOption(map.get(name)).orElseFail(new Exception(s"File $name not found in map."))

  def all(resourceName: String): ZIO[Any & Scope, Throwable, ZIPMap] =
    val resourcePath = getClass.getResource(resourceName)
    val fileBytes    = ZIO.fromAutoCloseable(ZIO.attempt(resourcePath.openStream())).flatMap { stream =>
      ZIO.fromTry(scala.util.Try(stream.readAllBytes()))
    }

    fileBytes.map { bytes =>
      val zipInputStream = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))
      Iterator
        .continually(zipInputStream.getNextEntry)
        .takeWhile(_ != null)
        .flatMap { entry =>
          if !entry.isDirectory then
            Try {
              val content = scala.io.Source.fromInputStream(zipInputStream).mkString
              entry.getName -> content
            }.toOption.orElse(None)
          else None
        }
        .toMap
    }.map(ZIPMap(_))

  def get(resourceName: String, name: String): ZIO[Scope, Throwable, String] =
    all(resourceName).flatMap(_.get(name))
