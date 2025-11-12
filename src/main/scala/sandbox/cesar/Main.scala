package sandbox.cesar

@main def main(): Unit =
  val encrypted = Cesar.encrypt("HELLO WORLD!", 7)
  println(encrypted.mkString)
  val decrypted = Cesar.decrypt(encrypted, 7)
  println(decrypted.mkString)

object Cesar:
  private val alphabet     = ('a' to 'z').zipWithIndex.toMap
  private val alphabetSize = alphabet.size

  def encrypt(text: String, shift: Int): Seq[Char] = for
    ch             <- text
    (isUpper, base) = ch.isUpper -> ch.toLower
    mapped         <-
      alphabet.get(base) match
        case None        => Some(ch)
        case Some(value) =>
          alphabet
            .find(_._2 == Math.floorMod(value + shift, alphabetSize))
            .map: (encCh, _) =>
              if isUpper then encCh.toUpper else encCh
  yield mapped

  def decrypt(text: Seq[Char], shift: Int): Seq[Char] = for
    ch             <- text
    (isUpper, base) = ch.isUpper -> ch.toLower
    mapped         <-
      alphabet.get(base) match
        case None        => Some(ch)
        case Some(value) =>
          alphabet
            .find(_._2 == Math.floorMod(value - shift, alphabetSize))
            .map: (decCh, _) =>
              if isUpper then decCh.toUpper else decCh
  yield mapped
