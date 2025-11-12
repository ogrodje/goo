package sandbox.cesar

import zio.*
import zio.Console.printLine
import zio.stream.{UStream, ZStream}

object CesarZIOAPP extends ZIOAppDefault:
  def run = for
    _         <- ZIO.unit
    text       = "HELLO WORLD!"
    textStream = ZStream.fromIterable(text)
    encrypted  = CesarZIO.encrypt(textStream, 3).flatMap(CesarZIO.encrypt(_, 4))

    encryptedText <- encrypted.runCollect.map(_.mkString)
    _             <- printLine(encryptedText)

    decryptedText <- CesarZIO.decrypt(encrypted, 7).runCollect.map(_.mkString)
    _             <- printLine(decryptedText == text)
  yield ()

object CesarZIO:
  private val alphabet     = ('a' to 'z').zipWithIndex.toMap
  private val alphabetSize = alphabet.size

  def encrypt(chars: UStream[Char], shift: Int, direction: Int = 1): UStream[Char] = for
    c              <- chars
    (isUpper, base) = c.isUpper -> c.toLower
    mapped          =
      alphabet.get(base) match
        case None        => Some(c)
        case Some(value) =>
          alphabet
            .find(_._2 == Math.floorMod(value + shift * direction, alphabetSize))
            .map: (encCh, _) =>
              if isUpper then encCh.toUpper else encCh
  yield mapped.getOrElse(c)

  def encrypt(char: Char, shift: Int): UStream[Char] = encrypt(ZStream.fromIterable(char :: Nil), shift)

  def decrypt(chars: UStream[Char], shift: Int): UStream[Char] = encrypt(chars, shift, direction = -1)
